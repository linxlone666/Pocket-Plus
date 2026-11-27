package app.pocketoverlay;

import android.os.Build;

import java.util.Locale;

final class DisplayColor {
    static final String SATURATION = "display_color_saturation";
    static final String TEMPERATURE = "display_color_temperature";
    static final int NORMAL_SATURATION = 100;
    static final int NEUTRAL_TEMPERATURE = 0;

    enum Result { APPLIED, SYSTEM_FILTER_ACTIVE, FAILED }

    private final RetroidFirmware firmware = new RetroidFirmware();


    static boolean supported() {
        return "Retroid Pocket 6".equals(Build.MODEL);
    }

    Result setSaturation(int percent) {
        float factor = Math.max(0, Math.min(200, percent)) / 100f;
        return apply("1022 f " + factor);
    }

    Result setTemperature(int offset) {
        // The regular app settings API restricts reads of display filter state on Android 12+.
        String accessibility = firmware.query("settings list secure | "
                + "grep -E '^accessibility_display_(inversion|daltonizer)_enabled=1$' | head -1");
        if (accessibility.endsWith("_enabled=1")) return Result.SYSTEM_FILTER_ACTIVE;
        String filter = firmware.query("dumpsys -t 1 color_display | "
                + "awk '/Activated: true/{active=1} /COLOR DISPLAY MANAGER/{found=1} "
                + "END{if(found) print (active ? \"active\" : \"clear\")}'");
        if ("active".equals(filter)) return Result.SYSTEM_FILTER_ACTIVE;
        if (!"clear".equals(filter)) return Result.FAILED;

        float warmth = Math.max(-100, Math.min(100, offset)) / 100f;
        if (warmth == 0f) return apply("1015 i32 0");
        float red = 1f + Math.min(0f, warmth) * 0.24f;
        float green = 1f - Math.abs(warmth) * 0.09f;
        float blue = 1f - Math.max(0f, warmth) * 0.30f;
        // SurfaceFlinger expects a column-major RGBA matrix with a preserved alpha channel.
        String matrix = String.format(Locale.ROOT,
                "1015 i32 1 f %.4f f 0 f 0 f 0 f 0 f %.4f f 0 f 0 f 0 f 0 f %.4f f 0 f 0 f 0 f 0 f 1",
                red, green, blue);
        return apply(matrix);
    }

    private Result apply(String args) {
        // Only preset transaction IDs and bounded numeric values are used for these controls.
        String response = firmware.query("service call SurfaceFlinger " + args + " 2>&1");
        return response.startsWith("Result: Parcel(") && !response.contains("Error")
                ? Result.APPLIED : Result.FAILED;
    }
}
