package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

/**
 * Lapisan transparan di atas wallpaper: hujan bila cuaca hujan, kilat bila badai, kosong bila cerah.
 * Data cuaca dari Open-Meteo (gratis, tanpa API key), diambil tiap 20 menit hanya saat Home tampil.
 * Tidak bisa difokus atau diklik.
 */
public final class WeatherOverlayView extends View {
    // ===== PENGATURAN: ubah di sini (lokasi diatur lewat Pengaturan Beranda) =====
    /** -1 = pakai cuaca asli. Isi 63 untuk mencoba hujan, 95 untuk badai, 0 untuk cerah. */
    private static final int TEST_CODE = -1;
    private static final long REFRESH_MS = 20 * 60 * 1000L;
    // =====================================

    private static final int NONE = 0, LIGHT = 1, RAIN = 2, HEAVY = 3, STORM = 4;
    private static final int MAX_DROPS = 150;
    private static final float SLANT = 0.18f;
    private static volatile int lastCode = -1;

    private final float density;
    private final Random rnd = new Random();
    private final Paint dropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] x = new float[MAX_DROPS];
    private final float[] y = new float[MAX_DROPS];
    private final float[] speed = new float[MAX_DROPS]; // dp per detik
    private final float[] len = new float[MAX_DROPS];   // dp
    private final float[] lines = new float[MAX_DROPS * 4];
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int mode = NONE;
    private int drops = 0;
    private boolean active, fetching, seedPending;
    private long lastFrame, nextFlashAt;
    private float flash;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            fetch();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    public WeatherOverlayView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        dropPaint.setColor(0x99C8DCFF);
        dropPaint.setStrokeWidth(1.5f * density);
        dropPaint.setStrokeCap(Paint.Cap.ROUND);
        if (lastCode >= 0) setCode(lastCode);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setActive(isShown());
    }

    @Override
    protected void onDetachedFromWindow() {
        setActive(false);
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        setActive(isVisible && isAttachedToWindow());
    }

    private void setActive(boolean on) {
        if (on == active) return;
        active = on;
        handler.removeCallbacks(refresh);
        if (on) {
            lastFrame = 0;
            refresh.run();
            invalidate();
        }
    }

    private static int modeFor(int code) {
        if (code >= 95) return STORM;
        if (code == 65 || code == 66 || code == 67 || code == 82) return HEAVY;
        if (code == 63 || code == 81) return RAIN;
        if (code == 61 || code == 80 || (code >= 51 && code <= 57)) return LIGHT;
        return NONE;
    }

    private void setCode(int code) {
        int m = modeFor(code);
        int n = m == LIGHT ? 45 : m == RAIN ? 90 : m == NONE ? 0 : 140;
        boolean wasNone = mode == NONE;
        mode = m;
        drops = n;
        if (m != NONE && wasNone) seedPending = true;
        lastFrame = 0;
        nextFlashAt = SystemClock.uptimeMillis() + 3000 + rnd.nextInt(6000);
        invalidate();
    }

    private void fetch() {
        if (TEST_CODE >= 0) {
            setCode(TEST_CODE);
            return;
        }
        if (fetching) return;
        fetching = true;
        new Thread(() -> {
            int code = -1;
            HttpURLConnection c = null;
            try {
                HomePrefs.City city = HomePrefs.city(getContext());
                URL u = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + city.lat
                        + "&longitude=" + city.lng + "&current=weather_code&timezone=auto");
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                    code = new JSONObject(sb.toString()).getJSONObject("current").getInt("weather_code");
                }
            } catch (Exception ignored) {
                // tanpa internet: pakai cuaca terakhir yang diketahui
            } finally {
                if (c != null) c.disconnect();
            }
            final int result = code;
            handler.post(() -> {
                fetching = false;
                if (result >= 0) {
                    lastCode = result;
                    setCode(result);
                }
            });
        }).start();
    }

    private void respawn(int i, int w, int h, boolean anywhere) {
        x[i] = rnd.nextFloat() * (w * 1.2f) - w * 0.2f;
        y[i] = anywhere ? rnd.nextFloat() * h : -rnd.nextFloat() * h * 0.3f;
        speed[i] = 620f + rnd.nextFloat() * 300f;
        len[i] = 14f + rnd.nextFloat() * 16f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mode == NONE || !active) return;
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.033f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;

        if (seedPending) {
            for (int i = 0; i < MAX_DROPS; i++) respawn(i, w, h, true);
            seedPending = false;
        }

        canvas.drawColor(0x2A0A1424); // sedikit mendung

        int n = 0;
        for (int i = 0; i < drops; i++) {
            float dy = speed[i] * density * dt;
            y[i] += dy;
            x[i] += dy * SLANT;
            float l = len[i] * density;
            if (y[i] - l > h || x[i] > w + 40 * density) respawn(i, w, h, false);
            lines[n++] = x[i];
            lines[n++] = y[i];
            lines[n++] = x[i] - SLANT * l;
            lines[n++] = y[i] - l;
        }
        canvas.drawLines(lines, 0, n, dropPaint);

        if (mode == STORM) {
            if (now >= nextFlashAt) {
                flash = 0.55f;
                nextFlashAt = now + 5000 + rnd.nextInt(9000);
            }
            if (flash > 0f) {
                canvas.drawColor(Color.argb((int) (flash * 255), 255, 255, 255));
                flash -= dt * 2.5f;
            }
        }
        postInvalidateDelayed(33); // sekitar 30 fps
    }
}
