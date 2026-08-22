package com.sktpj.gbemulator.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GameBoyTest {
    private static byte[] syntheticRom() {
        byte[] rom = new byte[0x8000];
        rom[0x100] = 0x00;
        rom[0x101] = (byte) 0x18;
        rom[0x102] = (byte) 0xFE;
        byte[] title = "TESTROM".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, rom, 0x134, title.length);
        rom[0x147] = 0x00;
        rom[0x149] = 0x00;
        return rom;
    }

    @Test
    public void loadStartsAtPostBootEntryPoint() {
        GameBoy gb = new GameBoy();
        gb.loadRom(syntheticRom());
        assertEquals(0x0100, gb.getProgramCounter());
        assertEquals("TESTROM", gb.getRomTitle());
    }

    @Test
    public void nopAdvancesProgramCounter() {
        GameBoy gb = new GameBoy();
        gb.loadRom(syntheticRom());
        gb.step();
        assertEquals(0x0101, gb.getProgramCounter());
    }

    @Test
    public void runFrameProducesFrameBuffer() {
        GameBoy gb = new GameBoy();
        gb.loadRom(syntheticRom());
        gb.runFrame();
        assertEquals(GameBoy.WIDTH * GameBoy.HEIGHT, gb.copyFrameBuffer().length);
        assertTrue(gb.getProgramCounter() >= 0x0100);
    }

    @Test
    public void joypadUsesActiveLowBits() {
        GameBoy gb = new GameBoy();
        gb.loadRom(syntheticRom());
        gb.setButtons(GameBoy.BTN_RIGHT | GameBoy.BTN_A);
        assertEquals(0, gb.debugRead(0xFF00) & 0x01);
    }
}
