package com.sktpj.gbemulator.core;

import java.util.Arrays;

/**
 * Small DMG Game Boy core intended for the Android MVP.
 * It implements the SM83 CPU, DMG PPU, timer, joypad and common ROM mappers.
 * APU and MBC3 RTC are intentionally outside the 0.1.0 scope.
 */
public final class GameBoy {
    public static final int WIDTH = 160;
    public static final int HEIGHT = 144;

    public static final int BTN_RIGHT = 1 << 0;
    public static final int BTN_LEFT = 1 << 1;
    public static final int BTN_UP = 1 << 2;
    public static final int BTN_DOWN = 1 << 3;
    public static final int BTN_A = 1 << 4;
    public static final int BTN_B = 1 << 5;
    public static final int BTN_SELECT = 1 << 6;
    public static final int BTN_START = 1 << 7;

    private static final int FLAG_Z = 0x80;
    private static final int FLAG_N = 0x40;
    private static final int FLAG_H = 0x20;
    private static final int FLAG_C = 0x10;

    private static final int MBC_NONE = 0;
    private static final int MBC1 = 1;
    private static final int MBC3 = 3;
    private static final int MBC5 = 5;

    private static final int[] DMG_COLORS = {
            0xFFE0F8D0,
            0xFF88C070,
            0xFF346856,
            0xFF081820
    };

    private byte[] rom = new byte[0];
    private byte[] eram = new byte[0];
    private final byte[] vram = new byte[0x2000];
    private final byte[] wram = new byte[0x2000];
    private final byte[] oam = new byte[0xA0];
    private final byte[] hram = new byte[0x7F];
    private final int[] io = new int[0x80];
    private final int[] frame = new int[WIDTH * HEIGHT];
    private final int[] bgColorIndex = new int[WIDTH];

    private int cartType;
    private int mbcType;
    private boolean ramEnabled;
    private int romBank = 1;
    private int ramBank;
    private int mbc1Low = 1;
    private int mbc1High;
    private int mbc1Mode;
    private int mbc5BankHigh;

    private int a, f, b, c, d, e, h, l;
    private int sp, pc;
    private boolean ime;
    private int imeDelay;
    private boolean halted;

    private int ie;
    private int divCounter;
    private int timaCounter;
    private int lineCycles;
    private int ppuMode = 2;
    private boolean frameReady;
    private int joypadMask;

    public GameBoy() {
        Arrays.fill(frame, DMG_COLORS[0]);
        resetMachine();
    }

    public synchronized void loadRom(byte[] data) {
        if (data == null || data.length < 0x150) {
            throw new IllegalArgumentException("Game Boy ROM is too small");
        }
        rom = Arrays.copyOf(data, data.length);
        cartType = u8(rom[0x147]);
        mbcType = detectMbc(cartType);
        eram = new byte[ramSizeFromHeader(u8(rom[0x149]))];
        Arrays.fill(vram, (byte) 0);
        Arrays.fill(wram, (byte) 0);
        Arrays.fill(oam, (byte) 0);
        Arrays.fill(hram, (byte) 0);
        Arrays.fill(frame, DMG_COLORS[0]);
        resetMachine();
    }

    public synchronized boolean hasRom() {
        return rom.length >= 0x150;
    }

    public synchronized String getRomTitle() {
        if (!hasRom()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0x134; i <= 0x143 && i < rom.length; i++) {
            int ch = u8(rom[i]);
            if (ch == 0) break;
            if (ch >= 0x20 && ch <= 0x7E) sb.append((char) ch);
        }
        return sb.toString().trim();
    }

    public synchronized int getProgramCounter() {
        return pc & 0xFFFF;
    }

    public synchronized int[] copyFrameBuffer() {
        return Arrays.copyOf(frame, frame.length);
    }

    public synchronized void setButtons(int mask) {
        int before = readJoypad() & 0x0F;
        joypadMask = mask & 0xFF;
        int after = readJoypad() & 0x0F;
        if ((before & ~after) != 0) {
            requestInterrupt(4);
            if (halted) halted = false;
        }
    }

    public synchronized int debugRead(int address) {
        return read8(address);
    }

    public synchronized int step() {
        if (!hasRom()) return 0;
        int cycles = cpuStep();
        tickTimer(cycles);
        tickPpu(cycles);
        return cycles;
    }

    public synchronized void runFrame() {
        if (!hasRom()) return;
        frameReady = false;
        int guard = 200_000;
        while (!frameReady && guard-- > 0) {
            int cycles = cpuStep();
            tickTimer(cycles);
            tickPpu(cycles);
        }
    }

