package com.dennis.masterroom.desktop;

import java.io.IOException;

/** Something the player can play block by block: the takes (AbMixer) or the stems (StemMixer). */
interface Feed {
    /** The next block, interleaved stereo, {@link AbMixer#BLOCK} frames. False at the end (and back to the start). */
    boolean next(float[] out) throws IOException;
    int rate();
    long frames();
    long position();
    void seek(double fraction);
    void rewind(long frames);
    default double fraction() { return frames() == 0 ? 0 : position() / (double) frames(); }
    default double seconds() { return position() / (double) rate(); }
    default double length() { return frames() / (double) rate(); }
    default boolean ready() { return frames() > 0; }
}
