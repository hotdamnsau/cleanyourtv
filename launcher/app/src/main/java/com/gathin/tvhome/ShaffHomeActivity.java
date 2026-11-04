package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Beranda Shaff: layar Home baru.
 * Kanan atas: jam, tanggal, jadwal sholat. Tengah atas: salam. Tengah bawah: satu baris aplikasi
 * (scroll melingkar) dan pita 4 pertandingan. Kanan bawah: tombol Input, Wi-Fi, Atur (hanya ikon).
 */
public class ShaffHomeActivity extends Activity {
    // ===== PENGATURAN: ubah di sini =====
    private static final String GREETING_NAMES = "Shaff dan Syaz";
    private static final float CLOCK_SP = 56f;
    /** Ukuran salam = 50% ukuran jam. */
    private static final float GREETING_SP = 28f;
    private static final int SIDE_MARGIN_DP = 32;    // margin kiri jam, tanggal, dan baris aplikasi
    private static final int RIGHT_GUTTER_DP = 92;   // ruang kanan untuk tombol bulat
    private static final int BOTTOM_MARGIN_DP = 28;
    private static final int TILE_GAP_DP = 14;
    private static final int MAX_TILE_W_DP = 260;
    private static final int BUTTON_DP = 36;         // 70% dari ukuran sebelumnya (52)
    // =====================================

    private static final class Tile {
        LinearLayout cell;
        FrameLayout thumb;
        ImageView img;
        TextView name;
    }

