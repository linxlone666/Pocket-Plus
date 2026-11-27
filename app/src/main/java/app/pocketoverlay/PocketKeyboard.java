package app.pocketoverlay;

import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;

import android.widget.LinearLayout;


import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PocketKeyboard extends InputMethodService {
    static final String COMPONENT = "app.pocketoverlay/.PocketKeyboard";
    private static final String THAI = "keyboard_thai";
    private static final int COMMAND_KEY_HEIGHT = 41;
    private static final String[] ENGLISH = {"qwertyuiop", "asdfghjkl", "zxcvbnm,."};
    private static final String[] ENGLISH_SHIFT = {"QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM<>"};
    private static final String[] KEDMANEE = {"_ๅ/-ภถุึคตจขช", "ๆไำพะัีรนยบลฃ", "ฟหกดเ้่าสวง", "ผปแอิืทมใฝ"};
    private static final String[] KEDMANEE_SHIFT = {"%+๑๒๓๔ู฿๕๖๗๘๙", "๐\"ฎฑธํ๊ณฯญฐ,ฅ", "ฤฆฏโฌ็๋ษศซ.", "()ฉฮฺ์?ฒฬฦ"};
    private static final String[] SYMBOLS = {"1234567890", "@#$%&*+=-^", "()[]{}<>|",
            ".,!?;:_/\\'\"`~"};

    private final List<List<Button>> rows = new ArrayList<>();
    private LinearLayout keyboard;
    private FocusOutline outline;
    private boolean controllerFocus;
    private SharedPreferences preferences;
    private int selectedRow, selectedColumn;
    private int lastHatX, lastHatY;
    private boolean l2KeyDown, l2AxisDown;
    private enum ShiftMode { OFF, ONCE, LOCKED }

    private ShiftMode shiftMode = ShiftMode.OFF;
    private boolean thai, symbols, numeric;
    private int surface, keyColor, commandColor, foreground, accent;

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = OverlaySettings.open(this);
        thai = preferences.getBoolean(THAI, false);
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onUpdateExtractingVisibility(EditorInfo info) {
        setExtractViewShown(false);
    }

    @Override
    @android.annotation.SuppressLint("MissingSuperCall")
    public boolean onEvaluateInputViewShown() {
        // Pocket Keyboard is designed to remain visible with a physical controller connected.
        return true;
    }

    @Override
    public View onCreateInputView() {
        outline = new FocusOutline();
        keyboard = new LinearLayout(this) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                outline.draw(canvas);
            }

            @Override
            protected void onDetachedFromWindow() {
                outline.stop();
                super.onDetachedFromWindow();
            }
        };
        keyboard.setOrientation(LinearLayout.VERTICAL);
        keyboard.setPadding(dp(9), dp(7), dp(9), dp(8));
        keyboard.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop,
                oldRight, oldBottom) -> updateFocus(false));
        render();
        return keyboard;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        int type = info.inputType & InputType.TYPE_MASK_CLASS;
        numeric = type == InputType.TYPE_CLASS_NUMBER || type == InputType.TYPE_CLASS_PHONE
                || type == InputType.TYPE_CLASS_DATETIME;
        if (!restarting) {
            symbols = numeric;
            shiftMode = ShiftMode.OFF;
            selectedRow = 0;
            selectedColumn = lastHatX = lastHatY = 0;
            controllerFocus = false;
        }
        render();
        if (!restarting && keyboard != null) UiMotion.reveal(keyboard, 0.8f, 5, 145);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        l2KeyDown = false;
        l2AxisDown = false;
        super.onFinishInputView(finishingInput);
    }

    private void render() {
        if (keyboard == null) return;
        int theme = preferences.getInt(OverlaySettings.THEME, OverlaySettings.DARK);
        boolean dark = OverlaySettings.usesDarkColors(this, theme);
        surface = OverlayView.panelColor(this, theme, dark);
        foreground = OverlayView.statusColor(this, theme, dark);
        keyColor = dark ? 0xff302c38 : 0xfff7f0fa;
        commandColor = dark ? 0xff42394d : 0xffeaddf1;
        accent = dark ? 0xffd7c6fc : 0xff5b4179;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && theme == OverlaySettings.MATERIAL_YOU) {
            keyColor = getColor(dark ? android.R.color.system_neutral2_700 : android.R.color.system_neutral2_50);
            commandColor = getColor(dark ? android.R.color.system_accent2_800 : android.R.color.system_accent2_100);
            accent = getColor(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_700);
        }
        keyboard.setBackgroundColor(surface | 0xff000000);
        boolean bottomRowSelected = selectedRow == rows.size() - 1;
        keyboard.removeAllViews();
        rows.clear();

        String[] characters = symbols ? SYMBOLS : thai
                ? (shiftMode != ShiftMode.OFF ? KEDMANEE_SHIFT : KEDMANEE)
                : (shiftMode != ShiftMode.OFF ? ENGLISH_SHIFT : ENGLISH);

        int letterHeight = characters.length == 3 ? 41 : 36;
        for (int index = 0; index < characters.length; index++) {
            LinearLayout row = addRow();
            if (!symbols && index == characters.length - 1) {
                boolean locked = shiftMode == ShiftMode.LOCKED;
                String shiftLabel = getString(locked ? R.string.keyboard_shift_locked
                        : shiftMode == ShiftMode.ONCE ? R.string.keyboard_shift_once : R.string.keyboard_shift);
                addKey(row, locked ? "⇪" : "⇧", shiftLabel, "L1", 1, letterHeight, this::shift);
            }
            characters[index].codePoints().forEach(codePoint -> {
                String character = new String(Character.toChars(codePoint));
                // Give isolated marks a blank base for shaping the label only.
                String display = Character.getType(codePoint) == Character.NON_SPACING_MARK
                        ? "\u00a0" + character : character;
                addKey(row, display, null, null, 1, letterHeight, () -> write(character));
            });
            if (index == (characters.length == 3 ? 1 : 2)) {
                addKey(row, "⌫", getString(R.string.keyboard_delete), "B", 1, letterHeight, this::delete);
            }
        }
        LinearLayout row = addRow();
        addKey(row, symbols ? (thai ? "กขค" : "ABC") : "123",
                getString(R.string.keyboard_symbols), "L2", 1.2f, COMMAND_KEY_HEIGHT, this::toggleSymbols);
        String paste = getString(android.R.string.paste);
        addKey(row, paste, paste, null, 1.3f, COMMAND_KEY_HEIGHT, this::paste);
        addKey(row, thai ? "ไทย" : "English", getString(R.string.keyboard_language),
                "R1", 1.5f, COMMAND_KEY_HEIGHT, this::switchLanguage);
        addKey(row, thai ? "เว้นวรรค" : "Space", getString(R.string.keyboard_space),
                "Y", 4.5f, COMMAND_KEY_HEIGHT, () -> write(" "));
        addKey(row, "↵", getString(R.string.keyboard_enter), "Start", 1.3f, COMMAND_KEY_HEIGHT, this::enter);
        addKey(row, "⌄", getString(R.string.keyboard_hide), "X", 1, COMMAND_KEY_HEIGHT, () -> requestHideSelf(0));
        selectedRow = bottomRowSelected ? rows.size() - 1 : Math.min(selectedRow, rows.size() - 1);
        selectedColumn = Math.min(selectedColumn, rows.get(selectedRow).size() - 1);
        updateFocus(false);
    }


    private LinearLayout addRow() {
        LinearLayout row = new LinearLayout(this);
        row.setBaselineAligned(false);
        keyboard.addView(row, new LinearLayout.LayoutParams(-1, -2));
        rows.add(new ArrayList<>());
        return row;
    }

    private void addKey(LinearLayout row, String text, String description, String hint, float weight,
            int height, Runnable action) {
        Button button = new KeyboardKey(hint, text);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(true);
        button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        button.setTextSize((description != null && text.length() > 3 && weight < 2) ? 15 : 17);
        button.setMaxLines(1);
        button.setTextLocale(thai ? Locale.forLanguageTag("th-TH") : Locale.ENGLISH);
        button.setText(text);
        if (description != null) button.setContentDescription(description);
        else if (text.startsWith("\u00a0")) button.setContentDescription(text.substring(1));
        button.setTextColor(foreground);
        button.setStateListAnimator(null);
        GradientDrawable fill = keyBackground(description == null ? keyColor : commandColor);
        if ("L1".equals(hint) && shiftMode != ShiftMode.OFF) {
            fill.setStroke(dp(1), accent);
            button.setTextColor(accent);
        }
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 65)), fill, null));

        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        int rowIndex = rows.size() - 1;
        int column = rows.get(rowIndex).size();
        button.setOnTouchListener((view, event) -> {
            if (controllerFocus && event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                controllerFocus = false;
                updateFocus(false);
            }
            return false;
        });
        button.setOnClickListener(view -> {
            selectedRow = rowIndex;
            selectedColumn = column;
            updateFocus(false);
            feedback(button);
            action.run();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(height), weight);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(button, params);
        rows.get(rowIndex).add(button);
    }

    private GradientDrawable keyBackground(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(8));
        shape.setColor(color);
        return shape;
    }

    private Button selected() {
        return rows.get(selectedRow).get(selectedColumn);
    }

    private void select(int row, int column) {
        selectedRow = row;
        selectedColumn = column;
        controllerFocus = true;
        updateFocus(true);
    }

    private void updateFocus(boolean animate) {
        if (outline == null || rows.isEmpty()) return;
        outline.follow(controllerFocus ? selected() : null, animate);
    }

    private void feedback(View key) {
        key.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (UiMotion.enabled(this)) {
            key.animate().cancel();
            key.setScaleX(0.94f);
            key.setScaleY(0.92f);
            key.animate().scaleX(1f).scaleY(1f).setDuration(110).setInterpolator(UiMotion.ENTER).start();
        }
    }

    private void move(int dx, int dy) {
        int row = Math.max(0, Math.min(selectedRow + dy, rows.size() - 1));
        if (row == selectedRow) {
            int column = Math.max(0, Math.min(selectedColumn + dx, rows.get(row).size() - 1));
            select(row, column);
            return;
        }
        float x = selected().getX() + selected().getWidth() / 2f;
        List<Button> target = rows.get(row);
        int nearest = Math.min(selectedColumn, target.size() - 1);
        float distance = Float.MAX_VALUE;
        for (int index = 0; index < target.size(); index++) {
            Button button = target.get(index);
            float gap = Math.abs(x - button.getX() - button.getWidth() / 2f);
            if (button.getWidth() > 0 && gap < distance) {
                nearest = index;
                distance = gap;
            }
        }
        select(row, nearest);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isInputViewShown() || rows.isEmpty()) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (event.getRepeatCount() == 0) {
                    controllerFocus = true;
                    selected().performClick();
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1: return shortcut(event, this::shift, false);
            case KeyEvent.KEYCODE_BUTTON_L2:
                if (event.getRepeatCount() == 0) updateL2(true, l2AxisDown);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1: return shortcut(event, this::switchLanguage, false);
            case KeyEvent.KEYCODE_BUTTON_B: return shortcut(event, this::delete, true);
            case KeyEvent.KEYCODE_BUTTON_Y: return shortcut(event, () -> write(" "), false);
            case KeyEvent.KEYCODE_BUTTON_START: return shortcut(event, this::enter, false);
            case KeyEvent.KEYCODE_BUTTON_X:
                return true;
            default: return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isInputViewShown()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BUTTON_X: requestHideSelf(0); return true;
                case KeyEvent.KEYCODE_BUTTON_L2:
                    updateL2(false, l2AxisDown);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_BUTTON_L1:
                case KeyEvent.KEYCODE_BUTTON_R1:
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BUTTON_Y:
                case KeyEvent.KEYCODE_BUTTON_START: return true;
                default: break;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!isInputViewShown() || rows.isEmpty() || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            return super.onGenericMotionEvent(event);
        }
        int x = Math.round(event.getAxisValue(MotionEvent.AXIS_HAT_X));
        int y = Math.round(event.getAxisValue(MotionEvent.AXIS_HAT_Y));
        if (x != 0 && x != lastHatX) move(x, 0);
        if (y != 0 && y != lastHatY) move(0, y);
        float trigger = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE));
        boolean wasAxisDown = l2AxisDown;
        updateL2(l2KeyDown, trigger >= (wasAxisDown ? 0.35f : 0.6f));
        boolean handled = x != 0 || y != 0 || lastHatX != 0 || lastHatY != 0 || l2AxisDown || wasAxisDown;
        lastHatX = x;
        lastHatY = y;
        return handled || super.onGenericMotionEvent(event);
    }

    private void updateL2(boolean keyDown, boolean axisDown) {
        boolean wasDown = l2KeyDown || l2AxisDown;
        l2KeyDown = keyDown;
        l2AxisDown = axisDown;
        if (!wasDown && (keyDown || axisDown)) pressShortcut(this::toggleSymbols);
    }

    private boolean shortcut(KeyEvent event, Runnable action, boolean repeat) {
        if (repeat || event.getRepeatCount() == 0) pressShortcut(action);
        return true;
    }

    private void pressShortcut(Runnable action) {
        controllerFocus = true;
        updateFocus(false);
        keyboard.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        action.run();
    }

    private void shift() {
        if (!symbols) {
            switch (shiftMode) {
                case OFF: shiftMode = ShiftMode.ONCE; break;
                case ONCE: shiftMode = ShiftMode.LOCKED; break;
                case LOCKED: shiftMode = ShiftMode.OFF; break;
            }
            render();
        }
    }

    private void toggleSymbols() {
        symbols = !symbols;
        shiftMode = ShiftMode.OFF;
        render();
    }

    private void switchLanguage() {
        thai = !thai;
        preferences.edit().putBoolean(THAI, thai).apply();
        symbols = numeric;
        shiftMode = ShiftMode.OFF;
        render();
    }

    private void paste() {
        InputConnection editor = getCurrentInputConnection();
        if (editor != null) editor.performContextMenuAction(android.R.id.paste);
    }

    private void write(String text) {
        InputConnection editor = getCurrentInputConnection();
        if (editor != null) editor.commitText(text, 1);
        if (shiftMode == ShiftMode.ONCE) {
            shiftMode = ShiftMode.OFF;
            render();
        }
    }

    private void delete() {
        InputConnection editor = getCurrentInputConnection();
        if (editor == null) return;
        CharSequence selection = editor.getSelectedText(0);
        if (selection != null && selection.length() > 0) editor.commitText("", 1);
        else if (!editor.deleteSurroundingTextInCodePoints(1, 0)) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        }
    }

    private void enter() {
        if (!sendDefaultEditorAction(true)) sendKeyChar('\n');
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | (alpha << 24);
    }

    private final class KeyboardKey extends Button {
        private final String hint;
        private final String centeredSymbol;
        private final Rect glyphBounds = new Rect();
        private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        KeyboardKey(String hint, String text) {
            super(PocketKeyboard.this);
            this.hint = hint;
            centeredSymbol = "⌫↵⇧⇪⌄".contains(text) ? text : null;
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(dp(8));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (centeredSymbol == null) {
                super.onDraw(canvas);
            } else {
                glyphPaint.set(getPaint());
                glyphPaint.setColor(getCurrentTextColor());
                glyphPaint.setTextSize(getTextSize() * 1.24f);
                glyphPaint.getTextBounds(centeredSymbol, 0, centeredSymbol.length(), glyphBounds);
                canvas.drawText(centeredSymbol, getWidth() / 2f - glyphBounds.exactCenterX(),
                        getHeight() / 2f - glyphBounds.exactCenterY(), glyphPaint);
            }
            if (hint == null) return;
            float textWidth = paint.measureText(hint);
            float right = getWidth() - dp(4);
            float top = dp(3);
            float left = right - textWidth - dp(6);
            paint.setColor(withAlpha(accent, 42));
            canvas.drawRoundRect(left, top, right, top + dp(11), dp(3), dp(3), paint);
            paint.setColor(accent);
            canvas.drawText(hint, left + dp(3), top + dp(8), paint);
        }
    }

    private final class FocusOutline {
        private final RectF bounds = new RectF();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator motion;

        FocusOutline() {
            paint.setStyle(Paint.Style.STROKE);
        }

        void follow(Button key, boolean animate) {
            if (motion != null) motion.cancel();
            if (key == null) {
                bounds.setEmpty();
                keyboard.invalidate();
                return;
            }
            if (key.getWidth() == 0) return;
            View row = (View) key.getParent();
            RectF end = new RectF(key.getLeft() + row.getLeft(), key.getTop() + row.getTop(),
                    key.getRight() + row.getLeft(), key.getBottom() + row.getTop());
            end.inset(dp(1), dp(1));
            if (!animate || bounds.isEmpty() || !UiMotion.enabled(PocketKeyboard.this)) {
                bounds.set(end);
                keyboard.invalidate();
                return;
            }
            RectF start = new RectF(bounds);
            motion = ValueAnimator.ofFloat(0f, 1f);
            motion.setDuration(110);
            motion.setInterpolator(UiMotion.ENTER);
            motion.addUpdateListener(animator -> {
                float t = (float) animator.getAnimatedValue();
                bounds.set(start.left + (end.left - start.left) * t, start.top + (end.top - start.top) * t,
                        start.right + (end.right - start.right) * t, start.bottom + (end.bottom - start.bottom) * t);
                keyboard.invalidate();
            });
            motion.start();
        }

        void draw(Canvas canvas) {
            if (bounds.isEmpty()) return;
            paint.setColor(withAlpha(accent, 44));
            paint.setStrokeWidth(dp(5));
            canvas.drawRoundRect(bounds, dp(8), dp(8), paint);
            paint.setColor(accent);
            paint.setStrokeWidth(dp(2));
            canvas.drawRoundRect(bounds, dp(8), dp(8), paint);
        }

        void stop() {
            if (motion != null) motion.cancel();
        }
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
