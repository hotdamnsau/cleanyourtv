package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
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
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

/**
 * Pita statis berisi 4 pertandingan terdekat, seperti running text di TV tetapi tidak bergerak.
 * Liga dipilih lewat Atur. Hanya tampil bila API key terpasang dan minimal satu liga dipilih.
 */
public final class MatchStrip extends FrameLayout {
    /** Diisi otomatis oleh GitHub Actions dari secret FOOTBALL_API_KEY. Jangan tulis key di sini. */
    private static final String FOOTBALL_KEY = "__FOOTBALL_KEY__";
    private static final int MAX_MATCHES = 4;
    private static final int DAYS_AHEAD = 7;
    private static final long REFRESH_MS = 3 * 60 * 60 * 1000L;
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;

    private static final class Match {
        long start;
        String code, home, away, status;
    }

    private static volatile List<Match> cache = new ArrayList<>();
    private static volatile boolean loaded;
    private static volatile long nextFetchAt;
    private static volatile String lastSelectionKey = "";

    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final boolean hasKey;
    private final LinearLayout box;
    private final LinearLayout[] cells = new LinearLayout[MAX_MATCHES];
    private final TextView[] titles = new TextView[MAX_MATCHES];
    private final TextView[] subs = new TextView[MAX_MATCHES];
    private boolean active, fetching;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            refresh();
            maybeFetch();
            handler.postDelayed(this, 30_000L);
        }
    };

    public MatchStrip(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        hasKey = !FOOTBALL_KEY.isEmpty() && !FOOTBALL_KEY.startsWith("__");
        setFocusable(false);
        setClickable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x59000000);
        bg.setCornerRadius(14 * density);
        bg.setStroke(Math.max(2, dp(1.5f)), 0x99FFFFFF);
        box.setBackground(bg);
        box.setVisibility(GONE);
        for (int i = 0; i < MAX_MATCHES; i++) {
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(8), 0, dp(8), 0);
            titles[i] = text(12f, Color.WHITE, true);
            titles[i].setSingleLine(true);
            titles[i].setEllipsize(TextUtils.TruncateAt.END);
            subs[i] = text(10f, 0xB3FFFFFF, false);
            subs[i].setSingleLine(true);
            subs[i].setEllipsize(TextUtils.TruncateAt.END);
            cell.addView(titles[i]);
            cell.addView(subs[i]);
            box.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            cells[i] = cell;
        }
        addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(4f, 0f, 2f, 0xAA000000);
        t.setFocusable(false);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    // ---- aktif hanya saat layar terlihat ----
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

    /** Perbarui tampilan dan ambil jadwal bila liga berubah. */
    void reload() {
        refresh();
        maybeFetch();
    }

    private void refresh() {
        Set<String> sel = HomePrefs.leagues(getContext());
        long now = System.currentTimeMillis();
        if (!hasKey || sel.isEmpty() || now < TIME_VALID_AFTER_MS) {
            box.setVisibility(GONE);
            return;
        }
        box.setVisibility(VISIBLE);

        TimeZone tz = TimeZone.getTimeZone(HomePrefs.city(getContext()).tz);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        hm.setTimeZone(tz);
        SimpleDateFormat dayKey = new SimpleDateFormat("yyyyMMdd", Locale.US);
        dayKey.setTimeZone(tz);
        SimpleDateFormat dayName = new SimpleDateFormat("EEE d MMM", new Locale("id", "ID"));
        dayName.setTimeZone(tz);
        String today = dayKey.format(now);
        Calendar tm = Calendar.getInstance(tz);
        tm.setTimeInMillis(now);
        tm.add(Calendar.DAY_OF_MONTH, 1);
        String tomorrow = dayKey.format(tm.getTime());

        int shown = 0;
        for (Match m : cache) {
            if (shown >= MAX_MATCHES) break;
            if (!sel.contains(m.code)) continue;
            boolean live = "IN_PLAY".equals(m.status) || "PAUSED".equals(m.status);
            boolean upcoming = "TIMED".equals(m.status) || "SCHEDULED".equals(m.status);
            if (!live && !upcoming) continue;
            if (m.start < now - 3 * 3600_000L) continue;
            String dk = dayKey.format(m.start);
            String day = dk.equals(today) ? "Hari ini" : dk.equals(tomorrow) ? "Besok" : dayName.format(m.start);
            titles[shown].setText(m.home + " vs " + m.away);
            subs[shown].setText((live ? "LIVE" : hm.format(m.start) + " \u00B7 " + day)
                    + " \u00B7 " + HomePrefs.leagueShortName(m.code));
            subs[shown].setVisibility(VISIBLE);
            cells[shown].setVisibility(VISIBLE);
            shown++;
        }
        if (shown == 0) {
            titles[0].setText(loaded ? "Tidak ada jadwal " + DAYS_AHEAD + " hari ke depan" : "Memuat jadwal...");
            subs[0].setVisibility(GONE);
            cells[0].setVisibility(VISIBLE);
            shown = 1;
        }
        for (int i = shown; i < MAX_MATCHES; i++) cells[i].setVisibility(GONE);
    }

    // ---- ambil jadwal ----
    private void maybeFetch() {
        if (!hasKey || fetching) return;
        final Set<String> sel = HomePrefs.leagues(getContext());
        if (sel.isEmpty()) return;
        String key = TextUtils.join(",", new TreeSet<>(sel));
        if (!key.equals(lastSelectionKey)) { // liga diubah: ambil ulang segera
            lastSelectionKey = key;
            nextFetchAt = 0;
        }
        long now = System.currentTimeMillis();
        if (now < nextFetchAt) return;
        fetching = true;
        nextFetchAt = now + 10 * 60 * 1000L; // bila gagal, coba lagi 10 menit
        final String codes = key;
        new Thread(() -> {
            List<Match> result = null;
            HttpURLConnection c = null;
            try {
                long n = System.currentTimeMillis();
                SimpleDateFormat d = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                d.setTimeZone(TimeZone.getTimeZone("UTC"));
                URL u = new URL("https://api.football-data.org/v4/matches?competitions=" + codes
                        + "&dateFrom=" + d.format(n - 3 * 3600_000L)
                        + "&dateTo=" + d.format(n + DAYS_AHEAD * 24 * 3600_000L));
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
                // tanpa internet atau key salah: tetap tampil data terakhir
            } finally {
                if (c != null) c.disconnect();
            }
            final List<Match> parsed = result;
            handler.post(() -> {
                fetching = false;
                if (parsed != null) {
                    cache = parsed;
                    loaded = true;
                    nextFetchAt = System.currentTimeMillis() + REFRESH_MS;
                    refresh();
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
            m.code = o.getJSONObject("competition").optString("code", "");
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
}
