package app.pocketoverlay;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class PerformanceProfiles {
    private static final String PROFILE = "app_perf_profile_";
    private static final String PREFIX = "app_perf_mode_";
    private static final String FAN_PREFIX = "app_fan_mode_";
    private static final int SYSTEM = -1;

    enum Control {
        FAN("fan_mode", FAN_PREFIX, "app_fan_previous", "app_fan_applied", R.string.perf_mode_fan,
                new int[]{0, 1, 4, 5, 6}, new int[]{R.string.navigation_fan_off,
                        R.string.navigation_fan_quiet, R.string.navigation_fan_smart,
                        R.string.navigation_fan_sport, R.string.navigation_fan_custom}),
        PERFORMANCE("performance_mode", PREFIX, "app_perf_previous", "app_perf_applied",
                R.string.perf_mode_performance, new int[]{0, 1, 2},
                new int[]{R.string.navigation_performance_standard, R.string.navigation_performance_performance,
                        R.string.navigation_performance_high});

        final String setting;
        private final String prefix;
        private final String previousKey;
        private final String appliedKey;
        final int title;
        final int[] values;
        final int[] labels;

        Control(String setting, String prefix, String previousKey, String appliedKey,
                int title, int[] values, int[] labels) {
            this.setting = setting;
            this.prefix = prefix;
            this.previousKey = previousKey;
            this.appliedKey = appliedKey;
            this.title = title;
            this.values = values;
            this.labels = labels;
        }

        int mode(SharedPreferences settings, String app) {
            return settings.getInt(prefix + app, SYSTEM);
        }

        void save(SharedPreferences settings, String app, int mode) {
            if (index(mode) >= 0) settings.edit().putInt(prefix + app, mode).apply();
            else settings.edit().remove(prefix + app).apply();
        }

        int label(int mode) {
            int index = index(mode);
            return index < 0 ? R.string.perf_mode_system : labels[index];
        }

        private int index(int mode) {
            for (int i = 0; i < values.length; i++) {
                if (mode == values[i]) return i;
            }
            return -1;
        }
    }

    private final Context context;
    private final SharedPreferences settings;
    private final RetroidFirmware firmware = new RetroidFirmware();
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private ScheduledFuture<?> pending;
    private String foregroundPackage;
    private int lastFan = SYSTEM;
    private int lastPerformance = SYSTEM;
    private boolean configured;

    PerformanceProfiles(Context context, SharedPreferences settings) {
        this.context = context;
        this.settings = settings;
        worker.setRemoveOnCancelPolicy(true);
        configured = !packages(settings).isEmpty();
    }

    static boolean supported() {
        return "Retroid Pocket 6".equals(Build.MODEL);
    }

    static boolean isProfileKey(String key) {
        return key != null && (key.startsWith(PROFILE) || key.startsWith(PREFIX) || key.startsWith(FAN_PREFIX));
    }

    static boolean hasProfile(SharedPreferences settings, String app) {
        return settings.contains(PROFILE + app) || settings.contains(PREFIX + app)
                || settings.contains(FAN_PREFIX + app);
    }

    static void add(SharedPreferences settings, String app) {
        settings.edit().putBoolean(PROFILE + app, true).apply();
    }

    static void remove(SharedPreferences settings, String app) {
        settings.edit().remove(PROFILE + app).remove(PREFIX + app).remove(FAN_PREFIX + app).apply();
    }

    static Set<String> packages(SharedPreferences settings) {
        Set<String> packages = new TreeSet<>();
        for (String key : settings.getAll().keySet()) {
            if (key.startsWith(PROFILE)) packages.add(key.substring(PROFILE.length()));
            else if (key.startsWith(PREFIX)) packages.add(key.substring(PREFIX.length()));
            else if (key.startsWith(FAN_PREFIX)) packages.add(key.substring(FAN_PREFIX.length()));
        }
        return packages;
    }

    void profilesChanged() {
        configured = !packages(settings).isEmpty();
        refresh();
    }

    void refresh() {
        if (!supported() || worker.isShutdown()) return;
        if (pending != null) pending.cancel(false);
        if (!configured && !settings.contains(Control.FAN.previousKey)
                && !settings.contains(Control.PERFORMANCE.previousKey)) return;
        pending = worker.schedule(this::applyForegroundProfile, 180, TimeUnit.MILLISECONDS);
    }

    private void applyForegroundProfile() {
        if (!Settings.System.canWrite(context) || !context.getSystemService(PowerManager.class).isInteractive()
                || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
        String activity = firmware.foregroundActivity();
        int slash = activity.indexOf('/');
        if (slash <= 0) return;
        String app = activity.substring(0, slash);
        int fan = Control.FAN.mode(settings, app);
        int performance = Control.PERFORMANCE.mode(settings, app);
        boolean newApp = !app.equals(foregroundPackage);
        if (newApp || fan != lastFan) {
            if (apply(Control.FAN, fan)) lastFan = fan;
            else lastFan = Integer.MIN_VALUE;
        }
        if (newApp || performance != lastPerformance) {
            if (apply(Control.PERFORMANCE, performance)) lastPerformance = performance;
            else lastPerformance = Integer.MIN_VALUE;
        }
        foregroundPackage = app;
    }

    private boolean apply(Control control, int target) {
        if (control.index(target) < 0) return restore(control);

        int current = currentMode(control);
        // Custom fan mode and firmware-specific values can be restored as long as they exist.
        if (current < 0) return false;
        int previous = settings.getInt(control.previousKey, SYSTEM);
        int applied = settings.getInt(control.appliedKey, SYSTEM);
        if (previous < 0 || current != applied) previous = current;
        if (target != current && !writeMode(control, target)) return false;
        settings.edit().putInt(control.previousKey, previous).putInt(control.appliedKey, target).apply();
        return true;
    }

    void close() {
        if (pending != null) pending.cancel(false);
        worker.execute(() -> {
            restore(Control.FAN);
            restore(Control.PERFORMANCE);
        });
        worker.shutdown();
    }

    private boolean restore(Control control) {
        int previous = settings.getInt(control.previousKey, SYSTEM);
        if (previous < 0) return true;
        if (!Settings.System.canWrite(context)) return false;
        int current = currentMode(control);
        if (current < 0) return false;
        if (current == settings.getInt(control.appliedKey, SYSTEM) && current != previous
                && !writeMode(control, previous)) return false;
        settings.edit().remove(control.previousKey).remove(control.appliedKey).apply();
        return true;
    }

    private int currentMode(Control control) {
        return Settings.System.getInt(context.getContentResolver(), control.setting, SYSTEM);
    }

    private boolean writeMode(Control control, int value) {
        return firmware.writeSystemSetting(context, control.setting, value);
    }
}