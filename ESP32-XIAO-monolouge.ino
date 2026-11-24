#include "esp_camera.h"
#include <WiFi.h>
#include <WebServer.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Preferences.h>
#include "esp_sleep.h"
#include "board_config.h"

// Bluetooth UUIDs matching the Monologue Android app
#define SERVICE_UUID "12345678-1234-1234-1234-1234567890ab"
#define COMMAND_UUID "12345678-1234-1234-1234-1234567890ac"
#define STATUS_UUID  "12345678-1234-1234-1234-1234567890ae"

void startCameraServer();
void setupLedFlash();

WebServer sleepServer(82);
Preferences prefs;

BLECharacteristic *statusChar = nullptr;
QueueHandle_t commands;

struct Command {
  char text[128];
};

String ssid;
String password;

bool cameraReady = false;
bool serversStarted = false;
bool connecting = false;
bool saveOnSuccess = false;

unsigned long connectStarted = 0;

// ===========================
// Bluetooth status messages
// ===========================

void publish(const String &message) {
  Serial.println(message);

  if (!statusChar) return;

  statusChar->setValue(message.c_str());

  if (message.length() <= 20) {
    statusChar->notify();
  }
}

// ===========================
// Bluetooth command handling
// ===========================

class CommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    auto raw = characteristic->getValue();

    if (raw.length() == 0 || raw.length() >= 128) {
      return;
    }

    Command cmd = {};

    memcpy(cmd.text, raw.c_str(), raw.length());

    if (commands) {
      xQueueSend(commands, &cmd, 0);
    }
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  void onDisconnect(BLEServer *server) override {
    server->startAdvertising();
  }
};

// ===========================
// Initialise Bluetooth
// ===========================

void startBLE() {
  Serial.println("Initialising Bluetooth...");

  BLEDevice::init("ESP32-Glasses");
  BLEDevice::setMTU(185);

  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);

  BLECharacteristic *command = service->createCharacteristic(
    COMMAND_UUID,
    BLECharacteristic::PROPERTY_WRITE
  );

  command->setCallbacks(new CommandCallbacks());

  statusChar = service->createCharacteristic(
    STATUS_UUID,
    BLECharacteristic::PROPERTY_READ |
    BLECharacteristic::PROPERTY_NOTIFY
  );

  statusChar->addDescriptor(new BLE2902());
  statusChar->setValue("WIFI_NOT_SET");

  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();

  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);

  BLEDevice::startAdvertising();

  Serial.println("Bluetooth initialised");
  Serial.println("BLE advertising as ESP32-Glasses");
}

// ===========================
// Deep sleep
// ===========================

void goToSleep() {
  publish("SLEEPING");

  delay(500);

  esp_sleep_enable_ext0_wakeup(GPIO_NUM_0, 0);

  Serial.println("Entering deep sleep");

  delay(200);

  esp_deep_sleep_start();
}

void handleSleep() {
  sleepServer.send(
    200,
    "text/plain",
    "Going to sleep until BOOT is pressed"
  );

  goToSleep();
}

// ===========================
// Wi-Fi status
// ===========================

void reportWiFi() {
  if (WiFi.status() == WL_CONNECTED && serversStarted) {
    String ip = WiFi.localIP().toString();

    publish("WIFI_IP:" + ip);

    publish(
      "WIFI_CONNECTED:http://" + ip + "/capture"
    );

    Serial.print("Camera URL: http://");
    Serial.print(ip);
    Serial.println("/capture");

  } else if (connecting) {
    publish("WIFI_CONNECTING");

  } else if (ssid.isEmpty()) {
    publish("WIFI_NOT_SET");

  } else {
    publish("WIFI_SAVED_NOT_CONNECTED");
  }
}

// ===========================
// Connect to Wi-Fi
// ===========================

void connectWiFi(bool save) {
  if (ssid.isEmpty()) {
    publish("WIFI_NOT_SET");
    return;
  }

  if (!cameraReady) {
    publish("WIFI_FAILED");
    Serial.println("Camera not ready");
    return;
  }

  Serial.println("Connecting to WiFi...");

  WiFi.mode(WIFI_STA);
  WiFi.disconnect(false, false);

  delay(100);

  WiFi.begin(ssid.c_str(), password.c_str());
  WiFi.setSleep(false);

  connecting = true;
  saveOnSuccess = save;
  connectStarted = millis();

  publish("WIFI_CONNECTING");
}

// ===========================
// Process Android app commands
// ===========================

void processCommand(const String &cmd) {

  if (cmd.startsWith("SET_WIFI:")) {

    int split = cmd.indexOf('|', 9);

    if (split < 0) {
      publish("WIFI_FAILED");
      return;
    }

    String newSsid = cmd.substring(9, split);
    String newPassword = cmd.substring(split + 1);

    if (
      newSsid.isEmpty() ||
      newSsid.length() > 32 ||
      newPassword.length() > 64
    ) {
      publish("WIFI_FAILED");
      return;
    }

    ssid = newSsid;
    password = newPassword;

    Serial.println("WiFi credentials received");

    publish("WIFI_SAVED");

    connectWiFi(true);

  } else if (cmd == "CONNECT_WIFI") {

    connectWiFi(false);

  } else if (cmd == "WIFI_STATUS") {

    reportWiFi();

  } else if (cmd == "PING") {

    publish("PONG");

  } else if (cmd == "SLEEP") {

    goToSleep();
  }
}

