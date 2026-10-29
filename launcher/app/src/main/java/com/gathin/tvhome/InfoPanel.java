package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Panel kecil di layar Home: sholat berikutnya + hitung mundur, kelima waktu sholat hari ini,
 * dan beberapa pertandingan sepak bola 24 jam ke depan (football-data.org).
 * Jadwal sholat dihitung di perangkat. Jadwal bola hanya muncul bila API key terpasang.
 */
public final class InfoPanel extends LinearLayout {
    // ===== PENGATURAN: ubah di sini =====
    private static final double LAT = -6.9175;       // Bandung
    private static final double LNG = 107.6191;
    private static final String TZ_ID = "Asia/Jakarta"; // WIB
    /** Diisi otomatis oleh GitHub Actions dari secret FOOTBALL_API_KEY. Jangan tulis key di sini. */
    private static final String FOOTBALL_KEY = "__FOOTBALL_KEY__";
    /** PL=Liga Inggris, PD=La Liga, SA=Serie A, BL1=Bundesliga, FL1=Ligue 1, CL=Liga Champions */
    private static final String COMPETITIONS = "PL,PD,SA,BL1,FL1,CL";
    private static final int MAX_MATCHES = 4;
    private static final long FOOTBALL_REFRESH_MS = 3 * 60 * 60 * 1000L;
    /** Jarak dari tepi layar (dp). Geser bila menimpa elemen lain. */
    private static final int MARGIN_TOP_DP = 96;
    private static final int MARGIN_RIGHT_DP = 48;
    // =====================================

    /** Sama dengan launcher: sebelum jam TV benar (sebelum 2025), panel disembunyikan. */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;

    private static final class Match {
        long start;
        String comp, home, away, status;
    }

