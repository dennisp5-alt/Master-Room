package com.dennis.masterroom.engine;

import de.sciss.jump3r.mp3.BitStream;
import de.sciss.jump3r.mp3.GainAnalysis;
import de.sciss.jump3r.mp3.ID3Tag;
import de.sciss.jump3r.mp3.Lame;
import de.sciss.jump3r.mp3.LameGlobalFlags;
import de.sciss.jump3r.mp3.MPEGMode;
import de.sciss.jump3r.mp3.Presets;
import de.sciss.jump3r.mp3.Quantize;
import de.sciss.jump3r.mp3.QuantizePVT;
import de.sciss.jump3r.mp3.Reservoir;
import de.sciss.jump3r.mp3.Takehiro;
import de.sciss.jump3r.mp3.VBRTag;
import de.sciss.jump3r.mp3.Version;
import de.sciss.jump3r.mp3.VbrMode;
import de.sciss.jump3r.mpg.Common;
import de.sciss.jump3r.mpg.Interface;
import de.sciss.jump3r.mpg.MPGLib;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Finished files for release: a 320 kbps MP3 and WAVs that carry the song's details. */
public final class Release {
    private Release() {}

    public static final class Info {
        public String title = "", artist = "", album = "", year = "", track = "", isrc = "";
    }

