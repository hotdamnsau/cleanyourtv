package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

/** Wallpaper + lapisan gelap tipis supaya tulisan putih terbaca. */
final class HomeBackdrop {
    /** 1.0 = standar. Naikkan (misal 1.3) bila masih kurang gelap, turunkan (0.7) bila terlalu gelap. */
    private static final float STRENGTH = 1.0f;

    private HomeBackdrop() {}

    static Drawable create(Context c) {
        Drawable wall = c.getDrawable(R.drawable.shaff_wallpaper);
        GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{a(0.62f), a(0.42f), a(0.52f)});
        return new LayerDrawable(new Drawable[]{wall, scrim});
    }

    private static int a(float alpha) {
        int v = Math.round(Math.min(1f, alpha * STRENGTH) * 255f);
        return v << 24; // hitam dengan alpha v
    }
}
