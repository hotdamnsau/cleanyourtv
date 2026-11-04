package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;

/** Membuka SettingsPanel layar penuh. Dipakai oleh Beranda lama (tombol Atur). */
public class ShaffSettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new SettingsPanel(this, getIntent().getStringExtra("page")));
    }
}
