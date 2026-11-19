package com.dennis.masterroom.desktop;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Master Room's icon, drawn rather than stored: the console's loudness arc (lit green into gold) on a dark
 * tile, with the gold target mark above it. Used for the window, and written as a Windows .ico for the app
 * and the installer ("java ... AppIcon out.ico [preview.png]").
 */
public final class AppIcon {
    private AppIcon() {}

    static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    static BufferedImage draw(int size) {
        BufferedImage im = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float s = size;
        float inset = s * 0.03f, corner = s * 0.22f;
        g.setPaint(new GradientPaint(0, 0, new Color(0x242A35), 0, s, new Color(0x0A0D12)));
        g.fill(new RoundRectangle2D.Float(inset, inset, s - 2 * inset, s - 2 * inset, corner, corner));
        if (size >= 32) {
            g.setColor(new Color(0x414751));
            g.setStroke(new BasicStroke(Math.max(1f, s / 96f)));
            g.draw(new RoundRectangle2D.Float(inset, inset, s - 2 * inset - 1, s - 2 * inset - 1, corner, corner));
        }
        float cx = s / 2f, cy = s * 0.70f, r = s * 0.33f;
        float width = Math.max(1.6f, s * (size <= 24 ? 0.14f : 0.10f));
        // Lit part of the arc: green rising into gold, then the unlit rest.
        int segments = size <= 24 ? 5 : size <= 48 ? 9 : 15;
        float from = 172, to = 8, lit = 0.72f;
        float span = (from - to) / segments, gap = size <= 24 ? span * 0.22f : span * 0.30f;
        for (int i = 0; i < segments; i++) {
            float at = (float) i / (segments - 1);
            float start = from - i * span - gap / 2;
            Color c = at < 0.45f ? new Color(0x27E36B) : at <= lit ? new Color(0xFFB83D) : new Color(0x987142);
            if (at > lit) c = new Color(c.getRed(), c.getGreen(), c.getBlue(), 110);
            g.setColor(c);
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, start, -(span - gap), Arc2D.OPEN));
        }
        // The target mark, gold, pointing at the arc where the lit part ends.
        double a = Math.toRadians(from - lit * (from - to) - span / 2);
        float tipR = r + width * 0.75f, baseR = r + width * 0.75f + s * 0.13f;
        float tx = cx + (float) (Math.cos(a) * tipR), ty = cy - (float) (Math.sin(a) * tipR);
        float bx = cx + (float) (Math.cos(a) * baseR), by = cy - (float) (Math.sin(a) * baseR);
        float nx = (float) -Math.sin(a), ny = (float) -Math.cos(a), half = s * 0.065f;
        Path2D.Float tri = new Path2D.Float();
        tri.moveTo(tx, ty);
        tri.lineTo(bx + nx * half, by + ny * half);
        tri.lineTo(bx - nx * half, by - ny * half);
        tri.closePath();
        g.setPaint(new GradientPaint(0, by - half, new Color(0xFFD47A), 0, ty, new Color(0xE39C24)));
        g.fill(tri);
        // A small gold base under the arc, like the meter's reading.
        if (size >= 32) {
            g.setColor(new Color(0xFFB83D));
            float bw = s * 0.26f, bh = Math.max(2f, s * 0.05f);
            g.fill(new RoundRectangle2D.Float(cx - bw / 2, cy + s * 0.04f, bw, bh, bh, bh));
        }
        g.dispose();
        return im;
    }

    static List<Image> images() {
        List<Image> out = new ArrayList<>();
        for (int s : SIZES) out.add(draw(s));
        return out;
    }

    /** An .ico with every size as PNG (Windows Vista and later read PNG entries). */
    static void writeIco(File f) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (int s : SIZES) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(draw(s), "png", b);
            pngs.add(b.toByteArray());
        }
        ByteBuffer head = ByteBuffer.allocate(6 + 16 * SIZES.length).order(ByteOrder.LITTLE_ENDIAN);
        head.putShort((short) 0).putShort((short) 1).putShort((short) SIZES.length);
        int offset = 6 + 16 * SIZES.length;
        for (int i = 0; i < SIZES.length; i++) {
            int s = SIZES[i];
            head.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0)
                .putShort((short) 1).putShort((short) 32).putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(head.array());
            for (byte[] p : pngs) out.write(p);
        }
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        writeIco(new File(args[0]));
        if (args.length > 1) {
            int[] show = {16, 24, 32, 48, 64, 128, 256};
            BufferedImage sheet = new BufferedImage(16 + 24 + 32 + 48 + 64 + 128 + 256 + 8 * 20, 276, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = sheet.createGraphics();
            g.setColor(new Color(0xF2F2F2)); g.fillRect(0, 0, sheet.getWidth(), 138);
            g.setColor(new Color(0x202020)); g.fillRect(0, 138, sheet.getWidth(), 138);
            int x = 10;
            for (int s : show) {
                g.drawImage(draw(s), x, s > 128 ? 10 : 10, null);
                g.drawImage(draw(Math.min(s, 128)), x, 148, null);
                x += s + 20;
            }
            g.dispose();
            ImageIO.write(sheet, "png", new File(args[1]));
        }
        System.out.println("ICON " + new File(args[0]).getAbsolutePath());
    }
}
