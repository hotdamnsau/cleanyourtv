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
import android.view.View;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

/**
 * Tiga pertandingan sepak bola terdekat dari liga yang dipilih (football-data.org).
 * Posisinya di bawah tombol-tombol kanan atas. Tidak bisa difokus.
 * Hanya tampil bila API key terpasang dan minimal satu liga dipilih.
 */
public final class MatchPanel extends FrameLayout {
    /** Diisi otomatis oleh GitHub Actions dari secret FOOTBALL_API_KEY. Jangan tulis key di sini. */
    private static final String FOOTBALL_KEY = "__FOOTBALL_KEY__";
    private static final int MAX_MATCHES = 3;
    private static final int DAYS_AHEAD = 7;
    private static final long REFRESH_MS = 3 * 60 * 60 * 1000L;
    private static final int GAP_BELOW_ANCHOR_DP = 12;
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;

    private static final class Match {
        long start;
        String code, home, away, status;
    }

    private static volatile List<Match> cache = new ArrayList<>();
    private static volatile Set<String> cachedCodes = new HashSet<>();
    private static volatile boolean loaded;
    private static volatile long nextFetchAt;
    private static volatile String lastSelectionKey = "";

    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final boolean hasKey;
    private final LinearLayout box;
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

    /** Pasang ke layar Home, di bawah {@code anchor} (baris tombol Input/Wi-Fi/Setelan), rata kanan. */
    public static MatchPanel attach(final FrameLayout root, final View anchor) {
        final MatchPanel panel = new MatchPanel(root.getContext());
        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
        View.OnLayoutChangeListener l = (v, a, b, c, d, e, f, g, h) -> panel.post(() -> panel.place(root, anchor));
        anchor.addOnLayoutChangeListener(l);
        root.addOnLayoutChangeListener(l);
        return panel;
    }

    private MatchPanel(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        hasKey = !FOOTBALL_KEY.isEmpty() && !FOOTBALL_KEY.startsWith("__");
        setFocusable(false);
        setClickable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.END);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x59000000);
        bg.setCornerRadius(16 * density);
        bg.setStroke(Math.max(2, dp(1.5f)), 0x99FFFFFF);
        box.setBackground(bg);
        box.setVisibility(GONE);

        TextView heading = text(10f, 0x99FFFFFF, true);
        heading.setText("PERTANDINGAN TERDEKAT");
        heading.setLetterSpacing(0.1f);
        box.addView(heading);
        for (int i = 0; i < MAX_MATCHES; i++) {
            titles[i] = text(13f, Color.WHITE, true);
            titles[i].setSingleLine(true);
            titles[i].setEllipsize(TextUtils.TruncateAt.END);
            titles[i].setMaxWidth(dp(360));
            subs[i] = text(10f, 0xB3FFFFFF, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(i == 0 ? 6 : 5);
            box.addView(titles[i], lp);
            box.addView(subs[i]);
        }
        addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.END);
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
        int right = root.getWidth() - (a[0] - r[0] + anchor.getWidth());
        int top = a[1] - r[1] + anchor.getHeight() + dp(GAP_BELOW_ANCHOR_DP);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (lp.rightMargin != right || lp.topMargin != top) {
            lp.rightMargin = Math.max(0, right);
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

    // ---- tampilan ----
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
            titles[shown].setText((live ? "LIVE" : hm.format(m.start)) + "   " + m.home + " vs " + m.away);
            subs[shown].setText(HomePrefs.leagueShortName(m.code) + "  ·  " + (live ? "sedang berlangsung" : day));
            titles[shown].setVisibility(VISIBLE);
            subs[shown].setVisibility(VISIBLE);
            shown++;
        }
        if (shown == 0) {
            titles[0].setText(loaded ? "Tidak ada jadwal " + DAYS_AHEAD + " hari ke depan" : "Memuat jadwal...");
            subs[0].setText("");
            titles[0].setVisibility(VISIBLE);
            subs[0].setVisibility(GONE);
            shown = 1;
        }
        for (int i = shown; i < MAX_MATCHES; i++) {
            titles[i].setVisibility(GONE);
            subs[i].setVisibility(GONE);
        }
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
                // tanpa internet atau key salah: panel tetap menampilkan data terakhir
            } finally {
                if (c != null) c.disconnect();
            }
            final List<Match> parsed = result;
            handler.post(() -> {
                fetching = false;
                if (parsed != null) {
                    cache = parsed;
                    cachedCodes = new HashSet<>(sel);
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