    private void resetMachine() {
        a = 0x01;
        f = 0xB0;
        b = 0x00;
        c = 0x13;
        d = 0x00;
        e = 0xD8;
        h = 0x01;
        l = 0x4D;
        sp = 0xFFFE;
        pc = 0x0100;
        ime = false;
        imeDelay = 0;
        halted = false;
        ie = 0;
        divCounter = 0;
        timaCounter = 0;
        lineCycles = 0;
        ppuMode = 2;
        frameReady = false;
        joypadMask = 0;
        ramEnabled = false;
        romBank = 1;
        ramBank = 0;
        mbc1Low = 1;
        mbc1High = 0;
        mbc1Mode = 0;
        mbc5BankHigh = 0;

        Arrays.fill(io, 0);
        io[0x00] = 0xCF;
        io[0x0F] = 0xE1;
        io[0x40] = 0x91;
        io[0x41] = 0x82;
        io[0x44] = 0;
        io[0x47] = 0xFC;
        io[0x48] = 0xFF;
        io[0x49] = 0xFF;
    }

    private int detectMbc(int type) {
        if (type >= 0x01 && type <= 0x03) return MBC1;
        if (type >= 0x0F && type <= 0x13) return MBC3;
        if (type >= 0x19 && type <= 0x1E) return MBC5;
        return MBC_NONE;
    }

    private int ramSizeFromHeader(int code) {
        return switch (code) {
            case 0x01 -> 2 * 1024;
            case 0x02 -> 8 * 1024;
            case 0x03 -> 32 * 1024;
            case 0x04 -> 128 * 1024;
            case 0x05 -> 64 * 1024;
            default -> 0;
        };
    }

    private int cpuStep() {
        int pending = (ie & io[0x0F]) & 0x1F;
        if (pending != 0) {
            halted = false;
            if (ime) {
                int bit = Integer.numberOfTrailingZeros(pending);
                ime = false;
                io[0x0F] &= ~(1 << bit);
                push16(pc);
                pc = 0x40 + bit * 8;
                return 20;
            }
        }

        if (halted) return 4;

        int op = fetch8();
        int cycles = executeOpcode(op);
        if (imeDelay > 0) {
            imeDelay--;
            if (imeDelay == 0) ime = true;
        }
        return cycles;
    }

