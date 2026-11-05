package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Wallpaper pilihan pengguna (dari flashdisk atau penyimpanan internal).
 * Gambar dipilih lewat pemilih berkas sistem, lalu disalin ke penyimpanan aplikasi dalam bentuk
 * 16:9 maksimum 1920x1080, supaya ringan di TV dan tetap bisa dipakai setelah flashdisk dicabut.
 */
final class WallpaperStore {
    private static final String FILE_NAME = "wallpaper.jpg";
    private static final int TARGET_W = 1920, TARGET_H = 1080;

    private WallpaperStore() {}

    static File file(Context c) {
        return new File(c.getFilesDir(), FILE_NAME);
    }

    static boolean hasCustom(Context c) {
        return file(c).exists();
    }

    static void reset(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
    }

    /** Wallpaper pilihan pengguna, atau null bila belum ada / gagal dibaca. */
    static Drawable loadCustom(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        try {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            return b == null ? null : new BitmapDrawable(c.getResources(), b);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** Menyalin gambar dari {@code uri}. Dipanggil dari thread latar. Melempar IOException bila gagal. */
    static void importFrom(Context c, Uri uri) throws IOException {
        ContentResolver cr = c.getContentResolver();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Berkas tidak bisa dibuka");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Bukan berkas gambar");

        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= TARGET_W && bounds.outHeight / (sample * 2) >= TARGET_H) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Berkas tidak bisa dibuka");
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new IOException("Gambar tidak bisa dibaca");

        int degrees = 0;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in != null) {
                int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
                else if (o == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
                else if (o == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
            }
        } catch (IOException | RuntimeException ignored) {
            // tanpa data rotasi: pakai apa adanya
        }
        if (degrees != 0) {
            Matrix m = new Matrix();
            m.postRotate(degrees);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        }

        // Potong bagian tengah ke rasio 16:9 supaya tidak melar di layar TV.
        int w = bmp.getWidth(), h = bmp.getHeight();
        int cw = w, ch = h;
        if (w * 9 > h * 16) cw = h * 16 / 9;
        else ch = w * 9 / 16;
        Bitmap crop = Bitmap.createBitmap(bmp, (w - cw) / 2, (h - ch) / 2, cw, ch);
        if (cw > TARGET_W) crop = Bitmap.createScaledBitmap(crop, TARGET_W, TARGET_H, true);

        File tmp = new File(c.getFilesDir(), FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (!crop.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw new IOException("Gagal menyimpan");
        }
        File dest = file(c);
        if (dest.exists() && !dest.delete()) throw new IOException("Gagal mengganti wallpaper lama");
        if (!tmp.renameTo(dest)) throw new IOException("Gagal menyimpan wallpaper");
    }
}
