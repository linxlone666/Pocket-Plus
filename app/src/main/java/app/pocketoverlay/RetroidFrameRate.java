package app.pocketoverlay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.regex.Pattern;
import java.util.TreeSet;

/** Reads foreground presentation timestamps through the stock firmware service. */
final class RetroidFrameRate {
    private static final long LAYER_RECHECK_NS = 4_000_000_000L;
    private static final int MAX_LAYER_TRIES = 4;

    private final RetroidFirmware firmware = new RetroidFirmware();
    private String activity;
    private String layer;
    private long nextLayerSearch;

    float read() {
        String foreground = firmware.foregroundActivity();
        if (!foreground.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
                || foreground.startsWith("com.android.systemui/")) {
            activity = layer = null;
            return Float.NaN;
        }
        if (!foreground.equals(activity)) {
            activity = foreground;
            layer = null;
            nextLayerSearch = 0;
        }

        float cached = layer == null ? Float.NaN : readLayer(layer);
        long now = System.nanoTime();
        if (now < nextLayerSearch) return cached;
        nextLayerSearch = now + LAYER_RECHECK_NS;

        List<String> layers = findLayers(foreground);

        int tries = 0;
        for (String candidate : layers) {
            if (candidate.equals(layer)) {
                if (Float.isFinite(cached)) return cached;
                continue;
            }
            if (tries++ == MAX_LAYER_TRIES) break;
            float fps = readLayer(candidate);
            if (Float.isFinite(fps)) {
                layer = candidate;
                return fps;
            }
        }
        return cached;
    }

    private float readLayer(String name) {
        // Keep the short reply within the firmware's buffer.
        String timestamps = firmware.query("dumpsys -t 1 SurfaceFlinger --latency " + quote(name)
                + " | awk '$2>0 && $2<9223372036854775807{print $2}' | tail -8 | xargs");

        return calculateFps(timestamps);
    }

    private List<String> findLayers(String foreground) {
        int slash = foreground.indexOf('/');
        String app = foreground.substring(0, slash);
        String fullActivity = foreground.charAt(slash + 1) == '.'
                ? app + "/" + app + foreground.substring(slash + 1) : foreground;
        // Retroid's shell service returns a single line. Join candidates before it reads stdout.
        String names = firmware.query("dumpsys -t 1 SurfaceFlinger --list | grep -F "
                + quote(app) + " | grep -vE '^(Background for |Dim Layer)|ActivityRecord|Splash Screen|animation-leash'"
                + " | tail -8 | tr '\\n' '\\t'");
        Pattern owner = Pattern.compile("(?<![A-Za-z0-9_.])" + Pattern.quote(app) + "(?![A-Za-z0-9_.])");
        List<String> layers = new ArrayList<>();
        for (String name : names.split("\\t")) {
            if (owner.matcher(name).find()) layers.add(name);
        }
        layers.sort(Comparator.comparingInt(name -> layerPriority(name, foreground, fullActivity)));
        return layers;
    }

    private static int layerPriority(String name, String activity, String fullActivity) {
        boolean match = name.contains(activity) || name.contains(fullActivity);
        boolean surface = name.startsWith("SurfaceView");
        return (match ? 0 : 4) + (surface ? 0 : 2) + (name.contains("BLAST") ? 0 : 1);
    }

    private static float calculateFps(String timestamps) {
        TreeSet<Long> presented = new TreeSet<>();
        for (String timestamp : timestamps.split("\\s+")) {
            try {
                long time = Long.parseLong(timestamp);
                if (time > 0 && time < Long.MAX_VALUE) {
                    presented.add(time);
                }
            } catch (NumberFormatException ignored) {
                // No frame data or a firmware diagnostic.
            }
        }
        if (presented.size() < 2) {
            return Float.NaN;
        }
        long newest = presented.last();
        long age = System.nanoTime() - newest;
        if (age < 0 || age > 1_500_000_000L) {
            return Float.NaN;
        }
        NavigableSet<Long> recent = presented.tailSet(newest - 1_000_000_000L, true);
        long span = newest - recent.first();
        return span > 0 ? (recent.size() - 1) * 1_000_000_000f / span : Float.NaN;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

}
