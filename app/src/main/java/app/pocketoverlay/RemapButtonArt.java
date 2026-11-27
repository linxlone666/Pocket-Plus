package app.pocketoverlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;

/** Controller artwork for a single button, a remap, or a shortcut combo. */
final class RemapButtonArt extends Drawable {
    private final Bitmap console;
    private final Rp6Palette palette;
    private final int source;
    private final boolean rear;
    private final int output;
    private final String sourceLabel;
    private final String outputLabel;
    private final int accent;
    private final boolean combo;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Path TRIGGER = shoulderShape(true);
    private static final Path BUMPER = shoulderShape(false);
    private static final Path DPAD = dpadShape();
    private static final Path START = systemGlyph(KeyEvent.KEYCODE_BUTTON_START);
    private static final Path SELECT = systemGlyph(KeyEvent.KEYCODE_BUTTON_SELECT);
    private static final Path HOME = systemGlyph(KeyEvent.KEYCODE_HOME);
    private static final Path BACK = systemGlyph(KeyEvent.KEYCODE_BACK);

    RemapButtonArt(Bitmap console, int source, boolean rear, String sourceLabel,
                   int output, String outputLabel, int accent) {
        this(console, source, rear, sourceLabel, output, outputLabel, accent, false);
    }

    RemapButtonArt(Bitmap console, int source, boolean rear, String sourceLabel,
                   int output, String outputLabel, int accent, boolean combo) {
        this.console = console;
        palette = new Rp6Palette(console);
        this.source = source;
        this.rear = rear;
        this.sourceLabel = sourceLabel;
        this.output = output;
        this.outputLabel = outputLabel;
        this.accent = accent;
        this.combo = combo;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        canvas.save();
        canvas.translate(bounds.left, bounds.top);
        boolean paired = output != KeyEvent.KEYCODE_UNKNOWN;
        canvas.scale(bounds.width() / (paired ? 80f : 28f), bounds.height() / 28f);
        if (!paired) {
            drawButton(canvas, 14, source, rear, sourceLabel);
        } else {
            drawButton(canvas, 14, source, rear, sourceLabel);
            paint.setColor(accent);
            paint.setStrokeWidth(1.5f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(34, 14, 46, 14, paint);
            if (combo) {
                canvas.drawLine(40, 9, 40, 19, paint);
            } else {
                canvas.drawLine(42, 10, 46, 14, paint);
                canvas.drawLine(42, 18, 46, 14, paint);
            }
            drawButton(canvas, 66, output, false, outputLabel);
        }
        canvas.restore();
    }

    private void drawButton(Canvas canvas, int x, int key, boolean rear, String label) {
        if (rear) {
            drawRearButton(canvas, x, label);
            return;
        }
        if (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT) {
            drawDpad(canvas, x, key);
            return;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_L1 || key == KeyEvent.KEYCODE_BUTTON_R1
                || key == KeyEvent.KEYCODE_BUTTON_L2 || key == KeyEvent.KEYCODE_BUTTON_R2) {
            drawShoulderButton(canvas, x, key, label);
            return;
        }
        Path engraving = engravingFor(key);
        if (engraving != null) {
            drawSystemCap(canvas, paint, x, 14, palette.shell, engraving);
            return;
        }
        Rect crop = cropFor(key);
        if (crop != null && console != null) {
            paint.setColor(0xffffffff);
            canvas.save();
            Path edge = new Path();
            edge.addCircle(x, 14, 13, Path.Direction.CW);
            canvas.clipPath(edge);
            canvas.drawBitmap(console, crop, new RectF(x - 13, 1, x + 13, 27), paint);
            canvas.restore();
            return;
        }

        paint.setColor(Rp6Palette.blend(palette.trim, Color.WHITE, .27f));
        canvas.drawRoundRect(x - 14, 4, x + 14, 25, 8, 7, paint);
        paint.setColor(palette.trim);
        canvas.drawRoundRect(x - 13, 5, x + 13, 23, 7, 6, paint);
        drawLabel(canvas, x, 14, label, label.length() > 3 ? 7 : 11, Rp6Palette.ink(palette.trim));
    }

    private static Path dpadShape() {
        Path path = new Path();
        path.moveTo(-4, -12);
        path.lineTo(4, -12);
        path.lineTo(5, -5);
        path.lineTo(12, -4);
        path.lineTo(12, 4);
        path.lineTo(5, 5);
        path.lineTo(4, 12);
        path.lineTo(-4, 12);
        path.lineTo(-5, 5);
        path.lineTo(-12, 4);
        path.lineTo(-12, -4);
        path.lineTo(-5, -5);
        path.close();
        return path;
    }

    private void drawDpad(Canvas canvas, int x, int key) {
        canvas.save();
        canvas.translate(x, 14);
        paint.setColor(palette.trim);
        canvas.drawPath(DPAD, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Rp6Palette.blend(palette.trim, Color.WHITE, .45f));
        canvas.drawPath(DPAD, paint);
        paint.setStyle(Paint.Style.FILL);
        float angle = key == KeyEvent.KEYCODE_DPAD_DOWN ? 180
                : key == KeyEvent.KEYCODE_DPAD_LEFT ? -90
                : key == KeyEvent.KEYCODE_DPAD_RIGHT ? 90 : 0;
        canvas.rotate(angle);
        paint.setColor(accent);
        canvas.drawRoundRect(-3.3f, -11, 3.3f, -4.1f, 1.4f, 1.4f, paint);
        canvas.restore();
    }

    static Path shoulderShape(boolean trigger) {
        Path path = new Path();
        if (trigger) {
            path.moveTo(1, 23);
            path.cubicTo(2, 11, 6, 4, 13, 3);
            path.cubicTo(19, 2, 24, 7, 26, 14);
            path.quadTo(27, 18, 27, 23);
            path.lineTo(1, 23);
        } else {
            path.moveTo(1, 9);
            path.quadTo(13, 7, 26, 9);
            path.quadTo(28, 11, 27, 16);
            path.quadTo(13, 18, 1, 16);
            path.quadTo(0, 12, 1, 9);
        }
        path.close();
        return path;
    }

    private void drawShoulderButton(Canvas canvas, int x, int key, String label) {
        boolean trigger = key == KeyEvent.KEYCODE_BUTTON_L2 || key == KeyEvent.KEYCODE_BUTTON_R2;
        boolean right = key == KeyEvent.KEYCODE_BUTTON_R1 || key == KeyEvent.KEYCODE_BUTTON_R2;
        canvas.save();
        canvas.translate(x + (right ? 13 : -13), 0);
        canvas.scale(right ? -.93f : .93f, 1);
        int color = trigger ? palette.trigger : palette.bumper;
        drawShell(canvas, trigger ? TRIGGER : BUMPER, color);
        canvas.restore();
        drawLabel(canvas, x, trigger ? 16 : 12.7f, label, trigger ? 9 : 7, Rp6Palette.ink(color));
    }

    private void drawRearButton(Canvas canvas, int x, String label) {
        Path paddle = new Path();
        paddle.moveTo(x - 8, 3);
        paddle.quadTo(x, 1, x + 8, 3);
        paddle.quadTo(x + 12, 4, x + 11, 9);
        paddle.lineTo(x + 9, 21);
        paddle.quadTo(x + 8, 25, x + 3, 25);
        paddle.lineTo(x - 7, 24);
        paddle.quadTo(x - 11, 24, x - 11, 19);
        paddle.lineTo(x - 11, 8);
        paddle.quadTo(x - 11, 4, x - 8, 3);
        paddle.close();
        drawShell(canvas, paddle, palette.shell);
        drawLabel(canvas, x, 14, label, 9, Rp6Palette.ink(palette.shell));
    }

    private void drawShell(Canvas canvas, Path shape, int color) {
        paint.setColor(color);
        canvas.drawPath(shape, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(Rp6Palette.blend(color, Color.GRAY, .55f));
        canvas.drawPath(shape, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private static Path systemGlyph(int key) {
        Path shape = new Path();
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_START:
                shape.moveTo(-3.5f, -4.3f);
                shape.lineTo(4.6f, 0);
                shape.lineTo(-3.5f, 4.3f);
                shape.close();
                break;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                shape.addRoundRect(-3.8f, -3.8f, 3.8f, 3.8f, .8f, .8f, Path.Direction.CW);
                break;
            case KeyEvent.KEYCODE_HOME:
                shape.moveTo(-4.8f, -.3f);
                shape.lineTo(0, -4.8f);
                shape.lineTo(4.8f, -.3f);
                shape.moveTo(-3.7f, -1.2f);
                shape.lineTo(-3.7f, 4.2f);
                shape.lineTo(-.9f, 4.2f);
                shape.lineTo(-.9f, 1.8f);
                shape.lineTo(.9f, 1.8f);
                shape.lineTo(.9f, 4.2f);
                shape.lineTo(3.7f, 4.2f);
                shape.lineTo(3.7f, -1.2f);
                break;
            case KeyEvent.KEYCODE_BACK:
                shape.moveTo(-.5f, -5);
                shape.lineTo(-4.8f, -2.1f);
                shape.lineTo(-.5f, .4f);
                shape.moveTo(-4.5f, -2.1f);
                shape.lineTo(.9f, -2.1f);
                shape.cubicTo(5.8f, -2.1f, 5.6f, 4.4f, 1.1f, 4.4f);
                shape.lineTo(-1.7f, 4.4f);
                shape.quadTo(-4.1f, 4.4f, -4.3f, 2.5f);
                break;
            default:
                throw new IllegalArgumentException("Unsupported system button");
        }
        return shape;
    }

    private static Path engravingFor(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_START: return START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return SELECT;
            case KeyEvent.KEYCODE_HOME: return HOME;
            case KeyEvent.KEYCODE_BACK: return BACK;
            default: return null;
        }
    }

    static boolean isSystemCap(int key) {
        return engravingFor(key) != null;
    }

    private static void drawSystemCap(Canvas canvas, Paint paint, float x, float y, int faceColor, Path engraving) {
        canvas.save();
        canvas.translate(x, y);
        paint.setColor(0xff3b3435);
        canvas.drawCircle(0, .6f, 10.2f, paint);
        paint.setColor(faceColor);
        canvas.drawCircle(0, 0, 9.2f, paint);
        boolean light = Color.luminance(faceColor) > .28f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(light ? 0xfffaf3e9 : 0xff807a80);
        canvas.translate(0, .65f);
        canvas.drawPath(engraving, paint);
        canvas.translate(0, -.65f);
        paint.setColor(light ? 0xff5c5048 : 0xffe4dfd7);
        canvas.drawPath(engraving, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    private void drawLabel(Canvas canvas, int x, float y, String label, float size, int color) {
        paint.setColor(color);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size);
        canvas.drawText(label, x, y - (paint.ascent() + paint.descent()) / 2, paint);
    }

    static int pairedFaceKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A: return KeyEvent.KEYCODE_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_B: return KeyEvent.KEYCODE_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_X: return KeyEvent.KEYCODE_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_Y: return KeyEvent.KEYCODE_BUTTON_X;
            default: return key;
        }
    }

    static Bitmap withPhysicalCaps(Bitmap stock, boolean swapFaces) {
        Bitmap artwork = stock.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(artwork);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        drawSystemCap(canvas, paint, 122, 49, stock.getPixel(122, 37), SELECT);
        drawSystemCap(canvas, paint, 616, 51, stock.getPixel(616, 39), START);
        drawSystemCap(canvas, paint, 118, 252, stock.getPixel(118, 239), HOME);
        drawSystemCap(canvas, paint, 619, 254, stock.getPixel(619, 241), BACK);
        if (!swapFaces) return artwork;

        Path cap = new Path();
        int[] faceKeys = {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y};
        for (int key : faceKeys) {
            Rect destination = cropFor(key);
            Rect source = cropFor(pairedFaceKey(key));
            cap.reset();
            cap.addCircle(destination.exactCenterX(), destination.exactCenterY(),
                    destination.width() / 2f - 1, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(cap);
            canvas.drawBitmap(stock, source, destination, paint);
            canvas.restore();
        }
        return artwork;
    }

    // Coordinates refer to the shared 736 × 312 RP6 front artwork.
    private static Rect cropFor(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A: return new Rect(665, 87, 695, 117);
            case KeyEvent.KEYCODE_BUTTON_B: return new Rect(637, 117, 667, 147);
            case KeyEvent.KEYCODE_BUTTON_X: return new Rect(636, 58, 666, 88);
            case KeyEvent.KEYCODE_BUTTON_Y: return new Rect(607, 87, 637, 117);
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return new Rect(59, 72, 109, 122);
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return new Rect(625, 170, 677, 222);
            default: return null;
        }
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
