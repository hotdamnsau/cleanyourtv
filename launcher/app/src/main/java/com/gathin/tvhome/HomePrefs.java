package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
}
