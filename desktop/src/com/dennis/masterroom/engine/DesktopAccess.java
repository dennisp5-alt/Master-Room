package com.dennis.masterroom.engine;

import java.io.File;
import java.io.IOException;

/**
 * Windows app only (desktop/src): opens the engine's own MP3 decoder, which is package-private,
 * so the desktop reads MP3 exactly as the engine's streaming check does. Adds no processing.
 */
public final class DesktopAccess {
    private DesktopAccess() {}
    public static Audio decodeMp3(File mp3) throws IOException { return Release.decode(mp3); }
}
