package com.dennis.masterroom.desktop;

import javax.swing.*;
import java.io.File;
import java.util.Arrays;

/** Master Room for Windows. No arguments: the window. --master: command line. --screenshot: a PNG of the window, for checks. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--master")) System.exit(Cli.run(args));
        int shot = Arrays.asList(args).indexOf("--screenshot");
        if (shot >= 0) {
            System.setProperty("java.awt.headless", System.getProperty("java.awt.headless", "true"));
            Look.load();
            MainWindow.tooltips();
            File png = new File(args[shot + 1]);
            MasterJob.Outcome demo = Arrays.asList(args).contains("--demo") ? Demo.outcome() : null;
            boolean busy = Arrays.asList(args).contains("--busy");
            MainWindow.screenshot(png, 1180, 760, demo, demo != null || busy ? "Dusty Road (final mix).wav" : null, busy);
            System.out.println("SCREENSHOT " + png.getAbsolutePath());
            return;
        }
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        System.setProperty("awt.useSystemAAFontSettings", "lcd");
        SwingUtilities.invokeLater(() -> {
            Look.load();
            MainWindow.tooltips();
            MainWindow w = new MainWindow();
            w.showFrame();
            if (args.length > 0 && new File(args[0]).isFile()) w.pick(new File(args[0]));
        });
    }
}
