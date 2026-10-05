/*
 * Copyright 2018 Gaurav Kumar
 * Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Adapted from gauravk95/audio-visualizer-android BarVisualizer:
 * source/destination bar interpolation and batched waveform sampling.
 * Changes: centered rounded bars, unsigned PCM, safe sample bounds, lifecycle
 * cleanup, optional visualization, and AndroidX-free integration.
 */
package ai.opennomi.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.audiofx.Visualizer;
import android.view.View;

/** Uses the playing MediaPlayer session; never opens a microphone. */
public final class FishWaveView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] source = new float[36], target = new float[36];
    private volatile byte[] samples;
    private Visualizer visualizer;
    private int session = -1, desiredSession;
    private int batch;
    private boolean reduced;
    public FishWaveView(Context context) { super(context); paint.setStrokeCap(Paint.Cap.ROUND); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    public void configure(int id, boolean reduceMotion) {
        reduced = reduceMotion; desiredSession = id;
        if (isAttachedToWindow() && session != id) attachSession(id);
        invalidate();
    }
    private void attachSession(int id) {
        release(); session = id;
        if (id <= 0) return;
        try {
            visualizer = new Visualizer(id);
            visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[0]);
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] data, int rate) { samples = data.clone(); postInvalidate(); }
                @Override public void onFftDataCapture(Visualizer v, byte[] data, int rate) {}
            }, Visualizer.getMaxCaptureRate() / 4, true, false);
            visualizer.setEnabled(true);
        } catch (RuntimeException e) { release(); session = id; }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        byte[] data = samples;
        if (batch == 0) {
            for (int i = 0; i < target.length; i++) {
                source[i] = target[i];
                int x = data == null || data.length == 0 ? -1 : Math.min(data.length - 1, i * data.length / target.length);
                target[i] = x < 0 ? 0f : Math.abs((data[x] & 255) - 128) / 128f;
            }
        }
        batch++;
        float progress = reduced ? 1f : batch / 5f;
        float step = getWidth() / (float) target.length;
        paint.setStrokeWidth(Math.max(2f, step * .35f));
        for (int i = 0; i < target.length; i++) {
            float value = source[i] + progress * (target[i] - source[i]);
            float half = Math.max(2f, value * getHeight() * .46f);
            paint.setColor(Color.rgb(126 + i * 2, 215 - i, 247));
            float x = (i + .5f) * step;
            canvas.drawLine(x, getHeight() / 2f - half, x, getHeight() / 2f + half, paint);
        }
        if (batch >= 5) batch = 0;
        if (data != null && !reduced) postInvalidateDelayed(32);
    }
    private void release() {
        Visualizer old = visualizer; visualizer = null; samples = null; batch = 0;
        if (old != null) { try { old.setEnabled(false); old.release(); } catch (RuntimeException ignored) {} }
        java.util.Arrays.fill(source, 0f); java.util.Arrays.fill(target, 0f);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attachSession(desiredSession); }
    @Override protected void onDetachedFromWindow() { release(); session = -1; super.onDetachedFromWindow(); }
}
