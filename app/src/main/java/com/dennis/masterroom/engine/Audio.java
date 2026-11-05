package com.dennis.masterroom.engine;

/** Stereo audio held in memory while the song is on the bench. */
public final class Audio {
    public final float[] l, r;
    public final int rate;

    public Audio(float[] l, float[] r, int rate) {
        if (l.length != r.length) throw new IllegalArgumentException("channel lengths differ");
        this.l = l; this.r = r; this.rate = rate;
    }
    public Audio(int frames, int rate) { this(new float[frames], new float[frames], rate); }
    public int frames() { return l.length; }
    public double seconds() { return l.length / (double) rate; }
    /** Writes the samples to a plain file, for reading back with load. */
    void store(java.io.File f) throws java.io.IOException {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(1 << 20).order(java.nio.ByteOrder.nativeOrder());
        try (java.nio.channels.FileChannel out = new java.io.FileOutputStream(f).getChannel()) {
            for (float[] x : new float[][]{l, r}) {
                int at = 0;
                while (at < x.length) {
                    int n = Math.min(b.capacity() / 4, x.length - at);
                    b.clear();
                    b.asFloatBuffer().put(x, at, n);
                    b.limit(n * 4);
                    while (b.hasRemaining()) out.write(b);
                    at += n;
                }
            }
        }
    }

    static Audio load(java.io.File f, int frames, int rate) throws java.io.IOException {
        Audio a = new Audio(frames, rate);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(1 << 20).order(java.nio.ByteOrder.nativeOrder());
        try (java.nio.channels.FileChannel in = new java.io.FileInputStream(f).getChannel()) {
            for (float[] x : new float[][]{a.l, a.r}) {
                int at = 0;
                while (at < x.length) {
                    int n = Math.min(b.capacity() / 4, x.length - at);
                    b.clear();
                    b.limit(n * 4);
                    while (b.hasRemaining()) if (in.read(b) < 0) throw new java.io.EOFException("working copy ended early");
                    b.flip();
                    b.asFloatBuffer().get(x, at, n);
                    at += n;
                }
            }
        }
        return a;
    }

    public Audio copy() { return new Audio(l.clone(), r.clone(), rate); }

    public Audio gained(double db) {
        Audio out = copy();
        float g = (float) Math.pow(10, db / 20);
        for (int i = 0; i < out.l.length; i++) { out.l[i] *= g; out.r[i] *= g; }
        return out;
    }
}