// ===========================
// Initialise camera
// ===========================

bool initCamera() {

  Serial.println("Initialising camera...");

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

  config.frame_size = FRAMESIZE_QVGA;
  config.pixel_format = PIXFORMAT_JPEG;

  config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;

  config.jpeg_quality = 12;
  config.fb_count = 1;

  if (psramFound()) {

    Serial.println("PSRAM detected");

    config.fb_location = CAMERA_FB_IN_PSRAM;

  } else {

    Serial.println("PSRAM not detected");

    config.fb_location = CAMERA_FB_IN_DRAM;
  }

  esp_err_t err = esp_camera_init(&config);

  if (err != ESP_OK) {

    Serial.printf(
      "Camera init failed: 0x%x\n",
      err
    );

    return false;
  }

  sensor_t *s = esp_camera_sensor_get();

  if (s && s->id.PID == OV3660_PID) {

    s->set_vflip(s, 1);
    s->set_brightness(s, 1);
    s->set_saturation(s, -2);
  }

#if defined(LED_GPIO_NUM)
  setupLedFlash();
#endif

  Serial.println("Camera init OK");

  return true;
}

// ===========================
// Main setup
// ===========================

void setup() {

  Serial.begin(115200);

  delay(3000);

  Serial.println();
  Serial.println("========================");
  Serial.println("Booting Monologue...");
  Serial.println("========================");

  commands = xQueueCreate(8, sizeof(Command));

  if (!commands) {

    Serial.println("Command queue allocation failed");

    return;
  }

  // Start Bluetooth before camera initialisation
  startBLE();

  delay(500);

  // Initialise camera
  cameraReady = initCamera();

  if (!cameraReady) {
    Serial.println("Camera failed, Bluetooth remains active");
  }

  // Load saved Wi-Fi credentials
  if (prefs.begin("monologue", false)) {

    ssid = prefs.getString("ssid", "");
    password = prefs.getString("password", "");

  } else {

    Serial.println("Preferences initialisation failed");
  }

  if (!ssid.isEmpty() && cameraReady) {

    Serial.println("Saved WiFi credentials found");

    connectWiFi(false);

  } else {

    publish("WIFI_NOT_SET");

    Serial.println("Waiting for Android app");
    Serial.println("Connect to ESP32-Glasses");
  }
}

// ===========================
// Main loop
// ===========================

void loop() {

  Command cmd;

  // Receive commands from Android
  if (
    commands &&
    xQueueReceive(commands, &cmd, 0) == pdTRUE
  ) {
    processCommand(String(cmd.text));
  }

  // Manage Wi-Fi connection
  if (connecting) {

    if (WiFi.status() == WL_CONNECTED) {

      connecting = false;

      Serial.println("WiFi connected");

      // Save credentials only after successful connection
      if (saveOnSuccess) {

        prefs.putString("ssid", ssid);
        prefs.putString("password", password);

        Serial.println("WiFi credentials saved");
      }

      // Start camera and sleep servers
      if (!serversStarted) {

        startCameraServer();

        sleepServer.on(
          "/sleep",
          HTTP_GET,
          handleSleep
        );

        sleepServer.begin();

        serversStarted = true;

        Serial.println("Camera server started");
      }

      reportWiFi();

      Serial.print("IP address: ");
      Serial.println(WiFi.localIP());

      Serial.print("Capture URL: http://");
      Serial.print(WiFi.localIP());
      Serial.println("/capture");

      Serial.print("Stream URL: http://");
      Serial.print(WiFi.localIP());
      Serial.println(":81/stream");

      Serial.print("Sleep URL: http://");
      Serial.print(WiFi.localIP());
      Serial.println(":82/sleep");

    } else if (
      millis() - connectStarted >= 20000UL
    ) {

      connecting = false;

      WiFi.disconnect(false, false);

      publish("WIFI_FAILED");

      Serial.println("WiFi connection timed out");
      Serial.println("Waiting for new credentials");
    }
  }

  if (serversStarted) {

    sleepServer.handleClient();
  }

  // Periodic diagnostic message
  static unsigned long lastHeartbeat = 0;

  if (millis() - lastHeartbeat >= 10000UL) {

    lastHeartbeat = millis();

    Serial.printf(
      "Monologue running | Camera: %s | WiFi: %s | Free heap: %u\n",
      cameraReady ? "OK" : "FAILED",
      WiFi.status() == WL_CONNECTED ? "CONNECTED" : "DISCONNECTED",
      ESP.getFreeHeap()
    );
  }

  delay(10);
}
