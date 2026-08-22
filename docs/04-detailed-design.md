# 詳細設計 0.1.0

DD-01: `GameBoy`はpost-boot DMGレジスタ値からPC=0x0100で起動する。CPU decodeはSM83のx/y/z規則を使い、CB命令も規則 decodeする。
DD-02: 0x0000-0x7FFFをCartridge ROM、0x8000-0x9FFFをVRAM、0xA000-0xBFFFを外部RAM、0xC000-0xDFFFをWRAM、0xFE00-0xFE9FをOAM、0xFF80-0xFFFEをHRAMとして扱う。
DD-03: PPUは456 T-cycle/line、144 visible lines + 10 VBlank linesで進め、Mode 2/3/0とVBlankを更新する。
DD-04: visible line終了時にBG/Window/SpriteをDMG paletteで160x144 framebufferへ合成する。
DD-05: TimerはDIVとTAC周波数に応じたTIMA更新を行い、overflow時にTMA reloadとTimer interruptを要求する。
DD-06: MainActivityのemulation threadはframe readyまでcoreを進め、約59.7fpsを上限としてGameBoyViewを更新する。
DD-07: GameBoyViewは全pointerからbutton maskを再構成し、pointer up時の押しっぱなしを防ぐ。
