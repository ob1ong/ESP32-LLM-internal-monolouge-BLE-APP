# ESP32 AI Glasses

A wearable smart-glasses prototype built around an **ESP32 camera** and an **Android app**.

The system works like this:

* the Android app connects to the glasses over **BLE**
* the app sends Wi-Fi credentials to the ESP32 over BLE
* the ESP32 joins Wi-Fi and exposes a `/capture` image endpoint
* the phone fetches images over **HTTP**
* the phone sends those images to a vision model
* the result is spoken back as a short **first-person internal monologue**

This project is designed for lightweight, real-time scene awareness with hands-free audio output.

---

## Features

* BLE device discovery and connection
* Wi-Fi provisioning from phone to ESP32 over BLE
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

  * connect to device
  * send Wi-Fi SSID/password
  * receive device status
  * sleep command

* **Wi-Fi / HTTP** for image transfer

  * the phone fetches images from `http://<device-ip>/capture`

This split is important because BLE is fine for commands, but poor for moving camera images.

---

## Hardware

This project has been developed around:

* **Seeed XIAO ESP32S3 Sense**
* onboard camera module
* Android phone for the companion app

### Camera pin mapping

The firmware assumes the XIAO ESP32S3 Sense camera pin layout used in the project code.

---

## Software stack

### ESP32 side

* Arduino framework
* `esp_camera`
* `WiFi.h`
* `WebServer.h`
* BLE libraries from the ESP32 Arduino stack

### Android side

* Kotlin
* Jetpack Compose
* Android BLE GATT APIs
* Android TextToSpeech
* OpenAI API client code in app

---

## How it works

### 1. Power on the glasses

The ESP32 boots and starts advertising over BLE as:

```text
ESP32-Glasses
```

### 2. Open the Android app

The app scans for the ESP32 BLE device and connects.

### 3. Send Wi-Fi credentials

The app sends a BLE command like:

```text
SET_WIFI:YourSSID|YourPassword
```

The ESP32 stores those credentials in RAM and attempts to connect to Wi-Fi.

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

### Required permissions

The app needs:

* Bluetooth scan
* Bluetooth connect
* Internet
* notifications on newer Android versions

Make sure `AndroidManifest.xml` includes:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

If you are fetching from a local ESP32 over plain HTTP, you also need to allow cleartext traffic.

Inside the `<application>` tag:

```xml
android:usesCleartextTraffic="true"
```

---

## Android app setup

### 1. Open the app

You should see fields for:

* OpenAI API key
* Wi-Fi SSID
* Wi-Fi password
* Prompt

### 2. Tap **Connect ESP32**

The app scans for the BLE device and connects.

### 3. Tap **Set Up ESP32 Wi-Fi**

This sends Wi-Fi credentials over BLE and waits for the ESP32 to report a capture URL.

### 4. Confirm capture URL appears

The app should display something like:

```text
Capture URL: http://192.168.1.55/capture
```

### 5. Tap **Start**

The app begins the loop:

* fetch image
* optionally preprocess image
* send to model
* speak result

### 6. Tap **Stop** to halt the loop

### 7. Tap **Sleep Glasses** to put the ESP32 into deep sleep

---

## Default prompt

Current default prompt:

```text
Respond as if you were my internal monologue in the first person. One or two concrete sentences only. Don't be repetitive between responses. Don't start with I.
```

This is intended to produce short, practical, internal-monologue-style observations.

---

## TTS backlog control

The app limits how far ahead it can generate new responses relative to speech playback.

This prevents unnecessary API calls when the speech queue is already backed up.

The current behavior is:

* allow up to **2 messages ahead**
* keep track of the **currently speaking** text
* show both the current spoken text and the last response in the UI

---

## Image preprocessing in the app

Depending on the current app version, the image can be transformed before being sent to the model.

Examples of transformations used during development:

* rotate 90 degrees right
* mirror across vertical axis
* no transform

These transformations happen on the phone so the ESP32 can keep sending the original high-resolution image.

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

* `/` basic status page
* `/capture` JPEG image capture

---

## Flashing the ESP32

### 1. Open Arduino IDE

Install the ESP32 board package if needed.

### 2. Select the correct board

