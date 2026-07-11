# Android Platform Tools attribution

ADB Touchpad redistributes one executable from the AndroidIDE Android Platform Tools build:

| Field | Value |
| --- | --- |
| Packaged file | `app/src/main/jniLibs/arm64-v8a/libadbexec.so` |
| Original file | `platform-tools/adb` |
| Release | Android Platform Tools 34.0.4 for ARM (`armeabi-v7a`) |
| Release asset | [`platform-tools-34.0.4-arm.tar.xz`](https://github.com/AndroidIDEOfficial/androidide-tools/releases/download/v34.0.4/platform-tools-34.0.4-arm.tar.xz) |
| Source tag | [`AndroidIDEOfficial/platform-tools@v34.0.4`](https://github.com/AndroidIDEOfficial/platform-tools/tree/v34.0.4) |
| SHA-256 | `7d250fc83094cef408dd11ddb228d4f955a3e21c3b0773403bba323efa927707` |

The file is byte-for-byte identical to `adb` in that release asset. It is named `libadbexec.so` only so Android installs it into an app-owned executable native-library directory; ADB Touchpad launches it as a separate process and does not link it as a native library.

The executable is a 32-bit ARM ELF even though the APK packages it under `arm64-v8a`. A compatible 32-bit userspace is therefore required.

The AndroidIDE platform-tools build project is distributed under GPL-3.0; see [`GPL-3.0.txt`](GPL-3.0.txt). Notices for ADB and its bundled upstream components are reproduced verbatim in [`NOTICE.txt`](NOTICE.txt), taken from the same 34.0.4 ARM release archive.
