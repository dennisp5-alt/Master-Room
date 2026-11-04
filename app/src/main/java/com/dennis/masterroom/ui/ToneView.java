package com.dennis.masterroom.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.dennis.masterroom.engine.Listen;

/** What the master did to the tone: the source and the chosen take against the target. */
final class ToneView extends View {
    private static final int FIRST = 3, LAST = 28;
    private static final double TOP = 4, BOTTOM = -10;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private double[] source, take;
    private String takeName = "";
    private boolean hidden;

    ToneView(Context c) {
        super(c);
        setContentDescription("Tone of the source and selected version against the target");
    }

    /** Each song is lined up with the target on its own 150 Hz to 3 kHz level. */
    static double[] againstTarget(double[] song, double[] target) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < 31; i++) {
            if (Listen.BANDS[i] >= 150 && Listen.BANDS[i] <= 3000) { sum += song[i] - target[i]; n++; }
        }
        double offset = sum / n;
        double[] out = new double[31];
        for (int i = 0; i < 31; i++) out[i] = song[i] - target[i] - offset;
        return out;
    }

    void show(double[] source, double[] take, String takeName, boolean hidden) {
        this.source = source; this.take = take; this.takeName = takeName; this.hidden = hidden;
        invalidate();
    }

    private float x(int band) { return Look.dp(8) + (band - FIRST) * (getWidth() - Look.dp(16f)) / (float) (LAST - FIRST); }

    private float y(double db) {
        float top = Look.dp(26), plot = getHeight() - Look.dp(48);
        double v = Math.max(BOTTOM, Math.min(TOP, db));
        return top + (float) ((TOP - v) / (TOP - BOTTOM)) * plot;
    }

    private void curve(Canvas c, double[] data, int color, float widthDp) {
        path.reset();
        for (int b = FIRST; b <= LAST; b++) {
            if (b == FIRST) path.moveTo(x(b), y(data[b])); else path.lineTo(x(b), y(data[b]));
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Look.dp(widthDp));
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        c.drawPath(path, paint);
    }

    private float legend(Canvas c, float x, int color, String name) {
        paint.setColor(color);
        c.drawRect(x, Look.dp(10), x + Look.dp(14), Look.dp(13), paint);
        paint.setColor(Look.MID);
        c.drawText(name, x + Look.dp(19), Look.dp(16), paint);
        return x + Look.dp(19) + paint.measureText(name) + Look.dp(14);
    }

    @Override protected void onDraw(Canvas c) {
        paint.setPathEffect(null);
        paint.setTypeface(Look.mono);
        paint.setTextSize(Look.dp(12));
        paint.setTextAlign(Paint.Align.CENTER);
        if (hidden || source == null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Look.DIM);
            paint.setTypeface(Look.body);
            paint.setTextSize(Look.dp(14));
            c.drawText(hidden ? "Hidden during the blind test" : "Master a song to see its tone", getWidth() / 2f, getHeight() / 2f, paint);
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Look.dp(1.5f));
        paint.setColor(Look.DIM);
        paint.setPathEffect(new DashPathEffect(new float[]{Look.dp(5), Look.dp(6)}, 0));
        c.drawLine(0, y(0), getWidth(), y(0), paint);
        paint.setPathEffect(null);
        curve(c, source, Look.BLUE, 2f);
        if (take != null) curve(c, take, Look.AMBER, 2.5f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Look.DIM);
        int[] marks = {4, 10, 17, 24};
        String[] names = {"50", "200", "1k", "5k"};
        for (int i = 0; i < marks.length; i++) c.drawText(names[i], x(marks[i]), getHeight() - Look.dp(4), paint);
        paint.setTypeface(Look.body);
        paint.setTextSize(Look.dp(13));
        paint.setTextAlign(Paint.Align.LEFT);
        float at = legend(c, Look.dp(4), Look.BLUE, "Source");
        if (take != null) at = legend(c, at, Look.AMBER, takeName);
        legend(c, at, Look.DIM, "Target");
    }
}
