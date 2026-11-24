# ESP32 AI Glasses

A wearable smart-glasses prototype built around an **ESP32 camera** and an **Android app**.

The system works like this:

* The Android app connects to the glasses over **BLE**
* The app sends Wi-Fi credentials to the ESP32 over BLE
* The ESP32 joins Wi-Fi and exposes a `/capture` image endpoint
* The phone fetches images over **HTTP**
* The phone sends those images to a vision model
* The result is spoken back as a short **first-person internal monologue**

This project is designed for lightweight, real-time scene awareness with hands-free audio output.

---

## Features

* BLE device discovery and connection
* Wi-Fi provisioning from phone to ESP32 over BLE
* No hardcoded Wi-Fi credentials required
* ESP32 camera image capture over HTTP
* Android app support for prompt editing
* OpenAI vision analysis on captured frames
* Text-to-speech playback on Android
* Queue control so the app does not run too many requests ahead
* Current spoken text tracking in the UI
* Sleep command to put the glasses into deep sleep
* Optional in-app image preprocessing before sending to the model

---

## Architecture

```text
ESP32 Camera  <--BLE-->  Android App  <--HTTPS/API-->  Vision Model
     |                         |
     |---- HTTP /capture ------|
     |
   Wi-Fi
```

### Transport split

The project uses two different transports for different jobs:

* **BLE** for control and setup
  * Connect to device
  * Send Wi-Fi SSID/password
  * Receive device status
  * Sleep command

* **Wi-Fi / HTTP** for image transfer
  * The phone fetches images from `http://<device-ip>/capture`

This split is important because BLE is suitable for commands, but less suitable for transferring camera images.

---

## Hardware

This project has been developed around:

* **Seeed XIAO ESP32S3 Sense**
* Onboard camera module
* Android phone for the companion app
* USB-C data cable
* Compatible battery or USB power supply
* Headphones or Bluetooth earbuds (optional)

### Camera pin mapping

The firmware assumes the XIAO ESP32S3 Sense camera pin layout used in the project code.

---

## Software stack

### ESP32 side

* Arduino framework
* `esp_camera`
* `WiFi.h`
* `WebServer.h`
* `Preferences.h`
* BLE libraries from the ESP32 Arduino stack

### Android side

* Kotlin
* Jetpack Compose
* Android BLE GATT APIs
* Android TextToSpeech
* OpenAI API client code in app

---

## Flashing the ESP32

### 1. Install Arduino IDE

Download Arduino IDE from:

https://www.arduino.cc/en/software

Arduino IDE 2.x is recommended.

### 2. Install the ESP32 board package

Open Arduino IDE and navigate to:

File > Preferences

Add the following URL to Additional Boards Manager URLs:

```text
https://espressif.github.io/arduino-esp32/package_esp32_index.json
```

Open Boards Manager, search for `esp32`, and install the package by Espressif Systems.

Use a version that includes the CameraWebServer example with `board_config.h`.

### 3. Prepare the CameraWebServer sketch

IMPORTANT: The Monologue firmware depends on supporting files from the Arduino ESP32 CameraWebServer example.

Downloading the `.ino` file alone is not sufficient.

1. Open Arduino IDE.
2. Navigate to:

   File > Examples > ESP32 > Camera > CameraWebServer

3. Select File > Save As.
4. Save the sketch as `Monologue`.
5. Download `ESP32-XIAO-monolouge.ino` from this repository.
6. Copy the entire contents of the downloaded firmware.
7. Replace the contents of the main `Monologue.ino` tab with the downloaded code.
8. Save the sketch.

Do not delete the other tabs or supporting files.

Your sketch folder should contain:

```text
Monologue/
├── Monologue.ino
├── board_config.h
├── camera_pins.h
├── app_httpd.cpp
├── camera_index.h
└── partitions.csv (if supplied by the example)
```

Keep any additional files supplied with the CameraWebServer example.

These files provide the camera pin definitions, camera configuration and HTTP server required by Monologue.

### 4. Configure the camera

Open the `board_config.h` tab.

Uncomment the following line:

```cpp
#define CAMERA_MODEL_XIAO_ESP32S3
```

Comment out all other camera model definitions.

Only one camera model should be enabled.

Do not enter your Wi-Fi credentials into the firmware. The Android app sends them over Bluetooth.

### 5. Select the correct board

Navigate to:

Tools > Board > esp32 > XIAO_ESP32S3

The XIAO ESP32S3 Sense uses the same board selection as the standard XIAO ESP32S3.

### 6. Configure Arduino board settings