    private int executeOpcode(int op) {
        int x = op >>> 6;
        int y = (op >>> 3) & 7;
        int z = op & 7;
        int p = y >>> 1;
        int q = y & 1;

        if (x == 0) {
            switch (z) {
                case 0:
                    if (y == 0) return 4;
                    if (y == 1) {
                        int addr = fetch16();
                        write8(addr, sp & 0xFF);
                        write8(addr + 1, (sp >>> 8) & 0xFF);
                        return 20;
                    }
                    if (y == 2) {
                        fetch8();
                        return 4;
                    }
                    if (y == 3) {
                        pc = (pc + signed8(fetch8())) & 0xFFFF;
                        return 12;
                    }
                    int off = signed8(fetch8());
                    if (condition(y - 4)) {
                        pc = (pc + off) & 0xFFFF;
                        return 12;
                    }
                    return 8;
                case 1:
                    if (q == 0) {
                        setRp(p, fetch16());
                        return 12;
                    }
                    addHl(getRp(p));
                    return 8;
                case 2:
                    if (q == 0) {
                        int addr = switch (p) {
                            case 0 -> getBC();
                            case 1 -> getDE();
                            case 2 -> getHL();
                            default -> getHL();
                        };
                        write8(addr, a);
                        if (p == 2) setHL((getHL() + 1) & 0xFFFF);
                        if (p == 3) setHL((getHL() - 1) & 0xFFFF);
                    } else {
                        int addr = switch (p) {
                            case 0 -> getBC();
                            case 1 -> getDE();
                            case 2 -> getHL();
                            default -> getHL();
                        };
                        a = read8(addr);
                        if (p == 2) setHL((getHL() + 1) & 0xFFFF);
                        if (p == 3) setHL((getHL() - 1) & 0xFFFF);
                    }
                    return 8;
                case 3:
                    if (q == 0) setRp(p, (getRp(p) + 1) & 0xFFFF);
                    else setRp(p, (getRp(p) - 1) & 0xFFFF);
                    return 8;
                case 4: {
                    int value = getR(y);
                    int result = (value + 1) & 0xFF;
                    int carry = f & FLAG_C;
                    f = carry;
                    if (result == 0) f |= FLAG_Z;
                    if ((value & 0x0F) == 0x0F) f |= FLAG_H;
                    setR(y, result);
                    return y == 6 ? 12 : 4;
                }
                case 5: {
                    int value = getR(y);
                    int result = (value - 1) & 0xFF;
                    int carry = f & FLAG_C;
                    f = carry | FLAG_N;
                    if (result == 0) f |= FLAG_Z;
                    if ((value & 0x0F) == 0) f |= FLAG_H;
                    setR(y, result);
                    return y == 6 ? 12 : 4;
                }
                case 6:
                    setR(y, fetch8());
                    return y == 6 ? 12 : 8;
                case 7:
                    executeMiscRotate(y);
                    return 4;
                default:
                    return 4;
            }
        }

        if (x == 1) {
            if (y == 6 && z == 6) {
                halted = true;
                return 4;
            }
            setR(y, getR(z));
            return (y == 6 || z == 6) ? 8 : 4;
        }

        if (x == 2) {
            alu(y, getR(z));
            return z == 6 ? 8 : 4;
        }

        switch (z) {
            case 0:
                if (y <= 3) {
                    if (condition(y)) {
                        pc = pop16();
                        return 20;
                    }
                    return 8;
                }
                if (y == 4) {
                    write8(0xFF00 | fetch8(), a);
                    return 12;
                }
                if (y == 5) {
                    int value = signed8(fetch8());
                    int old = sp;
                    int result = (sp + value) & 0xFFFF;
                    f = 0;
                    if (((old ^ value ^ result) & 0x10) != 0) f |= FLAG_H;
                    if (((old ^ value ^ result) & 0x100) != 0) f |= FLAG_C;
                    sp = result;
                    return 16;
                }
                if (y == 6) {
                    a = read8(0xFF00 | fetch8());
                    return 12;
                }
                int value = signed8(fetch8());
                int old = sp;
                int result = (sp + value) & 0xFFFF;
                f = 0;
                if (((old ^ value ^ result) & 0x10) != 0) f |= FLAG_H;
                if (((old ^ value ^ result) & 0x100) != 0) f |= FLAG_C;
                setHL(result);
                return 12;
            case 1:
                if (q == 0) {
                    setRp2(p, pop16());
                    return 12;
                }
                if (p == 0) {
                    pc = pop16();
                    return 16;
                }
                if (p == 1) {
                    pc = pop16();
                    ime = true;
                    imeDelay = 0;
                    return 16;
                }
                if (p == 2) {
                    pc = getHL();
                    return 4;
                }
                sp = getHL();
                return 8;
            case 2:
                if (y <= 3) {
                    int addr = fetch16();
                    if (condition(y)) {
                        pc = addr;
                        return 16;
                    }
                    return 12;
                }
                if (y == 4) {
                    write8(0xFF00 | c, a);
                    return 8;
                }
                if (y == 5) {
                    write8(fetch16(), a);
                    return 16;
                }
                if (y == 6) {
                    a = read8(0xFF00 | c);
                    return 8;
                }
                a = read8(fetch16());
                return 16;
            case 3:
                if (y == 0) {
                    pc = fetch16();
                    return 16;
                }
                if (y == 1) return executeCb(fetch8());
                if (y == 6) {
                    ime = false;
                    imeDelay = 0;
                    return 4;
                }
                if (y == 7) {
                    imeDelay = 2;
                    return 4;
                }
                return 4;
            case 4:
                if (y <= 3) {
                    int addr = fetch16();
                    if (condition(y)) {
                        push16(pc);
                        pc = addr;
                        return 24;
                    }
                    return 12;
                }
                return 4;
            case 5:
                if (q == 0) {
                    push16(getRp2(p));
                    return 16;
                }
                if (p == 0) {
                    int addr = fetch16();
                    push16(pc);
                    pc = addr;
                    return 24;
                }
                return 4;
            case 6:
                alu(y, fetch8());
                return 8;
            case 7:
                push16(pc);
                pc = y * 8;
                return 16;
            default:
                return 4;
        }
    }

