package com.dennis.masterroom.desktop;

import com.dennis.masterroom.engine.Listen;
import com.dennis.masterroom.engine.Spectrum;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;

/**
 * Build 3: the phone's console views, drawn for the desktop. Nothing here is invented: with nothing
 * measured the meter stays dark, and the lights only move with audio that is actually being heard.
 */
final class Console {
    private Console() {}

    private static Graphics2D smooth(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        return g;
    }

    private static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a))); }

    /** What the meter follows while something plays. */
    interface Live {
        boolean on();
        /** Fast level for the lights (LUFS-scaled). */
        float light();
        /** Momentary loudness (400 ms) for the number, or NaN while it fills. */
        double lufs();
    }

    /**
     * The phone's Light Arc meter. The gold marker is the round's target. At rest the lights show the
     * selected version's measured loudness; while playing they follow what is being heard.
     */
    static final class Meter extends JComponent {
        private static final float W = 342f, H = 188f, CX = 171f, CY = 160f, R = 128f, LENGTH = 22f;
        private static final float LOW = -24f, HIGH = -6f, FALL = 0.14f;
        private static final int SEGMENTS = 33;
        private static final Color GREEN = new Color(0x27E36B), RED = new Color(0xFF3B30);
        float target = -14, measured = Float.NaN;
        String restingCaption = "AWAITING A ROUND";
        boolean hidden;
        Live live;
        /** For screenshots: a fixed playing state. */
        float demoLight = Float.NaN; double demoLufs = Double.NaN;
        private float shown = LOW - 1;

        Meter() { setPreferredSize(new Dimension(342, 188)); setMinimumSize(new Dimension(240, 132)); }

        @Override public Dimension getPreferredSize() { return new Dimension(348, Math.round(348 * H / W)); }
        @Override public Dimension getMaximumSize() { Dimension d = getPreferredSize(); return new Dimension(Integer.MAX_VALUE, d.height); }

        boolean playing() { return !Float.isNaN(demoLight) || (live != null && live.on()); }

        /** True while the lights are still moving (so the window keeps redrawing). */
        boolean settling() {
            float wanted = wanted();
            return playing() || Math.abs(wanted - shown) > 0.05f;
        }

        private float wanted() {
            if (!Float.isNaN(demoLight)) return demoLight;
            if (live != null && live.on()) return live.light();
            return Float.isFinite(measured) && !hidden ? measured : LOW - 1;
        }

        private static double angle(float lufs) {
            float at = (Math.max(LOW, Math.min(HIGH, lufs)) - LOW) / (HIGH - LOW);
            return Math.toRadians(-82 + at * 164);
        }

        private static Color colour(float lufs) { return lufs < -14 ? GREEN : lufs < -10 ? Look.AMBER : RED; }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            float wanted = wanted();
            shown = wanted > shown ? wanted : shown + (wanted - shown) * FALL;
            float s = Math.min(getWidth() / W, getHeight() / H);
            g.translate((getWidth() - W * s) / 2, 0);
            g.scale(s, s);
            g.setColor(new Color(0, 0, 0, 0x24));
            g.fill(new RoundRectangle2D.Float(0, 0, W, H, 16, 16));
            for (int i = 0; i < SEGMENTS; i++) {
                float lufs = LOW + (HIGH - LOW) * i / (SEGMENTS - 1);
                double a = angle(lufs);
                float sin = (float) Math.sin(a), cos = (float) Math.cos(a);
                Line2D.Float seg = new Line2D.Float(CX + R * sin, CY - R * cos, CX + (R - LENGTH) * sin, CY - (R - LENGTH) * cos);
                boolean lit = lufs <= shown;
                Color c = colour(lufs);
                if (lit) {
                    g.setColor(alpha(c, 0x55));
                    g.setStroke(new BasicStroke(13, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(seg);
                }
                g.setColor(lit ? c : alpha(c, 0x26));
                g.setStroke(new BasicStroke(7, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(seg);
            }
            // The target sits outside the lights so it never reads as a measurement.
            double t = angle(target);
            float sin = (float) Math.sin(t), cos = (float) Math.cos(t);
            float ax = CX + (R + 6) * sin, ay = CY - (R + 6) * cos, bx = CX + (R + 19) * sin, by = CY - (R + 19) * cos;
            float dx = (ay - by) * 0.45f, dy = (bx - ax) * 0.45f;
            Path2D.Float tri = new Path2D.Float();
            tri.moveTo(ax, ay); tri.lineTo(bx + dx, by + dy); tri.lineTo(bx - dx, by - dy); tri.closePath();
            g.setColor(Look.AMBER); g.fill(tri);

            boolean playing = playing();
            double number = !Double.isNaN(demoLufs) ? demoLufs : playing ? live.lufs() : hidden ? Double.NaN : measured;
            String caption = playing ? (hidden ? "NOW  ·  AS HEARD" : "NOW  ·  AS HEARD  ·  LUFS") : hidden ? "HIDDEN IN THE BLIND TEST" : restingCaption;
            if (playing && hidden) number = Double.NaN;
            center(g, Double.isFinite(number) ? String.format(Locale.US, "%.1f", number) : "--", Look.size(Look.mono, 30), Look.TEXT, 118);
            center(g, caption, Look.size(Look.label, 11), Look.DIM, 134);
            center(g, String.format(Locale.US, "TARGET %.1f LUFS  ·  CEILING %.1f dBTP", target, com.dennis.masterroom.engine.Engineer.ceilingFor(target)), Look.size(Look.label, 12), Look.AMBER, 156);
            g.setFont(Look.size(Look.label, 11)); g.setColor(Look.DIM);
            g.drawString("-24", 12, 178);
            g.drawString("-6", W - 12 - g.getFontMetrics().stringWidth("-6"), 178);
            g.dispose();
        }

        private static void center(Graphics2D g, String s, Font f, Color c, float y) {
            g.setFont(f); g.setColor(c);
            g.drawString(s, CX - g.getFontMetrics().stringWidth(s) / 2f, y);
        }
    }

    /** The phone's EQ lights: rainbow bars from the audio being heard; at rest a dim picture of the version's tone. */
    static final class Lights extends JComponent {
        interface Source { boolean now(float[] out); }
        private static final float FALL = 0.86f;
        private final float[] wanted = new float[Spectrum.BARS], shown = new float[Spectrum.BARS];
        private final Color[] colours = new Color[Spectrum.BARS];
        private float[] resting = new float[Spectrum.BARS];
        private float light = 0.4f;
        Source source;
        /** For screenshots: fixed live bars. */
        float[] demo;

        Lights() {
            for (int i = 0; i < colours.length; i++) colours[i] = Color.getHSBColor(i * 280f / 360f / (colours.length - 1), 0.85f, 1f);
            setPreferredSize(new Dimension(340, 170));
        }

        void setResting(float[] r) { resting = r == null ? new float[Spectrum.BARS] : r; repaint(); }

        private boolean live() {
            if (demo != null) { System.arraycopy(demo, 0, wanted, 0, wanted.length); return true; }
            return source != null && source.now(wanted);
        }

        boolean settling = true;

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            boolean live = live();
            if (!live) System.arraycopy(resting, 0, wanted, 0, wanted.length);
            boolean moving = live;
            for (int i = 0; i < shown.length; i++) {
                float before = shown[i];
                shown[i] = demo != null ? wanted[i] : wanted[i] > before ? wanted[i] : wanted[i] + (before - wanted[i]) * FALL;
                if (Math.abs(shown[i] - wanted[i]) > 0.004f) moving = true;
            }
            float aim = live ? 1f : 0.4f;
            light = demo != null ? 1f : light + (aim - light) * 0.15f;
            if (Math.abs(light - aim) > 0.01f) moving = true;
            settling = moving;

            int w = getWidth(), total = getHeight(), mirror = 26, h = total - mirror - 3;
            float step = (float) w / shown.length, width = step * 0.62f, glow = 3, corner = 6;
            int a = (int) (255 * light);
            for (int i = 0; i < shown.length; i++) {
                float x = i * step + (step - width) / 2, size = Math.max(4, shown[i] * h);
                g.setColor(alpha(colours[i], a / 4));
                g.fill(new RoundRectangle2D.Float(x - glow, h - size - glow, width + 2 * glow, size + glow, corner, corner));
                g.setColor(alpha(colours[i], a));
                g.fill(new RoundRectangle2D.Float(x, h - size, width, size, corner, corner));
                g.setColor(alpha(colours[i], a / 4));
                g.fill(new RoundRectangle2D.Float(x, h + 3, width, size * mirror / h, corner, corner));
            }
            g.dispose();
        }
    }

    /** What the master did to the tone: the original and the selected take against the target. */
    static final class Tone extends JComponent {
        private static final int FIRST = 3, LAST = 28;
        private double[] source, take;
        private String takeName = "";
        private boolean hidden;
        private double top = 4, bottom = -10;

        Tone() { setPreferredSize(new Dimension(340, 170)); }

        /** Each curve is lined up with the target on its own 150 Hz to 3 kHz level (the phone's rule). */
        static double[] againstTarget(double[] song, double[] target) {
            if (song == null || target == null) return null;
            double sum = 0;
            int n = 0;
            for (int i = 0; i < 31; i++) if (Listen.BANDS[i] >= 150 && Listen.BANDS[i] <= 3000) { sum += song[i] - target[i]; n++; }
            double offset = sum / n;
            double[] out = new double[31];
            for (int i = 0; i < 31; i++) out[i] = song[i] - target[i] - offset;
            return out;
        }

        void show(double[] source, double[] take, String takeName, boolean hidden) {
            this.source = source; this.take = take; this.takeName = takeName == null ? "" : takeName; this.hidden = hidden;
            top = 4; bottom = -10;
            for (double[] d : new double[][]{source, take}) {
                if (d == null) continue;
                for (int b = FIRST; b <= LAST; b++) { top = Math.max(top, Math.ceil(d[b])); bottom = Math.min(bottom, Math.floor(d[b])); }
            }
            repaint();
        }

        private float x(int band) { return 8 + (band - FIRST) * (getWidth() - 16f - 44) / (LAST - FIRST); }
        private float y(double db) { float from = 30, plot = getHeight() - 56; return from + (float) ((top - db) / (top - bottom)) * plot; }

        private void curve(Graphics2D g, double[] d, Color c, float width) {
            Path2D.Float p = new Path2D.Float();
            for (int b = FIRST; b <= LAST; b++) { if (b == FIRST) p.moveTo(x(b), y(d[b])); else p.lineTo(x(b), y(d[b])); }
            g.setColor(c); g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(p);
        }

        private float legend(Graphics2D g, float x, Color c, String name) {
            g.setColor(c); g.fillRect((int) x, 10, 14, 3);
            g.setColor(Look.MID); g.drawString(name, x + 19, 16);
            return x + 19 + g.getFontMetrics().stringWidth(name) + 14;
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            int w = getWidth(), h = getHeight();
            if (hidden || source == null) {
                g.setFont(Look.size(Look.body, 14)); g.setColor(Look.DIM);
                String s = hidden ? "Hidden during the blind test" : "Master a song to see its tone";
                g.drawString(s, (w - g.getFontMetrics().stringWidth(s)) / 2f, h / 2f);
                g.dispose(); return;
            }
            g.setColor(Look.DIM);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{5, 6}, 0));
            g.draw(new Line2D.Float(0, y(0), w - 44, y(0)));
            curve(g, source, Look.BLUE, 2f);
            if (take != null) curve(g, take, Look.AMBER, 2.5f);
            g.setFont(Look.size(Look.mono, 12)); g.setColor(Look.DIM);
            int[] marks = {4, 10, 17, 24};
            String[] names = {"50", "200", "1k", "5k"};
            for (int i = 0; i < marks.length; i++) g.drawString(names[i], x(marks[i]) - g.getFontMetrics().stringWidth(names[i]) / 2f, h - 5);
            g.setFont(Look.size(Look.mono, 11));
            for (double v : new double[]{top, 0, bottom}) {
                String s = Math.abs(v) < 0.5 ? "0 dB" : String.format(Locale.US, "%+.0f dB", v);
                g.drawString(s, w - g.getFontMetrics().stringWidth(s), y(v) + 4);
            }
            g.setFont(Look.size(Look.body, 13));
            float at = legend(g, 4, Look.BLUE, "Original");
            if (take != null) at = legend(g, at, Look.AMBER, takeName);
            legend(g, at, Look.DIM, "Target (dashed)");
            g.dispose();
        }
    }
}