Use the following recommended settings:

| Setting | Value |
|---|---|
| Board | XIAO_ESP32S3 |
| USB CDC On Boot | Enabled |
| USB Mode (if available) | Hardware CDC and JTAG |
| PSRAM | OPI PSRAM |
| Flash Size | 8 MB |
| Partition Scheme | Huge APP (3MB No OTA/1MB SPIFFS), if available |
| Upload Speed | 115200 |
| Erase All Flash Before Sketch Upload | Disabled |
| Port | Current ESP32 COM port |

Some options may differ depending on the ESP32 board package version.

OPI PSRAM enables the additional 8 MB of memory available on the XIAO ESP32S3 Sense. This memory is particularly useful for storing camera frame buffers.

### 7. Find the ESP32 COM port

Connect the ESP32 using a USB-C data cable.

On Windows, open Device Manager and expand:

```text
Ports (COM & LPT)
```

Alternatively, open PowerShell and run:

```powershell
[System.IO.Ports.SerialPort]::GetPortNames()
```

For more detailed information:

```powershell
Get-PnpDevice -Class Ports -PresentOnly
```

Unplug and reconnect the ESP32 to identify which COM port belongs to it.

Select that port in Arduino IDE:

Tools > Port

The COM port may change after uploading, restarting or entering download mode.

### 8. Compile and upload

Press the Verify button in Arduino IDE to compile the firmware.

If compilation succeeds:

1. Close Serial Monitor.
2. Confirm the correct COM port is selected.
3. Press Upload.

A successful upload should end with something similar to:

```text
Writing... 100%
Hash of data verified.
Hard resetting via RTS pin...
```

### 9. Enter BOOT mode manually if required

If Arduino IDE cannot connect to the ESP32:

1. Hold down the BOOT button.
2. While holding BOOT, press and release RESET.
3. Release BOOT.
4. Check which COM port appears.
5. Select the correct port.
6. Upload the firmware.

Alternatively:

1. Disconnect the USB cable.
2. Hold down BOOT.
3. Reconnect the USB cable.
4. Release BOOT.

Do not press RESET again before uploading, as this exits download mode.

### 10. Confirm boot output

After uploading, open Serial Monitor at:

```text
115200 baud
```

If the COM port has changed, select the new port.

Press RESET once.

The firmware should initialise Bluetooth and the camera.

Expected output may include:

```text
Booting Monologue...
Initialising Bluetooth...
Bluetooth initialised
BLE advertising as ESP32-Glasses
Initialising camera...
PSRAM detected
Camera init OK
Waiting for Android app
```

The ESP32 should advertise over Bluetooth even before Wi-Fi credentials have been configured.

Actual messages may vary depending on the firmware version.

---

## How it works

### 1. Power on the glasses

The ESP32 boots and starts advertising over BLE as:

```text
ESP32-Glasses
```

### 2. Open the Android app

The app scans for the ESP32 BLE device and connects.

You do not need to manually pair the ESP32 through Android Bluetooth settings.

### 3. Send Wi-Fi credentials

The app sends a BLE command like:

```text
SET_WIFI:YourSSID|YourPassword
```

The ESP32 receives those credentials and attempts to connect to Wi-Fi.

The updated firmware is designed to save successfully connected credentials in non-volatile memory using the Preferences library.

### 4. ESP32 connects to Wi-Fi

Once connected, it sends BLE status messages such as:

```text
WIFI_IP:192.168.1.55
WIFI_CONNECTED:http://192.168.1.55/capture
```

### 5. App fetches camera image

The Android app stores the capture URL and fetches images over HTTP.

### 6. Vision analysis

The image is sent to the OpenAI API along with the configured prompt.

### 7. Spoken response

The resulting short text is read aloud using Android TextToSpeech.

---

## Android app guide

### Installation

Download the Android APK supplied in this repository.

Open the APK on your Android phone.

Android may require permission to install applications from the browser or file manager used to open the APK.

### Required permissions

The app needs:

* Bluetooth scan
* Bluetooth connect
* Internet
* Notifications on newer Android versions
* Location permission on some older Android versions

Make sure `AndroidManifest.xml` includes:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

If you are fetching from a local ESP32 over plain HTTP, you also need to allow cleartext traffic.

Inside the `<application>` tag:

```xml
android:usesCleartextTraffic="true"
```

For production use, a more restrictive network security configuration is recommended.

### OpenAI API key

Create an API key at:

https://platform.openai.com/api-keys

Enter the API key into the Android app.

OpenAI API usage is billed separately from a ChatGPT subscription.