    private int executeCb(int op) {
        int x = op >>> 6;
        int y = (op >>> 3) & 7;
        int z = op & 7;
        int value = getR(z);

        if (x == 0) {
            int result;
            switch (y) {
                case 0: {
                    int carry = (value >>> 7) & 1;
                    result = ((value << 1) | carry) & 0xFF;
                    setRotateFlags(result, carry);
                    break;
                }
                case 1: {
                    int carry = value & 1;
                    result = ((value >>> 1) | (carry << 7)) & 0xFF;
                    setRotateFlags(result, carry);
                    break;
                }
                case 2: {
                    int carryIn = (f & FLAG_C) != 0 ? 1 : 0;
                    int carry = (value >>> 7) & 1;
                    result = ((value << 1) | carryIn) & 0xFF;
                    setRotateFlags(result, carry);
                    break;
                }
                case 3: {
                    int carryIn = (f & FLAG_C) != 0 ? 1 : 0;
                    int carry = value & 1;
                    result = ((value >>> 1) | (carryIn << 7)) & 0xFF;
                    setRotateFlags(result, carry);
                    break;
                }
                case 4: {
                    int carry = (value >>> 7) & 1;
                    result = (value << 1) & 0xFF;
                    setRotateFlags(result, carry);
                    break;
                }
                case 5: {
                    int carry = value & 1;
                    result = ((value >>> 1) | (value & 0x80)) & 0xFF;
                    setRotateFlags(result, carry);
                    break;
                }
                case 6:
                    result = ((value << 4) | (value >>> 4)) & 0xFF;
                    f = result == 0 ? FLAG_Z : 0;
                    break;
                default: {
                    int carry = value & 1;
                    result = (value >>> 1) & 0xFF;
                    setRotateFlags(result, carry);
                    break;
                }
            }
            setR(z, result);
            return z == 6 ? 16 : 8;
        }

        if (x == 1) {
            int carry = f & FLAG_C;
            f = carry | FLAG_H;
            if ((value & (1 << y)) == 0) f |= FLAG_Z;
            return z == 6 ? 12 : 8;
        }

        if (x == 2) {
            setR(z, value & ~(1 << y));
            return z == 6 ? 16 : 8;
        }

        setR(z, value | (1 << y));
        return z == 6 ? 16 : 8;
    }

    private void executeMiscRotate(int y) {
        switch (y) {
            case 0: {
                int carry = (a >>> 7) & 1;
                a = ((a << 1) | carry) & 0xFF;
                f = carry != 0 ? FLAG_C : 0;
                break;
            }
            case 1: {
                int carry = a & 1;
                a = ((a >>> 1) | (carry << 7)) & 0xFF;
                f = carry != 0 ? FLAG_C : 0;
                break;
            }
            case 2: {
                int carryIn = (f & FLAG_C) != 0 ? 1 : 0;
                int carry = (a >>> 7) & 1;
                a = ((a << 1) | carryIn) & 0xFF;
                f = carry != 0 ? FLAG_C : 0;
                break;
            }
            case 3: {
                int carryIn = (f & FLAG_C) != 0 ? 1 : 0;
                int carry = a & 1;
                a = ((a >>> 1) | (carryIn << 7)) & 0xFF;
                f = carry != 0 ? FLAG_C : 0;
                break;
            }
            case 4:
                daa();
                break;
            case 5:
                a ^= 0xFF;
                f = (f & (FLAG_Z | FLAG_C)) | FLAG_N | FLAG_H;
                break;
            case 6:
                f = (f & FLAG_Z) | FLAG_C;
                break;
            case 7:
                f = (f & FLAG_Z) | ((f & FLAG_C) == 0 ? FLAG_C : 0);
                break;
            default:
                break;
        }
    }

    private void daa() {
        int correction = 0;
        boolean carry = (f & FLAG_C) != 0;
        if ((f & FLAG_N) == 0) {
            if ((f & FLAG_H) != 0 || (a & 0x0F) > 9) correction |= 0x06;
            if (carry || a > 0x99) {
                correction |= 0x60;
                carry = true;
            }
            a = (a + correction) & 0xFF;
        } else {
            if ((f & FLAG_H) != 0) correction |= 0x06;
            if (carry) correction |= 0x60;
            a = (a - correction) & 0xFF;
        }
        f &= FLAG_N;
        if (a == 0) f |= FLAG_Z;
        if (carry) f |= FLAG_C;
    }

    private void alu(int operation, int value) {
        value &= 0xFF;
        switch (operation) {
            case 0: addA(value, 0); break;
            case 1: addA(value, (f & FLAG_C) != 0 ? 1 : 0); break;
            case 2: subA(value, 0, true); break;
            case 3: subA(value, (f & FLAG_C) != 0 ? 1 : 0, true); break;
            case 4:
                a &= value;
                f = FLAG_H | (a == 0 ? FLAG_Z : 0);
                break;
            case 5:
                a ^= value;
                f = a == 0 ? FLAG_Z : 0;
                break;
            case 6:
                a |= value;
                f = a == 0 ? FLAG_Z : 0;
                break;
            case 7:
                subA(value, 0, false);
                break;
            default:
                break;
        }
    }

    private void addA(int value, int carry) {
        int old = a;
        int sum = old + value + carry;
        a = sum & 0xFF;
        f = 0;
        if (a == 0) f |= FLAG_Z;
        if (((old & 0x0F) + (value & 0x0F) + carry) > 0x0F) f |= FLAG_H;
        if (sum > 0xFF) f |= FLAG_C;
    }

    private void subA(int value, int carry, boolean store) {
        int old = a;
        int result = old - value - carry;
        int out = result & 0xFF;
        int flags = FLAG_N;
        if (out == 0) flags |= FLAG_Z;
        if ((old & 0x0F) < ((value & 0x0F) + carry)) flags |= FLAG_H;
        if (old < value + carry) flags |= FLAG_C;
        f = flags;
        if (store) a = out;
    }