    private static final class Thumb {
        Drawable drawable;
        boolean banner;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Thumb> thumbCache = new HashMap<>();
    private final List<AppCatalog.App> apps = new ArrayList<>();
    private float d;
    private FrameLayout root;
    private TextView greeting, clock, date;
    private LinearLayout tileRow;
    private Tile[] tiles = new Tile[0];
    private ImageView[] buttons = new ImageView[3];
    private int start = 0;
    private int slidePx;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTexts();
            handler.postDelayed(this, 5000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        d = getResources().getDisplayMetrics().density;
        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setBackground(HomeBackdrop.create(this));
        root.addView(new WeatherOverlayView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildTop();
        buildBottom();
        buildButtons();
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildTiles();
        handler.removeCallbacks(tick);
        tick.run();
        root.post(() -> {
            if (tiles.length > 0) tiles[0].cell.requestFocus();
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        // Sebagai Home, Back tidak keluar. Bila dibuka sebagai aplikasi biasa, Back menutupnya.
        if (!getIntent().hasCategory(Intent.CATEGORY_HOME)) super.onBackPressed();
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    // ---------------- atas: jam+tanggal (kiri), salam (tengah), sholat (kanan) ----------------
    private void buildTop() {
        greeting = new TextView(this);
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, GREETING_SP);
        greeting.setTypeface(Typeface.DEFAULT_BOLD);
        greeting.setTextColor(Color.WHITE);
        greeting.setGravity(Gravity.CENTER);
        greeting.setShadowLayer(8f, 0f, 3f, 0xAA000000);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        gp.topMargin = dp(22);
        root.addView(greeting, gp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.START);
        clock = new TextView(this);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, CLOCK_SP);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setTextColor(Color.WHITE);
        clock.setShadowLayer(8f, 0f, 3f, 0xAA000000);
        clock.setIncludeFontPadding(false);
        date = new TextView(this);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        date.setLetterSpacing(0.15f);
        date.setTextColor(0xE6FFFFFF);
        date.setShadowLayer(6f, 0f, 2f, 0xAA000000);
        col.addView(clock);
        col.addView(date);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        cp.topMargin = dp(18);
        cp.leftMargin = dp(SIDE_MARGIN_DP);
        root.addView(col, cp);

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        pp.topMargin = dp(18);
        pp.rightMargin = dp(40);
        root.addView(new PrayerBar(this, true), pp);
    }

    private void updateTexts() {
        TimeZone tz = TimeZone.getTimeZone(HomePrefs.city(this).tz);
        Calendar cal = Calendar.getInstance(tz);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        String g;
        if (h >= 4 && h < 11) g = "Selamat Pagi";
        else if (h >= 11 && h < 15) g = "Selamat Siang";
        else if (h >= 15 && h < 18) g = "Selamat Sore";
        else g = "Selamat Malam";

        SimpleDateFormat t = new SimpleDateFormat("HH:mm", Locale.US);
        t.setTimeZone(tz);
        clock.setText(t.format(cal.getTime()));
        Locale id = new Locale("id", "ID");
        SimpleDateFormat df = new SimpleDateFormat("EEEE d MMMM", id);
        df.setTimeZone(tz);
        date.setText(df.format(cal.getTime()).toUpperCase(id));
        greeting.setText(g + "\n" + GREETING_NAMES); // baris 1: salam, baris 2: nama
    }

    // ---------------- bawah: aplikasi + pita pertandingan ----------------
    private void buildBottom() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        tileRow = new LinearLayout(this);
        tileRow.setOrientation(LinearLayout.HORIZONTAL);
        tileRow.setClipChildren(false);
        col.addView(tileRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(14);
        col.addView(new MatchStrip(this), sp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = dp(SIDE_MARGIN_DP);
        lp.rightMargin = dp(RIGHT_GUTTER_DP);
        lp.bottomMargin = dp(BOTTOM_MARGIN_DP);
        root.addView(col, lp);
    }

    private void rebuildTiles() {
        List<AppCatalog.App> all = AppCatalog.load(this);
        List<String> sel = HomePrefs.apps(this);
        apps.clear();
        if (sel == null) {
            apps.addAll(all);
        } else {
            for (String pkg : sel) {
                for (AppCatalog.App a : all) {
                    if (a.pkg.equals(pkg)) {
                        apps.add(a);
                        break;
                    }
                }
            }
        }
        tileRow.removeAllViews();
        int n = apps.size();
        if (n == 0) {
            tiles = new Tile[0];
            TextView hint = new TextView(this);
            hint.setText("Belum ada aplikasi. Buka tombol Atur > Aplikasi.");
            hint.setTextColor(Color.WHITE);
            hint.setTextSize(16);
            hint.setShadowLayer(6f, 0f, 2f, 0xAA000000);
            tileRow.addView(hint);
            return;
        }
        int k = Math.min(HomePrefs.visibleApps(this), n);
        float rowW = getResources().getDisplayMetrics().widthPixels - dp(SIDE_MARGIN_DP + RIGHT_GUTTER_DP);
        int gap = dp(TILE_GAP_DP);
        int tileW = Math.min((int) ((rowW - gap * (k - 1)) / k), dp(MAX_TILE_W_DP));
        int tileH = tileW * 9 / 16;
        slidePx = tileW + gap;
        if (start >= n) start = 0;
        tiles = new Tile[k];
        for (int i = 0; i < k; i++) {
            tiles[i] = makeTile(i, tileW, tileH);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i < k - 1) lp.rightMargin = gap;
            tileRow.addView(tiles[i].cell, lp);
        }
        bindTiles();
    }

    /** Fokus tetap di aplikasi paling kiri; daftar bergeser melingkar. dir = +1 (kanan) atau -1 (kiri). */
    private void shift(int dir) {
        int n = apps.size();
        if (n < 2) return;
        start = (start + dir + n) % n;
        bindTiles();
        tileRow.animate().cancel();
        tileRow.setTranslationX(dir * slidePx);
        tileRow.animate().translationX(0f).setDuration(140).start();
    }

    private Tile makeTile(final int index, int w, int h) {
        final Tile t = new Tile();
        t.cell = new LinearLayout(this);
        t.cell.setOrientation(LinearLayout.VERTICAL);
        t.cell.setGravity(Gravity.CENTER_HORIZONTAL);
        t.cell.setClipChildren(false);
        t.cell.setFocusable(index == 0); // fokus selalu di aplikasi paling kiri
        t.cell.setClickable(true);

        t.thumb = new FrameLayout(this);
        t.thumb.setClipToOutline(true);
        final float radius = 12 * d;
        t.thumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        t.img = new ImageView(this);
        t.thumb.addView(t.img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        t.cell.addView(t.thumb, new LinearLayout.LayoutParams(w, h));

        t.name = new TextView(this);
        t.name.setTextColor(Color.WHITE);
        t.name.setTextSize(14);
        t.name.setTypeface(Typeface.DEFAULT_BOLD);
        t.name.setSingleLine(true);
        t.name.setEllipsize(TextUtils.TruncateAt.END);
        t.name.setGravity(Gravity.CENTER);
        t.name.setShadowLayer(6f, 0f, 2f, 0xCC000000);
        t.name.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(10);
        t.cell.addView(t.name, np);

        final GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(radius);
        border.setStroke(dp(3), Color.WHITE);

        t.cell.setOnFocusChangeListener((v, has) -> {
            t.thumb.setForeground(has ? border : null);
            t.name.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            t.cell.setTranslationZ(has ? dp(12) : 0f);
            t.cell.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(120).start();
        });
        t.cell.setOnClickListener(v -> {
            AppCatalog.App a = apps.get((start + index) % apps.size());
            try {
                startActivity(a.launch);
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, "Tidak bisa membuka " + a.label, Toast.LENGTH_SHORT).show();
            }
        });
        t.cell.setOnKeyListener((v, keyCode, event) -> {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) shift(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) shift(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) buttons[0].requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    return true;
                default:
                    return false;
            }
        });
        return t;
    }

