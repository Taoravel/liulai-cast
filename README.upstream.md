# AirPlay &amp; DLNA Receiver for Android (TV · Phone · Tablet)

Turn any Android device — especially an **Android TV** — into a wireless receiver.

- 📱 **AirPlay** — mirror or stream from iPhone, iPad, and Mac.
- 🎵 **DLNA** — play video / music / photo files sent from Android apps or a Windows PC.
- 🔁 **Screen mirror an Android phone** to the TV, even if the TV's built‑in Cast / Miracast doesn't work.

Works on **Android 9 through Android 16**. One app for TVs, phones, and tablets.

> Based on the excellent open‑source [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server), with DLNA, in‑app screen mirroring, dark mode, and Android‑TV UI added on top.

---

## ✨ What it can do

| Feature | What it means |
|---|---|
| **AirPlay receiver** | Press *Screen Mirroring* on an iPhone/iPad/Mac and your screen (or audio) shows up on the Android device. H.264 + H.265 video, AAC/ALAC audio. |
| **DLNA media renderer** | From Android apps with a *"Play to / Cast to device"* button, or Windows' *"Cast to Device"*, send a video/song/photo **file** to play here. |
| **Android → TV screen mirroring** | Install the app on a phone too, tap **Mirror my screen to a TV**, and the phone's screen streams to the TV over Wi‑Fi. No Chromecast/Miracast needed. |
| **Background service** | Starts on boot, keeps running, and jumps to the front when a device connects. |
| **Dark mode** | System / Light / Dark, with a clean colour theme. |
| **Android TV ready** | Full remote (D‑pad) navigation with clear focus highlighting. |

---

## 📥 Install

1. Open the **[Releases](../../releases)** page.
2. Download the latest **`AirPlayDLNAReceiver-vX.X.X.apk`**.
3. Open the file on your Android device and tap **Install**. If asked, allow *"Install unknown apps"*.

### Installing on an Android TV (using a PC)

Android TVs usually have no file manager, so use **adb** over the network:

1. On the TV: **Settings → System → About → press "Build" 7 times** to unlock Developer options, then turn on **Network debugging / ADB debugging**. Note the TV's IP address.
2. On your PC (with [platform‑tools](https://developer.android.com/tools/releases/platform-tools) installed):
   ```bash
   adb connect <TV-IP>:5555
   adb install -r AirPlayDLNAReceiver-v1.0.0.apk
   ```
3. Accept the prompt on the TV. The app appears in the launcher as **"AirPlay Server"**.

You can also copy the APK to a USB stick and install it with a TV file‑manager app.

---

## 🚀 How to use

Make sure the sending device and the Android receiver are on the **same Wi‑Fi network**.

### From iPhone / iPad / Mac (AirPlay)
1. Open the app on the receiver once so the server starts.
2. On the Apple device open **Control Center → Screen Mirroring** (or AirPlay from a video/music app).
3. Pick your device from the list. Your screen / audio appears on the receiver.

### From Android or Windows — play a media file (DLNA)
- **Android:** in an app that has a *"Play to / Cast to device"* option (many video and music players), pick this device.
- **Windows:** open the **Movies &amp; TV** app or right‑click a video in **File Explorer → Cast to Device**, then pick this device.

### Mirror an Android phone's screen to the TV
1. Install this same app on the phone **and** the TV.
2. On the phone, open the app and tap **"Mirror my screen to a TV"**.
3. Choose your TV from the list and approve the screen‑capture prompt. Your phone screen shows on the TV.

### A note about "Cast" and "Miracast"
Google Cast (the Chromecast button) and Miracast **receivers** are part of a TV's system software and **cannot be added by any app** — so this app can't turn a TV into a Chromecast. If your TV already has them, use them directly. This app gives you **AirPlay**, **DLNA**, and its **own Android→TV mirroring** as open alternatives.

---

## 🔐 Permissions (asked on first launch)

The app shows a short welcome screen asking for:

- **Notifications** – keeps the background service alive.
- **Display over other apps** – lets the picture pop up over whatever is on screen when a device connects.
- **Disable battery optimization** – stops the system from killing the background receiver.

You can change these any time in your system settings.

> DRM‑protected content (Netflix, the Apple TV app, etc.) **cannot** be received — that's an Apple/Google restriction, not a bug.

---

## ⚙️ Settings worth knowing

- **Device name** – what shows up in the AirPlay/DLNA list.
- **Resolution / Max FPS** – lower these (e.g. 1280×720 @ 30) on cheaper TVs if mirroring stutters.
- **PIN** – require a code before a device can connect.
- **Theme** – System / Light / Dark.
- Toggles for the **DLNA** renderer and **screen‑mirroring** receiver.

---

## 🛠️ Build it yourself

Requirements: Android SDK (platform 35), **NDK 27.0.12077973**, **CMake 3.22.1**, JDK 17+. The native AirPlay core is compiled with the NDK; OpenSSL is pulled in as a prebuilt for Android, so no extra setup is needed there. See **[BUILDING.md](BUILDING.md)** for full details.

```bash
git clone https://github.com/F-e-n-y-x/airplay-dlna-receiver.git
cd airplay-dlna-receiver
./gradlew assembleRelease   # output: app/build/outputs/apk/release/
```

> Build from a path **without spaces** — the Android NDK fails on paths that contain spaces.

---

## 🙏 Credits

- **[jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)** — the AirPlay receiver this project is built on.
- **[UxPlay](https://github.com/FDH2/UxPlay)** — the AirPlay / RAOP protocol core.
- **[ALAC](https://github.com/macosforge/alac)**, **[libplist](https://github.com/libimobiledevice/libplist)**, **[openssl‑cmake](https://github.com/viaduck/openssl-cmake)**.
- Screen mirroring uses Android **MediaProjection** + **Media3 (ExoPlayer)**.

## 📄 License

Licensed under **GPL‑3.0** (because it builds on GPL‑3.0 projects). See **[LICENSE](LICENSE)**. If you share a modified version, you must also share your source under GPL‑3.0.

## ⚠️ Disclaimer

This project is **not affiliated with Apple Inc.** "AirPlay" is a trademark of Apple Inc. Use it with content you have the right to stream.