    private void addHl(int value) {
        int old = getHL();
        int result = old + value;
        int z = f & FLAG_Z;
        f = z;
        if (((old & 0x0FFF) + (value & 0x0FFF)) > 0x0FFF) f |= FLAG_H;
        if (result > 0xFFFF) f |= FLAG_C;
        setHL(result & 0xFFFF);
    }

    private void setRotateFlags(int result, int carry) {
        f = result == 0 ? FLAG_Z : 0;
        if (carry != 0) f |= FLAG_C;
    }

    private boolean condition(int code) {
        return switch (code & 3) {
            case 0 -> (f & FLAG_Z) == 0;
            case 1 -> (f & FLAG_Z) != 0;
            case 2 -> (f & FLAG_C) == 0;
            default -> (f & FLAG_C) != 0;
        };
    }

    private int fetch8() {
        int value = read8(pc);
        pc = (pc + 1) & 0xFFFF;
        return value;
    }

    private int fetch16() {
        int lo = fetch8();
        int hi = fetch8();
        return lo | (hi << 8);
    }

    private int getR(int index) {
        return switch (index & 7) {
            case 0 -> b;
            case 1 -> c;
            case 2 -> d;
            case 3 -> e;
            case 4 -> h;
            case 5 -> l;
            case 6 -> read8(getHL());
            default -> a;
        };
    }

    private void setR(int index, int value) {
        value &= 0xFF;
        switch (index & 7) {
            case 0 -> b = value;
            case 1 -> c = value;
            case 2 -> d = value;
            case 3 -> e = value;
            case 4 -> h = value;
            case 5 -> l = value;
            case 6 -> write8(getHL(), value);
            default -> a = value;
        }
    }

    private int getRp(int p) {
        return switch (p & 3) {
            case 0 -> getBC();
            case 1 -> getDE();
            case 2 -> getHL();
            default -> sp;
        };
    }

    private void setRp(int p, int value) {
        value &= 0xFFFF;
        switch (p & 3) {
            case 0 -> setBC(value);
            case 1 -> setDE(value);
            case 2 -> setHL(value);
            default -> sp = value;
        }
    }

    private int getRp2(int p) {
        return switch (p & 3) {
            case 0 -> getBC();
            case 1 -> getDE();
            case 2 -> getHL();
            default -> ((a << 8) | f);
        };
    }

    private void setRp2(int p, int value) {
        value &= 0xFFFF;
        switch (p & 3) {
            case 0 -> setBC(value);
            case 1 -> setDE(value);
            case 2 -> setHL(value);
            default -> {
                a = (value >>> 8) & 0xFF;
                f = value & 0xF0;
            }
        }
    }

    private int getBC() { return (b << 8) | c; }
    private int getDE() { return (d << 8) | e; }
    private int getHL() { return (h << 8) | l; }
    private void setBC(int v) { b = (v >>> 8) & 0xFF; c = v & 0xFF; }
    private void setDE(int v) { d = (v >>> 8) & 0xFF; e = v & 0xFF; }
    private void setHL(int v) { h = (v >>> 8) & 0xFF; l = v & 0xFF; }

    private void push16(int value) {
        sp = (sp - 1) & 0xFFFF;
        write8(sp, (value >>> 8) & 0xFF);
        sp = (sp - 1) & 0xFFFF;
        write8(sp, value & 0xFF);
    }

    private int pop16() {
        int lo = read8(sp);
        sp = (sp + 1) & 0xFFFF;
        int hi = read8(sp);
        sp = (sp + 1) & 0xFFFF;
        return lo | (hi << 8);
    }

    private int read8(int address) {
        int addr = address & 0xFFFF;
        if (addr < 0x4000) {
            if (rom.length == 0) return 0xFF;
            int bank = 0;
            if (mbcType == MBC1 && mbc1Mode != 0) bank = (mbc1High << 5);
            int index = bank * 0x4000 + addr;
            return romRead(index);
        }
        if (addr < 0x8000) {
            int bank = effectiveRomBank();
            int index = bank * 0x4000 + (addr - 0x4000);
            return romRead(index);
        }
        if (addr < 0xA000) return u8(vram[addr - 0x8000]);
        if (addr < 0xC000) return readExternalRam(addr - 0xA000);
        if (addr < 0xE000) return u8(wram[addr - 0xC000]);
        if (addr < 0xFE00) return u8(wram[addr - 0xE000]);
        if (addr < 0xFEA0) return u8(oam[addr - 0xFE00]);
        if (addr < 0xFF00) return 0xFF;
        if (addr < 0xFF80) return readIo(addr & 0x7F);
        if (addr < 0xFFFF) return u8(hram[addr - 0xFF80]);
        return ie | 0xE0;
    }

