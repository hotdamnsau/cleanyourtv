package com.gathin.tvhome;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pengaturan Beranda: kota (jadwal sholat + cuaca) dan liga sepak bola. Bisa dipakai dengan remote. */
public class ShaffSettingsActivity extends Activity {
    private static final Object[][] PRESETS = {
            {"Jakarta", -6.2088, 106.8456, "Asia/Jakarta"},
            {"Bandung", -6.9175, 107.6191, "Asia/Jakarta"},
            {"Surabaya", -7.2575, 112.7521, "Asia/Jakarta"},
            {"Yogyakarta", -7.7956, 110.3695, "Asia/Jakarta"},
            {"Semarang", -6.9667, 110.4167, "Asia/Jakarta"},
            {"Medan", 3.5952, 98.6722, "Asia/Jakarta"},
            {"Palembang", -2.9761, 104.7754, "Asia/Jakarta"},
            {"Makassar", -5.1477, 119.4327, "Asia/Makassar"},
            {"Denpasar", -8.6705, 115.2126, "Asia/Makassar"},
            {"Balikpapan", -1.2379, 116.8529, "Asia/Makassar"},
            {"Manado", 1.4748, 124.8421, "Asia/Makassar"},
            {"Jayapura", -2.5337, 140.7181, "Asia/Jayapura"},
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private float density;
    private TextView cityLabel;
    private EditText searchBox;
    private LinearLayout results;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101418);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(64), dp(36), dp(64), dp(48));
        scroll.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final String page = getIntent().getStringExtra("page");
        col.addView(label("leagues".equals(page) ? "Liga sepak bola" : "location".equals(page) ? "Lokasi" : "Pengaturan Beranda", 26, true, 0));

        if (page == null || "location".equals(page)) {
        col.addView(label("Lokasi (jadwal sholat dan cuaca)", 17, true, 24));
        cityLabel = label("", 15, false, 4);
        col.addView(cityLabel);
        col.addView(label("Pilih cepat:", 13, false, 12));
        for (int i = 0; i < PRESETS.length; i += 4) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < Math.min(i + 4, PRESETS.length); j++) {
                final Object[] p = PRESETS[j];
                Button b = button((String) p[0], v -> choose((String) p[0], (Double) p[1], (Double) p[2], (String) p[3]));
                b.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.rightMargin = dp(6);
                lp.topMargin = dp(6);
                row.addView(b, lp);
            }
            col.addView(row);
        }

        col.addView(label("Atau cari kota lain:", 13, false, 16));
        searchBox = new EditText(this);
        searchBox.setHint("Ketik nama kota, lalu tekan Cari");
        searchBox.setHintTextColor(0x88FFFFFF);
        searchBox.setTextColor(Color.WHITE);
        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            doSearch();
            return true;
        });
        col.addView(searchBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(button("Cari", v -> doSearch()), margin(6));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        col.addView(results);

        }
        if (page == null || "leagues".equals(page)) {
        col.addView(label("Liga sepak bola yang ditampilkan", 17, true, 28));
        col.addView(label("Ditampilkan maksimal 3 pertandingan terdekat dari liga yang aktif.", 13, false, 4));
        final Set<String> selected = HomePrefs.leagues(this);
        for (final String[] lg : HomePrefs.LEAGUES) {
            final Button b = button(toggleText(selected.contains(lg[0]), lg[1]), null);
            b.setOnClickListener(v -> {
                if (!selected.remove(lg[0])) selected.add(lg[0]);
                HomePrefs.setLeagues(this, selected);
                b.setText(toggleText(selected.contains(lg[0]), lg[1]));
            });
            col.addView(b, margin(6));
        }

        }

        setContentView(scroll);
        if (cityLabel != null) refreshCityLabel();
    }

    private static String toggleText(boolean on, String name) {
        return (on ? "●  " : "○  ") + name;
    }

    private void refreshCityLabel() {
        HomePrefs.City c = HomePrefs.city(this);
        cityLabel.setText("Sekarang: " + c.name + "  (" + c.tz + ")");
    }

    private void choose(String name, double lat, double lng, String tz) {
        HomePrefs.setCity(this, new HomePrefs.City(name, lat, lng, tz));
        refreshCityLabel();
        Toast.makeText(this, "Lokasi: " + name, Toast.LENGTH_SHORT).show();
    }

    private void doSearch() {
        final String q = searchBox.getText().toString().trim();
        if (q.isEmpty()) return;
        results.removeAllViews();
        results.addView(label("Mencari...", 13, false, 6));
        new Thread(() -> {
            final List<Object[]> found = new ArrayList<>();
            HttpURLConnection c = null;
            try {
                URL u = new URL("https://geocoding-api.open-meteo.com/v1/search?name="
                        + URLEncoder.encode(q, "UTF-8") + "&count=6&language=id&format=json");
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONArray arr = new JSONObject(sb.toString()).optJSONArray("results");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            String sub = o.optString("admin1", "");
                            String country = o.optString("country", "");
                            if (!country.isEmpty()) sub = sub.isEmpty() ? country : sub + ", " + country;
                            found.add(new Object[]{o.getString("name"), o.getDouble("latitude"),
                                    o.getDouble("longitude"), o.optString("timezone", "Asia/Jakarta"), sub});
                        }
                    }
                }
            } catch (Exception ignored) {
                // tanpa internet: hasil kosong
            } finally {
                if (c != null) c.disconnect();
            }
            ui.post(() -> showResults(found));
        }).start();
    }

    private void showResults(List<Object[]> found) {
        results.removeAllViews();
        if (found.isEmpty()) {
            results.addView(label("Tidak ditemukan. Periksa ejaan atau koneksi internet.", 13, false, 6));
            return;
        }
        for (final Object[] f : found) {
            String text = f[0] + (((String) f[4]).isEmpty() ? "" : "  -  " + f[4]);
            results.addView(button(text, v -> choose((String) f[0], (Double) f[1], (Double) f[2], (String) f[3])),
                    margin(6));
        }
    }

    // ---- bantuan tampilan ----
    private int dp(float v) {
        return Math.round(v * density);
    }

    private LinearLayout.LayoutParams margin(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private TextView label(String text, float sp, boolean bold, int topDp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(bold ? Color.WHITE : 0xCCFFFFFF);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        t.setLayoutParams(lp);
        return t;
    }

    private GradientDrawable rect(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        return g;
    }

    private Button button(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(15);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setStateListAnimator(null);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rect(0xFFFFFFFF));
        bg.addState(new int[]{android.R.attr.state_pressed}, rect(0xFFFFFFFF));
        bg.addState(new int[]{}, rect(0x26FFFFFF));
        b.setBackground(bg);
        b.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[]{0xFF101418, 0xFF101418, Color.WHITE}));
        if (l != null) b.setOnClickListener(l);
        return b;
    }
}
