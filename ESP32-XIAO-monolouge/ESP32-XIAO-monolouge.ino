#include "esp_camera.h"
#include <WiFi.h>
#include <WebServer.h>
#include "esp_sleep.h"

// Bluetooth additions
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#include "board_config.h"

// Bluetooth UUIDs
#define SERVICE_UUID "12345678-1234-1234-1234-1234567890ab"
#define COMMAND_UUID "12345678-1234-1234-1234-1234567890ac"
#define STATUS_UUID  "12345678-1234-1234-1234-1234567890ae"

// No hardcoded WiFi credentials
String ssid = "";
String password = "";

BLECharacteristic *statusChar = nullptr;

bool serversStarted = false;
bool wifiRequested = false;
bool wifiConnecting = false;
bool sleepRequested = false;
bool phoneConnected = false;
bool restartAdvertising = false;

unsigned long wifiStartTime = 0;

void startCameraServer();
void setupLedFlash();

WebServer sleepServer(82);

// ===========================
// Bluetooth status
// ===========================

void sendStatus(String message) {
  Serial.println(message);

  if (statusChar != nullptr) {
    statusChar->setValue(message.c_str());

    if (phoneConnected) {
      statusChar->notify();
    }
  }
}

void reportWiFi() {
  if (WiFi.status() == WL_CONNECTED) {

    String ip = WiFi.localIP().toString();

    sendStatus("WIFI_IP:" + ip);

    delay(100);

    sendStatus(
      "WIFI_CONNECTED:http://" + ip + "/capture"
    );

  } else if (wifiConnecting) {

    sendStatus("WIFI_CONNECTING");

  } else if (ssid.isEmpty()) {

    sendStatus("WIFI_NOT_SET");

  } else {

    sendStatus("WIFI_SAVED_NOT_CONNECTED");
  }
}

// ===========================
// Bluetooth callbacks
// ===========================

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    phoneConnected = true;
  }

  void onDisconnect(BLEServer *server) override {
    phoneConnected = false;
    restartAdvertising = true;
  }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {

    String command = String(
      characteristic->getValue().c_str()
    );

    if (command.startsWith("SET_WIFI:")) {

      int separator = command.indexOf('|', 9);

      if (separator < 0) {
        sendStatus("WIFI_FAILED");
        return;
      }

      if (wifiRequested || wifiConnecting) {
        sendStatus("WIFI_CONNECTING");
        return;
      }

      ssid = command.substring(9, separator);
      password = command.substring(separator + 1);

      if (ssid.isEmpty()) {
        sendStatus("WIFI_FAILED");
        return;
      }

      Serial.println("WiFi credentials received");

      sendStatus("WIFI_SAVED");

      wifiRequested = true;

    } else if (command == "CONNECT_WIFI") {

      if (!wifiConnecting) {
        wifiRequested = true;
      }

    } else if (command == "WIFI_STATUS") {

      reportWiFi();

    } else if (command == "PING") {

      sendStatus("PONG");

    } else if (command == "SLEEP") {

      sleepRequested = true;
    }
  }
};

// ===========================
// Initialise Bluetooth
// ===========================

void startBluetooth() {

  Serial.println("Starting Bluetooth...");

  BLEDevice::init("ESP32-Glasses");

  BLEDevice::setMTU(185);

  BLEServer *server = BLEDevice::createServer();

  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(
    SERVICE_UUID
  );

  BLECharacteristic *commandChar =
    service->createCharacteristic(
      COMMAND_UUID,
      BLECharacteristic::PROPERTY_WRITE |
      BLECharacteristic::PROPERTY_WRITE_NR
    );

  commandChar->setCallbacks(
    new CommandCallbacks()
  );

  statusChar = service->createCharacteristic(
    STATUS_UUID,
    BLECharacteristic::PROPERTY_READ |
    BLECharacteristic::PROPERTY_NOTIFY
  );

  statusChar->addDescriptor(new BLE2902());

  statusChar->setValue("WIFI_NOT_SET");

  service->start();

  BLEAdvertising *advertising =
    BLEDevice::getAdvertising();

  advertising->addServiceUUID(SERVICE_UUID);

  advertising->setScanResponse(true);

  BLEDevice::startAdvertising();

  Serial.println(
    "BLE advertising as ESP32-Glasses"
  );
}

// ===========================
// Original deep sleep
// ===========================

void enterSleep() {

  Serial.println(
    "Configuring wake on BOOT button (GPIO0)..."
  );

  esp_sleep_enable_ext0_wakeup(GPIO_NUM_0, 0);

  Serial.println("Entering deep sleep now");

  delay(100);

  esp_deep_sleep_start();
}

void handleSleep() {

  sleepServer.send(
    200,
    "text/plain",
    "Going to sleep until BOOT is pressed"
  );

  delay(200);

  enterSleep();
}

