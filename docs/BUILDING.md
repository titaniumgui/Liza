# Building Liza

## Requirements

| Tool | Version |
|---|---|
| JDK | 17–21 |
| Android SDK | Platform 35 and Build-Tools 35 |
| Gradle | 8.11.1 (downloaded by the wrapper) |
| Android Gradle Plugin | 8.7.3 |

Android Studio Ladybug or newer includes all of these.

## Steps

1. Clone the repository and open the folder in Android Studio, **or** create `local.properties` with your SDK path:
   ```properties
   sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
   ```
2. Make sure Gradle can find a JDK 17–21: set `JAVA_HOME`, or add `org.gradle.java.home=<path to JDK>` to your **own** `~/.gradle/gradle.properties`, not the project's.
3. Build the debug APK:
   ```powershell
   $env:JAVA_HOME = 'C:\path\to\jdk-17'
   .\gradlew.bat assembleDebug
   ```
   On macOS and Linux, generate a shell wrapper first with `gradle wrapper`, or build from Android Studio.
4. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The EZVIZ SDK is downloaded from Maven Central (`io.github.ezviz-open:ezviz-sdk:5.33.1`). No manual SDK download is needed.

## Installing on a device

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Use a physical ARM phone. x86_64 emulator images work only when they include ARM translation; the Google Play API 30+ images do.

## Release builds

The project has no release signing configuration yet. To publish a signed release:

1. Create a keystore. `*.jks` and `*.keystore` are already git-ignored, so it stays out of the repository.
2. Add a `signingConfigs.release` block that reads its passwords from environment variables or `~/.gradle/gradle.properties`, never from the repository.
3. Run `.\gradlew.bat assembleRelease`.

If you turn on R8 (`minifyEnabled true`), add keep rules for the EZVIZ SDK packages (`com.videogo.**`, `com.ezviz.**`). The SDK uses reflection and JNI.
