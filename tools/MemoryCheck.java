import com.dennis.masterroom.engine.*;
import java.util.*;

/** Masters a 3:44 song on a tight memory limit. Run with: java -Xmx300m -Xmn8m -XX:+UseSerialGC MemoryCheck */
public final class MemoryCheck {
    public static void main(String[] a) {
        int rate = 48000, n = 224 * rate;
        Audio song = new Audio(n, rate);
        Random r = new Random(5);
        double lowL = 0, lowR = 0;
        for (int i = 0; i < n; i++) {
            double big = (i / (24 * rate)) % 2 == 1 ? 2.0 : 1.0, beat = (i % (rate / 2)) / (double) (rate / 2);
            double kick = 0.5 * Math.exp(-beat * 12) * Math.sin(2 * Math.PI * 60 * i / rate);
            lowL += 0.05 * (r.nextGaussian() - lowL);
            lowR += 0.05 * (r.nextGaussian() - lowR);
            song.l[i] = (float) (big * (0.25 * lowL + 0.02 * r.nextGaussian()) + kick);
            song.r[i] = (float) (big * (0.25 * lowR + 0.02 * r.nextGaussian()) + kick);
        }
        Audio[] holder = {song};
        song = null;
        Engineer.Progress quiet = (s, p) -> {};
        double lufs = Double.parseDouble(a[0]);
        Engineer.Session s = Engineer.prepare(holder, Target.builtIn(Target.Style.ROCK, true), lufs, new Engineer.Options(), quiet);
        for (Engineer.Take t : Engineer.Take.values()) {
            Engineer.Result out = Engineer.master(s, t, lufs, quiet);
            System.out.printf(Locale.US, "%s %.2f LUFS: %s%n", t.label, out.after.loudness.integrated, out.verdict);
            out.master = null;
        }
        s.release();
        System.out.println("MEMORY_CHECK_PASS");
    }
}
