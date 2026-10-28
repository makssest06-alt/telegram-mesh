# Mesh-Telegram APK Build Instructions

## Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 35
- NDK 27.2.12479018
- 16GB+ RAM (NDK compilation is heavy)

## Step-by-step

1. Open project in Android Studio:
   ```
   File → Open → C:\Users\Colorful\opencodeProject\Telegram-master
   ```

2. Wait for Gradle sync. If NDK path is wrong, set `ndk.dir` in `local.properties`:
   ```
   ndk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk\\ndk\\27.2.12479018
   ```

3. Change applicationId (to avoid conflict with official Telegram):
   - Edit `gradle.properties`: set `APP_PACKAGE=org.telegram.mesh`
   - Or override in `TMessagesProj_App/build.gradle`:
     ```groovy
     defaultConfig {
         applicationId "org.telegram.mesh"
     }
     ```

4. Build APK:
   - Debug: `Build → Select Build Variant → afatDebug → Build APK`
   - Or via terminal: `./gradlew :TMessagesProj_App:assembleAfatDebug`

5. APK location:
   ```
   TMessagesProj_App/build/outputs/apk/afat/debug/
   ```

## Known issues
- Google Play Services Nearby requires Google Play Services on device (emulator with Play Store)
- First launch: grant Nearby Devices permission in Settings
- The mesh feature is in the app's Settings → Mesh Network
- Enable "Mesh Mode" there, then it auto-discovers peers on the same WiFi/Nearby

## Build variants
- `afatDebug`: full debug build
- `afatRelease`: release (requires signing key)
- For testing, use `afatDebug` on two devices on same WiFi