    /** Encodes a 16-bit 44.1 kHz stereo WAV at 320 kbps, with tags and gapless information. */
    public static void mp3(File wav, File out, Info info) throws IOException {
        Lame lame = new Lame();
        MPGLib mpg = new MPGLib();
        VBRTag vbr = new VBRTag();
        ID3Tag id3 = new ID3Tag();
        GainAnalysis ga = new GainAnalysis();
        BitStream bs = new BitStream();
        Presets presets = new Presets();
        QuantizePVT qp = new QuantizePVT();
        Quantize q = new Quantize();
        Version ver = new Version();
        Reservoir rv = new Reservoir();
        Takehiro tk = new Takehiro();
        Common common = new Common();
        Interface intf = new Interface();
        lame.setModules(ga, bs, presets, qp, q, vbr, ver, id3, mpg);
        bs.setModules(ga, mpg, ver, vbr);
        id3.setModules(bs, ver);
        presets.setModules(lame);
        q.setModules(bs, rv, qp, tk);
        qp.setModules(tk, rv, lame.enc.psy);
        rv.setModules(bs);
        tk.setModules(qp);
        vbr.setModules(lame, bs, ver);
        mpg.setModules(intf, common);
        intf.setModules(vbr, common);

        Wav.Header h = Wav.header(wav);
        if (h.rate != 44100 || h.channels != 2 || h.bits != 16 || h.format != 1) throw new IOException("MP3 needs the 16-bit 44.1 kHz file");
        LameGlobalFlags f = lame.lame_init();
        try (RandomAccessFile mp3 = new RandomAccessFile(out, "rw"); InputStream in = new FileInputStream(wav)) {
            mp3.setLength(0);
            f.num_channels = 2;
            f.in_samplerate = 44100;
            f.out_samplerate = 44100;
            f.brate = 320;
            f.VBR = VbrMode.vbr_off;
            f.mode = MPEGMode.JOINT_STEREO;
            f.quality = 2;
            f.bWriteVbrTag = true;
            f.findReplayGain = false;
            f.write_id3tag_automatic = true;
            f.num_samples = (int) Math.min(Integer.MAX_VALUE, h.frames);
            id3.id3tag_init(f);
            id3.id3tag_add_v2(f);
            if (!info.title.isEmpty()) id3.id3tag_set_title(f, info.title);
            if (!info.artist.isEmpty()) id3.id3tag_set_artist(f, info.artist);
            if (!info.album.isEmpty()) id3.id3tag_set_album(f, info.album);
            if (!info.year.isEmpty()) id3.id3tag_set_year(f, info.year);
            if (!info.track.isEmpty()) id3.id3tag_set_track(f, info.track);
            if (!info.isrc.isEmpty()) id3.id3tag_set_fieldvalue(f, "TSRC=" + info.isrc.toUpperCase(Locale.US));
            if (lame.lame_init_params(f) < 0) throw new IOException("MP3 encoder could not start");
            long skip = h.dataStart;
            while (skip > 0) { long s = in.skip(skip); if (s <= 0) throw new IOException("WAV ended early"); skip -= s; }
            byte[] raw = new byte[1152 * 4], encoded = new byte[32768];
            int[] l = new int[1152], r = new int[1152];
            long left = h.frames;
            while (left > 0) {
                int want = (int) Math.min(1152, left) * 4, got = 0;
                while (got < want) { int n = in.read(raw, got, want - got); if (n < 0) break; got += n; }
                int frames = got / 4;
                if (frames == 0) break;
                for (int i = 0; i < frames; i++) {
                    int p = i * 4;
                    l[i] = (short) ((raw[p] & 255) | raw[p + 1] << 8) << 16;
                    r[i] = (short) ((raw[p + 2] & 255) | raw[p + 3] << 8) << 16;
                }
                int size = lame.lame_encode_buffer_int(f, l, r, frames, encoded, 0, encoded.length);
                if (size < 0) throw new IOException("MP3 encoding failed");
                mp3.write(encoded, 0, size);
                left -= frames;
            }
            int size = lame.lame_encode_flush(f, encoded, 0, encoded.length);
            if (size < 0) throw new IOException("MP3 encoding failed");
            mp3.write(encoded, 0, size);
            // The library's own tag writer skips files that have an ID3 header, so the
            // gapless frame goes straight after the header.
            mp3.seek(0);
            byte[] head = new byte[10];
            mp3.readFully(head);
            long offset = 0;
            if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                offset = 10 + ((head[6] & 127) << 21) + ((head[7] & 127) << 14) + ((head[8] & 127) << 7) + (head[9] & 127);
            }
            byte[] tag = new byte[2880];
            int n = vbr.getLameTagFrame(f, tag);
            if (n > 0 && n <= tag.length && offset + n <= mp3.length()) { mp3.seek(offset); mp3.write(tag, 0, n); }
        } finally {
            lame.lame_close(f);
        }
    }

    /** Copies a WAV with a standard LIST INFO block of the song's details added at the end. */
    public static void copyTagged(File wav, OutputStream out, Info info) throws IOException {
        byte[] list = infoChunk(info);
        long size = wav.length();
        byte[] b = new byte[1 << 16];
        try (InputStream in = new FileInputStream(wav)) {
            int n = in.read(b, 0, 12);
            if (n < 12) throw new IOException("WAV too short");
            long riff = size - 8 + list.length;
            b[4] = (byte) riff; b[5] = (byte) (riff >> 8); b[6] = (byte) (riff >> 16); b[7] = (byte) (riff >> 24);
            out.write(b, 0, 12);
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        out.write(list);
    }

    private static byte[] infoChunk(Info i) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write("INFO".getBytes(StandardCharsets.US_ASCII));
        field(body, "INAM", i.title);
        field(body, "IART", i.artist);
        field(body, "IPRD", i.album);
        field(body, "ICRD", i.year);
        field(body, "ITRK", i.track);
        if (!i.isrc.isEmpty()) field(body, "ICMT", "ISRC " + i.isrc.toUpperCase(Locale.US));
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write("LIST".getBytes(StandardCharsets.US_ASCII));
        size(all, body.size());
        body.writeTo(all);
        return all.toByteArray();
    }

    private static void field(ByteArrayOutputStream o, String id, String value) throws IOException {
        if (value == null || value.isEmpty()) return;
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        o.write(id.getBytes(StandardCharsets.US_ASCII));
        size(o, v.length + 1);
        o.write(v);
        o.write(0);
        if (((v.length + 1) & 1) == 1) o.write(0);
    }

    private static void size(ByteArrayOutputStream o, int v) {
        o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24);
    }
}
