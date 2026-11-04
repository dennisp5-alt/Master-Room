package com.dennis.masterroom.ui;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Wav;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Opens a WAV directly; anything else the phone can decode goes through MediaCodec. */
final class Decoder {
    private Decoder() {}

    static Audio open(Context c, Uri uri) throws IOException {
        try (InputStream raw = c.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("Could not open that file");
            InputStream in = new BufferedInputStream(raw, 1 << 16);
            in.mark(16);
            byte[] head = new byte[12];
            int got = in.read(head);
            in.reset();
            if (got == 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') {
                return Wav.read(in);
            }
        }
        return decode(c, uri);
    }

    private static Audio decode(Context c, Uri uri) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(c, uri, null);
            MediaFormat format = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { ex.selectTrack(i); format = f; break; }
            }
            if (format == null) throw new IOException("No audio in that file");
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            float[] l = new float[rate * 240], r = new float[rate * 240];
            int n = 0;
            boolean inputDone = false, outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                if (!inputDone) {
                    int id = codec.dequeueInputBuffer(10000);
                    if (id >= 0) {
                        ByteBuffer b = codec.getInputBuffer(id);
                        int size = ex.readSampleData(b, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(id, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(id, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int id = codec.dequeueOutputBuffer(info, 10000);
                if (id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                } else if (id >= 0) {
                    ByteBuffer b = codec.getOutputBuffer(id);
                    b.position(info.offset); b.limit(info.offset + info.size);
                    b.order(ByteOrder.LITTLE_ENDIAN);
                    int step = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
                    int frames = info.size / (step * channels);
                    if (n + frames > l.length) {
                        int grow = Math.max(l.length * 2, n + frames);
                        l = Arrays.copyOf(l, grow); r = Arrays.copyOf(r, grow);
                    }
                    for (int i = 0; i < frames; i++, n++) {
                        float a = 0, z = 0;
                        for (int k = 0; k < channels; k++) {
                            float v = step == 4 ? b.getFloat() : b.getShort() / 32768f;
                            if (k == 0) a = v; else if (k == 1) z = v;
                        }
                        l[n] = a; r[n] = channels > 1 ? z : a;
                    }
                    codec.releaseOutputBuffer(id, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            return new Audio(Arrays.copyOf(l, n), Arrays.copyOf(r, n), rate);
        } finally {
            if (codec != null) { try { codec.stop(); } catch (RuntimeException ignored) { } codec.release(); }
            ex.release();
        }
    }
}