    private void write8(int address, int value) {
        int addr = address & 0xFFFF;
        int v = value & 0xFF;
        if (addr < 0x8000) {
            writeCartridgeControl(addr, v);
            return;
        }
        if (addr < 0xA000) {
            vram[addr - 0x8000] = (byte) v;
            return;
        }
        if (addr < 0xC000) {
            writeExternalRam(addr - 0xA000, v);
            return;
        }
        if (addr < 0xE000) {
            wram[addr - 0xC000] = (byte) v;
            return;
        }
        if (addr < 0xFE00) {
            wram[addr - 0xE000] = (byte) v;
            return;
        }
        if (addr < 0xFEA0) {
            oam[addr - 0xFE00] = (byte) v;
            return;
        }
        if (addr < 0xFF00) return;
        if (addr < 0xFF80) {
            writeIo(addr & 0x7F, v);
            return;
        }
        if (addr < 0xFFFF) {
            hram[addr - 0xFF80] = (byte) v;
            return;
        }
        ie = v & 0x1F;
    }

    private int romRead(int index) {
        if (rom.length == 0) return 0xFF;
        int normalized = index % rom.length;
        if (normalized < 0) normalized += rom.length;
        return u8(rom[normalized]);
    }

    private int effectiveRomBank() {
        int bank;
        if (mbcType == MBC1) {
            bank = ((mbc1High & 3) << 5) | (mbc1Low & 0x1F);
            if ((bank & 0x1F) == 0) bank++;
        } else if (mbcType == MBC3) {
            bank = romBank & 0x7F;
            if (bank == 0) bank = 1;
        } else if (mbcType == MBC5) {
            bank = ((mbc5BankHigh & 1) << 8) | (romBank & 0xFF);
        } else {
            bank = 1;
        }
        int count = Math.max(1, (rom.length + 0x3FFF) / 0x4000);
        return bank % count;
    }

    private int effectiveRamBank() {
        if (mbcType == MBC1) return mbc1Mode != 0 ? (mbc1High & 3) : 0;
        if (mbcType == MBC3) return ramBank & 3;
        if (mbcType == MBC5) return ramBank & 0x0F;
        return 0;
    }

    private int readExternalRam(int offset) {
        if (eram.length == 0 || !ramEnabled) return 0xFF;
        int bank = effectiveRamBank();
        int index = bank * 0x2000 + offset;
        if (index >= eram.length) index %= eram.length;
        return u8(eram[index]);
    }

    private void writeExternalRam(int offset, int value) {
        if (eram.length == 0 || !ramEnabled) return;
        int bank = effectiveRamBank();
        int index = bank * 0x2000 + offset;
        if (index >= eram.length) index %= eram.length;
        eram[index] = (byte) value;
    }

    private void writeCartridgeControl(int addr, int value) {
        if (mbcType == MBC_NONE) return;
        if (mbcType == MBC1) {
            if (addr < 0x2000) {
                ramEnabled = (value & 0x0F) == 0x0A;
            } else if (addr < 0x4000) {
                mbc1Low = value & 0x1F;
                if (mbc1Low == 0) mbc1Low = 1;
            } else if (addr < 0x6000) {
                mbc1High = value & 0x03;
            } else {
                mbc1Mode = value & 1;
            }
            return;
        }
        if (mbcType == MBC3) {
            if (addr < 0x2000) {
                ramEnabled = (value & 0x0F) == 0x0A;
            } else if (addr < 0x4000) {
                romBank = value & 0x7F;
                if (romBank == 0) romBank = 1;
            } else if (addr < 0x6000) {
                if (value <= 3) ramBank = value;
            }
            return;
        }
        if (mbcType == MBC5) {
            if (addr < 0x2000) {
                ramEnabled = (value & 0x0F) == 0x0A;
            } else if (addr < 0x3000) {
                romBank = value;
            } else if (addr < 0x4000) {
                mbc5BankHigh = value & 1;
            } else if (addr < 0x6000) {
                ramBank = value & 0x0F;
            }
        }
    }

    private int readIo(int reg) {
        if (reg == 0x00) return readJoypad();
        if (reg == 0x04) return (divCounter >>> 8) & 0xFF;
        if (reg == 0x0F) return io[0x0F] | 0xE0;
        if (reg == 0x41) return io[0x41] | 0x80;
        return io[reg] & 0xFF;
    }

