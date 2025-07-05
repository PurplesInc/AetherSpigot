package net.minecraft.server;

public class NibbleArray {

    private final byte[] a;

    public NibbleArray() {
        this.a = new byte[2048];
    }

    public NibbleArray(byte[] abyte) {
        this.a = abyte;
        if (abyte.length != 2048) {
            throw new IllegalArgumentException("ChunkNibbleArrays should be 2048 bytes not: " + abyte.length);
        }
    }

    public int a(int i, int j, int k) {
        return this.a(this.b(i, j, k));
    }

    public void a(int i, int j, int k, int l) {
        this.a(this.b(i, j, k), l);
    }

    private int b(int i, int j, int k) {
        return j << 8 | k << 4 | i;
    }

    public int a(int i) {
        int j = this.c(i);

        return this.a[j] >> ((i & 1) << 2) & 15; // PandaSpigot
    }

    public void a(int i, int j) {
        int k = this.c(i);

        // PandaSpigot start
        int shift = (i & 1) << 2;
        this.a[k] = (byte) (this.a[k] & ~(15 << shift) | (j & 15) << shift);
        // PandaSpigot end
    }

    private boolean b(int i) {
        return (i & 1) == 0;
    }

    private int c(int i) {
        return i >> 1;
    }

    public byte[] a() {
        return this.a;
    }
}
