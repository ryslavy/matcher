/*
 * Pure-Java QR Code generator (Model 2, byte mode, error correction level M, versions 1-10),
 * following Project Nayuki's QR Code generator library (MIT License).
 *
 * Copyright (c) Project Nayuki. (MIT License)
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * - The above copyright notice and this permission notice shall be included in all copies or
 *   substantial portions of the Software.
 */
package com.gathin.tvhome;

import android.graphics.Bitmap;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Dependency-free ISO/IEC 18004 QR code encoder (Model 2, byte mode, ECC Level M, versions 1–10)
 * plus crisp Android {@link Bitmap} renderer for phone-assisted configuration URLs.
 */
final class QrBitmap {

    private static final int[] ECC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final boolean[] FINDER_PATTERN = {
            true, false, true, true, true, false, true, false, false, false, false
    };

    private QrBitmap() { }

    /** Pure-JVM QR module matrix {@code [y][x]} (true = dark, without 4-module quiet zone). */
    static boolean[][] encode(String text) {
        return encode(text.getBytes(StandardCharsets.UTF_8), -1);
    }

    static boolean[][] encode(byte[] data, int mask) {
        int version = 1;
        while (data.length > capacity(version)) {
            if (++version > 10) {
                throw new IllegalArgumentException("Payload too long for QR v1-10: " + data.length + " bytes");
            }
        }
        int size = version * 4 + 17;
        boolean[][] modules = new boolean[size][size];
        boolean[][] function = new boolean[size][size];
        drawFunctions(version, modules, function);
        drawCodewords(interleave(version, codewords(version, data)), modules, function);
        if (mask < 0) {
            int best = Integer.MAX_VALUE;
            for (int m = 0; m < 8; m++) {
                applyMask(m, modules, function);
                drawFormat(m, modules, function);
                int score = penalty(modules);
                if (score < best) {
                    best = score;
                    mask = m;
                }
                applyMask(m, modules, function);
            }
        }
        applyMask(mask, modules, function);
        drawFormat(mask, modules, function);
        return modules;
    }

    /** Renders a high-contrast QR code Bitmap (black modules on white with 4-module quiet zone). */
    static Bitmap renderBitmap(String text, int targetPx) {
        boolean[][] m = encode(text);
        int quiet = 4;
        int totalModules = m.length + quiet * 2;
        int scale = Math.max(2, targetPx / totalModules);
        int sizePx = totalModules * scale;
        int[] pixels = new int[sizePx * sizePx];
        Arrays.fill(pixels, 0xFFFFFFFF);
        for (int y = 0; y < m.length; y++) {
            for (int x = 0; x < m.length; x++) {
                if (!m[y][x]) continue;
                int px0 = (x + quiet) * scale;
                int py0 = (y + quiet) * scale;
                for (int dy = 0; dy < scale; dy++) {
                    int row = (py0 + dy) * sizePx + px0;
                    for (int dx = 0; dx < scale; dx++) {
                        pixels[row + dx] = 0xFF101318;
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888);
    }

    private static int capacity(int version) {
        return dataCodewords(version) - (version < 10 ? 2 : 3);
    }

    private static int rawModules(int version) {
        int n = (16 * version + 128) * version + 64;
        if (version >= 2) {
            int align = version / 7 + 2;
            n -= (25 * align - 10) * align - 55;
            if (version >= 7) n -= 36;
        }
        return n;
    }

    private static int dataCodewords(int version) {
        return rawModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version];
    }

    private static void set(boolean[][] modules, boolean[][] function, int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private static void drawFunctions(int version, boolean[][] modules, boolean[][] function) {
        int size = modules.length;
        for (int i = 0; i < size; i++) {
            set(modules, function, 6, i, i % 2 == 0);
            set(modules, function, i, 6, i % 2 == 0);
        }
        drawFinder(modules, function, 3, 3);
        drawFinder(modules, function, size - 4, 3);
        drawFinder(modules, function, 3, size - 4);
        int[] align = alignmentPositions(version);
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        set(modules, function, align[i] + dx, align[j] + dy,
                                Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                    }
                }
            }
        }
        drawFormat(0, modules, function);
        if (version >= 7) {
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = (version << 12) | rem;
            for (int i = 0; i < 18; i++) {
                boolean dark = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3;
                int b = i / 3;
                set(modules, function, a, b, dark);
                set(modules, function, b, a, dark);
            }
        }
    }

