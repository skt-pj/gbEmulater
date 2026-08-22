# 基本設計 0.1.0

BD-01 (REQ-01): MainActivityがStorage Access FrameworkでROMを取得する。
BD-02 (REQ-02,03,05): pure JavaのGameBoy coreがCPU/Bus/PPU/Timer/Cartridgeを保持する。
BD-03 (REQ-04): GameBoyViewがフレーム表示とタッチ領域を担当し、coreへbutton maskを渡す。
BD-04 (REQ-06): compileSdk/targetSdk 36、Java 17、minSdk 23のAndroid applicationとする。
BD-05 (REQ-07): GitHub Actionsでunit test、release build、apksigner、SHA-256、artifact uploadを行う。
BD-06 (REQ-08): ROMはdocument Uriからメモリへ読み込み、repository/assetsには置かない。