Do not publish your API key or Wi-Fi password in the GitHub repository.

---

## Android app setup

### 1. Open the app

You should see fields for:

* OpenAI API key
* Wi-Fi SSID
* Wi-Fi password
* Prompt

### 2. Tap Connect ESP32

The app scans for the BLE device and connects.

Ensure Bluetooth is enabled and the required permissions have been granted.

### 3. Tap Set Up ESP32 Wi-Fi

Enter your Wi-Fi network name and password.

This sends Wi-Fi credentials over BLE and waits for the ESP32 to report a capture URL.

The ESP32 requires a compatible 2.4 GHz Wi-Fi network.

### 4. Confirm capture URL appears

The app should display something like:

```text
Capture URL: http://192.168.1.55/capture
```

You can test the camera by opening the capture URL in your phone's web browser.

If the browser displays an image, the camera server is working.

The phone must be able to communicate with the ESP32 over the local network.

### 5. Tap Start

The app begins the loop:

* Fetch image
* Optionally preprocess image
* Send to model
* Speak result

### 6. Tap Stop to halt the loop

### 7. Tap Sleep Glasses to put the ESP32 into deep sleep

Press BOOT to wake the glasses if GPIO0 wake-up is configured in the firmware.

Alternatively, press RESET or power cycle the device.

---

## Using a mobile hotspot

Monologue can be used away from home by connecting the ESP32 to a compatible mobile hotspot.

1. Enable your phone's mobile hotspot.
2. Configure the hotspot to use 2.4 GHz.
3. Open the Monologue app.
4. Connect to the ESP32 over Bluetooth.
5. Enter the hotspot SSID and password.
6. Tap Set Up ESP32 Wi-Fi.
7. Wait for the capture URL.
8. Press Start.

Some phones may restrict communication between the phone and connected hotspot clients.

If this occurs, try a separate mobile hotspot or another phone.

Bluetooth is used for configuration and control, not camera image transmission.

Internet connectivity is required for OpenAI vision processing.

---

## Customisation (app source v1.1)

The prompt is empty on a fresh install. If left blank, the app requests concise, neutral scene descriptions.

Custom instructions are optional. Settings are saved locally; the API key and Wi-Fi credentials are not saved by these new app settings.

| Setting | Default | Range |
|---|---|---|
| Capture interval (pause after each cycle) | 1 second | 1–60 seconds |
| Response length (approximate target) | 25 words | 10–100 words |
| Responses loaded in advance | 2 | 0–5 |
| Context memory | 0 | 0–10 previous generated responses |
| Speech speed and pitch | 1.0× | 0.5–2.0× |
| Voice | System default | Voices available from Android TTS |
| Only respond to visual changes | Off | On/off |
| Interrupt outdated speech | Off | On/off |

Stop a session before adjusting these controls.

The queue counts the current utterance plus buffered ones. At zero, the next capture waits until speech finishes; at two, up to two additional responses can be buffered.

The API request is sequential, not parallel. Actual capture timing depends on network, processing and queue availability.

The word target is not a guaranteed exact word count.

Experimental change detection compares small grayscale thumbnails. It can be triggered by lighting or motion and can miss meaningful changes. It only checks when the queue allows a capture.

Interrupting outdated speech stops both current and queued utterances.

The app sends captured images and optional prompt/context to OpenAI; do not use it where recording is prohibited.

It is not a safety or navigation aid.

The existing repository APK is v1.0 until you build and replace it. See UPDATE.md for build and test steps.

---

## Image preprocessing in the app

Depending on the current app version, the image can be transformed before being sent to the model.

Examples of transformations used during development:

* Rotate 90 degrees right
* Mirror across vertical axis
* No transform

These transformations happen on the phone to reduce the processing workload on the ESP32.

---

## ESP32 firmware guide

### BLE commands supported

The ESP32 firmware supports commands like:

```text
PING
SET_WIFI:<ssid>|<password>
CONNECT_WIFI
WIFI_STATUS
SLEEP
```

### BLE status messages

The ESP32 sends status messages such as:

```text
PONG
WIFI_SAVED
WIFI_CONNECTING
WIFI_IP:<ip>
WIFI_CONNECTED:http://<ip>/capture
WIFI_FAILED
WIFI_NOT_SET
WIFI_SAVED_NOT_CONNECTED
SLEEPING
```

### HTTP endpoints

The ESP32 serves:

| Endpoint | Description |
|---|---|
| `/` | Camera web interface |
| `/capture` | JPEG image capture |
| `:81/stream` | Camera stream, if enabled |
| `:82/sleep` | Deep-sleep command, if enabled |

