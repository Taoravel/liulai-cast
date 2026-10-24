# Building from source

## Requirements

- **JDK 17+** (the Android Studio bundled JBR works).
- **Android SDK** with:
  - Platform **android-35**
  - **NDK 27.0.12077973**
  - **CMake 3.22.1**
- A build path **without spaces** (the Android NDK's reproducible‑build flags break on paths that contain spaces).

Install the SDK pieces with `sdkmanager`:

```bash
sdkmanager "platforms;android-35" "ndk;27.0.12077973" "cmake;3.22.1"
```

## Build

```bash
git clone https://github.com/F-e-n-y-x/airplay-dlna-receiver.git
cd airplay-dlna-receiver
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

`assembleDebug` works too and is faster.

## Notes on the native code

- The AirPlay/RAOP core is the C library [UxPlay](https://github.com/FDH2/UxPlay), compiled with the NDK (`app/src/main/cpp`). The third‑party sources (UxPlay, ALAC, libplist, openssl‑cmake) are vendored in this repo, so you do **not** need `git submodule` — a plain clone builds.
- **OpenSSL** is not compiled from source. `app/src/main/cpp/CMakeLists.txt` uses prebuilt OpenSSL for Android (downloaded at configure time by `openssl-cmake`), which keeps the build working on any host OS, including Windows.
- Video is decoded with Android **MediaCodec** and rendered to a `SurfaceView`; audio uses MediaCodec or a software ALAC fallback.

## Signing

The release build is unsigned unless you provide a keystore. To sign, create one and add these lines to `local.properties` (this file is git‑ignored and must never be committed):

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=********
keyAlias=********
keyPassword=********
```

Generate a keystore with:

```bash
keytool -genkeypair -v -keystore your-release.jks -alias your-alias \
  -keyalg RSA -keysize 2048 -validity 10000
```

## Version range

`minSdk 26` (Android 9) → `targetSdk 35`. Android 8 is the floor because the prebuilt OpenSSL uses a libc function (`__sendto_chk`) that only exists on API 26+.

## License

GPL‑3.0. Any distributed build must make its corresponding source available under GPL‑3.0.