    private static volatile List<Match> cache = new ArrayList<>();
    private static volatile boolean everLoaded;
    private static volatile long nextFetchAt;

    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView next, row, matchHeading;
    private final TextView[] matchRows = new TextView[MAX_MATCHES];
    private final boolean hasKey;
    private boolean active, fetching;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            refreshTexts();
            maybeFetch();
            handler.postDelayed(this, 30_000L);
        }
    };

    public static FrameLayout.LayoutParams layoutParams(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        lp.topMargin = (int) (MARGIN_TOP_DP * d);
        lp.rightMargin = (int) (MARGIN_RIGHT_DP * d);
        return lp;
    }

    public InfoPanel(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        hasKey = !FOOTBALL_KEY.isEmpty() && !FOOTBALL_KEY.startsWith("__");
        setOrientation(VERTICAL);
        setGravity(Gravity.END);
        setFocusable(false);
        setClickable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        int px = (int) (16 * density), py = (int) (12 * density);
        setPadding(px, py, px, py);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x73000000);
        bg.setCornerRadius(16 * density);
        setBackground(bg);

        next = text(20f, Color.WHITE, true);
        row = text(13f, 0xBFFFFFFF, false);
        addView(next);
        addView(row);

        matchHeading = text(11f, 0x99FFFFFF, true);
        matchHeading.setText("PERTANDINGAN");
        matchHeading.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = (int) (10 * density);
        addView(matchHeading, hp);
        for (int i = 0; i < MAX_MATCHES; i++) {
            matchRows[i] = text(14f, Color.WHITE, false);
            matchRows[i].setSingleLine(true);
            matchRows[i].setEllipsize(TextUtils.TruncateAt.END);
            matchRows[i].setMaxWidth((int) (460 * density));
            addView(matchRows[i]);
        }
        int vis = hasKey ? VISIBLE : GONE;
        matchHeading.setVisibility(vis);
        for (TextView t : matchRows) t.setVisibility(GONE);
        refreshTexts();
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.END);
        t.setShadowLayer(4f, 0f, 2f, 0xAA000000);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setFocusable(false);
        return t;
    }

    // ---- siklus hidup: aktif hanya saat Home terlihat ----
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

    // ---- isi teks ----
    private void refreshTexts() {
        long nowMs = System.currentTimeMillis();
        if (nowMs < TIME_VALID_AFTER_MS) {
            setAlpha(0f);
            return;
        }
        setAlpha(1f);
        TimeZone tz = TimeZone.getTimeZone(TZ_ID);
        Calendar cal = Calendar.getInstance(tz);
        cal.setTimeInMillis(nowMs);
        double off = tz.getOffset(nowMs) / 3600000.0;
        int[] t = PrayerTimes.minutesOfDay(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH), LAT, LNG, off);
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        int[] order = {0, 2, 3, 4, 5};
        int idx = -1;
        for (int i : order) {
            if (t[i] > nowMin) {
                idx = i;
                break;
            }
        }
        int when, diff;
        if (idx >= 0) {
            when = t[idx];
            diff = when - nowMin;
        } else { // semua sudah lewat: Subuh besok
            Calendar c2 = (Calendar) cal.clone();
            c2.add(Calendar.DAY_OF_MONTH, 1);
            int[] t2 = PrayerTimes.minutesOfDay(c2.get(Calendar.YEAR), c2.get(Calendar.MONTH) + 1,
                    c2.get(Calendar.DAY_OF_MONTH), LAT, LNG, off);
            idx = 0;
            when = t2[0];
            diff = when + 1440 - nowMin;
        }
        String left = diff >= 60 ? (diff / 60) + " j " + (diff % 60) + " m lagi" : diff + " menit lagi";
        next.setText(PrayerTimes.NAMES[idx] + " " + PrayerTimes.format(when) + "  ·  " + left);

        StringBuilder sb = new StringBuilder();
        for (int i : order) {
            if (sb.length() > 0) sb.append("   ");
            sb.append(PrayerTimes.NAMES[i]).append(' ').append(PrayerTimes.format(t[i]));
        }
        row.setText(sb.toString());

        if (hasKey) showMatches(nowMs, tz);
    }

    private void showMatches(long nowMs, TimeZone tz) {
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        hm.setTimeZone(tz);
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
        day.setTimeZone(tz);
        String today = day.format(nowMs);

        int shown = 0;
        for (Match m : cache) {
            if (shown >= MAX_MATCHES) break;
            boolean live = "IN_PLAY".equals(m.status) || "PAUSED".equals(m.status);
            boolean upcoming = "TIMED".equals(m.status) || "SCHEDULED".equals(m.status);
            if (!live && !upcoming) continue;
            if (m.start < nowMs - 3 * 3600_000L || m.start > nowMs + 24 * 3600_000L) continue;
            String when = live ? "LIVE" : (day.format(m.start).equals(today) ? "" : "Besok ") + hm.format(m.start);
            matchRows[shown].setText(when + "   " + m.home + " vs " + m.away + "  ·  " + m.comp);
            matchRows[shown].setVisibility(VISIBLE);
            shown++;
        }
        if (shown == 0 && everLoaded) {
            matchRows[0].setText("Tidak ada pertandingan 24 jam ke depan");
            matchRows[0].setVisibility(VISIBLE);
            shown = 1;
        }
        for (int i = shown; i < MAX_MATCHES; i++) matchRows[i].setVisibility(GONE);
    }

    // ---- ambil jadwal bola ----
    private void maybeFetch() {
        if (!hasKey || fetching) return;
        long now = System.currentTimeMillis();
        if (now < nextFetchAt) return;
        fetching = true;
        nextFetchAt = now + 10 * 60 * 1000L; // bila gagal, coba lagi 10 menit
        new Thread(() -> {
            List<Match> result = null;
            HttpURLConnection c = null;
            try {
                long n = System.currentTimeMillis();
                SimpleDateFormat d = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                d.setTimeZone(TimeZone.getTimeZone("UTC"));
                URL u = new URL("https://api.football-data.org/v4/matches?competitions=" + COMPETITIONS
                        + "&dateFrom=" + d.format(n - 3 * 3600_000L) + "&dateTo=" + d.format(n + 36 * 3600_000L));
                c = (HttpURLConnection) u.openConnection();
                c.setRequestProperty("X-Auth-Token", FOOTBALL_KEY);
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                    result = parse(sb.toString());
                }
            } catch (Exception ignored) {
                // tanpa internet atau key salah: panel bola tetap kosong
            } finally {
                if (c != null) c.disconnect();
            }
            final List<Match> parsed = result;
            handler.post(() -> {
                fetching = false;
                if (parsed != null) {
                    cache = parsed;
                    everLoaded = true;
                    nextFetchAt = System.currentTimeMillis() + FOOTBALL_REFRESH_MS;
                    refreshTexts();
                }
            });
        }).start();
    }

    private static List<Match> parse(String body) throws Exception {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        JSONArray arr = new JSONObject(body).getJSONArray("matches");
        List<Match> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Match m = new Match();
            m.start = iso.parse(o.getString("utcDate")).getTime();
            m.status = o.optString("status", "");
            m.comp = compName(o.getJSONObject("competition").optString("code", ""));
            m.home = teamName(o.getJSONObject("homeTeam"));
            m.away = teamName(o.getJSONObject("awayTeam"));
            out.add(m);
        }
        Collections.sort(out, new Comparator<Match>() {
            @Override
            public int compare(Match a, Match b) {
                return Long.compare(a.start, b.start);
            }
        });
        return out;
    }

    private static String teamName(JSONObject t) {
        String s = t.optString("shortName", "");
        if (s.isEmpty() || "null".equals(s)) s = t.optString("name", "");
        return s.isEmpty() || "null".equals(s) ? "?" : s;
    }

    private static String compName(String code) {
        switch (code) {
            case "PL": return "Liga Inggris";
            case "PD": return "La Liga";
            case "SA": return "Serie A";
            case "BL1": return "Bundesliga";
            case "FL1": return "Ligue 1";
            case "CL": return "Liga Champions";
            default: return code;
        }
    }
}