The Android app primarily uses `/capture`.

---

## Typical Serial output

A healthy startup sequence looks something like:

```text
Booting Monologue...
Initialising Bluetooth...
Bluetooth initialised
BLE advertising as ESP32-Glasses
Initialising camera...
PSRAM detected
Camera init OK
WIFI_NOT_SET
Waiting for Android app
```

When Wi-Fi is provisioned:

```text
WiFi credentials received
WIFI_SAVED
Connecting to WiFi...
WIFI_CONNECTING
WiFi connected
WiFi credentials saved
Camera server started
WIFI_IP:192.168.1.55
WIFI_CONNECTED:http://192.168.1.55/capture
```

Actual output may differ depending on the firmware version.

---

## Troubleshooting

### board_config.h: No such file or directory

Example error:

```text
fatal error: board_config.h: No such file or directory
```

The supporting CameraWebServer files are missing.

Open the Arduino CameraWebServer example, save a complete copy and replace only the main `.ino` contents.

Refer to the flashing instructions above.

### Failed to connect to ESP32-S3

Example error:

```text
A fatal error occurred: Failed to connect to ESP32-S3:
No serial data received.
```

Possible solutions:

* Select the correct COM port.
* Enter BOOT mode manually.
* Use a USB-C data cable.
* Try another USB port.
* Close Serial Monitor.
* Reduce the upload speed to 115200.

### Cannot configure port

Example error:

```text
OSError(22, 'A device which does not exist was specified.')
```

The ESP32 serial port may have disappeared or changed.

Check the currently available ports:

```powershell
Get-PnpDevice -Class Ports -PresentOnly
```

Enter BOOT mode manually and select the newly detected COM port.

A firmware crash may also cause the native USB connection to disappear during normal operation.

### Serial Monitor timeout

Example error:

```text
Could not connect to COM3 serial port.
```

Possible solutions:

* Close Serial Monitor.
* Unplug and reconnect the ESP32.
* Restart Arduino IDE.
* Select the current COM port.
* Enable USB CDC On Boot.
* Set Serial Monitor to 115200 baud.

The COM port used for uploading may differ from the COM port used during normal operation.

### ESP32 keeps rebooting

Start by testing the board in isolation.

Use a tiny sketch first to confirm the board is stable:

```cpp
void setup() {
  Serial.begin(115200);
  delay(3000);
  Serial.println("ESP32 STARTED");
}

void loop() {
  Serial.println("ESP32 RUNNING");
  delay(1000);
}
```

Then test camera-only, Wi-Fi and BLE individually.

This helps identify whether the failure is in:

* Board setup
* Camera setup
* Wi-Fi
* BLE integration
* Memory allocation
* Power supply

If necessary, enable Erase All Flash Before Sketch Upload as a recovery step.

This will remove any previously stored Wi-Fi credentials and other flash settings.

### Only ESP-ROM boot messages appear

Example:

```text
ESP-ROM:esp32s3-20210327
boot:0x2b (SPI_FAST_FLASH_BOOT)
entry 0x403c88b8
```

These messages alone do not indicate a firmware failure.

Check:

* USB CDC On Boot is enabled.
* The correct COM port is selected.
* Serial Monitor is set to 115200.
* The firmware includes a short startup delay.
* The board is not continuously restarting.

### Camera initialisation failed

Check that:

* The correct camera model is selected.
* OPI PSRAM is enabled.
* The camera ribbon cable is properly connected.
* The power supply is stable.
* The supporting CameraWebServer files are present.

Disconnect power before adjusting the camera hardware.

### ESP32 does not appear over Bluetooth

Check that:

* The BLE-enabled firmware has been uploaded.
* Bluetooth is enabled on the phone.
* Nearby Devices permissions have been granted.
* The ESP32 is not in deep sleep.
* The device is advertising as ESP32-Glasses.

Use a BLE scanner such as nRF Connect to independently test Bluetooth advertising.

### cam_hal: FB-OVF

This means frame buffer overflow. It often happens when the camera is producing frames faster than they are being consumed.

Common mitigations:

* Reduce frame size.
* Increase JPEG compression.
* Use `fb_count = 1`.
* Use `CAMERA_GRAB_WHEN_EMPTY`.
* Avoid streaming-style capture when you only need still frames.

### App says cleartext HTTP not permitted

You need to allow plain HTTP traffic to the ESP32.

Add to the Android application manifest:

```xml
android:usesCleartextTraffic="true"
```

### ESP32 connects to Wi-Fi but app does not get capture URL

