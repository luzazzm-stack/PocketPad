package com.pocketpad.tv;

/**
 * Minimal QR encoder: byte mode, error correction level M, versions 1–10,
 * fixed mask 0. Enough for the pairing URL (~60 bytes) without pulling in
 * a library. Structure follows the reference algorithm (Nayuki's).
 */
final class QrCode {
    // Index = version; level M only.
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] NUM_BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int FORMAT_M = 0, MASK = 0;

    final int size;
    private final boolean[][] modules, isFunction;

    boolean get(int x, int y) { return modules[y][x]; }

    static QrCode encode(byte[] data) {
        for (int ver = 1; ver <= 10; ver++) {
            int ccBits = ver <= 9 ? 8 : 16;
            int cap = dataCodewords(ver) * 8;
            if (4 + ccBits + data.length * 8 <= cap) return new QrCode(ver, data, ccBits, cap);
        }
        throw new IllegalArgumentException("data too long");
    }

    private QrCode(int ver, byte[] data, int ccBits, int capBits) {
        size = ver * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];

        // Bit stream: mode 0100, count, bytes, terminator, byte pad, 0xEC/0x11 fill.
        boolean[] bits = new boolean[capBits];
        int n = 0;
        n = put(bits, n, 0x4, 4);
        n = put(bits, n, data.length, ccBits);
        for (byte b : data) n = put(bits, n, b & 0xFF, 8);
        n += Math.min(4, capBits - n);
        n = (n + 7) / 8 * 8;
        byte[] cw = new byte[capBits / 8];
        for (int i = 0; i < n && i < capBits; i++) if (bits[i]) cw[i >>> 3] |= 1 << (7 - (i & 7));
        for (int i = n / 8, pad = 0xEC; i < cw.length; i++, pad ^= 0xEC ^ 0x11) cw[i] = (byte) pad;

        drawFunctionPatterns(ver);
        drawCodewords(addEccAndInterleave(ver, cw));
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                if (!isFunction[y][x] && (x + y) % 2 == 0) modules[y][x] = !modules[y][x];
        drawFormatBits();
    }

    private static int put(boolean[] bits, int n, int val, int len) {
        for (int i = len - 1; i >= 0; i--) bits[n++] = ((val >>> i) & 1) != 0;
        return n;
    }

    private static int rawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int na = ver / 7 + 2;
            r -= (25 * na - 10) * na - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    private static int dataCodewords(int ver) {
        return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver];
    }

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private void drawFunctionPatterns(int ver) {
        for (int i = 0; i < size; i++) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0); }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
        int[] pos = alignmentPositions(ver);
        int na = pos.length;
        for (int i = 0; i < na; i++)
            for (int j = 0; j < na; j++)
                if (!(i == 0 && j == 0 || i == 0 && j == na - 1 || i == na - 1 && j == 0))
                    for (int dy = -2; dy <= 2; dy++)
                        for (int dx = -2; dx <= 2; dx++)
                            set(pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        drawFormatBits(); // placeholder so these cells count as function modules
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, c = i / 3;
                set(a, c, b); set(c, a, b);
            }
        }
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy)), x = cx + dx, y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d != 2 && d != 4);
            }
    }

    private static int[] alignmentPositions(int ver) {
        if (ver == 1) return new int[0];
        int na = ver / 7 + 2;
        int step = (ver * 8 + na * 3 + 5) / (na * 4 - 4) * 2;
        int[] r = new int[na];
        r[0] = 6;
        for (int i = na - 1, p = ver * 4 + 10; i >= 1; i--, p -= step) r[i] = p;
        return r;
    }

    private void drawFormatBits() {
        int data = FORMAT_M << 3 | MASK, rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6)); set(8, 8, bit(bits, 7)); set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    private static byte[] addEccAndInterleave(int ver, byte[] data) {
        int nb = NUM_BLOCKS[ver], eccLen = ECC_PER_BLOCK[ver];
        int raw = rawModules(ver) / 8, numShort = nb - raw % nb, shortLen = raw / nb;
        byte[][] blocks = new byte[nb][];
        byte[] div = rsDivisor(eccLen);
        for (int i = 0, k = 0; i < nb; i++) {
            int datLen = shortLen - eccLen + (i < numShort ? 0 : 1);
            byte[] dat = new byte[datLen];
            System.arraycopy(data, k, dat, 0, datLen);
            k += datLen;
            byte[] ecc = rsRemainder(dat, div);
            byte[] b = new byte[shortLen + 1];
            System.arraycopy(dat, 0, b, 0, datLen);
            System.arraycopy(ecc, 0, b, b.length - eccLen, eccLen);
            blocks[i] = b;
        }
        byte[] out = new byte[raw];
        int o = 0;
        for (int i = 0; i < blocks[0].length; i++)
            for (int j = 0; j < nb; j++)
                if (i != shortLen - eccLen || j >= numShort) out[o++] = blocks[j][i];
        return out;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < degree) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int f = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= mul(div[i] & 0xFF, f);
        }
        return r;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++)
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
        }
    }
}