    private void writeIo(int reg, int value) {
        switch (reg) {
            case 0x00:
                io[0x00] = 0xC0 | (value & 0x30) | 0x0F;
                break;
            case 0x04:
                divCounter = 0;
                break;
            case 0x05:
            case 0x06:
                io[reg] = value;
                break;
            case 0x07:
                io[0x07] = value & 0x07;
                timaCounter = 0;
                break;
            case 0x0F:
                io[0x0F] = value & 0x1F;
                break;
            case 0x40: {
                boolean wasOn = (io[0x40] & 0x80) != 0;
                io[0x40] = value;
                boolean nowOn = (value & 0x80) != 0;
                if (wasOn && !nowOn) {
                    lineCycles = 0;
                    io[0x44] = 0;
                    setPpuMode(0);
                    updateLyc();
                } else if (!wasOn && nowOn) {
                    lineCycles = 0;
                    io[0x44] = 0;
                    setPpuMode(2);
                    updateLyc();
                }
                break;
            }
            case 0x41:
                io[0x41] = (io[0x41] & 0x07) | (value & 0x78);
                break;
            case 0x44:
                break;
            case 0x45:
                io[0x45] = value;
                updateLyc();
                break;
            case 0x46:
                io[0x46] = value;
                dmaTransfer(value);
                break;
            default:
                io[reg] = value;
                break;
        }
    }

    private int readJoypad() {
        int select = io[0x00] & 0x30;
        int result = 0xC0 | select | 0x0F;
        if ((select & 0x10) == 0) {
            if ((joypadMask & BTN_RIGHT) != 0) result &= ~0x01;
            if ((joypadMask & BTN_LEFT) != 0) result &= ~0x02;
            if ((joypadMask & BTN_UP) != 0) result &= ~0x04;
            if ((joypadMask & BTN_DOWN) != 0) result &= ~0x08;
        }
        if ((select & 0x20) == 0) {
            if ((joypadMask & BTN_A) != 0) result &= ~0x01;
            if ((joypadMask & BTN_B) != 0) result &= ~0x02;
            if ((joypadMask & BTN_SELECT) != 0) result &= ~0x04;
            if ((joypadMask & BTN_START) != 0) result &= ~0x08;
        }
        return result & 0xFF;
    }

    private void dmaTransfer(int page) {
        int source = (page & 0xFF) << 8;
        for (int i = 0; i < 0xA0; i++) {
            oam[i] = (byte) read8(source + i);
        }
    }

    private void tickTimer(int cycles) {
        divCounter = (divCounter + cycles) & 0xFFFF;
        if ((io[0x07] & 0x04) == 0) return;

        int threshold = switch (io[0x07] & 0x03) {
            case 0 -> 1024;
            case 1 -> 16;
            case 2 -> 64;
            default -> 256;
        };
        timaCounter += cycles;
        while (timaCounter >= threshold) {
            timaCounter -= threshold;
            int tima = io[0x05] + 1;
            if (tima > 0xFF) {
                io[0x05] = io[0x06] & 0xFF;
                requestInterrupt(2);
            } else {
                io[0x05] = tima;
            }
        }
    }

    private void tickPpu(int cycles) {
        if ((io[0x40] & 0x80) == 0) return;
        int remaining = cycles;
        while (remaining > 0) {
            int ly = io[0x44] & 0xFF;
            int boundary;
            if (ly >= 144) boundary = 456;
            else if (lineCycles < 80) boundary = 80;
            else if (lineCycles < 252) boundary = 252;
            else boundary = 456;

            int delta = Math.min(remaining, boundary - lineCycles);
            lineCycles += delta;
            remaining -= delta;

            if (lineCycles != boundary) continue;

            if (ly < 144 && boundary == 80) {
                setPpuMode(3);
            } else if (ly < 144 && boundary == 252) {
                renderScanline(ly);
                setPpuMode(0);
            } else if (boundary == 456) {
                lineCycles = 0;
                ly++;
                if (ly == 144) {
                    io[0x44] = 144;
                    updateLyc();
                    setPpuMode(1);
                    requestInterrupt(0);
                    frameReady = true;
                } else if (ly > 153) {
                    io[0x44] = 0;
                    updateLyc();
                    setPpuMode(2);
                } else {
                    io[0x44] = ly;
                    updateLyc();
                    if (ly < 144) setPpuMode(2);
                    else setPpuMode(1);
                }
            }
        }
    }

    private void setPpuMode(int mode) {
        if (ppuMode == mode && (io[0x41] & 0x03) == mode) return;
        ppuMode = mode;
        io[0x41] = (io[0x41] & ~0x03) | mode;
        boolean statIrq = (mode == 0 && (io[0x41] & 0x08) != 0)
                || (mode == 1 && (io[0x41] & 0x10) != 0)
                || (mode == 2 && (io[0x41] & 0x20) != 0);
        if (statIrq) requestInterrupt(1);
    }

