package com.dennis.masterroom.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Purpose-drawn rack icons. Decoration only; stage state is shown by the real processing LED. */
final class SignalIconView extends View {
    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();

    SignalIconView(Context context, int stage) {
        super(context);
        kind = stage;
        String[] labels = {"Equalizer", "Dynamics", "Compressor", "Tone colour",
                "Peak clipper", "True peak limiter"};
        setContentDescription(labels[Math.max(0, Math.min(stage, labels.length - 1))]);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float s = Math.min(getWidth(), getHeight()) / 28f;
        canvas.save();
        canvas.translate((getWidth() - 28f * s) / 2, (getHeight() - 28f * s) / 2);
        canvas.scale(s, s);
        paint.setColor(Look.AMBER);
        paint.setStrokeWidth(1.9f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        line.reset();
        switch (kind) {
            case 0: // Broad EQ shelf and presence bell.
                line.moveTo(2, 18);
                line.cubicTo(5, 18, 7, 18, 9, 15);
                line.cubicTo(11, 11, 12, 9, 15, 11);
                line.cubicTo(19, 14, 20, 9, 23, 9);
                line.lineTo(26, 9);
                canvas.drawPath(line, paint);
                canvas.drawLine(2, 22, 26, 22, paint);
                break;
            case 1: // Dynamics: levels with varying amplitudes.
                for (int i = 0; i < 5; i++) {
                    float h = new float[]{9, 17, 23, 13, 19}[i];
                    canvas.drawRoundRect(new RectF(3 + i * 5, 25 - h, 5 + i * 5, 25), 1, 1, paint);
                }
                break;
            case 2: // Compressor: dial and input/output needle.
                canvas.drawArc(new RectF(5, 5, 23, 23), 145, 250, false, paint);
                canvas.drawLine(14, 14, 19, 9, paint);
                canvas.drawCircle(14, 14, 1.7f, paint);
                break;
            case 3: // Tone: smooth sinusoidal stage.
                line.moveTo(2, 14);
                line.cubicTo(5, 2, 9, 2, 14, 14);
                line.cubicTo(19, 26, 23, 26, 26, 14);
                canvas.drawPath(line, paint);
                break;
            case 4: // Clipping: flat-topped waveform.
                line.moveTo(2, 20);
                line.lineTo(7, 20);
                line.lineTo(10, 7);
                line.lineTo(18, 7);
                line.lineTo(21, 20);
                line.lineTo(26, 20);
                canvas.drawPath(line, paint);
                break;
            default: // Limiter: locked ceiling, peaks below.
                canvas.drawLine(3, 7, 25, 7, paint);
                line.moveTo(3, 21);
                line.lineTo(7, 21);
                line.lineTo(11, 11);
                line.lineTo(15, 18);
                line.lineTo(19, 10);
                line.lineTo(24, 10);
                canvas.drawPath(line, paint);
        }
        canvas.restore();
    }
}
