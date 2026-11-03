package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Daftar aplikasi yang terpasang di TV (kecuali aplikasi ini sendiri). */
final class AppCatalog {
    private AppCatalog() {}

    static final class App {
        final String pkg;
        final String label;
        final Intent launch;
        private final ResolveInfo ri;

        App(String pkg, String label, Intent launch, ResolveInfo ri) {
            this.pkg = pkg;
            this.label = label;
            this.launch = launch;
            this.ri = ri;
        }

        /** Gambar banner TV (16:9), atau null bila aplikasi tidak punya. */
        Drawable banner(PackageManager pm) {
            Drawable b = ri.activityInfo.loadBanner(pm);
            if (b == null) b = ri.activityInfo.applicationInfo.loadBanner(pm);
            return b;
        }

        Drawable icon(PackageManager pm) {
            return ri.loadIcon(pm);
        }
    }

    static List<App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> map = new LinkedHashMap<>();
        add(pm, map, Intent.CATEGORY_LEANBACK_LAUNCHER, c.getPackageName());
        add(pm, map, Intent.CATEGORY_LAUNCHER, c.getPackageName());
        List<App> list = new ArrayList<>(map.values());
        Collections.sort(list, new Comparator<App>() {
            @Override
            public int compare(App a, App b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return list;
    }

    private static void add(PackageManager pm, Map<String, App> map, String category, String self) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(self) || map.containsKey(pkg)) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            CharSequence label = ri.loadLabel(pm);
            map.put(pkg, new App(pkg, label == null ? pkg : label.toString(), launch, ri));
        }
    }
}