    private void bindTiles() {
        int n = apps.size();
        PackageManagerHolder pm = new PackageManagerHolder(this);
        for (int i = 0; i < tiles.length; i++) {
            AppCatalog.App a = apps.get((start + i) % n);
            tiles[i].name.setText(a.label);
            Thumb th = thumbCache.get(a.pkg);
            if (th == null) {
                th = new Thumb();
                Drawable b = a.banner(pm.pm);
                th.banner = b != null;
                th.drawable = b != null ? b : a.icon(pm.pm);
                thumbCache.put(a.pkg, th);
            }
            Drawable dr = th.drawable;
            if (dr != null && dr.getConstantState() != null) dr = dr.getConstantState().newDrawable(getResources());
            tiles[i].img.setImageDrawable(dr);
            if (th.banner) {
                tiles[i].img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                tiles[i].img.setPadding(0, 0, 0, 0);
                tiles[i].thumb.setBackgroundColor(Color.TRANSPARENT);
            } else {
                tiles[i].img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                tiles[i].img.setPadding(dp(22), dp(14), dp(22), dp(14));
                tiles[i].thumb.setBackgroundColor(0xE61C2229);
            }
        }
    }

    /** Pembungkus kecil supaya jelas dari mana PackageManager diambil. */
    private static final class PackageManagerHolder {
        final android.content.pm.PackageManager pm;

        PackageManagerHolder(Context c) {
            pm = c.getPackageManager();
        }
    }

