package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Jadwal sholat berjejer (Subuh, Dzuhur, Ashar, Maghrib, Isya), waktu terdekat diberi bingkai putih.
 * Dihitung di perangkat untuk kota pilihan (HomePrefs). Tidak bisa difokus.
 * Posisinya mengikuti blok jam: tepat di bawahnya.
 */
public final class PrayerBar extends LinearLayout {
    private static final int[] ORDER = {0, 2, 3, 4, 5}; // indeks di PrayerTimes
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    private static final int GAP_BELOW_CLOCK_DP = 12;

    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinearLayout[] cells = new LinearLayout[5];
    private final TextView[] names = new TextView[5];
    private final TextView[] times = new TextView[5];
    private final TextView[] counts = new TextView[5];
    private int highlighted = -1;
    private boolean active;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            refresh();
            handler.postDelayed(this, 20_000L);
        }
    };

    /** Pasang ke layar Home, di bawah {@code anchor} (blok jam). */
    public static PrayerBar attach(final FrameLayout root, final View anchor) {
        final PrayerBar bar = new PrayerBar(root.getContext());
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        View.OnLayoutChangeListener l = (v, a, b, c, d, e, f, g, h) -> bar.post(() -> bar.place(root, anchor));
        anchor.addOnLayoutChangeListener(l);
        root.addOnLayoutChangeListener(l);
        return bar;
    }

    private PrayerBar(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setFocusable(false);
        setClickable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int i = 0; i < 5; i++) {
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setMinimumWidth(dp(80));
            cell.setPadding(dp(10), dp(6), dp(10), dp(6));
            names[i] = text(12f, false);
            times[i] = text(19f, true);
            counts[i] = text(11f, false);
            counts[i].setVisibility(GONE);
            cell.addView(names[i]);
            cell.addView(times[i]);
            cell.addView(counts[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i < 4) lp.rightMargin = dp(4);
            addView(cell, lp);
            cells[i] = cell;
        }
        refresh();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private TextView text(float sp, boolean bold) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(0xCCFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(4f, 0f, 2f, 0xAA000000);
        t.setFocusable(false);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void place(View root, View anchor) {
        if (root.getWidth() == 0 || anchor.getWidth() == 0) return;
        int[] a = new int[2], r = new int[2];
        anchor.getLocationInWindow(a);
        root.getLocationInWindow(r);
        int left = a[0] - r[0];
        int top = a[1] - r[1] + anchor.getHeight() + dp(GAP_BELOW_CLOCK_DP);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (lp.leftMargin != left || lp.topMargin != top) {
            lp.leftMargin = left;
            lp.topMargin = top;
            setLayoutParams(lp);
        }
    }

    // ---- aktif hanya saat Home terlihat ----
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
        handler.removeCallbacks(tick);
        if (on) tick.run();
    }

    // ---- isi ----
    private void refresh() {
        long now = System.currentTimeMillis();
        if (now < TIME_VALID_AFTER_MS) {
            setAlpha(0f);
            return;
        }
        setAlpha(1f);
        HomePrefs.City city = HomePrefs.city(getContext());
        TimeZone tz = TimeZone.getTimeZone(city.tz);
        Calendar cal = Calendar.getInstance(tz);
        cal.setTimeInMillis(now);
        double off = tz.getOffset(now) / 3600000.0;
        int[] t = PrayerTimes.minutesOfDay(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH), city.lat, city.lng, off);
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        int[] shown = new int[5];
        for (int k = 0; k < 5; k++) shown[k] = t[ORDER[k]];
        int hi = -1;
        for (int k = 0; k < 5; k++) {
            if (shown[k] > nowMin) {
                hi = k;
                break;
            }
        }
        int diff;
        if (hi >= 0) {
            diff = shown[hi] - nowMin;
        } else { // semua sudah lewat: Subuh besok
            Calendar c2 = (Calendar) cal.clone();
            c2.add(Calendar.DAY_OF_MONTH, 1);
            int[] t2 = PrayerTimes.minutesOfDay(c2.get(Calendar.YEAR), c2.get(Calendar.MONTH) + 1,
                    c2.get(Calendar.DAY_OF_MONTH), city.lat, city.lng, off);
            hi = 0;
            shown[0] = t2[0];
            diff = shown[0] + 1440 - nowMin;
        }

        for (int k = 0; k < 5; k++) {
            names[k].setText(PrayerTimes.NAMES[ORDER[k]]);
            times[k].setText(PrayerTimes.format(shown[k]));
        }
        counts[hi].setText(diff >= 60 ? (diff / 60) + " j " + (diff % 60) + " m" : diff + " mnt");
        if (hi != highlighted) {
            for (int k = 0; k < 5; k++) {
                boolean on = k == hi;
                cells[k].setBackground(on ? highlightBackground() : null);
                names[k].setTextColor(on ? 0xFFFFFFFF : 0xCCFFFFFF);
                times[k].setTextColor(on ? 0xFFFFFFFF : 0xCCFFFFFF);
                counts[k].setVisibility(on ? VISIBLE : GONE);
            }
            highlighted = hi;
        }
    }

    private GradientDrawable highlightBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x40FFFFFF);
        g.setCornerRadius(14 * density);
        g.setStroke(Math.max(2, dp(1.5f)), 0xFFFFFFFF);
        return g;
    }
}
