package com.dennis.masterroom.ui;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Streaming;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** The phone's own AAC encoder and decoder, as Apple Music and YouTube use that format. */
final class AacCodec implements Streaming.Codec {
    private static final String MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int TIMEOUT_US = 10000, RATE = 44100;

    @Override public String name() { return "AAC 256"; }

    @Override public Audio roundTrip(Audio in) throws IOException {
        byte[] pcm = new byte[in.frames() * 4];
        ByteBuffer b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < in.frames(); i++) {
            b.putShort((short) Math.round(Math.max(-1, Math.min(1, in.l[i])) * 32767));
            b.putShort((short) Math.round(Math.max(-1, Math.min(1, in.r[i])) * 32767));
        }
        List<byte[]> packets = new ArrayList<>();
        byte[] config = encode(pcm, packets);
        return decode(config, packets);
    }

    private static byte[] encode(byte[] pcm, List<byte[]> packets) throws IOException {
        MediaFormat f = MediaFormat.createAudioFormat(MIME, RATE, 2);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 256000);
        MediaCodec c = MediaCodec.createEncoderByType(MIME);
        byte[] config = null;
        try {
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            c.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int at = 0;
            boolean fed = false;
            while (true) {
                if (!fed) {
                    int i = c.dequeueInputBuffer(TIMEOUT_US);
                    if (i >= 0) {
                        ByteBuffer buffer = c.getInputBuffer(i);
                        buffer.clear();
                        int n = Math.min(buffer.remaining(), pcm.length - at);
                        buffer.put(pcm, at, n);
                        long time = at / 4 * 1000000L / RATE;
                        at += n;
                        fed = at >= pcm.length;
                        c.queueInputBuffer(i, 0, n, time, fed ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    }
                }
                int o = c.dequeueOutputBuffer(info, TIMEOUT_US);
                if (o >= 0) {
                    byte[] data = new byte[info.size];
                    ByteBuffer out = c.getOutputBuffer(o);
                    out.position(info.offset);
                    out.get(data);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) config = data;
                    else if (info.size > 0) packets.add(data);
                    c.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            c.stop();
        } finally {
            c.release();
        }
        if (config == null) throw new IOException("AAC encoder gave no setup data");
        return config;
    }

    private static Audio decode(byte[] config, List<byte[]> packets) throws IOException {
        MediaFormat f = MediaFormat.createAudioFormat(MIME, RATE, 2);
        f.setByteBuffer("csd-0", ByteBuffer.wrap(config));
        f.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT);
        MediaCodec c = MediaCodec.createDecoderByType(MIME);
        ByteArrayOutputStream raw = new ByteArrayOutputStream(packets.size() * 8192);
        boolean floats = false;
        try {
            c.configure(f, null, null, 0);
            c.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int next = 0;
            boolean fed = false;
            while (true) {
                if (!fed) {
                    int i = c.dequeueInputBuffer(TIMEOUT_US);
                    if (i >= 0) {
                        ByteBuffer buffer = c.getInputBuffer(i);
                        buffer.clear();
                        if (next < packets.size()) {
                            byte[] p = packets.get(next);
                            buffer.put(p);
                            c.queueInputBuffer(i, 0, p.length, next * 23000L, 0);
                            next++;
                        } else {
                            c.queueInputBuffer(i, 0, 0, next * 23000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            fed = true;
                        }
                    }
                }
                int o = c.dequeueOutputBuffer(info, TIMEOUT_US);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat now = c.getOutputFormat();
                    floats = now.containsKey(MediaFormat.KEY_PCM_ENCODING) && now.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                } else if (o >= 0) {
                    byte[] data = new byte[info.size];
                    ByteBuffer out = c.getOutputBuffer(o);
                    out.position(info.offset);
                    out.get(data);
                    raw.write(data, 0, data.length);
                    c.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            c.stop();
        } finally {
            c.release();
        }
        byte[] all = raw.toByteArray();
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        int frames = all.length / (floats ? 8 : 4);
        Audio out = new Audio(frames, RATE);
        for (int i = 0; i < frames; i++) {
            out.l[i] = floats ? b.getFloat() : b.getShort() / 32768f;
            out.r[i] = floats ? b.getFloat() : b.getShort() / 32768f;
        }
        return out;
    }
}