// ===========================
// WiFi provisioning
// ===========================

void startWiFi() {

  if (ssid.isEmpty()) {

    sendStatus("WIFI_NOT_SET");

    return;
  }

  Serial.println("WiFi connecting");

  WiFi.persistent(false);

  WiFi.mode(WIFI_STA);

  WiFi.begin(
    ssid.c_str(),
    password.c_str()
  );

  WiFi.setSleep(false);

  wifiConnecting = true;

  wifiStartTime = millis();

  sendStatus("WIFI_CONNECTING");
}

void checkWiFi() {

  if (!wifiConnecting) {
    return;
  }

  if (WiFi.status() == WL_CONNECTED) {

    wifiConnecting = false;

    Serial.println("WiFi connected");

    Serial.print("IP address: ");
    Serial.println(WiFi.localIP());

    if (!serversStarted) {

      startCameraServer();

      sleepServer.on(
        "/sleep",
        HTTP_GET,
        handleSleep
      );

      sleepServer.begin();

      serversStarted = true;
    }

    Serial.println("Camera Ready!");

    Serial.print("Base URL: http://");
    Serial.println(WiFi.localIP());

    Serial.print("Image URL: http://");
    Serial.print(WiFi.localIP());
    Serial.println("/capture");

    Serial.print("Stream URL: http://");
    Serial.print(WiFi.localIP());
    Serial.println(":81/stream");

    Serial.print("Sleep URL: http://");
    Serial.print(WiFi.localIP());
    Serial.println(":82/sleep");

    reportWiFi();

  } else if (
    millis() - wifiStartTime >= 20000
  ) {

    wifiConnecting = false;

    WiFi.disconnect();

    sendStatus("WIFI_FAILED");
  }
}

// ===========================
// Original camera setup
// ===========================

void setup() {

  Serial.begin(115200);

  delay(3000);

  Serial.setDebugOutput(true);

  Serial.println();

  Serial.println("Booting...");

  camera_config_t config = {};

  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;

  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;

  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;

  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;

  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;

  config.xclk_freq_hz = 20000000;

  config.frame_size = FRAMESIZE_UXGA;

  config.pixel_format = PIXFORMAT_JPEG;

  config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;

  config.fb_location = CAMERA_FB_IN_PSRAM;

  config.jpeg_quality = 12;

  config.fb_count = 1;

  if (config.pixel_format == PIXFORMAT_JPEG) {

    if (psramFound()) {

      config.jpeg_quality = 10;

      config.fb_count = 2;

      config.grab_mode = CAMERA_GRAB_LATEST;

    } else {

      config.frame_size = FRAMESIZE_SVGA;

      config.fb_location = CAMERA_FB_IN_DRAM;
    }

  } else {

    config.frame_size = FRAMESIZE_240X240;

#if CONFIG_IDF_TARGET_ESP32S3
    config.fb_count = 2;
#endif

  }

#if defined(CAMERA_MODEL_ESP_EYE)

  pinMode(13, INPUT_PULLUP);
  pinMode(14, INPUT_PULLUP);

#endif

  esp_err_t err = esp_camera_init(&config);

  if (err != ESP_OK) {

    Serial.printf(
      "Camera init failed with error 0x%x\n",
      err
    );

    return;
  }

  sensor_t *s = esp_camera_sensor_get();

  if (s == nullptr) {
    Serial.println("Camera sensor unavailable");
    return;
  }

  if (s->id.PID == OV3660_PID) {

    s->set_vflip(s, 1);

    s->set_brightness(s, 1);

    s->set_saturation(s, -2);
  }

  if (config.pixel_format == PIXFORMAT_JPEG) {

    s->set_framesize(s, FRAMESIZE_QVGA);
  }

#if defined(CAMERA_MODEL_M5STACK_WIDE) || defined(CAMERA_MODEL_M5STACK_ESP32CAM)

  s->set_vflip(s, 1);

  s->set_hmirror(s, 1);

#endif

#if defined(CAMERA_MODEL_ESP32S3_EYE)

  s->set_vflip(s, 1);

#endif

#if defined(LED_GPIO_NUM)

  setupLedFlash();

#endif

  Serial.println("Camera init OK");

  // Bluetooth replaces the original hardcoded WiFi setup

  startBluetooth();

  Serial.println("Waiting for WiFi credentials from app");
}

// ===========================
// Main loop
// ===========================

void loop() {

  if (restartAdvertising) {

    restartAdvertising = false;

    BLEDevice::startAdvertising();
  }

  if (wifiRequested) {

    wifiRequested = false;

    startWiFi();
  }

  checkWiFi();

  if (sleepRequested) {

    sleepRequested = false;

    sendStatus("SLEEPING");

    delay(500);

    enterSleep();
  }

  if (serversStarted) {

    sleepServer.handleClient();
  }

  delay(10);
}
