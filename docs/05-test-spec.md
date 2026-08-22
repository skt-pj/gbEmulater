# 試験仕様 0.1.0

UT-01: synthetic ROMをloadした直後にPC=0x0100となる。
UT-02: synthetic ROMでNOP実行後にPCが進む。
UT-03: frame実行でVBlankまで到達し、160x144 framebufferを返す。
UT-04: Joypad mask設定時にP1 readがactive-lowで反映される。

CI-01: `:app:testDebugUnitTest`が成功する。
CI-02: `:app:assembleRelease`が成功する。
CI-03: release APKへ`apksigner verify --verbose --print-certs`が成功する。
CI-04: release APKのSHA-256を保存する。
CI-05: APK、SHA-256、署名情報、build-metadataを同一artifactへ格納する。

AT-01: Pixel 10aへAPKをインストールできる。
AT-02: ROM選択画面から`.gb`を選択し、映像が更新される。
AT-03: D-pad/A/B/START/SELECTの同時入力がゲームへ反映される。