    // ---------------- kanan bawah: tombol bulat ----------------
    private void buildButtons() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        buttons[0] = roundButton(R.drawable.shaff_ic_input, "Input");
        buttons[1] = roundButton(R.drawable.shaff_ic_wifi, "Wi-Fi");
        buttons[2] = roundButton(R.drawable.shaff_ic_tune, "Atur");
        for (int i = 0; i < 3; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP));
            if (i > 0) lp.topMargin = dp(8);
            col.addView(buttons[i], lp);
            final int index = i;
            buttons[i].setOnKeyListener((v, keyCode, event) -> {
                boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (down && index > 0) buttons[index - 1].requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (down && index < 2) buttons[index + 1].requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (down && tiles.length > 0) tiles[0].cell.requestFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return true;
                    default:
                        return false;
                }
            });
        }
        buttons[0].setOnClickListener(v -> showInputs());
        buttons[1].setOnClickListener(v -> openWifi());
        buttons[2].setOnClickListener(v -> showAturMenu());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = dp(40);
        lp.bottomMargin = dp(BOTTOM_MARGIN_DP);
        root.addView(col, lp);
    }

    private ImageView roundButton(int iconRes, String description) {
        ImageView v = new ImageView(this);
        v.setImageResource(iconRes);
        v.setContentDescription(description);
        int p = dp(9);
        v.setPadding(p, p, p, p);
        v.setFocusable(true);
        v.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, oval(0xFFFFFFFF, 0));
        bg.addState(new int[]{android.R.attr.state_pressed}, oval(0xFFFFFFFF, 0));
        bg.addState(new int[]{}, oval(0x40FFFFFF, 0x80FFFFFF));
        v.setBackground(bg);
        v.setImageTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[]{0xFF101418, 0xFF101418, Color.WHITE}));
        return v;
    }

    private GradientDrawable oval(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (stroke != 0) g.setStroke(Math.max(2, dp(1.5f)), stroke);
        return g;
    }

    // ---------------- aksi tombol ----------------
    private void openWifi() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            openSystemSettings();
        }
    }

    private void openSystemSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Setelan tidak ditemukan di TV ini", Toast.LENGTH_SHORT).show();
        }
    }

    private void showInputs() {
        final List<TvInputInfo> inputs = new ArrayList<>();
        try {
            TvInputManager tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tm != null) {
                for (TvInputInfo info : tm.getTvInputList()) if (info.isPassthroughInput()) inputs.add(info);
            }
        } catch (RuntimeException ignored) {
            // TV tidak mengizinkan membaca daftar input
        }
        if (inputs.isEmpty()) {
            Toast.makeText(this, "Input tidak ditemukan. Pakai Beranda klasik (tombol Atur).", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[inputs.size()];
        Runnable[] actions = new Runnable[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            final TvInputInfo info = inputs.get(i);
            CharSequence label = info.loadCustomLabel(this);
            if (label == null || label.length() == 0) label = info.loadLabel(this);
            names[i] = label == null ? info.getId() : label.toString();
            actions[i] = () -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException e) {
                    Toast.makeText(this, "Input tidak bisa dibuka", Toast.LENGTH_SHORT).show();
                }
            };
        }
        showMenu(names, actions);
    }

    private void showAturMenu() {
        showMenu(new String[]{"Lokasi (sholat dan cuaca)", "Liga sepak bola", "Aplikasi dan jumlah tampil",
                        "Setelan sistem", "Beranda klasik"},
                new Runnable[]{
                        () -> startActivity(new Intent(this, ShaffSettingsActivity.class).putExtra("page", "location")),
                        () -> startActivity(new Intent(this, ShaffSettingsActivity.class).putExtra("page", "leagues")),
                        () -> startActivity(new Intent(this, ShaffSettingsActivity.class).putExtra("page", "apps")),
                        this::openSystemSettings,
                        () -> startActivity(new Intent(this, MainActivity.class))
                });
    }

    /** Daftar pilihan (dropdown) di sebelah kiri tombol bulat. */
    private void showMenu(String[] names, final Runnable[] actions) {
        final PopupWindow[] holder = new PopupWindow[1];
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(0xF2181C20);
        panel.setCornerRadius(16 * d);
        panel.setStroke(Math.max(2, dp(1.5f)), 0x66FFFFFF);
        list.setBackground(panel);
        final TextView[] first = new TextView[1];
        for (int i = 0; i < names.length; i++) {
            final int which = i;
            TextView item = ShaffMenu.item(this, d, names[i]);
            item.setOnClickListener(x -> {
                holder[0].dismiss();
                actions[which].run();
            });
            list.addView(item);
            if (first[0] == null) first[0] = item;
        }
        PopupWindow popup = new PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        holder[0] = popup;
        popup.showAtLocation(root, Gravity.BOTTOM | Gravity.END, dp(40 + BUTTON_DP + 14), dp(BOTTOM_MARGIN_DP));
        list.post(() -> {
            if (first[0] != null) first[0].requestFocus();
        });
    }
}
