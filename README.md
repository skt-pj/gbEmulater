# gbEmulater

Android向けの初代Game Boyエミュレータ。初期版はPixel 10a（Android 16）を対象に、端末内の`.gb` ROMを選択して実行する。

## 実装済み

- SM83/LR35902 CPU命令（通常命令・CB prefix）
- 割り込み、タイマー、Joypad
- DMG PPU（BG / Window / Sprite）
- ROM only / MBC1 / MBC3 / MBC5
- Android Storage Access FrameworkによるROM選択
- 画面上のD-pad / A / B / START / SELECT
- release APKの共通署名とGitHub Actionsでの検証

## 現時点の制約

- 音声(APU)は未実装
- MBC3 RTCは未実装
- Game Boy Color専用モードは未対応
- ROMは同梱しない

## Build

Android Studioで開くか、Gradle 8.13 + JDK 17で実行する。

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

release buildでは`ci/skt-common-signing.jks`が必要。GitHub Actionsは共通署名materialから復元する。
