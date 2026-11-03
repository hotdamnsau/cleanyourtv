package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pilihan kota dan liga, disimpan di perangkat. Diubah lewat ShaffSettingsActivity. */
final class HomePrefs {
    private static final String FILE = "shaff_home";

    /** {kode football-data.org, nama lengkap, nama singkat}. Semua ada di paket gratis. */
    static final String[][] LEAGUES = {
            {"PL", "Liga Inggris (Premier League)", "Liga Inggris"},
            {"PD", "La Liga (Spanyol)", "La Liga"},
            {"SA", "Serie A (Italia)", "Serie A"},
            {"BL1", "Bundesliga (Jerman)", "Bundesliga"},
            {"FL1", "Ligue 1 (Prancis)", "Ligue 1"},
            {"CL", "Liga Champions", "Liga Champions"},
            {"DED", "Eredivisie (Belanda)", "Eredivisie"},
            {"PPL", "Primeira Liga (Portugal)", "Liga Portugal"},
            {"ELC", "Championship (Inggris)", "Championship"},
            {"BSA", "Serie A Brasil", "Serie A Brasil"},
            {"WC", "Piala Dunia", "Piala Dunia"},
            {"EC", "Piala Eropa", "Piala Eropa"},
    };
    static final Set<String> DEFAULT_LEAGUES =
            new LinkedHashSet<>(Arrays.asList("PL", "PD", "SA", "BL1", "FL1", "CL"));

    static final class City {
        final String name, tz;
        final double lat, lng;

        City(String name, double lat, double lng, String tz) {
            this.name = name;
            this.lat = lat;
            this.lng = lng;
            this.tz = tz;
        }
    }

    private HomePrefs() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static City city(Context c) {
        SharedPreferences p = prefs(c);
        try {
            return new City(p.getString("city_name", "Bandung"),
                    Double.parseDouble(p.getString("city_lat", "-6.9175")),
                    Double.parseDouble(p.getString("city_lng", "107.6191")),
                    p.getString("city_tz", "Asia/Jakarta"));
        } catch (NumberFormatException e) {
            return new City("Bandung", -6.9175, 107.6191, "Asia/Jakarta");
        }
    }

    static void setCity(Context c, City city) {
        prefs(c).edit().putString("city_name", city.name)
                .putString("city_lat", String.valueOf(city.lat))
                .putString("city_lng", String.valueOf(city.lng))
                .putString("city_tz", city.tz).apply();
    }

    static Set<String> leagues(Context c) {
        Set<String> s = prefs(c).getStringSet("leagues", null);
        return s == null ? new LinkedHashSet<>(DEFAULT_LEAGUES) : new LinkedHashSet<>(s);
    }

    static void setLeagues(Context c, Set<String> s) {
        prefs(c).edit().putStringSet("leagues", new HashSet<>(s)).apply();
    }

    static String leagueShortName(String code) {
        for (String[] l : LEAGUES) if (l[0].equals(code)) return l[2];
        return code;
    }

    // ---- aplikasi di Beranda Shaff ----
    /** Jumlah aplikasi yang tampil sekaligus di layar (4 sampai 8). */
    static int visibleApps(Context c) {
        return Math.max(3, Math.min(8, prefs(c).getInt("app_count", 6)));
    }

    static void setVisibleApps(Context c, int n) {
        prefs(c).edit().putInt("app_count", n).apply();
    }

    /** Daftar paket aplikasi yang ditampilkan, berurutan. null = belum diatur (tampilkan semua). */
    static List<String> apps(Context c) {
        String s = prefs(c).getString("apps", null);
        if (s == null) return null;
        List<String> out = new ArrayList<>();
        for (String line : s.split("\n")) if (!line.isEmpty()) out.add(line);
        return out;
    }

    static void setApps(Context c, List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) sb.append(p).append('\n');
        prefs(c).edit().putString("apps", sb.toString()).apply();
    }
}
