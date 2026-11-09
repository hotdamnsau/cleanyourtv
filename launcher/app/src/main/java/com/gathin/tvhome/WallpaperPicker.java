package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pemilih gambar buatan sendiri yang dirancang untuk remote TV: daftar penyimpanan (internal dan
 * flashdisk), folder, dan gambar, dengan pratinjau di kanan. Tidak bergantung pada aplikasi file TV.
 * Atas/Bawah = pindah, OK = buka folder atau pilih gambar, Back = naik satu folder atau tutup.
 */
final class WallpaperPicker extends LinearLayout {
    interface OnPick {
        void picked(File image);
    }

    private static final int KIND_ROOT = 0, KIND_UP = 1, KIND_DIR = 2, KIND_IMAGE = 3;

    private static final class Entry {
        final int kind;
        final String label;
        final File file;

        Entry(int kind, String label, File file) {
            this.kind = kind;
            this.label = label;
            this.file = file;
        }
    }

    private final Activity act;
    private final Dialog dialog;
    private final OnPick onPick;
    private final float d;
    private final TextView path, status, hint;
    private final ListView list;
    private final ImageView preview;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> roots = new ArrayList<>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private File current; // null = daftar penyimpanan
    private int previewToken;

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView t = convertView instanceof TextView ? (TextView) convertView : newRow();
            Entry e = entries.get(position);
            String prefix = e.kind == KIND_UP ? "\u2191  " : e.kind == KIND_IMAGE ? "\u25AA  " : "\u25B8  ";
            t.setText(prefix + e.label);
            return t;
        }
    };

    WallpaperPicker(Activity act, Dialog dialog, OnPick onPick) {
        super(act);
        this.act = act;
        this.dialog = dialog;
        this.onPick = onPick;
        d = act.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setBackgroundColor(0xFF101418);
        setPadding(dp(48), dp(32), dp(48), dp(32));

        LinearLayout left = new LinearLayout(act);
        left.setOrientation(VERTICAL);
        left.addView(label("Pilih wallpaper", 24, true));
        path = label("", 13, false);
        left.addView(path);
        status = label("", 13, false);
        status.setTextColor(0xFFFFB74D);
        left.addView(status);

        list = new ListView(act);
        list.setDivider(null);
        list.setItemsCanFocus(false);
        list.setFocusable(true);
        GradientDrawable sel = new GradientDrawable();
        sel.setColor(0x40FFFFFF);
        sel.setCornerRadius(10 * d);
        sel.setStroke(Math.max(2, dp(1.5f)), 0xFFFFFFFF);
        list.setSelector(sel);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> activate(entries.get(pos)));
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onSelect(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(10);
        left.addView(list, lp);
        addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f));

        LinearLayout right = new LinearLayout(act);
        right.setOrientation(VERTICAL);
        preview = new ImageView(act);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(0xFF1C2229);
        right.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));
        hint = label("Atas/Bawah: pindah\nOK: buka folder atau pilih gambar\nBack: naik satu folder, atau tutup", 13, false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(14);
        right.addView(hint, hp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f);
        rp.leftMargin = dp(24);
        addView(right, rp);

        showRoots();
    }

    /** Dipanggil saat tombol Back ditekan: naik satu folder, atau menutup bila sudah di daftar penyimpanan. */
    boolean back() {
        if (current == null) {
            dialog.dismiss();
            return true;
        }
        File parent = current.getParentFile();
        if (parent == null || isRoot(current)) showRoots();
        else show(parent);
        return true;
    }

    // ---------------- isi daftar ----------------
    private void showRoots() {
        current = null;
        path.setText("Pilih penyimpanan");
        status.setText("");
        entries.clear();
        roots.clear();
        findRoots(roots);
        entries.addAll(roots);
        if (entries.isEmpty()) status.setText("Tidak ada penyimpanan yang bisa dibaca.");
        refresh(0);
    }

    private void show(File dir) {
        current = dir;
        path.setText(dir.getAbsolutePath());
        status.setText("");
        entries.clear();
        entries.add(new Entry(KIND_UP, "Naik satu folder", null));
        File[] files = dir.listFiles();
        if (files == null) {
            status.setText("Folder tidak bisa dibaca (izin ditolak, atau flashdisk belum siap).");
        } else {
            List<File> dirs = new ArrayList<>(), images = new ArrayList<>();
            for (File f : files) {
                String n = f.getName();
                if (n.startsWith(".")) continue;
                if (f.isDirectory()) dirs.add(f);
                else if (isImage(n)) images.add(f);
            }
            Comparator<File> byName = new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            };
            Collections.sort(dirs, byName);
            Collections.sort(images, byName);
            for (File f : dirs) entries.add(new Entry(KIND_DIR, f.getName(), f));
            for (File f : images) entries.add(new Entry(KIND_IMAGE, f.getName(), f));
            if (dirs.isEmpty() && images.isEmpty()) status.setText("Tidak ada folder atau gambar (jpg, png, webp) di sini.");
        }
        refresh(entries.size() > 1 ? 1 : 0);
    }

    private void refresh(int selection) {
        adapter.notifyDataSetChanged();
        list.setSelection(selection);
        list.requestFocus();
        onSelect(selection);
    }

    private void activate(Entry e) {
        switch (e.kind) {
            case KIND_UP:
                back();
                break;
            case KIND_IMAGE:
                dialog.dismiss();
                onPick.picked(e.file);
                break;
            default:
                show(e.file);
        }
    }

    private void onSelect(int position) {
        if (position < 0 || position >= entries.size()) return;
        Entry e = entries.get(position);
        if (e.kind == KIND_IMAGE) {
            loadPreview(e.file);
        } else {
            previewToken++;
            preview.setImageDrawable(null);
        }
    }

    private void loadPreview(final File f) {
        final int token = ++previewToken;
        preview.setImageDrawable(null);
        exec.execute(() -> {
            if (token != previewToken) return; // sudah berpindah pilihan
            Bitmap b = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 640) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                b = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            } catch (Throwable ignored) {
                // pratinjau gagal: abaikan, gambar tetap bisa dipilih
            }
            final Bitmap bb = b;
            post(() -> {
                if (token == previewToken && bb != null) preview.setImageBitmap(bb);
            });
        });
    }

    // ---------------- penyimpanan ----------------
    private void findRoots(List<Entry> out) {
        Map<String, Entry> map = new LinkedHashMap<>();
        StorageManager sm = (StorageManager) act.getSystemService(Activity.STORAGE_SERVICE);

        File internal = Environment.getExternalStorageDirectory();
        if (internal != null) map.put(internal.getAbsolutePath(), new Entry(KIND_ROOT, "Penyimpanan internal", internal));

        // Volume tambahan (flashdisk, kartu SD) dari lokasi folder aplikasi di tiap volume.
        File[] dirs = act.getExternalFilesDirs(null);
        if (dirs != null) {
            for (File f : dirs) {
                if (f == null) continue;
                String p = f.getAbsolutePath();
                int i = p.indexOf("/Android/");
                if (i <= 0) continue;
                String rootPath = p.substring(0, i);
                if (!map.containsKey(rootPath)) {
                    File root = new File(rootPath);
                    map.put(rootPath, new Entry(KIND_ROOT, volumeLabel(sm, root), root));
                }
            }
        }
        // Cadangan: daftar langsung isi /storage.
        File[] st = new File("/storage").listFiles();
        if (st != null) {
            for (File f : st) {
                String n = f.getName();
                if (n.equals("emulated") || n.equals("self") || !f.isDirectory()) continue;
                if (!map.containsKey(f.getAbsolutePath())) {
                    map.put(f.getAbsolutePath(), new Entry(KIND_ROOT, volumeLabel(sm, f), f));
                }
            }
        }
        out.addAll(map.values());
    }

    private String volumeLabel(StorageManager sm, File root) {
        String fallback = "Flashdisk / SD (" + root.getName() + ")";
        try {
            StorageVolume v = sm.getStorageVolume(root);
            if (v != null) {
                String desc = v.getDescription(act);
                if (desc != null && !desc.isEmpty()) return desc + " (" + root.getName() + ")";
            }
        } catch (RuntimeException ignored) {
            // pakai label bawaan
        }
        return fallback;
    }

    private boolean isRoot(File f) {
        for (Entry r : roots) if (r.file.getAbsolutePath().equals(f.getAbsolutePath())) return true;
        return false;
    }

    private static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    @Override
    protected void onDetachedFromWindow() {
        exec.shutdownNow();
        super.onDetachedFromWindow();
    }

    // ---------------- bantuan tampilan ----------------
    private int dp(float v) {
        return Math.round(v * d);
    }

    private TextView newRow() {
        TextView t = new TextView(act);
        t.setTextColor(Color.WHITE);
        t.setTextSize(17);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(14), dp(11), dp(14), dp(11));
        t.setLayoutParams(new AbsListView_LP());
        return t;
    }

    private TextView label(String text, float sp, boolean bold) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(bold ? Color.WHITE : 0xCCFFFFFF);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Parameter baris untuk ListView (lebar penuh, tinggi menyesuaikan). */
    private static final class AbsListView_LP extends android.widget.AbsListView.LayoutParams {
        AbsListView_LP() {
            super(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
