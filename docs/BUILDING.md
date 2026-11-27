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

`app/build.gradle` signs release builds from a `keystore.properties` file that never enters the repository:

1. Create a keystore outside the project:
   ```powershell
   keytool -genkeypair -keystore C:\keys\liza-release.jks -storetype PKCS12 -alias liza -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Create `keystore.properties` next to it:
   ```properties
   storeFile=C:/keys/liza-release.jks
   storePassword=...
   keyAlias=liza
   keyPassword=...
   ```
3. Point Gradle at it in your **own** `~/.gradle/gradle.properties`: `liza.signing=C:/keys/keystore.properties`. A `keystore.properties` in the project root also works. It, `*.jks` and `*.keystore` are git-ignored.
4. Run `.\gradlew.bat assembleRelease`. The signed APK is `app/build/outputs/apk/release/app-release.apk`.

Without a signing file, `assembleRelease` produces an unsigned APK. Keep a backup of the keystore and its passwords: every update must be signed with the same key, or users have to uninstall first. Code shrinking (R8) is off because the EZVIZ SDK relies on reflection and JNI.

If you turn on R8 (`minifyEnabled true`), add keep rules for the EZVIZ SDK packages (`com.videogo.**`, `com.ezviz.**`). The SDK uses reflection and JNI.
