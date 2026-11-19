package com.dennis.masterroom.desktop;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.InputStream;

/** The phone's Console look on Windows: the same colours (ui/Look.java) and the same Barlow and JetBrains Mono fonts. */
final class Look {
    private Look() {}

    static final Color BG = c(0x0A0D12), PANEL = c(0x141922), PANEL_TOP = c(0x242A35), RAISED = c(0x242A33), WELL = c(0x07090D);
    static final Color LINE = c(0x414751), HAIR = c(0x303741), TEXT = c(0xF7F4EE), MID = c(0xD5D3CE), DIM = c(0xA5ADB9);
    static final Color AMBER = c(0xFFB83D), GOLD_HI = c(0xFFD47A), GOLD_LO = c(0xE39C24), AMBER_DIM = c(0x987142), BLUE = c(0x78BDD9), HOT = c(0xD96C4B);

    static Font body, strong, label, title, mono;

    static void load() {
        body = font("Barlow-Regular.ttf", new Font("Segoe UI", Font.PLAIN, 14));
        strong = font("Barlow-SemiBold.ttf", new Font("Segoe UI Semibold", Font.PLAIN, 14));
        label = font("BarlowCondensed-SemiBold.ttf", new Font("Segoe UI", Font.BOLD, 13));
        title = font("BarlowCondensed-Bold.ttf", new Font("Segoe UI", Font.BOLD, 22));
        mono = font("JetBrainsMono.ttf", new Font(Font.MONOSPACED, Font.PLAIN, 13));
    }

    /** Fonts ship in the jar (/fonts) or a fonts folder beside it; Windows fonts stand in when missing. */
    private static Font font(String file, Font fallback) {
        try (InputStream in = Look.class.getResourceAsStream("/fonts/" + file)) {
            if (in != null) return register(Font.createFont(Font.TRUETYPE_FONT, in));
        } catch (Exception ignored) { }
        try {
            File jar = new File(Look.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File f = new File(jar.isFile() ? jar.getParentFile() : jar, "fonts/" + file);
            if (f.isFile()) return register(Font.createFont(Font.TRUETYPE_FONT, f));
        } catch (Exception ignored) { }
        return fallback;
    }

    private static Font register(Font f) {
        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
        return f.deriveFont(14f);
    }

    static Color c(int rgb) { return new Color(rgb); }
    static Font size(Font f, float pt) { return f.deriveFont(pt); }
}
