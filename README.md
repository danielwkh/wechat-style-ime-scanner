# Inkstone Input

Inkstone Input is an original personal-use Android keyboard with Traditional Chinese pinyin entry and an in-keyboard barcode and QR scanner. It is not affiliated with WeChat and is not intended for app-store distribution.

## Build

Requirements: JDK 17 and Android SDK Platform 35.

```sh
./gradlew assembleDebug
```

The installable APK is `app/build/outputs/apk/debug/app-debug.apk`. Each push builds a debug APK and creates a GitHub Release with the APK attached. The workflow uses the built-in repository `GITHUB_TOKEN`; no extra secret is needed.

## Install and enable

1. Install `app/build/outputs/apk/debug/app-debug.apk` on your Android device. If prompted, allow installation from the app or browser used to open it.
2. Open **Inkstone Input** and tap **前往管理鍵盤**. Enable **Inkstone 注音拼音** in Android's keyboard settings.
3. In any text field, open the system keyboard picker and select **Inkstone 注音拼音**.
4. Tap **掃描** and grant camera access when asked. A recognized barcode or QR code is inserted into the active text field.

The keyboard's **中/EN** control toggles pinyin composition and direct Latin input; **?123** opens the number/symbol layout. Tap a candidate to commit it, or use space to commit the first candidate (and raw pinyin when no candidate exists). The bottom row provides punctuation, space, and the editor action/newline key.

Pinyin candidates come from a small built-in local dictionary. Scanning uses CameraX and on-device ML Kit barcode recognition; scan contents are not uploaded.