    private void updateLyc() {
        boolean old = (io[0x41] & 0x04) != 0;
        boolean equal = (io[0x44] & 0xFF) == (io[0x45] & 0xFF);
        if (equal) io[0x41] |= 0x04;
        else io[0x41] &= ~0x04;
        if (!old && equal && (io[0x41] & 0x40) != 0) requestInterrupt(1);
    }

    private void renderScanline(int y) {
        int lcdc = io[0x40];
        boolean bgEnabled = (lcdc & 0x01) != 0;
        boolean windowEnabled = bgEnabled && (lcdc & 0x20) != 0 && y >= (io[0x4A] & 0xFF);
        int wxStart = (io[0x4B] & 0xFF) - 7;

        for (int x = 0; x < WIDTH; x++) {
            int color = 0;
            if (bgEnabled) {
                boolean useWindow = windowEnabled && x >= wxStart;
                int px;
                int py;
                int mapBase;
                if (useWindow) {
                    px = x - wxStart;
                    py = y - (io[0x4A] & 0xFF);
                    mapBase = (lcdc & 0x40) != 0 ? 0x1C00 : 0x1800;
                } else {
                    px = (x + (io[0x43] & 0xFF)) & 0xFF;
                    py = (y + (io[0x42] & 0xFF)) & 0xFF;
                    mapBase = (lcdc & 0x08) != 0 ? 0x1C00 : 0x1800;
                }
                int tileX = (px >>> 3) & 31;
                int tileY = (py >>> 3) & 31;
                int tileNumber = u8(vram[mapBase + tileY * 32 + tileX]);
                int tileAddress;
                if ((lcdc & 0x10) != 0) {
                    tileAddress = tileNumber * 16;
                } else {
                    tileAddress = 0x1000 + ((byte) tileNumber) * 16;
                }
                int row = (py & 7) * 2;
                int lo = u8(vram[(tileAddress + row) & 0x1FFF]);
                int hi = u8(vram[(tileAddress + row + 1) & 0x1FFF]);
                int bit = 7 - (px & 7);
                color = ((hi >>> bit) & 1) << 1 | ((lo >>> bit) & 1);
            }
            bgColorIndex[x] = color;
            frame[y * WIDTH + x] = paletteColor(io[0x47], color);
        }

        if ((lcdc & 0x02) == 0) return;
        int spriteHeight = (lcdc & 0x04) != 0 ? 16 : 8;
        int[] visible = new int[10];
        int count = 0;
        for (int i = 0; i < 40 && count < 10; i++) {
            int sy = u8(oam[i * 4]) - 16;
            if (y >= sy && y < sy + spriteHeight) visible[count++] = i;
        }

        for (int x = 0; x < WIDTH; x++) {
            int best = -1;
            int bestX = Integer.MAX_VALUE;
            for (int n = 0; n < count; n++) {
                int i = visible[n];
                int sx = u8(oam[i * 4 + 1]) - 8;
                if (x < sx || x >= sx + 8) continue;
                if (sx < bestX) {
                    bestX = sx;
                    best = i;
                }
            }
            if (best < 0) continue;

            int base = best * 4;
            int sy = u8(oam[base]) - 16;
            int sx = u8(oam[base + 1]) - 8;
            int tile = u8(oam[base + 2]);
            int attr = u8(oam[base + 3]);
            int row = y - sy;
            int col = x - sx;
            if ((attr & 0x40) != 0) row = spriteHeight - 1 - row;
            if ((attr & 0x20) != 0) col = 7 - col;
            if (spriteHeight == 16) tile &= 0xFE;
            if (row >= 8) {
                tile++;
                row -= 8;
            }
            int address = tile * 16 + row * 2;
            int lo = u8(vram[address & 0x1FFF]);
            int hi = u8(vram[(address + 1) & 0x1FFF]);
            int bit = 7 - col;
            int color = ((hi >>> bit) & 1) << 1 | ((lo >>> bit) & 1);
            if (color == 0) continue;
            if ((attr & 0x80) != 0 && bgColorIndex[x] != 0) continue;
            int palette = (attr & 0x10) != 0 ? io[0x49] : io[0x48];
            frame[y * WIDTH + x] = paletteColor(palette, color);
        }
    }

    private int paletteColor(int palette, int color) {
        int shade = (palette >>> (color * 2)) & 0x03;
        return DMG_COLORS[shade];
    }

    private void requestInterrupt(int bit) {
        io[0x0F] = (io[0x0F] | (1 << bit)) & 0x1F;
    }

    private static int signed8(int value) {
        return (byte) (value & 0xFF);
    }

    private static int u8(byte value) {
        return value & 0xFF;
    }
}
