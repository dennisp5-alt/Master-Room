package com.dennis.masterroom.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

/** WAV reading (16, 24, 32-bit PCM and 32-bit float) and dithered writing. */
public final class Wav {
    private Wav() {}

    /** Where the samples sit in a file, for the comparison player. */
    public static final class Header {
        public int format, channels, rate, bits;
        public long dataStart, frames;
        public int frameBytes() { return channels * bits / 8; }
    }

    public static Header header(File f) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 14)) {
            byte[] head = new byte[12];
            fill(in, head, 12);
            if (!tag(head, 0, "RIFF") || !tag(head, 8, "WAVE")) throw new IOException("Not a WAV file");
            Header h = new Header();
            long pos = 12;
            byte[] chunk = new byte[8];
            while (true) {
                fill(in, chunk, 8);
                pos += 8;
                long size = u32(chunk, 4);
                if (tag(chunk, 0, "fmt ")) {
                    byte[] fmt = new byte[(int) size];
                    fill(in, fmt, fmt.length);
                    h.format = u16(fmt, 0); h.channels = u16(fmt, 2); h.rate = (int) u32(fmt, 4); h.bits = u16(fmt, 14);
                    if (h.format == 0xFFFE && fmt.length >= 26) h.format = u16(fmt, 24);
                    if ((size & 1) == 1) skip(in, 1);
                    pos += size + (size & 1);
                } else if (tag(chunk, 0, "data")) {
                    if (h.channels < 1 || h.bits == 0) throw new IOException("WAV format chunk missing");
                    h.dataStart = pos;
                    long actual = Math.max(0, f.length() - pos);
                    h.frames = Math.min(size, actual) / h.frameBytes();
                    return h;
                } else {
                    skip(in, size + (size & 1));
                    pos += size + (size & 1);
                }
            }
        }
    }

    /** Decodes raw frames as read from the data chunk into stereo floats. */
    public static void decode(Header h, byte[] b, int frames, float[] l, float[] r) {
        int bytes = h.bits / 8, step = h.frameBytes();
        for (int i = 0; i < frames; i++) {
            int p = i * step;
            l[i] = sample(b, p, h.bits, h.format);
            r[i] = h.channels > 1 ? sample(b, p + bytes, h.bits, h.format) : l[i];
        }
    }

    public static Audio read(File f) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16)) { return read(in); }
    }

    public static Audio read(InputStream in) throws IOException {
        byte[] head = new byte[12];
        fill(in, head, 12);
        if (!tag(head, 0, "RIFF") || !tag(head, 8, "WAVE")) throw new IOException("Not a WAV file");
        int format = 0, channels = 0, rate = 0, bits = 0;
        byte[] chunk = new byte[8];
        while (true) {
            fill(in, chunk, 8);
            long size = u32(chunk, 4);
            if (tag(chunk, 0, "fmt ")) {
                byte[] fmt = new byte[(int) size];
                fill(in, fmt, fmt.length);
                format = u16(fmt, 0); channels = u16(fmt, 2); rate = (int) u32(fmt, 4); bits = u16(fmt, 14);
                if (format == 0xFFFE && fmt.length >= 26) format = u16(fmt, 24);
                if ((size & 1) == 1) skip(in, 1);
            } else if (tag(chunk, 0, "data")) {
                if (channels < 1 || bits == 0) throw new IOException("WAV format chunk missing");
                if (!(format == 1 && (bits == 16 || bits == 24 || bits == 32)) && !(format == 3 && bits == 32))
                    throw new IOException("Unsupported WAV: format " + format + ", " + bits + " bit");
                int bytes = bits / 8, frameBytes = bytes * channels;
                int frames = (int) Math.min(Integer.MAX_VALUE - 16, size / frameBytes);
                float[] l = new float[frames], r = new float[frames];
                byte[] buf = new byte[frameBytes * 8192];
                int done = 0;
                while (done < frames) {
                    int want = Math.min(8192, frames - done), got = 0, need = want * frameBytes;
                    while (got < need) {
                        int n = in.read(buf, got, need - got);
                        if (n < 0) break;
                        got += n;
                    }
                    int whole = got / frameBytes;
                    for (int i = 0; i < whole; i++) {
                        int p = i * frameBytes;
                        l[done + i] = sample(buf, p, bits, format);
                        r[done + i] = channels > 1 ? sample(buf, p + bytes, bits, format) : l[done + i];
                    }
                    done += whole;
                    if (whole < want) break;
                }
                if (done < frames) {
                    l = java.util.Arrays.copyOf(l, done);
                    r = java.util.Arrays.copyOf(r, done);
                }
                return new Audio(l, r, rate);
            } else {
                skip(in, size + (size & 1));
            }
        }
    }

    private static float sample(byte[] b, int p, int bits, int format) {
        if (format == 3) return Float.intBitsToFloat((b[p] & 255) | (b[p + 1] & 255) << 8 | (b[p + 2] & 255) << 16 | b[p + 3] << 24);
        if (bits == 16) return ((b[p] & 255) | b[p + 1] << 8) / 32768f;
        if (bits == 24) return ((b[p] & 255) | (b[p + 1] & 255) << 8 | b[p + 2] << 16) / 8388608f;
        return (float) (((b[p] & 255) | (b[p + 1] & 255) << 8 | (b[p + 2] & 255) << 16 | b[p + 3] << 24) / 2147483648.0);
    }

    /** Writes PCM with triangular dither; 16-bit also gets gentle noise shaping. */
    public static void write(File f, Audio a, int bits) throws IOException {
        if (bits != 16 && bits != 24) throw new IOException("bits");
        int frames = a.frames(), bytes = bits / 8;
        long data = (long) frames * 2 * bytes;
        Random rnd = new Random(0x4D524F4FL + frames);
        double scale = bits == 16 ? 32768.0 : 8388608.0, max = scale - 1;
        boolean shape = bits == 16;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f), 1 << 16)) {
            DataOutputStream d = new DataOutputStream(out);
            ascii(d, "RIFF"); le32(d, 36 + data); ascii(d, "WAVEfmt "); le32(d, 16); le16(d, 1); le16(d, 2);
            le32(d, a.rate); le32(d, (long) a.rate * 2 * bytes); le16(d, 2 * bytes); le16(d, bits);
            ascii(d, "data"); le32(d, data);
            double[] e1 = new double[2], e2 = new double[2];
            byte[] buf = new byte[8192 * 2 * bytes];
            int p = 0;
            for (int i = 0; i < frames; i++) {
                for (int c = 0; c < 2; c++) {
                    double x = (c == 0 ? a.l[i] : a.r[i]) * scale;
                    double wanted = shape ? x - 1.2 * e1[c] + 0.5 * e2[c] : x;
                    double tpdf = rnd.nextDouble() - rnd.nextDouble();
                    double q = Math.rint(wanted + tpdf);
                    if (q > max) q = max; else if (q < -scale) q = -scale;
                    e2[c] = e1[c]; e1[c] = q - wanted;
                    int v = (int) q;
                    buf[p++] = (byte) v; buf[p++] = (byte) (v >> 8);
                    if (bytes == 3) buf[p++] = (byte) (v >> 16);
                }
                if (p == buf.length) { d.write(buf, 0, p); p = 0; }
            }
            d.write(buf, 0, p);
            d.flush();
        }
    }

    private static void ascii(DataOutputStream d, String s) throws IOException { d.writeBytes(s); }
    private static void le16(DataOutputStream d, int v) throws IOException { d.write(v); d.write(v >> 8); }
    private static void le32(DataOutputStream d, long v) throws IOException { d.write((int) v); d.write((int) (v >> 8)); d.write((int) (v >> 16)); d.write((int) (v >> 24)); }
    private static boolean tag(byte[] b, int p, String t) { for (int i = 0; i < 4; i++) if (b[p + i] != t.charAt(i)) return false; return true; }
    private static int u16(byte[] b, int p) { return (b[p] & 255) | (b[p + 1] & 255) << 8; }
    private static long u32(byte[] b, int p) { return (b[p] & 255L) | (b[p + 1] & 255L) << 8 | (b[p + 2] & 255L) << 16 | (b[p + 3] & 255L) << 24; }
    private static void fill(InputStream in, byte[] b, int n) throws IOException {
        int got = 0;
        while (got < n) { int r = in.read(b, got, n - got); if (r < 0) throw new IOException("WAV ended early"); got += r; }
    }
    private static void skip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) { if (in.read() < 0) throw new IOException("WAV ended early"); s = 1; }
            n -= s;
        }
    }
}