    private static void drawFinder(boolean[][] modules, boolean[][] function, int x, int y) {
        int size = modules.length;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int xx = x + dx;
                int yy = y + dy;
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) {
                    set(modules, function, xx, yy, d != 2 && d != 4);
                }
            }
        }
    }

    private static int[] alignmentPositions(int version) {
        if (version == 1) return new int[0];
        int n = version / 7 + 2;
        int step = (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
        int[] out = new int[n];
        out[0] = 6;
        for (int i = n - 1, pos = version * 4 + 10; i >= 1; i--, pos -= step) out[i] = pos;
        return out;
    }

    private static void drawFormat(int mask, boolean[][] modules, boolean[][] function) {
        int rem = mask;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((mask << 10) | rem) ^ 0x5412;
        int size = modules.length;
        for (int i = 0; i <= 5; i++) set(modules, function, 8, i, bit(bits, i));
        set(modules, function, 8, 7, bit(bits, 6));
        set(modules, function, 8, 8, bit(bits, 7));
        set(modules, function, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(modules, function, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(modules, function, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(modules, function, 8, size - 15 + i, bit(bits, i));
        set(modules, function, 8, size - 8, true);
    }

    private static boolean bit(int value, int i) {
        return ((value >>> i) & 1) != 0;
    }

    private static byte[] codewords(int version, byte[] data) {
        byte[] out = new byte[dataCodewords(version)];
        int[] at = {0};
        append(out, at, 0b0100, 4);
        append(out, at, data.length, version < 10 ? 8 : 16);
        for (byte b : data) append(out, at, b & 0xFF, 8);
        int capacityBits = out.length * 8;
        append(out, at, 0, Math.min(4, capacityBits - at[0]));
        append(out, at, 0, (8 - at[0] % 8) % 8);
        for (int pad = 0xEC; at[0] < capacityBits; pad ^= 0xEC ^ 0x11) append(out, at, pad, 8);
        return out;
    }

    private static void append(byte[] out, int[] at, int value, int bits) {
        for (int i = bits - 1; i >= 0; i--, at[0]++) {
            if (((value >>> i) & 1) != 0) out[at[0] >>> 3] |= (byte) (0x80 >>> (at[0] & 7));
        }
    }

    private static byte[] interleave(int version, byte[] data) {
        int blocks = BLOCKS[version];
        int ecc = ECC_PER_BLOCK[version];
        int raw = rawModules(version) / 8;
        int shortBlocks = blocks - raw % blocks;
        int shortLen = raw / blocks;
        byte[] divisor = rsDivisor(ecc);
        byte[][] all = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int len = shortLen - ecc + (i < shortBlocks ? 0 : 1);
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(data, k, block, 0, len);
            byte[] rem = rsRemainder(data, k, len, divisor);
            System.arraycopy(rem, 0, block, block.length - ecc, ecc);
            k += len;
            all[i] = block;
        }
        byte[] out = new byte[raw];
        int n = 0;
        for (int i = 0; i < shortLen + 1; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i != shortLen - ecc || j >= shortBlocks) out[n++] = all[j][i];
            }
        }
        return out;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] out = new byte[degree];
        out[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                out[j] = (byte) gfMultiply(out[j] & 0xFF, root);
                if (j + 1 < degree) out[j] ^= out[j + 1];
            }
            root = gfMultiply(root, 0x02);
        }
        return out;
    }

    private static byte[] rsRemainder(byte[] data, int from, int len, byte[] divisor) {
        byte[] out = new byte[divisor.length];
        for (int i = from; i < from + len; i++) {
            int factor = (data[i] ^ out[0]) & 0xFF;
            System.arraycopy(out, 1, out, 0, out.length - 1);
            out[out.length - 1] = 0;
            for (int j = 0; j < out.length; j++) {
                out[j] ^= (byte) gfMultiply(divisor[j] & 0xFF, factor);
            }
        }
        return out;
    }

    private static int gfMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static void drawCodewords(byte[] data, boolean[][] modules, boolean[][] function) {
        int size = modules.length;
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
    }

    private static void applyMask(int mask, boolean[][] modules, boolean[][] function) {
        int size = modules.length;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (invert && !function[y][x]) modules[y][x] = !modules[y][x];
            }
        }
    }

    private static int penalty(boolean[][] m) {
        int size = m.length;
        int score = 0;
        int dark = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b <= size; b++) {
                    boolean same = b < size && at(m, pass, a, b) == at(m, pass, a, b - 1);
                    if (same) {
                        run++;
                    } else {
                        if (run >= 5) score += 3 + run - 5;
                        run = 1;
                    }
                }
                for (int b = 0; b + 11 <= size; b++) {
                    if (finderLike(m, pass, a, b, false) || finderLike(m, pass, a, b, true)) score += 40;
                }
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (m[y][x]) dark++;
                if (x + 1 < size && y + 1 < size
                        && m[y][x] == m[y][x + 1]
                        && m[y][x] == m[y + 1][x]
                        && m[y][x] == m[y + 1][x + 1]) {
                    score += 3;
                }
            }
        }
        int total = size * size;
        score += ((Math.abs(dark * 20 - total * 10) + total - 1) / total - 1) * 10;
        return score;
    }

    private static boolean at(boolean[][] m, int pass, int a, int b) {
        return pass == 0 ? m[a][b] : m[b][a];
    }

    private static boolean finderLike(boolean[][] m, int pass, int a, int b, boolean reversed) {
        for (int i = 0; i < 11; i++) {
            if (at(m, pass, a, b + i) != FINDER_PATTERN[reversed ? 10 - i : i]) return false;
        }
        return true;
    }
}
