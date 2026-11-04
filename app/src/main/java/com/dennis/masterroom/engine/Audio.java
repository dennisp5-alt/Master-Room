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
    public Audio copy() { return new Audio(l.clone(), r.clone(), rate); }

    public Audio gained(double db) {
        Audio out = copy();
        float g = (float) Math.pow(10, db / 20);
        for (int i = 0; i < out.l.length; i++) { out.l[i] *= g; out.r[i] *= g; }
        return out;
    }
}
