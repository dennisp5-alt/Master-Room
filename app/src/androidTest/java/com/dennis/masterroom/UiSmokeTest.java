package com.dennis.masterroom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;

import com.dennis.masterroom.engine.Audio;
import com.dennis.masterroom.engine.Wav;
import com.dennis.masterroom.ui.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** Drives the real screens with a synthetic song, from loading a file to saving a take. */
@RunWith(JUnit4.class)
public class UiSmokeTest {
    private final Instrumentation ins = InstrumentationRegistry.getInstrumentation();
    private final Context app = ins.getTargetContext();
    private Activity activity;
    private Instrumentation.ActivityMonitor picker;
    private File screens;
    private int shot;

    private static Audio song(int seconds, long seed, double level) {
        int rate = 48000, n = seconds * rate;
        Audio a = new Audio(n, rate);
        Random q = new Random(seed);
        double low = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / rate, section = ((int) (t / 6)) % 2 == 0 ? 0.55 : 1.0;
            double hit = Math.exp(-(i % (rate / 2)) / (0.07 * rate));
            low += (q.nextGaussian() - low) * 0.35;
            double tones = 0.12 * Math.sin(2 * Math.PI * 110 * t) + 0.08 * Math.sin(2 * Math.PI * 440 * t) + 0.05 * Math.sin(2 * Math.PI * 1760 * t);
            double kick = 0.45 * hit * Math.sin(2 * Math.PI * 62 * t);
            a.l[i] = (float) (level * section * (tones + kick + 0.08 * low));
            a.r[i] = (float) (level * section * (tones * 0.9 + kick + 0.08 * q.nextGaussian() * 0.5));
        }
        return a;
    }

    private void answerPicker(File f) throws Exception {
        if (picker != null) ins.removeMonitor(picker);
        IntentFilter filter = new IntentFilter(Intent.ACTION_OPEN_DOCUMENT, "audio/*");
        filter.addCategory(Intent.CATEGORY_OPENABLE);
        picker = ins.addMonitor(filter, new Instrumentation.ActivityResult(Activity.RESULT_OK, new Intent().setData(Uri.fromFile(f))), true);
    }

    private <T> T onUi(Callable<T> c) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            try { return c.call(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        ins.runOnMainSync(() -> { try { out.set(c.call()); } catch (Exception e) { err.set(e); } });
        if (err.get() != null) throw new RuntimeException(err.get());
        return out.get();
    }

    private void collect(View v, List<View> out) {
        if (v.getVisibility() != View.VISIBLE) return;
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private List<View> visible() {
        return onUi(() -> { List<View> all = new ArrayList<>(); collect(activity.getWindow().getDecorView(), all); return all; });
    }

    private View find(String wanted) {
        for (View v : visible()) {
            if (v instanceof TextView && wanted.contentEquals(((TextView) v).getText())) return v;
            if (v.getContentDescription() != null && wanted.contentEquals(v.getContentDescription())) return v;
        }
        return null;
    }

    private boolean hasText(String part) {
        for (View v : visible()) if (v instanceof TextView && ((TextView) v).getText().toString().contains(part)) return true;
        return false;
    }

    private void waitFor(String what, long ms, Callable<Boolean> c) throws Exception {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            if (Boolean.TRUE.equals(c.call())) return;
            SystemClock.sleep(150);
        }
        shot("timeout");
        throw new AssertionError("Timed out waiting for " + what);
    }

    private View clickableParent(View v) {
        View t = v;
        while (t != null && !t.isClickable()) t = t.getParent() instanceof View ? (View) t.getParent() : null;
        return t;
    }

    private void tap(String wanted) throws Exception {
        View target = null;
        for (View v : visible()) {
            boolean named = (v instanceof TextView && wanted.contentEquals(((TextView) v).getText()))
                    || (v.getContentDescription() != null && wanted.contentEquals(v.getContentDescription()));
            if (named && clickableParent(v) != null) { target = clickableParent(v); break; }
        }
        assertNotNull("No control: " + wanted, target);
        final View t = target;
        assertTrue("Disabled: " + wanted, t.isEnabled());
        onUi(() -> { t.performClick(); return true; });
        ins.waitForIdleSync();
    }

    private void scroll(boolean bottom) {
        onUi(() -> {
            for (View v : visible()) {
                if (v instanceof ScrollView) { ScrollView s = (ScrollView) v; s.scrollTo(0, bottom ? s.getChildAt(0).getHeight() : 0); }
            }
            return true;
        });
        ins.waitForIdleSync();
    }

    private void shot(String name) throws Exception {
        SystemClock.sleep(250);
        Bitmap b = ins.getUiAutomation().takeScreenshot();
        if (b == null) return;
        try (FileOutputStream out = new FileOutputStream(new File(screens, String.format("%02d_%s.png", ++shot, name)))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** No touch target on the given screen may be under 48dp in either direction. */
    private void checkTargets() {
        float density = app.getResources().getDisplayMetrics().density;
        for (View v : visible()) {
            if (v.isClickable() && v.isEnabled() && !(v instanceof ViewGroup && v.getParent() == null)) {
                int min = Math.round(47.5f * density);
                assertTrue("Small target " + v + " " + v.getWidth() + "x" + v.getHeight(), v.getHeight() >= min && v.getWidth() >= min);
            }
        }
    }

    @Test public void wholeFlow() throws Exception {
        File dir = app.getExternalFilesDir(null);
        screens = new File(dir, "screens");
        screens.mkdirs();
        File first = new File(dir, "Test_Song.wav"), other = new File(dir, "StemLab_Master.wav");
        Wav.write(first, song(24, 1, 0.5), 24);
        Wav.write(other, song(24, 2, 0.8), 24);
        answerPicker(first);
        // Eight remembered picks, so this run also proves the Yours take appears.
        try (java.io.PrintWriter w = new java.io.PrintWriter(new File(app.getFilesDir(), "taste.txt"))) {
            for (int i = 0; i < 8; i++) w.println("COUNTRY|blind|" + (i % 2 == 0 ? "Warm" : "Punch"));
        }

        Intent launch = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = ins.startActivitySync(launch);
        ins.waitForIdleSync();
        shot("session_empty");
        assertTrue("A meter must say when nothing is measured", hasText("--"));

        tap("Load");
        waitFor("real source measurement", 120000, () -> !hasText("Measuring") && !hasText("--"));
        scroll(false);
        shot("session_loaded");
        checkTargets();
        assertFalse("Genre list starts closed", hasText("Metal"));
        tap("Genre");
        SystemClock.sleep(600);
        assertTrue("Genre list opens", hasText("Metal") && hasText("Hip-Hop"));
        assertTrue("Grunge is on the list", hasText("Grunge"));
        shot("genre_list");
        tap("Rock");
        assertFalse("Picking a genre closes the list", hasText("Metal"));
        assertNotNull("Picked genre is shown", find("Rock"));
        tap("Genre");
        SystemClock.sleep(600);
        tap("Country");
        tap("-12");
        assertTrue(hasText("ceiling -2.0 dBTP"));
        tap("-14");
        assertTrue(hasText("ceiling -1.0 dBTP"));
        scroll(true);
        shot("session_bottom");
        checkTargets();

        // The main page can play the song, with the arc following it.
        scroll(false);
        tap("PLAY SONG");
        SystemClock.sleep(2500);
        assertNotNull("Song plays from the main page", find("STOP SONG"));
        shot("session_song_playing");
        tap("STOP SONG");
        assertNotNull(find("PLAY SONG"));
        scroll(true);

        // Stop: a wrong press can be stopped, and the song stays loaded and ready.
        tap("-12");
        tap("MASTER IT");
        SystemClock.sleep(2500);
        shot("session_stop_available");
        tap("STOP MASTERING");
        waitFor("render to stop", 60000, () -> find("MASTER IT") != null && hasText("Stopped."));
        assertTrue(find("MASTER IT").isEnabled());
        assertTrue("Song stays loaded", hasText("Test_Song"));
        assertTrue("Nothing half-made to compare", find("COMPARE") == null);
        shot("session_stopped");
        tap("-14");

        tap("START ALBUM");
        assertTrue(hasText("save a take to set its sound"));
        scroll(false);

        tap("MASTER IT");
        SystemClock.sleep(3000);
        scroll(true);
        assertNotNull("Stop is on offer while mastering", find("STOP MASTERING"));
        shot("session_rendering");
        // The render must survive leaving the app and the screen being closed.
        ins.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
        SystemClock.sleep(3000);
        Bitmap away = ins.getUiAutomation().takeScreenshot();
        assertNotNull(away);
        ins.runOnMainSync(() -> activity.finish());
        SystemClock.sleep(6000);
        activity = ins.startActivitySync(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ins.waitForIdleSync();
        shot("back_during_render");
        waitFor("the takes after coming back", 20 * 60 * 1000, () -> find("LEVEL MATCH") != null);
        shot("compare_top");
        checkTargets();
        for (String n : new String[]{"Source", "Clean", "Warm", "Punch", "Yours"}) assertNotNull(n, find(n));
        assertNotNull("Automatic check is shown", find("DISTORTION CHECK"));
        assertNotNull("PICK A TAKE stays disabled on Source", find("PICK A TAKE"));
        assertFalse(find("PICK A TAKE").isEnabled());

        tap("Warm");
        assertNotNull(find("SAVE WARM"));
        assertTrue("Take has a distortion result", hasText("Nothing likely to be heard") || hasText("may be audible"));
        assertTrue("Level match says how this take plays", hasText("Playing exactly as saved") || hasText("Playback trim on Warm"));
        tap("TONE");
        shot("compare_tone");
        tap("LIGHTS");

        tap("Play");
        SystemClock.sleep(2500);
        String before = ((TextView) firstWithText(" / ")).getText().toString();
        shot("compare_playing");
        tap("Punch");
        SystemClock.sleep(1200);
        String after = ((TextView) firstWithText(" / ")).getText().toString();
        // The emulator's audio can run faster than real time and reach the end of the song.
        boolean ended = find("Play") != null;
        assertTrue("Playback moved on after switching version: " + before + " then " + after, ended || !after.startsWith("0:00 "));
        if (!ended) tap("Pause");

        tap("Phone");
        assertTrue(hasText("preview"));
        shot("compare_preview");
        tap("Buds2");
        assertTrue(hasText("Measured tuning of the Galaxy Buds2"));
        tap("Buds2 Pro");
        assertTrue(hasText("Measured tuning of the Galaxy Buds2 Pro"));
        shot("compare_preview_buds");
        tap("Full");
        assertFalse(hasText("preview"));

        tap("Turn level match off");
        assertTrue(hasText("real level"));
        shot("compare_match_off");
        tap("Turn level match on");

        scroll(true);
        answerPicker(other);
        tap("Import Stem Lab master");
        waitFor("imported master", 120000, () -> hasText("StemLab_Master") && find("Import Stem Lab master") != null);
        shot("compare_imported");
        scroll(false);

        scroll(true);
        tap("Start blind test");
        assertTrue(hasText("Version 1"));
        for (String n : new String[]{"Source", "Clean", "Warm", "Punch", "Yours", "StemLab_Master"}) assertFalse(n + " leaked in blind test", hasText(n));
        assertFalse("Numbers leaked in blind test", hasText("-14.0"));
        assertFalse(hasText("Playback trim"));
        assertNull("Distortion results stay hidden in a blind test", find("DISTORTION CHECK"));
        shot("blind_on");
        tap("Version 2");
        tap("Reveal results");
        assertTrue(hasText("Your pick, Version 2, was"));
        shot("blind_results");
        tap("End blind test");

        tap("Warm");
        scroll(true);
        tap("Release details");
        ins.waitForIdleSync();
        onUi(() -> { for (View v : visible()) if (v instanceof android.widget.EditText) {
            CharSequence d = v.getContentDescription();
            if ("ARTIST".contentEquals(d)) ((TextView) v).setText("Test Artist");
            if ("TITLE".contentEquals(d)) ((TextView) v).setText("Smoke Song");
            if ("ISRC".contentEquals(d)) ((TextView) v).setText("AU-ABC-26-00001");
        } return true; });
        scroll(true);
        shot("release_details");
        tap("SAVE WARM");
        waitFor("saved take", 180000, () -> find("WARM SAVED") != null);
        shot("take_saved");
        assertEquals(false, find("WARM SAVED").isEnabled());
        List<String> saved = new ArrayList<>();
        try (android.database.Cursor c = app.getContentResolver().query(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{android.provider.MediaStore.MediaColumns.DISPLAY_NAME, android.provider.MediaStore.MediaColumns.SIZE}, null, null, null)) {
            while (c != null && c.moveToNext()) if (c.getLong(1) > 0) saved.add(c.getString(0));
        }
        for (String f : new String[]{"Smoke Song_Warm_24bit.wav", "Smoke Song_Warm_16bit_44k.wav", "Smoke Song_Warm_320k.mp3", "Smoke Song_Warm_session.txt"}) {
            assertTrue("Missing " + f + " in " + saved, saved.contains(f));
        }

        tap("Session Sheet");
        ins.waitForIdleSync();
        scroll(true);
        assertTrue(hasText("As it came in"));
        shot("session_sheet");

        tap("Back");
        assertNotNull("Session screen", find("MASTER IT"));
        shot("session_back_to_masters");
        tap("BACK TO YOUR MASTERS");
        assertNotNull("Selected take kept", find("WARM SAVED"));

        tap("Back");
        answerPicker(other);
        tap("ADD RECORD");
        waitFor("reference record", 120000, () -> hasText("1 reference record"));
        assertTrue("Album remembers the saved take", hasText("1 song"));
        shot("session_reference");
        tap("MANAGE");
        shot("reference_shelf");
        tap("REMOVE");
        assertFalse(hasText("1 reference record"));
        tap("END ALBUM");

        // Remaster: stopping keeps the last takes, and the old round stays to compare.
        tap("BACK TO YOUR MASTERS");
        assertTrue("Both streaming checks ran", hasText("MP3 128") && hasText("AAC 256"));
        tap("REMASTER");
        assertNotNull(find("MASTER IT"));
        tap("Commercial");
        tap("MASTER IT");
        SystemClock.sleep(2500);
        tap("STOP MASTERING");
        waitFor("stop during a remaster", 60000, () -> hasText("Your last takes are still here"));
        tap("BACK TO YOUR MASTERS");
        assertNotNull("Stop keeps the takes", find("Clean"));
        tap("REMASTER");
        tap("MASTER IT");
        waitFor("the second round", 20 * 60 * 1000, () -> find("LEVEL MATCH") != null && hasText("Clean, Country -14"));
        shot("compare_remastered");
        checkTargets();

        // Closing the app must not lose unsaved masters.
        ins.runOnMainSync(() -> ((com.dennis.masterroom.ui.MainActivity) activity).forgetAndReload());
        waitFor("masters after reopening", 30000, () -> find("LEVEL MATCH") != null && hasText("Clean, Country -14"));
        assertNotNull("Results come back too", find("DISTORTION CHECK"));
        shot("compare_after_reopen");
        tap("Clean");
        assertNotNull(find("SAVE CLEAN"));

        // Stems: Stem Studio's ZIP comes in, the balance moves, and the takes come out.
        File zip = new File(dir, "Stems.zip");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new FileOutputStream(zip))) {
            String[] names = {"Vocals", "Drums", "Bass", "Other", "Guitar", "Piano"};
            for (int i = 0; i < 6; i++) {
                File part = new File(dir, "part.wav");
                Wav.write(part, song(24, 10 + i, 0.12), 24);
                z.putNextEntry(new java.util.zip.ZipEntry("Test_Stems_" + names[i] + "_24bit.wav"));
                java.nio.file.Files.copy(part.toPath(), z);
                z.closeEntry();
                part.delete();
            }
        }
        tap("Back");
        scroll(false);
        answerPicker(zip);
        tap("LOAD STEMS");
        waitFor("stems to load and balance", 120000, () -> hasText("6 stems") && hasText("Auto balance:"));
        assertTrue("Song name comes from the stems", hasText("Test_Stems"));
        assertNull("The balance is the app's job until asked", find("M"));
        shot("stems_auto");
        tap("ADJUST MYSELF");
        assertNotNull("All six stems have rows", find("Piano"));
        shot("stems_loaded");
        checkTargets();
        tap("+");
        tap("+");
        assertTrue("Level moves in half dB", hasText("+1.0"));
        tap("PLAY SONG");
        waitFor("stems playing", 5000, () -> find("STOP STEMS") != null);
        SystemClock.sleep(1500);
        tap("S");
        SystemClock.sleep(800);
        shot("stems_playing");
        tap("S");
        // The emulator has no real audio clock, so the stems may already have reached the end.
        if (find("STOP STEMS") != null) tap("STOP STEMS");
        assertNotNull(find("PLAY SONG"));
        tap("PLAY SONG");
        waitFor("stems to run out and rewind", 60000, () -> find("PLAY SONG") != null && hasText("0:00 / 0:24"));
        tap("BACK TO AUTO");
        assertTrue("Auto takes the balance back", hasText("Auto balance:"));
        tap("MASTER IT");
        waitFor("masters from stems", 20 * 60 * 1000, () -> find("LEVEL MATCH") != null);
        shot("compare_from_stems");
        // Left open from earlier in this run, so only open it if it is shut.
        if (!hasText("As it came in")) tap("Session Sheet");
        ins.waitForIdleSync();
        scroll(true);
        assertTrue("The sheet says what happened to the stems", hasText("Vocals stem:") && hasText("Guitar stem:") && hasText("Auto balance:"));
        shot("stems_sheet");
        activity.finish();
    }

    private View firstWithText(String part) {
        for (View v : visible()) if (v instanceof TextView && ((TextView) v).getText().toString().contains(part)) return v;
        throw new AssertionError("No text " + part);
    }
}
