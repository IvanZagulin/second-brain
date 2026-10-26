# Android build and releases

Package `ru.secondbrain.app`, minSdk 29, target/compile SDK 36. Kotlin 2.2, AGP 8.13, Gradle 8.14,
Jetpack Compose (Material 3). Project lives in `android/`.

## Local build

Requirements: JDK 17+ (Android Studio's bundled JBR is fine) and the Android SDK (`local.properties`:
`sdk.dir=…`).

```bash
cd android
./gradlew :app:assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

Windows: AGP refuses project paths with non-ASCII characters. Map the repository to a drive letter
first, e.g. `subst S: "E:\второй мозг\second-brain"`, and build from `S:\android`.

Debug and release share the package name (Firebase is configured for it), so they replace each other on the
device. Debug builds additionally allow plain HTTP to `10.0.2.2`/`localhost` for emulator testing.

## Signing

The release key is permanent: the same key must sign every update, otherwise Android refuses to install it
over the previous version. Keep a copy of the keystore and its password outside the repository.

Locally, `android/keystore.properties` (gitignored):

```properties
storeFile=/absolute/path/release.jks
storePassword=…
keyAlias=secondbrain
keyPassword=…
```

In CI the same values come from repository secrets `SIGNING_KEYSTORE_BASE64` (base64 of the `.jks`),
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

Create a new key (only for a brand-new installation):

```bash
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias secondbrain -keyalg RSA -keysize 4096 -validity 36500
```

## Releasing

1. Add a `## [x.y.z]` section to `CHANGELOG.md`.
2. `git tag vx.y.z && git push origin vx.y.z`
3. `.github/workflows/release.yml` runs backend and Android tests, builds `app-release.apk` with
   `-PappVersion=x.y.z`, verifies the signature, writes `second-brain-x.y.z.apk.sha256` and publishes a GitHub
   Release with both files and the changelog section as notes.

`versionCode` is derived from the version (`major*10000 + minor*100 + patch`), so every new version installs over
the previous one.

## Updates on the phone

Settings → «Обновления» (and a 12-hourly background check) reads the latest GitHub Release (directly, or via
`/api/v1/system/updates/latest` on the server if GitHub is unreachable), downloads the APK, verifies its SHA-256
and hands it to the system installer. Android asks to allow installing from this app once, and confirms every
installation; silent installs are not possible. App data survives updates.

The very first APK is installed manually (download it from Releases on the phone and open it).

## Firebase

No `google-services.json` is bundled. The server reads it from `deploy/secrets/` and returns the client options
at pairing; the app initialises Firebase at runtime and registers its FCM token. Without Firebase the app polls
the server every 15 minutes (WorkManager) and on every launch.
