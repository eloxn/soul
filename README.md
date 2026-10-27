<div align="center">

<img src="./music/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="128" height="128" alt="Soul Logo" style="border-radius: 20%;" />

# Soul

**A powerful, modern, and completely free audio player for Android.**

Created with ❤️ by **[Eloxxn](https://github.com/eloxn)**

<p align="center">
  <a href="https://github.com/eloxn/soul/releases/latest/download/Soul-v1.0.1.apk">
    <img src="https://img.shields.io/badge/Download-Soul%20APK%20(v1.0.1)-blue?style=for-the-badge&logo=android&logoColor=white" alt="Download Soul APK" />
  </a>
</p>

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](./LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android_10+-green.svg?logo=android&logoColor=white)](https://www.android.com/)
[![GitHub Repo](https://img.shields.io/badge/GitHub-eloxn%2Fsoul-181717.svg?logo=github&logoColor=white)](https://github.com/eloxn/soul)
[![Latest Release](https://img.shields.io/github/v/release/eloxn/soul?color=success&label=Release)](https://github.com/eloxn/soul/releases/latest)

</div>

---

## 📥 Direct Download

You can download the latest signed release APK directly using the link below:

👉 **[Download Soul v1.0.1 APK](https://github.com/eloxn/soul/releases/download/v1.0.1/Soul-v1.0.1.apk)** *(39.3 MB • Signed with Scheme v2)*

All release notes and assets are also available on the **[Releases Page](https://github.com/eloxn/soul/releases)**.

---

## 🎵 About Soul

**Soul** is a high-performance, fully unlocked, audiophile-grade music player designed from the ground up for Android. Built with a passion for pristine acoustics and modern fluid design, Soul combines a custom low-latency C++ DSP audio engine with dynamic theming to bring your music to life.

Soul is **100% free and open-source** — no subscriptions, no ads, no paywalls, and no feature gates.

---

## ✨ Features

### 🎧 Custom C++ Audio Engine & DSP
- **Dual Decoder:** Seamless combination of hardware decoding and software decoding through FFmpeg.
- **Audiophile High-Res Support:** Native playback for FLAC, ALAC, DSD, WAV, MP3, AAC, and multi-channel audio (5.1 and 7.1).
- **10-Band Equalizer:** Precision 10-band EQ with +/-15 dB adjustment and customizable PreAmp.
- **Hardware Acceleration:** Native DSP audio processing utilizes ARM NEON SIMD auto-vectorization for near-zero CPU overhead and battery conservation.
- **Advanced Sound Effects:** Built-in Bass Boost, Treble adjustment, Reverb, Stereo Widening, and Tape Saturation for an authentic analog warmth.
- **Gapless Playback:** Uninterrupted transitions between tracks.
- **Real-Time Visualizers:**
  - Real-time Milkdrop visualizer rendering on GL surface at native display refresh rates.
  - Ultra-fast Canvas spectrum visualizer powered by a native PFFFT implementation.

### 🎨 Modern & Dynamic UI
- **Live Synced Lyrics Widget:** Interactive home screen widget showing active synchronized lyrics with previous/current/upcoming line previews and media controls.
- **Album Art Theming:** Interface dynamically adapts its palette to match the artwork of the currently playing track.
- **Material You & Custom Themes:** Full support for Android 12+ Monet dynamic theming alongside AMOLED Dark, Slate, Oil, and Light themes.
- **Edge-to-Edge:** Native predictive back animations, gesture navigation, and edge-to-edge layouts.
- **Synchronized Lyrics:** Instant LRC extraction from embedded tags and automatic online lyrics retrieval via LrcLib.
- **Dual Fast Scroll:** Jump to letters quickly or drag smoothly through large music libraries.

### 📂 Smart Library Management
- **Instant Auto-Scan:** Automatically recognizes additions and deletions in your media storage in real-time.
- **Playlists & Queues:** Robust M3U playlist support, multiple queue management, tag editing, and bookmarks.
- **True Randomized Shuffle:** Choose between Miller and Fisher-Yates shuffle algorithms.
- **Local Server Mode:** Host a lightweight local web server directly from your phone to stream music to other devices on your Wi-Fi network.

---

## 📸 Screenshots

| Player & Lyrics | Equalizer & DSP | Library |
| :---: | :---: | :---: |
| <img src="./screenshots/01.png" width="250" /> | <img src="./screenshots/04.png" width="250" /> | <img src="./screenshots/07.png" width="250" /> |
| <img src="./screenshots/02.png" width="250" /> | <img src="./screenshots/05.png" width="250" /> | <img src="./screenshots/08.png" width="250" /> |
| <img src="./screenshots/03.png" width="250" /> | <img src="./screenshots/06.png" width="250" /> | <img src="./screenshots/11.png" width="250" /> |

---

## 🛠️ Building from Source

### Prerequisites
- Android Studio / Android SDK (API 34+)
- Android NDK & CMake
- JDK 17 or JDK 21

### Build Instructions
Clone the repository:
```bash
git clone https://github.com/eloxn/soul.git
cd soul
```

Compile the release APK:
```bash
# Build the FOSS release APK
./gradlew :music:assembleFossRelease

# Or build the standard release APK
./gradlew :music:assemblePlayRelease
```

The compiled APK will be located in:
`music/build/outputs/apk/foss/release/music-foss-release.apk`

---

## 👤 Creator

Soul is created and maintained by **[Eloxxn](https://github.com/eloxn)**.

If you enjoy using Soul or want to contribute, feel free to star the repository or submit issues on GitHub!

---

## 📄 License

Soul is distributed under the [GNU AGPL v3 License](./LICENSE).
