# MONOLOGUE Android 1.1 — build and publish

This is a source update, not a compiled APK. The original ESP32 firmware does not change. The existing repository APK is still the old version until you replace it.

## Build on Windows

1. Open the project root (the folder containing `settings.gradle.kts`) in Android Studio. Install the SDK requested by the existing project (API 36.1) and let Gradle sync.
2. In Android Studio's PowerShell terminal run:

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
   ```

   The test APK is `app\build\outputs\apk\debug\app-debug.apk`. Install it on a test Android phone; use the original signing key for any distributed update. If signatures differ, Android will reject an in-place update. Do not uninstall your existing app without backing up needed settings.
3. Test BLE connection, Wi-Fi setup, capture, blank and custom prompts, queue values 0/2/5, response length, speed/pitch, voice selection, persistence, Stop/Disconnect/Sleep and image-change options with the real glasses. No Android build or hardware test was performed in the preparation environment.
4. For public distribution, use Build > Generate Signed Bundle / APK > APK in Android Studio and sign with the same key as the original distributed APK. Do not commit the keystore, credentials or local.properties.
5. Replace the repository-root `ESPGLASSESAPP.apk` with the tested signed APK; keep the filename so existing website links continue to work. The APK built from this source has version name 1.1 and version code 2.

## Push the source

Clone the repository rather than initializing a new repository. In PowerShell:

```powershell
git clone https://github.com/ob1ong/ESP32-LLM-internal-monolouge-BLE-APP.git
cd ESP32-LLM-internal-monolouge-BLE-APP
git apply --check "$HOME\Downloads\MONOLOGUE-android-v1.1.patch"
git apply "$HOME\Downloads\MONOLOGUE-android-v1.1.patch"
git status
git add README.md UPDATE.md app/build.gradle.kts app/src/main/java/com/example/esp32aiglasses app/src/test/java/com/example/esp32aiglasses/ResponseSettingsTest.kt
git commit -m "Add MONOLOGUE customisation settings"
git push origin main
```

If the patch check fails, do not force it; the repository may have changed since the supplied ZIP. Review/merge the changed files instead. If you already have a clone, use it after fetching/pulling the latest changes with a clean working tree. Sign into GitHub when prompted.

Once the tested APK has been generated and copied to the root:

```powershell
git add ESPGLASSESAPP.apk
git commit -m "Publish MONOLOGUE Android 1.1 APK"
git push origin main
```

You may optionally create a GitHub Release and attach the APK and unchanged INO file.