Use the board setting appropriate for the XIAO ESP32S3.

### 3. Check board settings

Recommended starting points:

* PSRAM enabled
* correct flash mode for the board package
* correct serial port selected

### 4. Paste in the firmware

Use the `.ino` sketch from this repo.

### 5. Upload

After upload, open Serial Monitor at:

```text
115200 baud
```

### 6. Confirm boot output

You should see camera init messages and BLE advertising start.

---

## Typical Serial output

A healthy sequence looks something like:

```text
BOOT
Before camera init
Camera init OK
BLE advertising as ESP32-Glasses
Ready
```

When Wi-Fi is provisioned:

```text
Received command: SET_WIFI:YourSSID|YourPassword
Saved SSID: YourSSID
STATUS: WIFI_SAVED
STATUS: WIFI_CONNECTING
Connecting to WiFi SSID: YourSSID
Final WiFi status: 3
WiFi connected
IP address: 192.168.1.55
STATUS: WIFI_IP:192.168.1.55
STATUS: WIFI_CONNECTED:http://192.168.1.55/capture
```

---

## Troubleshooting

### ESP32 keeps rebooting

Start by testing the board in isolation.

Use a tiny sketch first to confirm the board is stable. Then test camera-only. Then test Wi-Fi. Then test BLE.

This helps identify whether the failure is in:

* board setup
* camera setup
* Wi-Fi
* BLE integration

### `cam_hal: FB-OVF`

This means frame buffer overflow. It often happens when the camera is producing frames faster than they are being consumed.

Common mitigations:

* reduce frame size
* increase JPEG compression
* use `fb_count = 1`
* use `CAMERA_GRAB_WHEN_EMPTY`
* avoid streaming-style capture when you only need still frames

### App says cleartext HTTP not permitted

You need to allow plain HTTP traffic to the ESP32.

Add to the Android app:

```xml
android:usesCleartextTraffic="true"
```

### ESP32 connects to Wi-Fi but app does not get capture URL

Possible causes:

* BLE notification missed
* app not parsing `WIFI_CONNECTED:` correctly
* app not handling `WIFI_IP:` fallback correctly

The app should be able to use either:

```text
WIFI_CONNECTED:http://<ip>/capture
```

or:

```text
WIFI_IP:<ip>
```

### `Final WiFi status: 6`

This means the ESP32 failed to join the network and is disconnected.

Common causes:

* wrong SSID or password
* unsupported Wi-Fi security mode
* network is 5 GHz only
* enterprise/captive portal network

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

* ESP32 handles image capture and BLE control
* Android handles image preprocessing, API calls, queueing, and TTS

Trying to do heavy image transforms directly on the ESP32 can reduce reliability and resolution.

For that reason, image transforms are better done in the Android app when possible.

---

## Safety and limitations

This project is an experimental assistive / awareness prototype.

It should not be relied on as a sole navigation or safety device.

Limitations include:

* network latency
* model latency
* BLE timing issues
* image blur / low light conditions
* incomplete scene understanding by the model

---

## Future improvements

Possible next steps:

* save Wi-Fi credentials in ESP32 flash using `Preferences`
* auto-reconnect after wake or reboot
* configurable capture interval in app
* better app-side image transforms
* visual debug preview in the app
* battery / device status reporting over BLE
* wake-from-sleep control flow refinement
* on-device caching / throttling strategies

---

## Development notes

This project went through a few architectural changes:

* started with attempts to send images over BLE
* moved to BLE for setup/control and Wi-Fi for image transfer
* added BLE fallback status reads to improve reliability
* moved image transformation work to the Android app to preserve camera resolution and reduce ESP32 processing load

---

## Contributing

Contributions are welcome.

Good areas for contribution:

* Android BLE robustness
* ESP32 power management
* camera quality tuning
* prompt engineering
* app UI polish
* local image processing improvements

---

## License

Add your preferred license here.

For example:

```text
MIT License
```

---

## Quick start summary

### ESP32

1. flash firmware
2. open serial monitor at 115200
3. confirm BLE advertising starts

### Android

1. install app
2. connect to ESP32
3. send Wi-Fi credentials
4. wait for capture URL
5. press Start

---

note: image is rotated 90 to the right and flipped left right for current hardware