package app.pocketoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class NavigationPanel extends LinearLayout {
    private final int foreground;
    private boolean compact;
    private Runnable outside;
    private IntConsumer horizontal;
    private int hat;
    private LinearLayout header;
    private int title;
    private boolean titleChanged;
    private View firstChoice;
    private View currentChoice;

    NavigationPanel(Context context, SharedPreferences settings, int title, int detail) {
        this(context, settings, title, detail, false);
    }

    NavigationPanel(Context context, SharedPreferences settings, int title, int detail, boolean compact) {
        super(context);
        this.compact = compact;
        setOrientation(VERTICAL);
        setPadding(dp(compact ? 8 : 14), dp(compact ? 4 : 9),
                dp(compact ? 8 : 14), dp(compact ? 6 : 10));
        int theme = settings.getInt(OverlaySettings.THEME, OverlaySettings.DARK);
        boolean dark = OverlaySettings.usesDarkColors(context, theme);
        foreground = OverlayView.statusColor(context, theme, dark);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(0xff000000 | OverlayView.panelColor(context, theme, dark));
        fill.setCornerRadius(dp(compact ? 11 : 14));
        if (compact) fill.setStroke(dp(1), 0x24000000 | (foreground & 0x00ffffff));
        setBackground(fill);
        setClipToOutline(true);
        setContent(title, detail);
    }

    private NavigationPanel(Context context, int foreground) {
        super(context);
        this.foreground = foreground;
        setOrientation(VERTICAL);
    }

    NavigationPanel controlColumn() {
        NavigationPanel column = new NavigationPanel(getContext(), foreground);
        column.compact = compact;
        return column;
    }

    boolean isCompact() {
        return compact;
    }

    int foregroundColor() {
        return foreground;
    }

    void setContent(int title, int detail) {
        titleChanged = this.title != 0 && this.title != title;
        this.title = title;
        removeAllViews();
        firstChoice = null;
        currentChoice = null;
        header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        TextView heading = label(compact ? 14 : 15);
        if (compact) {
            heading.setPaddingRelative(dp(3), 0, 0, 0);
            header.setMinimumHeight(dp(24));
        }
        heading.setText(title);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(heading, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        if (detail != 0) {
            TextView hint = label(12);
            hint.setText(detail);
            hint.setAlpha(0.8f);
            addView(hint);
        }
    }

    void animateContentChange() {
        if (!titleChanged) return;
        titleChanged = false;
        for (int i = 0; i < getChildCount(); i++) {
            UiMotion.reveal(getChildAt(i), 0.78f, 3, 145);
        }
    }

    void setHeaderControl(View control) {
        header.addView(control, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(compact ? 28 : 40)));
    }

    void addFolderRow(int[] keys, int[] labels, int[] icons, Runnable[] actions) {
        addActionRow(keys, labels, actions, -1, false, icons, null);
    }

    void setBackAction(int text, Runnable action) {
        LinearLayout button = new LinearLayout(getContext());
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPaddingRelative(dp(5), 0, dp(7), 0);
        int tone = foreground & 0x00ffffff;
        GradientDrawable surface = new GradientDrawable();
        surface.setCornerRadius(dp(8));
        surface.setColor(0x12000000 | tone);
        surface.setStroke(dp(1), new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[0]},
                new int[]{foreground, 0x36000000 | tone}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30000000 | tone), surface, null));
        addKeyBadge(button, R.string.navigation_key_b, false);
        TextView caption = label(11);
        caption.setText(text);
        button.addView(caption);
        button.setContentDescription(getContext().getString(R.string.navigation_quick_folder_description,
                getContext().getString(text), getContext().getString(R.string.navigation_key_b)));
        button.setFocusableInTouchMode(true);
        button.setOnClickListener(view -> action.run());
        LayoutParams size = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(24));
        size.setMarginStart(dp(6));
        header.addView(button, size);
    }

    void addActionRow(int[] keys, int[] labels, Runnable[] actions) {
        addActionRow(keys, labels, actions, -1, false, null, null);
    }

    View[] addChoiceRow(int[] keys, int[] labels, Runnable[] actions, int selectedIndex) {
        return addActionRow(keys, labels, actions, selectedIndex, true, null, null);
    }

    void addStatusChoiceRow(int[] keys, int[] labels, int[] statuses, Runnable[] actions) {
        addActionRow(keys, labels, actions, -1, true, null, statuses);
    }

    Switch[] addSwitchRow(int[] labels, boolean[] values, Consumer<Boolean> left, Consumer<Boolean> right) {
        LinearLayout row = new LinearLayout(getContext());
        row.setPadding(0, dp(5), 0, 0);
        Switch[] switches = new Switch[labels.length];
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < labels.length; i++) {
            Switch toggle = new Switch(getContext());
            toggle.setText(labels[i]);
            toggle.setTextColor(foreground);
            toggle.setChecked(values[i]);
            toggle.setMinHeight(dp(compact ? 29 : 40));
            toggle.setPadding(dp(6), 0, dp(6), 0);
            toggle.setFocusable(true);
            Consumer<Boolean> onChange = i == 0 ? left : right;
            toggle.setOnCheckedChangeListener((button, checked) -> onChange.accept(checked));
            row.addView(toggle, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            switches[i] = toggle;
        }
        return switches;
    }

    SeekBar addPercentSlider(int label, int format, int percent, IntConsumer onChange) {
        return addSlider(label, format, 0, 100, 5, percent, onChange);
    }

    SeekBar addSlider(int label, int format, int min, int max, int step, int initial, IntConsumer onChange) {
        TextView value = label(13);
        value.setText(getContext().getString(format, initial));
        LayoutParams valueLayout = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        valueLayout.topMargin = dp(8);
        if (!compact) addView(value, valueLayout);

        SeekBar slider = new SeekBar(getContext());
        slider.setContentDescription(getContext().getString(label));
        slider.setMax(max);
        slider.setMin(min);
        slider.setKeyProgressIncrement(step);
        slider.setProgress(initial);
        slider.setFocusableInTouchMode(true);
        ColorStateList tint = ColorStateList.valueOf(foreground);
        slider.setThumbTintList(tint);
        slider.setProgressTintList(tint);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(dp(7));
        focus.setStroke(dp(1), new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[0]},
                new int[]{foreground, 0}));
        slider.setBackground(focus);
        slider.setPadding(dp(12), 0, dp(12), 0);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(getContext().getString(format, progress));
                if (fromUser) {
                    onChange.accept(progress);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        if (!compact) {
            addView(slider, new LayoutParams(LayoutParams.MATCH_PARENT, dp(42)));
        } else {
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            int tone = foreground & 0x00ffffff;
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(0x10000000 | tone);
            surface.setCornerRadius(dp(7));
            row.setBackground(surface);
            row.setPaddingRelative(dp(7), 0, dp(2), 0);
            value.setTextSize(11);
            row.addView(value, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            row.addView(slider, new LayoutParams(0, dp(29), 1));
            valueLayout.topMargin = dp(4);
            addView(row, valueLayout);
        }
        return slider;
    }

    void focusCurrentChoice() {
        (currentChoice == null ? firstChoice : currentChoice).requestFocus();
    }

    void confirmChoice() {
        View focused = findFocus();
        if (focused != null && focused != this) {
            focused.performClick();
        }
    }

    private View[] addActionRow(int[] keys, int[] labels, Runnable[] actions, int selectedIndex, boolean choiceRow, int[] icons, int[] statuses) {
        boolean folderRow = icons != null;
        LinearLayout row = new LinearLayout(getContext());
        row.setBaselineAligned(false);
        LayoutParams rowLayout = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowLayout.topMargin = dp(compact ? 3 : 5);
        addView(row, rowLayout);
        View[] tiles = new View[labels.length];
        for (int i = 0; i < labels.length; i++) {
            LinearLayout tile = new LinearLayout(getContext());
            tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setMinimumHeight(dp(compact ? 34 : 38));
            tile.setPadding(dp(7), dp(4), dp(6), dp(4));

            GradientDrawable fill = new GradientDrawable();
            boolean selected = i == selectedIndex;
            int rgb = foreground & 0x00ffffff;
            fill.setColor(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_selected}, new int[0]},
                    new int[]{0x36000000 | rgb, (compact ? 0x16000000 : 0x10000000) | rgb}));
            tile.setSelected(selected);
            fill.setCornerRadius(dp(7));
            tile.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x30000000 | (foreground & 0x00ffffff)), fill, null));

            if (folderRow) {
                ImageView icon = new ImageView(getContext());
                icon.setImageResource(icons[i]);
                icon.setColorFilter(foreground);
                LayoutParams iconSize = new LayoutParams(dp(15), dp(15));
                iconSize.setMarginEnd(dp(4));
                tile.addView(icon, iconSize);
            } else if (keys[i] != 0) {
                if (compact) {
                    addKeyBadge(tile, keys[i], false);
                } else {
                    TextView key = label(12);
                    key.setText(keys[i]);
                    key.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    tile.addView(key, new LayoutParams(dp(32), LayoutParams.WRAP_CONTENT));
                }
            }
            if (choiceRow) {
                tile.setFocusableInTouchMode(true);
                fill.setStroke(dp(2), new ColorStateList(
                        new int[][]{new int[]{android.R.attr.state_focused}, new int[0]},
                        new int[]{foreground, 0}));
                if (firstChoice == null) {
                    firstChoice = tile;
                }
                if (selected) {
                    currentChoice = tile;
                }
            }

            TextView action = label(compact ? 11 : 12);
            action.setText(labels[i]);
            if (selected || folderRow) {
                action.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            }
            if (statuses != null && statuses[i] != 0) {
                LinearLayout copy = new LinearLayout(getContext());
                copy.setOrientation(VERTICAL);
                copy.addView(action);
                TextView status = label(11);
                status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                status.setText(statuses[i]);
                copy.addView(status);
                tile.addView(copy, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            } else {
                tile.addView(action, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            }
            if (folderRow) {
                addKeyBadge(tile, keys[i], true);
                tile.setContentDescription(getContext().getString(R.string.navigation_quick_folder_description,
                        getContext().getString(labels[i]), getContext().getString(keys[i])));
            }
            Runnable runAction = actions[i];
            tile.setOnClickListener(view -> runAction.run());

            LayoutParams cell = new LayoutParams(0,
                    statuses == null ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT, 1);
            if (i > 0) {
                cell.setMarginStart(dp(5));
            }
            row.addView(tile, cell);
            tiles[i] = tile;
        }
        return tiles;
    }

    private void addKeyBadge(LinearLayout tile, int key, boolean end) {
        TextView badge = label(10);
        badge.setText(key);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setPadding(dp(4), 0, dp(4), 0);
        badge.setMinimumWidth(dp(18));
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(5));
        fill.setColor(0x30000000 | (foreground & 0x00ffffff));
        badge.setBackground(fill);
        LayoutParams size = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(18));
        if (end) size.setMarginStart(dp(4));
        else size.setMarginEnd(dp(5));
        tile.addView(badge, size);
    }

    void setControls(Runnable outside, IntConsumer horizontal) {
        this.outside = outside;
        this.horizontal = horizontal;
        setFocusableInTouchMode(true);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE && outside != null) {
            outside.run();
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (horizontal != null && event.getActionMasked() == MotionEvent.ACTION_MOVE
                && event.isFromSource(InputDevice.SOURCE_JOYSTICK)
                && ControllerRemap.isBuiltInController(event.getDevice())) {
            for (int i = 0; i < event.getHistorySize(); i++) {
                updateHat(event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_X, i));
            }
            updateHat(event.getAxisValue(MotionEvent.AXIS_HAT_X));
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private void updateHat(float axis) {
        int direction = axis > 0.65f ? 1 : axis < -0.65f ? -1 : 0;
        if (direction == hat) return;
        hat = direction;
        if (direction != 0) horizontal.accept(direction);
    }

    private TextView label(int sp) {
        TextView view = new TextView(getContext());
        view.setTextSize(sp);
        view.setTextColor(foreground);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
