package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * Tombol "Atur" di baris tombol kanan atas (setelah Setelan). Membuka daftar pilihan (dropdown):
 * lokasi, liga bola, tambah/hapus aplikasi, urutan aplikasi, dan menu lainnya. Bisa dipakai dengan remote.
 */
final class ShaffMenu {
    private ShaffMenu() {}

    static void install(final Activity act, final LinearLayout topButtons, final TextView template,
                        final View.OnKeyListener pillKeys, final Runnable openLibrary,
                        final Runnable toggleReorder, final Runnable moreMenu) {
        final float d = act.getResources().getDisplayMetrics().density;

        // Tombol dengan tampilan yang sama seperti tombol Setelan.
        final TextView b = new TextView(act);
        SpannableString label = new SpannableString("Atur  \u25BC");
        label.setSpan(new RelativeSizeSpan(0.6f), 5, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setText(label);
        b.setTextColor(template.getTextColors());
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, template.getTextSize());
        b.setTypeface(template.getTypeface());
        b.setLetterSpacing(template.getLetterSpacing());
        b.setGravity(Gravity.CENTER);
        b.setPadding(template.getPaddingLeft(), template.getPaddingTop(),
                template.getPaddingRight(), template.getPaddingBottom());
        Drawable bg = template.getBackground();
        if (bg != null && bg.getConstantState() != null) {
            b.setBackground(bg.getConstantState().newDrawable(act.getResources()).mutate());
        }
        b.setFocusable(true);
        b.setClickable(true);
        ViewGroup.LayoutParams tlp = template.getLayoutParams();
        LinearLayout.LayoutParams lp = tlp instanceof ViewGroup.MarginLayoutParams
                ? new LinearLayout.LayoutParams((ViewGroup.MarginLayoutParams) tlp)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        topButtons.addView(b, lp);
        // Samakan tinggi dengan tombol lain setelah layar tergambar.
        topButtons.post(() -> {
            if (template.getHeight() > 0) b.setMinHeight(template.getHeight());
        });

        // Navigasi remote: Kanan dari Setelan masuk ke tombol Atur; Atur adalah tombol paling kanan.
        template.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) b.requestFocus();
                return true;
            }
            return pillKeys.onKey(v, keyCode, event);
        });
        b.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) template.requestFocus();
                return true;
            }
            return pillKeys.onKey(v, keyCode, event); // Atas ditahan, Bawah kembali ke dock
        });

        b.setOnClickListener(v -> {
            final PopupWindow[] holder = new PopupWindow[1];
            LinearLayout list = new LinearLayout(act);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding((int) (8 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(0xF2181C20);
            panel.setCornerRadius(16 * d);
            panel.setStroke(Math.max(2, (int) (1.5f * d)), 0x66FFFFFF);
            list.setBackground(panel);

            TextView first = null;
            String[] names = {"Lokasi (sholat dan cuaca)", "Liga sepak bola", "Tambah / hapus aplikasi",
                    "Atur urutan aplikasi", "Menu lainnya (edit dock, dll)"};
            for (int i = 0; i < names.length; i++) {
                final int which = i;
                TextView item = item(act, d, names[i]);
                item.setOnClickListener(x -> {
                    holder[0].dismiss();
                    switch (which) {
                        case 0:
                            act.startActivity(new Intent(act, ShaffSettingsActivity.class).putExtra("page", "location"));
                            break;
                        case 1:
                            act.startActivity(new Intent(act, ShaffSettingsActivity.class).putExtra("page", "leagues"));
                            break;
                        case 2:
                            openLibrary.run();
                            break;
                        case 3:
                            toggleReorder.run();
                            break;
                        default:
                            moreMenu.run();
                            break;
                    }
                });
                list.addView(item);
                if (first == null) first = item;
            }
            PopupWindow popup = new PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, true);
            popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popup.setOutsideTouchable(true);
            holder[0] = popup;
            popup.showAsDropDown(b, 0, (int) (6 * d), Gravity.END);
            final TextView toFocus = first;
            list.post(() -> toFocus.requestFocus());
        });
    }

    private static TextView item(Activity act, float d, String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(16);
        t.setMinWidth((int) (300 * d));
        t.setPadding((int) (16 * d), (int) (11 * d), (int) (16 * d), (int) (11 * d));
        t.setFocusable(true);
        t.setClickable(true);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, rect(0xFFFFFFFF, d));
        sl.addState(new int[]{android.R.attr.state_pressed}, rect(0xFFFFFFFF, d));
        sl.addState(new int[]{}, rect(0x00000000, d));
        t.setBackground(sl);
        t.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[]{0xFF101418, 0xFF101418, Color.WHITE}));
        return t;
    }

    private static GradientDrawable rect(int color, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(10 * d);
        return g;
    }
}
