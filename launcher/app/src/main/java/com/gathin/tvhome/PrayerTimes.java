package com.gathin.tvhome;

/**
 * Jadwal sholat dihitung di perangkat (tanpa internet), metode Kemenag RI:
 * Subuh 20 derajat, Isya 18 derajat, Ashar standar (bayangan 1x), ditambah ihtiyat 2 menit.
 * Hasil bisa selisih sekitar 1-2 menit dari jadwal resmi.
 */
final class PrayerTimes {
    static final String[] NAMES = {"Subuh", "Terbit", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    private static final double FAJR_ANGLE = 20.0, ISHA_ANGLE = 18.0, RISE_SET_ANGLE = 0.833;
    private static final double IHTIYAT_MIN = 2.0;

    private PrayerTimes() {}

    /** Menit sejak tengah malam (waktu setempat) untuk Subuh, Terbit, Dzuhur, Ashar, Maghrib, Isya. */
    static int[] minutesOfDay(int year, int month, int day, double lat, double lng, double tzHours) {
        double jd = julian(year, month, day) - lng / (15.0 * 24.0);
        double[] h = new double[6];
        h[0] = angleTime(jd, FAJR_ANGLE, 5, lat, true);
        h[1] = angleTime(jd, RISE_SET_ANGLE, 6, lat, true);
        h[2] = midDay(jd, 12);
        h[3] = asrTime(jd, 1, 13, lat);
        h[4] = angleTime(jd, RISE_SET_ANGLE, 18, lat, false);
        h[5] = angleTime(jd, ISHA_ANGLE, 18, lat, false);
        int[] out = new int[6];
        for (int i = 0; i < 6; i++) {
            double t = h[i] + tzHours - lng / 15.0;
            double m = t * 60.0 + (i == 1 ? -IHTIYAT_MIN : IHTIYAT_MIN);
            if (Double.isNaN(m)) { out[i] = -1; continue; }
            m = m - 1440.0 * Math.floor(m / 1440.0);
            out[i] = (int) Math.round(m) % 1440;
        }
        return out;
    }

    static String format(int minutes) {
        if (minutes < 0) return "--:--";
        return String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    // ---- astronomi (rumus praytimes.org) ----
    private static double[] sun(double jd) {
        double d = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * d);
        double q = fixAngle(280.459 + 0.98564736 * d);
        double l = fixAngle(q + 1.915 * sin(g) + 0.020 * sin(2 * g));
        double e = 23.439 - 0.00000036 * d;
        double ra = fixHour(Math.toDegrees(Math.atan2(cos(e) * sin(l), cos(l))) / 15.0);
        double eqt = q / 15.0 - ra;
        double decl = Math.toDegrees(Math.asin(sin(e) * sin(l)));
        return new double[]{decl, eqt};
    }

    private static double midDay(double jd, double t) {
        double eqt = sun(jd + t / 24.0)[1];
        return fixHour(12 - eqt);
    }

    private static double angleTime(double jd, double angle, double t, double lat, boolean before) {
        double decl = sun(jd + t / 24.0)[0];
        double noon = midDay(jd, t);
        double a = (-sin(angle) - sin(decl) * sin(lat)) / (cos(decl) * cos(lat));
        if (a < -1 || a > 1) return Double.NaN;
        double tt = Math.toDegrees(Math.acos(a)) / 15.0;
        return noon + (before ? -tt : tt);
    }

    private static double asrTime(double jd, double factor, double t, double lat) {
        double decl = sun(jd + t / 24.0)[0];
        double angle = -Math.toDegrees(Math.atan(1.0 / (factor + Math.tan(Math.toRadians(Math.abs(lat - decl))))));
        return angleTime(jd, angle, t, lat, false);
    }

    private static double julian(int y, int m, int d) {
        if (m <= 2) { y -= 1; m += 12; }
        double a = Math.floor(y / 100.0);
        double b = 2 - a + Math.floor(a / 4.0);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }

    private static double sin(double d) { return Math.sin(Math.toRadians(d)); }
    private static double cos(double d) { return Math.cos(Math.toRadians(d)); }
    private static double fixAngle(double a) { a = a - 360.0 * Math.floor(a / 360.0); return a; }
    private static double fixHour(double a) { a = a - 24.0 * Math.floor(a / 24.0); return a; }
}