Possible causes:

* BLE notification missed.
* BLE MTU is too small for the status message.
* App not parsing `WIFI_CONNECTED:` correctly.
* App not handling `WIFI_IP:` fallback correctly.

The app should be able to use either:

```text
WIFI_CONNECTED:http://<ip>/capture
```

or:

```text
WIFI_IP:<ip>
```

Check the IP address in Serial Monitor and test the capture URL manually.

### Final WiFi status: 6

This means the ESP32 is disconnected from Wi-Fi.

Common causes:

* Wrong SSID or password.
* Unsupported Wi-Fi security mode.
* Network is 5 GHz only.
* Enterprise or captive portal network.
* Weak Wi-Fi signal.

Test with a plain 2.4 GHz WPA2 hotspot first.

### BLE status arrives but app does not update UI

This is usually an app-side state handling issue.

Make sure the app assigns the incoming URL to the `captureUrl` state variable.

### TTS backlog grows too much

Reduce how far ahead the app is allowed to queue messages.

This project currently uses a limited backlog policy to avoid wasting API calls.

---

## Performance notes

The project works best when responsibilities are split cleanly:

* ESP32 handles image capture and BLE control.
* Android handles image preprocessing, API calls, queueing and TTS.

Trying to do heavy image transforms directly on the ESP32 can reduce reliability.

For that reason, image transforms are better done in the Android app when possible.

---

## Privacy and security

Monologue captures images from the wearer's surroundings and may transmit them to an external AI service.

* Respect the privacy of other people.
* Do not use the camera where recording is prohibited.
* Do not publish Wi-Fi passwords or API keys.
* Use trusted Wi-Fi networks.
* Do not expose the ESP32 camera server to the public internet.

The current prototype may not provide authenticated BLE provisioning or encrypted local camera traffic.

Other devices on the same network may be able to access the camera endpoint.

Additional security measures are required before commercial deployment.

---

## Safety and limitations

This project is an experimental assistive / awareness prototype.

It should not be relied on as a sole navigation or safety device.

Limitations include:

* Network latency
* Model latency
* BLE timing issues
* Image blur and low-light conditions
* Incomplete scene understanding by the model
* Incorrect AI-generated descriptions
* Battery limitations

Do not rely on Monologue for driving, crossing roads, obstacle avoidance or other safety-critical activities.

---

## Future improvements

Possible next steps:

* Improved auto-reconnect after wake or reboot
* Better app-side image transforms
* Visual debug preview in the app
* Battery / device status reporting over BLE
* Wake-from-sleep control flow refinement
* On-device caching / throttling strategies
* Improved Bluetooth security
* Improved battery management
* Precompiled ESP32 firmware
* Complete standalone Arduino sketch folder
* Simplified installation process

---

## Development notes

This project went through a few architectural changes:

* Started with attempts to send images over BLE.
* Moved to BLE for setup/control and Wi-Fi for image transfer.
* Added BLE fallback status reads to improve reliability.
* Moved image transformation work to the Android app to reduce ESP32 processing load.
* Removed hardcoded Wi-Fi credentials from the firmware.
* Added support for BLE Wi-Fi provisioning.
* Added persistent Wi-Fi credential storage in the updated firmware.

---

## Contributing

Contributions are welcome.

Good areas for contribution:

* Android BLE robustness
* ESP32 power management
* Camera quality tuning
* Prompt engineering
* App UI polish
* Local image processing improvements
* Firmware reliability
* Security improvements

---

## Quick start summary

### ESP32

1. Install Arduino IDE.
2. Install the ESP32 board package.
3. Open the CameraWebServer example.
4. Save a copy as Monologue.
5. Replace the main `.ino` contents with the firmware from this repository.
6. Retain all supporting files.
7. Enable CAMERA_MODEL_XIAO_ESP32S3 in board_config.h.
8. Select the XIAO_ESP32S3 board.
9. Enable OPI PSRAM and USB CDC On Boot.
10. Select the correct COM port.
11. Compile and upload.
12. Open Serial Monitor at 115200.
13. Confirm BLE advertising starts.

### Android

1. Install the app.
2. Enable Bluetooth.
3. Connect to ESP32.
4. Send Wi-Fi credentials.
5. Wait for capture URL.
6. Enter OpenAI API key.
7. Press Start.

---

## Contact

For enquiries or interest in Monologue hardware:

aimonolouge@proton.me

---

Note: Images in the app are rotated 90 degrees to the right and flipped left to right for the current hardware configuration.

**Monologue — An artificial perspective on the world around you.**
