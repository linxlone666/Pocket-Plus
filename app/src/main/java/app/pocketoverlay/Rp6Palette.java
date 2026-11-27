package app.pocketoverlay;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;

/** Shell and trim colors shared by the front artwork, shoulder strip, and remap icons. */
final class Rp6Palette {
    final int shell;
    final int trim;
    final int trigger;
    final int bumper;

    Rp6Palette(Bitmap front) {
        // These two points are on the shell and shoulder molding in every front illustration.
        shell = front == null ? 0xffddd9d4 : front.getPixel(133, 63);
        trim = front == null ? 0xff767180 : front.getPixel(130, 17);
        trigger = blend(shell, Color.WHITE, .10f);
        bumper = blend(shell, Color.BLACK, .11f);
    }

    static int ink(int surface) {
        return Color.luminance(surface) > .28f ? 0xff332e35 : 0xfff6f2f6;
    }

    static int blend(int base, int target, float weight) {
        return Color.rgb(
                Math.round(Color.red(base) + (Color.red(target) - Color.red(base)) * weight),
                Math.round(Color.green(base) + (Color.green(target) - Color.green(base)) * weight),
                Math.round(Color.blue(base) + (Color.blue(target) - Color.blue(base)) * weight));
    }

    ColorMatrixColorFilter rearFilter() {
        float[] matrix = new float[20];
        shadeChannel(matrix, 0, Color.red(shell));
        shadeChannel(matrix, 5, Color.green(shell));
        shadeChannel(matrix, 10, Color.blue(shell));
        matrix[18] = 1;
        return new ColorMatrixColorFilter(matrix);
    }

    private static void shadeChannel(float[] matrix, int start, int color) {
        // Rear artwork uses pale plastic as its reference shade. Keep its molded highlights,
        // recesses, and vent relief when changing the shell color.
        float contrast = .22f + .60f * color / 255f;
        matrix[start] = .2126f * contrast;
        matrix[start + 1] = .7152f * contrast;
        matrix[start + 2] = .0722f * contrast;
        matrix[start + 4] = color - 222 * contrast;
    }
}
