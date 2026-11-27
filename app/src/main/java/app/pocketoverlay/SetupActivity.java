package app.pocketoverlay;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Window;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;

import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

import app.pocketoverlay.OverlaySettings.Binding;

public final class SetupActivity extends Activity {
    static boolean visible;
    private static final String SELECTED_PAGE = "selected_page";
    private enum Page {
        OVERLAY(R.drawable.ic_nav_adjust),
        PERFORMANCE(R.drawable.ic_nav_performance),
        SETTINGS(R.drawable.ic_settings),
        LIGHTING(R.drawable.ic_nav_lighting),
        ABOUT(R.drawable.ic_nav_about),
        REMAP(R.drawable.ic_nav_gamepad),
        PERF_MODE(R.drawable.ic_nav_profile),
        COLOR(R.drawable.ic_nav_color);

        final int icon;

        Page(int icon) {
            this.icon = icon;
        }
    }
    private int background, sidebarColor, surface, accent, textColor, muted, selection;
    private SharedPreferences settings;
    private LinearLayout content;
    private ScrollView scroll;
    private TextView overlayTab;
    private TextView performanceTab;
    private TextView perfModeTab;
    private TextView lightingTab;
    private TextView remapTab;
    private RemapDiagram remapDiagram;
    private Bitmap remapArtwork;
    private Bitmap physicalRemapArtwork;
    private ImageView remapRearView;
    private static final int[] CONSOLE_ARTWORK = {R.drawable.rp6_silver, R.drawable.rp6_black,
            R.drawable.rp6_16_bit, R.drawable.rp6_orange, R.drawable.rp6_light_purple};
    private StickLighting lighting;
    private LinearLayout lightingControls;
    private Button homeProtection;
    private TextView settingsTab;
    private TextView aboutTab;
    private TextView colorTab;
    private TextView colorStatus;
    private DisplayColor displayColor;
    private SeekBar saturationSlider, temperatureSlider;
    private final Handler colorHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService colorWorker = Executors.newSingleThreadExecutor();
    private final Runnable applyColor = this::applyPendingColor;
    private int pendingSaturation = -1;
    private Integer pendingTemperature;
    private boolean applyingColor;
    private boolean colorScheduled;
    private PermissionCard installPermission;
    private final ExecutorService updateWorker = Executors.newSingleThreadExecutor();
    private TextView updateStatus;
    private TextView publishedNotesText;
    private Button publishedNotesButton;
    private CharSequence publishedNotes;
    private boolean loadingNotes;
    private Button checkUpdates;
    private Button downloadUpdate;
    private AppUpdate.Release availableUpdate;
    private File downloadedUpdate;
    private CharSequence updateMessage;
    private boolean updateBusy;
    private Page page;
    private boolean darkTheme;
    private AlertDialog recordingDialog;
    private final EnumMap<Binding, Button> bindingButtons = new EnumMap<>(Binding.class);
    private PermissionCard shortcutPermission;
    private PermissionCard brightnessPermission;
    private Button permissionsPrompt;
    private ControllerRemap controllerRemap;
    private final EnumMap<ControllerRemap.Source, Button> outputButtons =
            new EnumMap<>(ControllerRemap.Source.class);
    private TextView controllerMode;
    private Button gamepadTest;
    private Switch faceSwapToggle;
    private TextView faceSwapStatus;
    private final Runnable remapChanged = this::updateRemapControls;
    private Switch performanceEnabled;
    private Runnable refreshPerformancePreset;
    private Button[] performanceLayouts;
    private Button performancePin;
    private PerformanceStatus performanceStatus;
    private final SharedPreferences.OnSharedPreferenceChangeListener performanceState = (prefs, key) -> {
        if (performanceStatus != null && key != null && key.startsWith("performance_")) {
            performanceStatus.render();
        }
        if (OverlaySettings.PERFORMANCE_METRICS.equals(key) && refreshPerformancePreset != null) {
            refreshPerformancePreset.run();
        }
        if (OverlaySettings.PERFORMANCE_LAYOUT.equals(key) && performanceLayouts != null) {
            int layout = prefs.getInt(key, OverlaySettings.PERFORMANCE_LIST);
            for (int i = 0; i < performanceLayouts.length; i++) {
                performanceLayouts[i].setSelected(i == layout);
            }
        }
        if (OverlaySettings.PERFORMANCE_LOCKED.equals(key) && performancePin != null) {
            performancePin.setSelected(prefs.getBoolean(key, false));
        }
        if (OverlaySettings.PERFORMANCE_ENABLED.equals(key) && performanceEnabled != null) {
            performanceEnabled.setChecked(prefs.getBoolean(key, false));
        }
    };

    private static final class PermissionCard {
        final TextView status;
        final Button action;

        PermissionCard(TextView status, Button action) {
            this.status = status;
            this.action = action;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        settings = OverlaySettings.open(this);
        applyTheme();
        super.onCreate(state);
        LinearLayout shell = new LinearLayout(this);
        shell.setBackgroundColor(background);
        shell.setBaselineAligned(false);
        boolean compact = getResources().getConfiguration().screenWidthDp < 520;
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        shell.addView(createSidebar(compact), new LinearLayout.LayoutParams(dp(compact ? 108 : 174), -1));
        View railEdge = new View(this);
        railEdge.setBackgroundColor((muted & 0x00ffffff) | 0x26000000);
        shell.addView(railEdge, new LinearLayout.LayoutParams(dp(1), -1));
        shell.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(compact ? 14 : 24);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        showPage(state == null ? Page.OVERLAY : Page.values()[state.getInt(SELECTED_PAGE)]);
        setContentView(shell);
        settings.registerOnSharedPreferenceChangeListener(performanceState);
    }

    private void applyTheme() {
        int theme = settings.getInt(OverlaySettings.THEME, OverlaySettings.DARK);
        boolean materialYou = theme == OverlaySettings.MATERIAL_YOU;
        boolean dark = OverlaySettings.usesDarkColors(this, theme);
        darkTheme = dark;
        if (materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setTheme(dark ? R.style.PocketOverlayDynamicDarkTheme : R.style.PocketOverlayDynamicLightTheme);
            background = getColor(dark ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_10);
            sidebarColor = getColor(dark ? android.R.color.system_neutral2_800 : android.R.color.system_neutral2_100);
            surface = getColor(dark ? android.R.color.system_neutral1_800 : android.R.color.system_neutral1_50);
            accent = getColor(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_700);
            textColor = getColor(dark ? android.R.color.system_neutral1_50 : android.R.color.system_neutral1_900);
            muted = getColor(dark ? android.R.color.system_neutral2_200 : android.R.color.system_neutral2_700);
            selection = getColor(dark ? android.R.color.system_accent2_700 : android.R.color.system_accent2_200);
        } else {
            setTheme(dark ? R.style.PocketOverlayTheme : R.style.PocketOverlayLightTheme);
            background = dark ? 0xff17151d : 0xfffbf7ff;
            sidebarColor = dark ? 0xff211e29 : 0xffeee7f4;
            surface = dark ? 0xff292532 : 0xfff2ebf7;
            accent = dark ? 0xffd7c6fc : 0xff5b4179;
            textColor = dark ? 0xfff4eefb : 0xff211929;
            muted = dark ? 0xffc0b5ca : 0xff5c5066;
            selection = dark ? 0xff3c304e : 0xffe8d9f9;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt(SELECTED_PAGE, page.ordinal());
    }

    private void showPage(Page selected) {
        boolean changedTab = page != null && page != selected;
        UiMotion.reset(content);
        closeLightingPage();
        if (performanceStatus != null) {
            performanceStatus.close();
            performanceStatus = null;
        }
        page = selected;
        content.removeAllViews();
        bindingButtons.clear();
        refreshPerformancePreset = null;
        remapDiagram = null;
        remapArtwork = null;
        physicalRemapArtwork = null;
        remapRearView = null;
        outputButtons.clear();
        controllerMode = null;
        gamepadTest = null;
        faceSwapToggle = null;
        faceSwapStatus = null;
        performanceEnabled = null;
        performancePin = null;
        homeProtection = null;
        performanceLayouts = null;
        shortcutPermission = null;
        brightnessPermission = null;
        installPermission = null;
        updateStatus = null;
        publishedNotesText = null;
        publishedNotesButton = null;
        checkUpdates = null;
        downloadUpdate = null;
        permissionsPrompt = null;
        colorStatus = null;
        saturationSlider = temperatureSlider = null;
        overlayTab.setSelected(page == Page.OVERLAY);
        performanceTab.setSelected(page == Page.PERFORMANCE);
        perfModeTab.setSelected(page == Page.PERF_MODE);
        lightingTab.setSelected(page == Page.LIGHTING);
        remapTab.setSelected(page == Page.REMAP);
        settingsTab.setSelected(page == Page.SETTINGS);
        aboutTab.setSelected(page == Page.ABOUT);
        colorTab.setSelected(page == Page.COLOR);
        switch (page) {
            case OVERLAY: createOverlayPage(); break;
            case PERFORMANCE: createPerformancePage(); break;
            case PERF_MODE: createPerfModePage(); break;
            case SETTINGS: createSettingsPage(); break;
            case LIGHTING: createLightingPage(); break;
            case ABOUT: createAboutPage(); break;
            case REMAP: createRemapPage(); break;
            case COLOR: createDisplayColorPage(); break;
        }
        updatePermissions();
        scroll.scrollTo(0, 0);
        if (changedTab) UiMotion.reveal(content, 0.80f, 5, 155);
    }

    private void addPageHeading(int title) {
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(page.icon);
        icon.setImageTintList(ColorStateList.valueOf(accent));
        icon.setBackground(rounded(selection, 11));
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        TextView caption = label(title, 28, textColor, true);
        caption.setAccessibilityHeading(true);
        LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(0, -2, 1);
        labelLayout.setMarginStart(dp(12));
        heading.addView(caption, labelLayout);
        addRow(heading, 0);
    }

    private void createOverlayPage() {
        addPageHeading(R.string.meters_title);
        addText(R.string.setup_intro, 14, muted, false, 4);
        permissionsPrompt = addControl();
        permissionsPrompt.setText(R.string.permissions_prompt);
        permissionsPrompt.setOnClickListener(view -> showPage(Page.SETTINGS));
        addText(R.string.preview_section, 16, accent, true, 20);
        LinearLayout previews = new LinearLayout(this);
        previews.setOrientation(LinearLayout.VERTICAL);
        previews.setPadding(dp(8), dp(8), dp(8), dp(8));
        previews.setBackground(rounded(sidebarColor, 14));
        addRow(previews, 8);
        Runnable volumePreview = addPreview(previews, false, 15, 0.53f);
        Runnable brightnessPreview = addPreview(previews, true, 20, 0.65f);

        addText(R.string.appearance_section, 16, accent, true, 20);
        Button batteryIcon = createControl();
        batteryIcon.setText(R.string.overlay_battery_icon);
        batteryIcon.setOnClickListener(view -> showBatteryIconOptions(() -> {
            volumePreview.run();
            brightnessPreview.run();
        }));
        addRow(batteryIcon, 8);
        TileGrid appearance = new TileGrid();
        addChoice(appearance, R.string.overlay_size, OverlaySettings.SIZE, OverlaySettings.DEFAULT_SIZE,
                new int[]{75, 90, 100, 110, 125},
                getResources().getStringArray(R.array.overlay_sizes), () -> {
                    volumePreview.run();
                    brightnessPreview.run();
                });
        addDuration(appearance, R.string.display_time, OverlaySettings.DISPLAY_TIME,
                OverlaySettings.DEFAULT_DISPLAY_TIME, 250, 10000);

        addText(R.string.meters_animation, 16, accent, true, 20);
        Switch slide = addToggle(R.string.slide_animation, OverlaySettings.SLIDE);
        TileGrid animation = new TileGrid();
        Button enter = addDuration(animation, R.string.slide_in_time, OverlaySettings.SLIDE_IN,
                OverlaySettings.DEFAULT_SLIDE_IN, 0, 2000);
        Button exit = addDuration(animation, R.string.slide_out_time, OverlaySettings.SLIDE_OUT,
                OverlaySettings.DEFAULT_SLIDE_OUT, 0, 2000);
        enter.setEnabled(slide.isChecked());
        exit.setEnabled(slide.isChecked());
        slide.setOnCheckedChangeListener((button, checked) -> {
            settings.edit().putBoolean(OverlaySettings.SLIDE, checked).apply();
            enter.setEnabled(checked);
            exit.setEnabled(checked);
        });

        addText(R.string.shortcuts_section, 16, accent, true, 20);
        addText(R.string.binding_hint, 14, muted, false, 4);
        TileGrid bindings = new TileGrid();
        bindTile(addTile(bindings), Binding.VOLUME_DOWN);
        bindTile(addTile(bindings), Binding.VOLUME_UP);
        bindTile(addTile(bindings), Binding.BRIGHTNESS_DOWN);
        bindTile(addTile(bindings), Binding.BRIGHTNESS_UP);
        refreshBindings();
        addToggle(R.string.block_meter_starter, OverlaySettings.BLOCK_METER_STARTER, true);
        addText(R.string.block_meter_starter_hint, 13, muted, false, 6);
        if ("Retroid Pocket 6".equals(Build.MODEL)) {
            addText(R.string.digital_triggers_hint, 13, muted, false, 6);
        }
        Switch repeat = addToggle(R.string.hold_to_repeat, OverlaySettings.REPEAT);
        TileGrid timing = new TileGrid();
        Button delay = addChoice(timing, R.string.repeat_delay, OverlaySettings.REPEAT_DELAY,
                OverlaySettings.DEFAULT_DELAY, new int[]{500, 1000, 1500, 2000},
                getResources().getStringArray(R.array.repeat_delays), null);
        Button speed = addChoice(timing, R.string.repeat_speed, OverlaySettings.REPEAT_INTERVAL,
                OverlaySettings.DEFAULT_INTERVAL, new int[]{250, 150, 100},
                getResources().getStringArray(R.array.repeat_speeds), null);
        delay.setEnabled(repeat.isChecked());
        speed.setEnabled(repeat.isChecked());
        repeat.setOnCheckedChangeListener((button, checked) -> {
            settings.edit().putBoolean(OverlaySettings.REPEAT, checked).apply();
            delay.setEnabled(checked);
            speed.setEnabled(checked);
        });
    }

    private void createPerfModePage() {
        addPageHeading(R.string.perf_mode_page);
        if (!PerformanceProfiles.supported()) {
            addText(R.string.navigation_mode_unavailable, 14, muted, false, 4);
            return;
        }
        addText(R.string.perf_mode_description, 14, muted, false, 4);
        permissionsPrompt = createControl();
        permissionsPrompt.setText(R.string.perf_mode_permission);
        permissionsPrompt.setOnClickListener(view -> showPage(Page.SETTINGS));
        addRow(permissionsPrompt, 8);
        Button addApp = createPageButton(R.string.perf_mode_add, this::chooseProfileApp);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.addView(addApp, new LinearLayout.LayoutParams(-2, -2));
        addRow(toolbar, 12);

        List<String> apps = new ArrayList<>(PerformanceProfiles.packages(settings));
        apps.sort(Comparator.comparing(this::profileAppLabel, String.CASE_INSENSITIVE_ORDER));
        if (apps.isEmpty()) addText(R.string.perf_mode_no_profiles, 14, muted, false, 12);
        for (String app : apps) addProfileRow(app);
    }

    private void addProfileRow(String app) {
        LinearLayout card = performancePanel();
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout row = createActionRow();
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identity = new LinearLayout(this);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(profileAppIcon(app));
        identity.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView name = label(profileAppLabel(app), 14, textColor, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(8), 0, 0, 0);
        identity.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        boolean narrow = row.getOrientation() == LinearLayout.VERTICAL;
        row.addView(identity, narrow ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        for (PerformanceProfiles.Control control : PerformanceProfiles.Control.values()) {
            Button choice = createControl();
            choice.setTextSize(13);
            choice.setMinHeight(dp(42));
            choice.setPadding(dp(8), dp(4), dp(8), dp(4));
            updateAppModeLabel(choice, app, control);
            choice.setOnClickListener(view -> showAppModeMenu(choice, app, control));
            addEqual(actions, choice);
        }
        Button remove = performanceOption(R.string.perf_mode_remove, () -> {
            PerformanceProfiles.remove(settings, app);
            showPage(Page.PERF_MODE);
        });
        LinearLayout.LayoutParams removeLayout = new LinearLayout.LayoutParams(-2, -2);
        removeLayout.setMarginStart(dp(6));
        actions.addView(remove, removeLayout);
        LinearLayout.LayoutParams actionsLayout = narrow ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -2, 2.4f);
        if (narrow) actionsLayout.topMargin = dp(8);
        else actionsLayout.setMarginStart(dp(10));
        row.addView(actions, actionsLayout);
        card.addView(row);
        addRow(card, 8);
    }

    private Drawable profileAppIcon(String app) {
        try {
            return getPackageManager().getApplicationIcon(app);
        } catch (PackageManager.NameNotFoundException ignored) {
            return getDrawable(R.mipmap.ic_launcher);
        }
    }

    private String profileAppLabel(String app) {
        PackageManager manager = getPackageManager();
        try {
            return manager.getApplicationLabel(manager.getApplicationInfo(app, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return app;
        }
    }

    private void chooseProfileApp() {
        PackageManager manager = getPackageManager();
        Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, String> launchable = new TreeMap<>();
        for (ResolveInfo info : manager.queryIntentActivities(launch, 0)) {
            String app = info.activityInfo.packageName;
            if (!PerformanceProfiles.hasProfile(settings, app)) launchable.put(app, profileAppLabel(app));
        }
        List<String> apps = new ArrayList<>(launchable.keySet());
        apps.sort(Comparator.comparing(launchable::get, String.CASE_INSENSITIVE_ORDER));
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.perf_mode_add).setNegativeButton(android.R.string.cancel, null);
        if (apps.isEmpty()) {
            dialog.setMessage(R.string.perf_mode_no_apps);
        } else {
            ArrayAdapter<String> rows = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, apps) {
                @Override public View getView(int position, View recycled, ViewGroup parent) {
                    LinearLayout row;
                    if (recycled == null) {
                        row = new LinearLayout(SetupActivity.this);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setMinimumHeight(dp(50));
                        row.setPaddingRelative(dp(18), dp(6), dp(18), dp(6));
                        row.addView(new ImageView(SetupActivity.this), new LinearLayout.LayoutParams(dp(30), dp(30)));
                        TextView title = label("", 15, textColor, false);
                        title.setPaddingRelative(dp(11), 0, 0, 0);
                        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                    } else {
                        row = (LinearLayout) recycled;
                    }
                    String app = getItem(position);
                    ((ImageView) row.getChildAt(0)).setImageDrawable(profileAppIcon(app));
                    ((TextView) row.getChildAt(1)).setText(launchable.get(app));
                    return row;
                }
            };
            dialog.setAdapter(rows, (choice, index) -> {
                PerformanceProfiles.add(settings, apps.get(index));
                showPage(Page.PERF_MODE);
            });
        }
        showPocketDialog(dialog);
    }

    private void updateAppModeLabel(Button button, String app, PerformanceProfiles.Control control) {
        setChoiceLabel(button, control.title, getText(control.label(control.mode(settings, app))));
    }

    private void showAppModeMenu(Button anchor, String app, PerformanceProfiles.Control control) {
        CharSequence[] labels = new CharSequence[control.labels.length + 1];
        labels[0] = getText(R.string.perf_mode_system);
        int checked = 0;
        int current = control.mode(settings, app);
        for (int i = 0; i < control.labels.length; i++) {
            labels[i + 1] = getText(control.labels[i]);
            if (current == control.values[i]) checked = i + 1;
        }
        ListPopupWindow popup = new ListPopupWindow(this);
        popup.setAnchorView(anchor);
        popup.setModal(true);
        popup.setWidth(Math.min(dp(244), Math.max(dp(208), anchor.getWidth())));
        popup.setBackgroundDrawable(rounded(surface, 12));
        popup.setAdapter(new ArrayAdapter<CharSequence>(this, android.R.layout.simple_list_item_single_choice, labels) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView option = (TextView) super.getView(position, convertView, parent);
                option.setTextColor(textColor);
                option.setTextSize(14);
                return option;
            }
        });
        popup.setOnItemClickListener((parent, item, position, id) -> {
            control.save(settings, app, position == 0 ? -1 : control.values[position - 1]);
            updateAppModeLabel(anchor, app, control);
            popup.dismiss();
        });
        popup.show();
        ListView items = popup.getListView();
        items.setPadding(dp(4), dp(4), dp(4), dp(4));
        items.setSelector(rounded(selection, 8));
        items.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        items.setItemChecked(checked, true);
    }

    private void createPerformancePage() {
        addPageHeading(R.string.performance_page);
        addText(R.string.performance_intro, 14, muted, false, 4);
        permissionsPrompt = addControl();
        permissionsPrompt.setText(R.string.permissions_prompt);
        permissionsPrompt.setOnClickListener(view -> showPage(Page.SETTINGS));
        performanceEnabled = addToggle(R.string.performance_enable, OverlaySettings.PERFORMANCE_ENABLED, false);
        EnumMap<PerformanceMetric, CheckBox> toggles = new EnumMap<>(PerformanceMetric.class);
        addPresetControls(false, preset -> {
            settings.edit().putInt(OverlaySettings.PERFORMANCE_METRICS, preset.metrics).apply();
            toggles.forEach((metric, toggle) -> toggle.setChecked(metric == PerformanceMetric.BATTERY
                    ? (preset.metrics & PerformanceMetric.BATTERY_MASK) != 0 : metric.selected(preset.metrics)));
        });

        LinearLayout choices = new LinearLayout(this);
        addRow(choices, 12);
        performancePin = performanceButton(R.string.performance_pin);
        performancePin.setSelected(settings.getBoolean(OverlaySettings.PERFORMANCE_LOCKED, false));
        performancePin.setOnClickListener(view -> settings.edit()
                .putBoolean(OverlaySettings.PERFORMANCE_LOCKED, !view.isSelected()).apply());
        addEqual(choices, performancePin);
        int[] labels = {R.string.performance_inline, R.string.performance_list,
                R.string.performance_full_list, R.string.performance_top_grid};
        performanceLayouts = new Button[labels.length];
        int current = settings.getInt(OverlaySettings.PERFORMANCE_LAYOUT, OverlaySettings.PERFORMANCE_LIST);
        for (int i = 0; i < labels.length; i++) {
            final int style = i;
            Button button = performanceButton(labels[i]);
            button.setSelected(current == style);
            button.setOnClickListener(view ->
                    settings.edit().putInt(OverlaySettings.PERFORMANCE_LAYOUT, style).apply());
            performanceLayouts[i] = button;
            addEqual(choices, button);
        }

        addText(R.string.performance_preview, 13, muted, true, 12);
        HorizontalScrollView previewFrame = new HorizontalScrollView(this);
        TextView preview = new TextView(this);
        PerformanceHud.setup(preview);
        preview.setPadding(dp(10), dp(6), dp(10), dp(6));
        previewFrame.addView(preview, new FrameLayout.LayoutParams(-2, -2));
        addRow(previewFrame, 5);
        previewFrame.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l != oldR - oldL) {
                previewFrame.post(() -> {
                    if (performanceStatus != null && previewFrame.isAttachedToWindow()) {
                        performanceStatus.render();
                    }
                });
            }
        });

        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout dashboard = new LinearLayout(this);
        dashboard.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        dashboard.setBaselineAligned(false);
        addRow(dashboard, 12);
        LinearLayout controls = performancePanel();
        LinearLayout status = performancePanel();
        if (wide) {
            addEqual(dashboard, controls);
            addEqual(dashboard, status);
        } else {
            dashboard.addView(controls);
            LinearLayout.LayoutParams statusLayout = new LinearLayout.LayoutParams(-1, -2);
            statusLayout.topMargin = dp(8);
            dashboard.addView(status, statusLayout);
        }

        controls.addView(label(R.string.performance_metrics_section, 15, textColor, true));
        LinearLayout appearance = new LinearLayout(this);
        addEqual(appearance, performanceOption(R.string.performance_background, () -> showPerformanceSlider(true)));
        addEqual(appearance, performanceOption(R.string.performance_scale, () -> showPerformanceSlider(false)));
        LinearLayout.LayoutParams appearanceLayout = new LinearLayout.LayoutParams(-1, -2);
        appearanceLayout.topMargin = dp(6);
        controls.addView(appearance, appearanceLayout);
        LinearLayout arrangement = new LinearLayout(this);
        addEqual(arrangement, performanceOption(R.string.performance_order, this::showPerformanceOrder));
        addEqual(arrangement, performanceOption(R.string.performance_position, this::showPerformancePosition));
        LinearLayout.LayoutParams arrangementLayout = new LinearLayout.LayoutParams(-1, -2);
        arrangementLayout.topMargin = dp(6);
        controls.addView(arrangement, arrangementLayout);

        int selected = settings.getInt(OverlaySettings.PERFORMANCE_METRICS, PerformanceMetric.DEFAULT);
        LinearLayout row = null;
        int index = 0;
        PerformanceMetric[] metricOptions = {
                PerformanceMetric.FPS, PerformanceMetric.REFRESH_RATE,
                PerformanceMetric.CPU, PerformanceMetric.GPU,
                PerformanceMetric.MEMORY, PerformanceMetric.FAN, PerformanceMetric.BATTERY
        };
        for (PerformanceMetric metric : metricOptions) {
            if (index++ % 2 == 0) {
                row = new LinearLayout(this);
                controls.addView(row);
            }
            CheckBox toggle = new CheckBox(this);
            toggle.setText(metric.title);
            toggle.setTextColor(textColor);
            toggle.setTextSize(12);
            toggle.setMinHeight(dp(34));
            toggle.setMinimumHeight(dp(34));
            toggle.setChecked(metric == PerformanceMetric.BATTERY
                    ? (selected & PerformanceMetric.BATTERY_MASK) != 0 : metric.selected(selected));
            LinearLayout option = new LinearLayout(this);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.addView(toggle, new LinearLayout.LayoutParams(0, -2, 1));
            if (metric == PerformanceMetric.CPU || metric == PerformanceMetric.GPU) {
                PerformanceMetric temperature = metric == PerformanceMetric.CPU
                        ? PerformanceMetric.CPU_TEMPERATURE : PerformanceMetric.GPU_TEMPERATURE;
                addPerformanceGear(option, temperature.title,
                        () -> showComponentMetrics(metric, temperature, toggle));
            } else if (metric == PerformanceMetric.FPS) {
                addPerformanceGear(option, R.string.performance_fps_graph, this::showFpsOptions);
            } else if (metric == PerformanceMetric.BATTERY) {
                addPerformanceGear(option, R.string.performance_battery_fields, () -> showBatteryFields(toggle));
            } else if (metric == PerformanceMetric.MEMORY) {
                addPerformanceGear(option, R.string.performance_ram_format, this::showRamFormat);
            } else if (metric == PerformanceMetric.FAN) {
                addPerformanceGear(option, R.string.performance_fan_format, this::showFanFormat);
            }
            addEqual(row, option);
            toggles.put(metric, toggle);
            toggle.setOnCheckedChangeListener((button, checked) -> {
                int mask = settings.getInt(OverlaySettings.PERFORMANCE_METRICS, PerformanceMetric.DEFAULT);
                if (metric == PerformanceMetric.BATTERY) {
                    int activeFields = mask & PerformanceMetric.BATTERY_MASK;
                    if (checked && activeFields == 0) {
                        settings.edit().putInt(OverlaySettings.PERFORMANCE_METRICS,
                                mask | batteryFields(mask)).apply();
                    } else if (!checked && activeFields != 0) {
                        settings.edit().putInt(OverlaySettings.PERFORMANCE_BATTERY_FIELDS, activeFields)
                                .putInt(OverlaySettings.PERFORMANCE_METRICS, mask & ~PerformanceMetric.BATTERY_MASK).apply();
                    }
                    return;
                }
                int updated = checked ? mask | metric.bit : mask & ~metric.bit;
                if (updated != mask) {
                    settings.edit().putInt(OverlaySettings.PERFORMANCE_METRICS, updated).apply();
                }
            });
        }
        createStatusCards(status, preview);

        addText(R.string.performance_drag_hint, 13, muted, false, 10);
        addText(R.string.shortcuts_section, 16, accent, true, 16);
        TileGrid shortcuts = new TileGrid();
        bindTile(addTile(shortcuts), Binding.PERFORMANCE);
        bindTile(addTile(shortcuts), Binding.PERFORMANCE_CYCLE);
        refreshBindings();
        addText(R.string.performance_shortcut_hint, 13, muted, false, 4);
    }

    private Button performanceButton(int title) {
        Button button = createControl();
        button.setText(title);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), dp(6), dp(6), dp(6));
        button.setBackground(controlBackground(true));
        return button;
    }

    private LinearLayout performancePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        panel.setBackground(rounded(sidebarColor, 14));
        return panel;
    }

    private void addEqual(LinearLayout row, View child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() != 0) {
            params.setMarginStart(dp(6));
        }
        row.addView(child, params);
    }

    private Button performanceOption(int title, Runnable onClick) {
        Button button = performanceButton(title);
        button.setOnClickListener(view -> onClick.run());
        return button;
    }

    private void addPerformanceGear(LinearLayout row, int title, Runnable onClick) {
        ImageButton gear = new ImageButton(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setColorFilter(accent);
        gear.setContentDescription(getText(title));
        gear.setBackground(controlBackground(true));
        gear.setOnClickListener(view -> onClick.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(40), dp(40));
        params.setMarginStart(dp(4));
        params.topMargin = dp(3);
        params.bottomMargin = dp(3);
        row.addView(gear, params);
    }

    private void showComponentMetrics(PerformanceMetric metric, PerformanceMetric temperature,
            CheckBox usageToggle) {
        int mask = settings.getInt(OverlaySettings.PERFORMANCE_METRICS, PerformanceMetric.DEFAULT);
        boolean[] checked = {metric.selected(mask), temperature.selected(mask)};
        String[] captions = {getString(R.string.performance_usage), getString(R.string.performance_temperature)};
        showPocketDialog(new AlertDialog.Builder(this)
                .setTitle(metric.title)
                .setMultiChoiceItems(captions, checked, (dialog, item, active) -> checked[item] = active)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int updated = settings.getInt(OverlaySettings.PERFORMANCE_METRICS, PerformanceMetric.DEFAULT);
                    updated &= ~(metric.bit | temperature.bit);
                    if (checked[0]) updated |= metric.bit;
                    if (checked[1]) updated |= temperature.bit;
                    settings.edit().putInt(OverlaySettings.PERFORMANCE_METRICS, updated).apply();
                    usageToggle.setChecked(checked[0]);
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private int batteryFields(int metrics) {
        int active = metrics & PerformanceMetric.BATTERY_MASK;
        if (active != 0) return active;
        int saved = settings.getInt(OverlaySettings.PERFORMANCE_BATTERY_FIELDS,
                PerformanceMetric.DEFAULT & PerformanceMetric.BATTERY_MASK) & PerformanceMetric.BATTERY_MASK;
        return saved == 0 ? PerformanceMetric.BATTERY.bit : saved;
    }

    private void showBatteryFields(CheckBox battery) {
        PerformanceMetric[] fields = PerformanceMetric.BATTERY_READINGS;
        int metrics = settings.getInt(OverlaySettings.PERFORMANCE_METRICS, PerformanceMetric.DEFAULT);
        int initial = batteryFields(metrics);
        CharSequence[] labels = {getText(R.string.performance_battery_level),
                getText(R.string.metric_battery_temperature), getText(R.string.metric_power),
                getText(R.string.metric_battery_time)};
        boolean[] checked = new boolean[fields.length];
        for (int i = 0; i < fields.length; i++) checked[i] = fields[i].selected(initial);
        showPocketDialog(new AlertDialog.Builder(this).setTitle(R.string.performance_battery_fields)
                .setMultiChoiceItems(labels, checked, (dialog, index, value) -> checked[index] = value)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int mask = settings.getInt(OverlaySettings.PERFORMANCE_METRICS, PerformanceMetric.DEFAULT);
                    int selected = 0;
                    for (int i = 0; i < fields.length; i++) if (checked[i]) selected |= fields[i].bit;
                    settings.edit().putInt(OverlaySettings.PERFORMANCE_BATTERY_FIELDS, selected)
                            .putInt(OverlaySettings.PERFORMANCE_METRICS, (mask & ~PerformanceMetric.BATTERY_MASK) | selected)
                            .apply();
                    battery.setChecked(selected != 0);
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void showFpsOptions() {
        boolean graph = settings.getBoolean(OverlaySettings.PERFORMANCE_FPS_GRAPH, false);
        showPocketDialog(new AlertDialog.Builder(this).setTitle(R.string.metric_fps)
                .setMultiChoiceItems(new CharSequence[]{getText(R.string.performance_fps_graph)}, new boolean[]{graph},
                        (dialog, which, enabled) -> settings.edit()
                                .putBoolean(OverlaySettings.PERFORMANCE_FPS_GRAPH, enabled).apply())
                .setPositiveButton(android.R.string.ok, null));
    }

    private void showRamFormat() {
        int[] formats = {OverlaySettings.RAM_PERCENTAGE, OverlaySettings.RAM_USED_ONLY,
                OverlaySettings.RAM_USED_TOTAL};
        showPerformanceChoice(R.string.performance_ram_format, R.array.performance_ram_formats,
                choiceIndex(OverlaySettings.ramFormat(settings), formats),
                choice -> settings.edit().putInt(OverlaySettings.PERFORMANCE_RAM_FORMAT, formats[choice]).apply());
    }

    private void showFanFormat() {
        showPerformanceChoice(R.string.performance_fan_format, R.array.performance_fan_formats,
                OverlaySettings.fanFormat(settings),
                choice -> settings.edit().putInt(OverlaySettings.PERFORMANCE_FAN_FORMAT, choice).apply());
    }

    private void showPerformancePosition() {
        boolean dragged = settings.contains(OverlaySettings.PERFORMANCE_X) || settings.contains(OverlaySettings.PERFORMANCE_Y);
        int selected = dragged ? -1 : settings.getInt(OverlaySettings.PERFORMANCE_POSITION,
                OverlaySettings.PERFORMANCE_DEFAULT_POSITION);
        showPerformanceChoice(R.string.performance_position, R.array.performance_positions, selected,
                choice -> settings.edit().putInt(OverlaySettings.PERFORMANCE_POSITION, choice)
                        .remove(OverlaySettings.PERFORMANCE_X).remove(OverlaySettings.PERFORMANCE_Y).apply());
    }

    private void showPerformanceChoice(int title, int options, int selected, IntConsumer onChoice) {
        showPocketDialog(new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, selected, (dialog, choice) -> {
                    onChoice.accept(choice);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private AlertDialog showPocketDialog(AlertDialog.Builder builder) {
        return showPocketDialog(builder.create());
    }

    private AlertDialog showPocketDialog(AlertDialog dialog) {
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(rounded(surface, 18));
        window.getDecorView().setClipToOutline(true);
        window.setWindowAnimations(UiMotion.enabled(this) ? R.style.PocketDialogMotion : 0);
        dialog.show();
        return dialog;
    }

    private void createStatusCards(LinearLayout panel, TextView preview) {
        panel.addView(label(R.string.performance_live_status, 15, textColor, true));
        EnumMap<PerformanceMetric, TextView> values = new EnumMap<>(PerformanceMetric.class);
        PerformanceMetric[] displayed = {
                PerformanceMetric.FPS, PerformanceMetric.REFRESH_RATE,
                PerformanceMetric.CPU, PerformanceMetric.GPU, PerformanceMetric.MEMORY, PerformanceMetric.FAN
        };
        for (int index = 0; index < displayed.length; index += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setBaselineAligned(false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(6);
            panel.addView(row, params);
            addEqual(row, statusValue(displayed[index], displayed[index].title, values, true));
            addEqual(row, statusValue(displayed[index + 1], displayed[index + 1].title, values, true));
        }

        LinearLayout battery = new LinearLayout(this);
        battery.setOrientation(LinearLayout.VERTICAL);
        battery.setBackground(rounded(background, 8));
        battery.setPadding(dp(9), dp(5), dp(9), dp(5));
        battery.addView(label(R.string.metric_battery, 13,
                PerformanceHud.color(PerformanceMetric.BATTERY, darkTheme), true));
        int[] titles = {R.string.performance_battery_level, R.string.metric_battery_temperature,
                R.string.metric_power, R.string.metric_battery_time};
        for (int index = 0; index < titles.length; index += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setBaselineAligned(false);
            battery.addView(row);
            for (int cell = index; cell < index + 2; cell++) {
                addEqual(row, statusValue(PerformanceMetric.BATTERY_READINGS[cell], titles[cell], values, false));
            }
        }
        LinearLayout.LayoutParams batteryLayout = new LinearLayout.LayoutParams(-1, -2);
        batteryLayout.topMargin = dp(6);
        panel.addView(battery, batteryLayout);
        performanceStatus = new PerformanceStatus(this, values, preview, battery);
        performanceStatus.setVisible(visible);
    }

    private LinearLayout statusValue(PerformanceMetric metric, int title,
            EnumMap<PerformanceMetric, TextView> values, boolean separateTile) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(separateTile ? 9 : 0), dp(5), dp(separateTile ? 9 : 0), dp(5));
        if (separateTile) tile.setBackground(rounded(background, 8));
        tile.addView(label(title, 12, separateTile ? PerformanceHud.color(metric, darkTheme) : muted, true));
        TextView value = new TextView(this);
        value.setText("—");
        value.setTextColor(textColor);
        value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        value.setTextSize(14);
        tile.addView(value);
        values.put(metric, value);
        return tile;
    }

    private void showPerformanceSlider(boolean opacity) {
        String key = opacity ? OverlaySettings.PERFORMANCE_OPACITY : OverlaySettings.PERFORMANCE_SCALE;
        int defaultValue = opacity ? OverlaySettings.PERFORMANCE_DEFAULT_OPACITY : 100;
        int min = opacity ? 0 : OverlaySettings.PERFORMANCE_MIN_SCALE;
        int max = opacity ? 100 : OverlaySettings.PERFORMANCE_MAX_SCALE;
        LinearLayout options = performancePanel();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = label(R.string.performance_preview, 13, muted, true);
        heading.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = new TextView(this);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        value.setTextSize(15);
        value.setTextColor(accent);
        value.setPadding(dp(10), dp(5), dp(10), dp(5));
        value.setBackground(rounded(selection, 12));
        heading.addView(value);
        options.addView(heading);

        FrameLayout sample = new FrameLayout(this);
        sample.setPadding(dp(8), dp(8), dp(8), dp(8));
        sample.setBackground(rounded(background, 10));
        LinearLayout.LayoutParams sampleLayout = new LinearLayout.LayoutParams(-1, -2);
        sampleLayout.topMargin = dp(10);
        options.addView(sample, sampleLayout);
        TextView preview = new TextView(this);
        PerformanceHud.setup(preview);
        preview.setPadding(dp(10), dp(6), dp(10), dp(6));
        sample.addView(preview, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        SeekBar slider = new SeekBar(this);
        slider.setContentDescription(getString(opacity ? R.string.performance_opacity : R.string.performance_scale));
        slider.setProgressTintList(ColorStateList.valueOf(accent));
        slider.setThumbTintList(ColorStateList.valueOf(accent));
        slider.setMax(max - min);
        slider.setProgress(settings.getInt(key, defaultValue) - min);
        LinearLayout.LayoutParams sliderLayout = new LinearLayout.LayoutParams(-1, dp(46));
        sliderLayout.topMargin = dp(8);
        options.addView(slider, sliderLayout);
        LinearLayout limits = new LinearLayout(this);
        TextView low = percentLabel(min);
        limits.addView(low, new LinearLayout.LayoutParams(0, -2, 1));
        limits.addView(percentLabel(max));
        options.addView(limits);

        PerformanceStatus status = performanceStatus;
        Runnable update = () -> {
            int amount = min + slider.getProgress();
            value.setText(getString(R.string.performance_percent, amount));
            int width = sample.getWidth() - sample.getPaddingLeft() - sample.getPaddingRight();
            status.draw(preview, PerformanceMetric.CPU.bit | PerformanceMetric.BATTERY.bit,
                    OverlaySettings.PERFORMANCE_INLINE,
                    opacity ? settings.getInt(OverlaySettings.PERFORMANCE_SCALE, 100) : amount,
                    opacity ? amount : settings.getInt(OverlaySettings.PERFORMANCE_OPACITY,
                            OverlaySettings.PERFORMANCE_DEFAULT_OPACITY), width);
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { update.run(); }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        sample.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l != oldR - oldL) {
                sample.post(update);
            }
        });
        update.run();
        AlertDialog dialog = showPocketDialog(new AlertDialog.Builder(this)
                .setTitle(opacity ? R.string.performance_opacity : R.string.performance_scale)
                .setView(insetDialogContent(options))
                .setNeutralButton(R.string.performance_default, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save, (window, which) ->
                        settings.edit().putInt(key, min + slider.getProgress()).apply()));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view -> slider.setProgress(defaultValue - min));
    }

    private TextView percentLabel(int percent) {
        TextView text = new TextView(this);
        text.setTextSize(12);
        text.setTextColor(muted);
        text.setText(getString(R.string.performance_percent, percent));
        return text;
    }

    private FrameLayout insetDialogContent(View child) {
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(18), dp(8), dp(18), dp(4));
        frame.addView(child, new FrameLayout.LayoutParams(-1, -2));
        return frame;
    }

    private void showPerformanceOrder() {
        List<PerformanceGroup> groups = PerformanceGroup.order(settings);
        ScrollView scroller = new ScrollView(this);
        LinearLayout options = performancePanel();
        TextView hint = label(R.string.performance_order_hint, 13, muted, false);
        options.addView(hint);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        options.addView(rows);
        scroller.addView(options);
        renderOrder(rows, groups);
        showPocketDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.performance_order)
                .setView(insetDialogContent(scroller))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.performance_reset_order, (dialog, which) ->
                        settings.edit().remove(OverlaySettings.PERFORMANCE_ORDER).apply())
                .setPositiveButton(R.string.save, (dialog, which) -> PerformanceGroup.save(settings, groups)));
    }

    private void renderOrder(LinearLayout rows, List<PerformanceGroup> groups) {
        rows.removeAllViews();
        for (int i = 0; i < groups.size(); i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            PerformanceGroup group = groups.get(i);
            row.setBackground(rounded(background, 8));
            row.setPadding(dp(8), dp(2), dp(4), dp(2));
            TextView number = new TextView(this);
            number.setText(Integer.toString(i + 1));
            number.setTextSize(12);
            number.setGravity(Gravity.CENTER);
            number.setTextColor(accent);
            number.setBackground(rounded(selection, 12));
            row.addView(number, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView title = label(group.primary.title, 14, PerformanceHud.color(group.primary, darkTheme), true);
            LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(0, -2, 1);
            titleLayout.setMarginStart(dp(10));
            row.addView(title, titleLayout);
            for (int direction : new int[]{-1, 1}) {
                Button arrow = performanceButton(direction < 0 ? R.string.performance_move_up : R.string.performance_move_down);
                arrow.setText(direction < 0 ? "↑" : "↓");
                arrow.setContentDescription(getString(direction < 0 ? R.string.performance_move_up
                        : R.string.performance_move_down, title.getText()));
                int destination = i + direction;
                arrow.setEnabled(destination >= 0 && destination < groups.size());
                arrow.setOnClickListener(view -> {
                    Collections.swap(groups, index, destination);
                    renderOrder(rows, groups);
                    LinearLayout moved = (LinearLayout) rows.getChildAt(destination);
                    View next = moved.getChildAt(direction < 0 ? 2 : 3);
                    (next.isEnabled() ? next : moved.getChildAt(direction < 0 ? 3 : 2)).requestFocus();
                });
                arrow.setMinHeight(dp(44));
                arrow.setMinimumWidth(0);
                LinearLayout.LayoutParams arrowLayout = new LinearLayout.LayoutParams(dp(46), dp(44));
                arrowLayout.setMarginStart(dp(5));
                row.addView(arrow, arrowLayout);
            }
            LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(-1, -2);
            rowLayout.topMargin = dp(5);
            rows.addView(row, rowLayout);
        }
    }

    private void addPresetControls(boolean remap, java.util.function.Consumer<PerformancePreset> applyBuiltIn) {
        int title = remap ? R.string.remap_presets : R.string.performance_presets;
        NamedPresets presets = new NamedPresets(settings, remap);
        PerformancePreset[] builtIn = remap ? new PerformancePreset[0] : PerformancePreset.values();
        Button chooser = createControl();
        Button delete = createControl();
        delete.setText(R.string.preset_delete);
        Runnable refresh = () -> {
            String name = presets.selectedName();
            PerformancePreset current = name == null ? matchingBuiltIn(builtIn) : null;
            boolean hasSaved = !presets.names().isEmpty();
            boolean canChoose = builtIn.length > 0 || hasSaved;
            CharSequence value = name != null ? name : current != null ? getText(current.title)
                    : getText(canChoose ? R.string.preset_choose_hint : R.string.preset_none);
            setChoiceLabel(chooser, title, value);
            chooser.setEnabled(canChoose);
            delete.setEnabled(hasSaved);
        };
        chooser.setOnClickListener(view -> choosePreset(title, presets, remap, builtIn, applyBuiltIn, refresh));
        delete.setOnClickListener(view -> {
            List<String> names = presets.names();
            if (!names.isEmpty()) choosePresetToDelete(presets, names, refresh);
        });
        Button save = createPageButton(R.string.preset_save_named, () -> chooseSaveTarget(remap, presets, refresh));
        LinearLayout actions = createActionRow();
        addActionItem(actions, chooser);
        addActionItem(actions, save);
        addActionItem(actions, delete);
        addRow(actions, 12);
        if (!remap) refreshPerformancePreset = refresh;
        refresh.run();
    }

    private PerformancePreset matchingBuiltIn(PerformancePreset[] builtIn) {
        int metrics = settings.getInt(OverlaySettings.PERFORMANCE_METRICS, PerformanceMetric.DEFAULT);
        for (PerformancePreset preset : builtIn) {
            if (preset.metrics == metrics) return preset;
        }
        return null;
    }

    private void choosePreset(int title, NamedPresets presets, boolean remap, PerformancePreset[] builtIn,
            java.util.function.Consumer<PerformancePreset> applyBuiltIn, Runnable refresh) {
        List<String> names = presets.names();
        String currentName = presets.selectedName();
        PerformancePreset current = currentName == null ? matchingBuiltIn(builtIn) : null;
        List<CharSequence> choices = new ArrayList<>();
        int checked = -1;
        for (PerformancePreset preset : builtIn) {
            if (preset == current) checked = choices.size();
            choices.add(getText(preset.title));
        }
        for (String name : names) {
            if (name.equals(currentName)) checked = choices.size();
            choices.add(name);
        }
        showPocketDialog(new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(choices.toArray(new CharSequence[0]), checked, (dialog, index) -> {
                    dialog.dismiss();
                    if (index < builtIn.length) {
                        presets.select(null);
                        applyBuiltIn.accept(builtIn[index]);
                    } else {
                        applyNamedPreset(presets, names.get(index - builtIn.length), remap);
                    }
                    refresh.run();
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void chooseSaveTarget(boolean remap, NamedPresets presets, Runnable refresh) {
        if (remap && controllerRemap == null) return;
        List<String> names = presets.names();
        if (names.isEmpty()) {
            saveNamedPreset(remap, presets, refresh);
            return;
        }
        List<CharSequence> targets = new ArrayList<>();
        targets.add(getText(R.string.preset_save_new));
        for (String name : names) targets.add(getString(R.string.preset_update_target, name));
        showPocketDialog(new AlertDialog.Builder(this).setTitle(R.string.preset_save_named)
                .setItems(targets.toArray(new CharSequence[0]), (dialog, index) -> {
                    if (index == 0) {
                        saveNamedPreset(remap, presets, refresh);
                    } else {
                        String name = names.get(index - 1);
                        showPocketDialog(new AlertDialog.Builder(this)
                                .setTitle(R.string.preset_update)
                                .setMessage(getString(R.string.preset_update_confirm, name))
                                .setPositiveButton(R.string.preset_update, (confirm, button) -> {
                                    boolean updated = presets.update(name, captureCurrentPreset(remap));
                                    if (updated) refresh.run();
                                    Toast.makeText(this, updated ? R.string.preset_saved
                                            : R.string.preset_update_failed, Toast.LENGTH_SHORT).show();
                                })
                                .setNegativeButton(android.R.string.cancel, null));
                    }
                })
                .setNeutralButton(R.string.preset_rename, (dialog, index) ->
                        choosePresetToRename(presets, names, refresh))
                .setNegativeButton(android.R.string.cancel, null));
    }

    private JSONObject captureCurrentPreset(boolean remap) {
        return remap ? controllerRemap.capturePreset() : NamedPresets.captureHud(settings);
    }

    private void saveNamedPreset(boolean remap, NamedPresets presets, Runnable onSaved) {
        if (remap && controllerRemap == null) return;
        editPresetName(R.string.preset_save_named, R.string.preset_save, R.string.preset_saved, "",
                name -> presets.save(name, captureCurrentPreset(remap)), onSaved);
    }

    private void choosePresetToRename(NamedPresets presets, List<String> names, Runnable onRenamed) {
        showPocketDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.preset_rename)
                .setItems(names.toArray(new String[0]), (dialog, index) -> {
                    String original = names.get(index);
                    editPresetName(R.string.preset_rename, R.string.preset_rename_action, R.string.preset_renamed,
                            original, name -> presets.rename(original, name), onRenamed);
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void editPresetName(int title, int confirm, int success, String initialName,
            java.util.function.Predicate<String> store, Runnable onChanged) {
        EditText name = new EditText(this);
        name.setSingleLine();
        name.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        name.setHint(R.string.preset_name_hint);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(48)});
        name.setText(initialName);
        name.setSelectAllOnFocus(!initialName.isEmpty());
        FrameLayout field = new FrameLayout(this);
        field.setPadding(dp(20), 0, dp(20), 0);
        field.addView(name);
        AlertDialog dialog = showPocketDialog(new AlertDialog.Builder(this)
                .setTitle(title).setView(field)
                .setPositiveButton(confirm, null)
                .setNegativeButton(android.R.string.cancel, null));
        Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        name.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            save.performClick();
            return true;
        });
        save.setOnClickListener(view -> {
            String newName = name.getText().toString().trim();
            if (newName.isEmpty()) {
                name.setError(getString(R.string.preset_name_hint));
                return;
            }
            if (!store.test(newName)) {
                name.setError(getString(R.string.preset_name_duplicate));
                return;
            }
            dialog.dismiss();
            onChanged.run();
            Toast.makeText(this, success, Toast.LENGTH_SHORT).show();
        });
    }

    private void applyNamedPreset(NamedPresets presets, String name, boolean remap) {
        JSONObject snapshot = presets.read(name);
        if (snapshot == null || (remap && (controllerRemap == null || !controllerRemap.applyPreset(snapshot)))) {
            Toast.makeText(this, R.string.preset_apply_failed, Toast.LENGTH_SHORT).show();
        } else {
            presets.select(name);
            if (!remap) {
                NamedPresets.applyHud(settings, snapshot);
                showPage(Page.PERFORMANCE);
            }
        }
    }

    private void choosePresetToDelete(NamedPresets presets, List<String> names, Runnable onDeleted) {
        showPocketDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.preset_delete)
                .setItems(names.toArray(new String[0]), (dialog, index) -> {
                    String name = names.get(index);
                    showPocketDialog(new AlertDialog.Builder(this).setTitle(R.string.preset_delete)
                            .setMessage(name)
                            .setPositiveButton(R.string.preset_delete, (confirm, selected) -> {
                                presets.delete(name);
                                onDeleted.run();
                            })
                            .setNegativeButton(android.R.string.cancel, null));
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void updateRemapControls() {
        if (faceSwapToggle == null || controllerRemap == null) return;
        ControllerRemap.Mode mode = controllerRemap.activeMode();
        controllerMode.setText(mode == null ? R.string.remap_mode_unavailable : mode.label);
        gamepadTest.setEnabled(mode != null && mode != ControllerRemap.Mode.OFF);
        faceSwapToggle.setOnCheckedChangeListener(null);
        faceSwapToggle.setChecked(controllerRemap.swapEnabled());
        faceSwapToggle.setEnabled(controllerRemap.available());
        faceSwapStatus.setText(controllerRemap.failed() ? R.string.remap_swap_failed
                : controllerRemap.available() ? R.string.remap_swap_hint : R.string.remap_swap_connecting);
        outputButtons.forEach((source, button) -> {
            showButtonArt(button, source, controllerRemap.effectiveOutput(source));
            button.setEnabled(controllerRemap.available());
        });
        faceSwapToggle.setOnCheckedChangeListener((button, checked) -> {
            controllerRemap.setSwapEnabled(checked);
            updateRemapControls();
        });
    }

    private void showGamepadTest() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(6), dp(12), 0);
        panel.addView(label(R.string.remap_gamepad_hint, 13, muted, false));
        GamepadTestView gamepad = new GamepadTestView(this, physicalRemapArtwork, accent, textColor, this::keyLabel);
        int height = (int) (getResources().getConfiguration().screenHeightDp * 0.65f);
        panel.addView(gamepad, new LinearLayout.LayoutParams(-1, dp(height)));
        AlertDialog dialog = showPocketDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.remap_test_gamepad)
                .setView(panel)
                .setPositiveButton(android.R.string.ok, null));
        dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94f), -2);
        dialog.setOnKeyListener((popup, key, event) -> gamepad.recordKey(event));
        gamepad.requestFocus();
    }

    private void createRemapPage() {
        addPageHeading(R.string.remap_page);
        addText(R.string.remap_hint, 14, muted, false, 4);
        permissionsPrompt = addControl();
        permissionsPrompt.setText(R.string.permissions_prompt);
        permissionsPrompt.setOnClickListener(view -> showPage(Page.SETTINGS));

        if (controllerRemap == null) {
            controllerRemap = ControllerRemap.acquire(this, settings);
            if (controllerRemap != null) controllerRemap.addListener(remapChanged);
        }
        if (controllerRemap != null) {
            faceSwapToggle = createToggle(R.string.remap_swap_faces, controllerRemap.swapEnabled());
            addRow(faceSwapToggle, 12);
            faceSwapStatus = label(getText(R.string.remap_swap_connecting), 13, muted, false);
            addRow(faceSwapStatus, 4);
            addPresetControls(true, null);
        }

        RemapDiagram diagram = new RemapDiagram(this, accent);
        remapDiagram = diagram;
        Runnable showColor = () -> {
            remapArtwork = BitmapFactory.decodeResource(getResources(), consoleArtworkResource());
            refreshPhysicalRemapArtwork();
        };
        Switch caps = createToggle(R.string.remap_swapped_caps, swappedCaps());
        caps.setOnCheckedChangeListener((button, checked) -> {
            settings.edit().putBoolean(OverlaySettings.REMAP_SWAPPED_CAPS, checked).apply();
            refreshPhysicalRemapArtwork();
        });
        addRow(caps, 12);
        addText(R.string.remap_swapped_caps_hint, 13, muted, false, 4);
        Button color = createControl();
        configureChoice(color, R.string.remap_color, OverlaySettings.REMAP_COLOR, 3,
                new int[]{0, 1, 2, 3, 4}, getResources().getStringArray(R.array.rp6_colors), showColor);
        LinearLayout tools = createActionRow();
        addActionItem(tools, color);
        if (controllerRemap != null) {
            LinearLayout modeStatus = new LinearLayout(this);
            modeStatus.setOrientation(LinearLayout.VERTICAL);
            modeStatus.setPadding(dp(14), dp(9), dp(14), dp(9));
            modeStatus.setGravity(Gravity.CENTER_VERTICAL);
            modeStatus.setBackground(rounded(sidebarColor, 10));
            modeStatus.addView(label(R.string.remap_mode_title, 12, muted, false));
            controllerMode = label(R.string.remap_mode_unavailable, 14, textColor, true);
            modeStatus.addView(controllerMode);

            gamepadTest = createControl();
            setChoiceLabel(gamepadTest, R.string.remap_test_gamepad, getText(R.string.remap_test_live));
            gamepadTest.setOnClickListener(view -> showGamepadTest());
            addActionItem(tools, gamepadTest);
            addActionItem(tools, modeStatus);
        }
        addRow(tools, 12);
        showColor.run();

        for (ControllerRemap.Source source : new ControllerRemap.Source[]{
                ControllerRemap.Source.L1, ControllerRemap.Source.L2,
                ControllerRemap.Source.R2, ControllerRemap.Source.R1}) {
            diagram.addShoulder(createOutputButton(source), source.key);
        }
        ControllerRemap.Source[] left = {ControllerRemap.Source.SELECT, ControllerRemap.Source.L3,
                ControllerRemap.Source.UP, ControllerRemap.Source.LEFT, ControllerRemap.Source.RIGHT,
                ControllerRemap.Source.DOWN, ControllerRemap.Source.HOME};
        ControllerRemap.Source[] right = {ControllerRemap.Source.START, ControllerRemap.Source.X,
                ControllerRemap.Source.Y, ControllerRemap.Source.A, ControllerRemap.Source.B,
                ControllerRemap.Source.R3, ControllerRemap.Source.BACK};
        for (int index = 0; index < left.length; index++) {
            diagram.addSystemButton(createOutputButton(left[index]), true, left[index].key);
            diagram.addSystemButton(createOutputButton(right[index]), false, right[index].key);
        }

        HorizontalScrollView diagramScroll = new HorizontalScrollView(this);
        diagramScroll.setFillViewport(true);
        diagramScroll.setBackground(rounded(sidebarColor, 14));
        diagramScroll.setPadding(dp(10), dp(12), dp(10), dp(12));
        diagramScroll.addView(diagram, new FrameLayout.LayoutParams(-2, -2));
        addRow(diagramScroll, 12);

        addText(R.string.remap_rear_view, 16, accent, true, 16);
        ImageView rear = new ImageView(this);
        rear.setImageResource(R.drawable.rp6_rear);
        remapRearView = rear;
        rear.setColorFilter(new Rp6Palette(remapArtwork).rearFilter());
        rear.setContentDescription(getText(R.string.remap_rear_description));
        rear.setScaleType(ImageView.ScaleType.FIT_CENTER);
        rear.setMaxHeight(dp(156));
        rear.setAdjustViewBounds(true);
        rear.setPadding(dp(8), dp(8), dp(8), dp(8));
        rear.setBackground(rounded(sidebarColor, 14));
        addRow(rear, 8);

        TileGrid rearButtons = new TileGrid();
        rearButtons.add(createOutputButton(ControllerRemap.Source.M2));
        rearButtons.add(createOutputButton(ControllerRemap.Source.M1));
        addHomeAndBackControls();
        addText(R.string.shortcuts_section, 16, accent, true, 20);
        TileGrid shortcuts = new TileGrid();
        bindTile(addTile(shortcuts), Binding.VOLUME_DOWN);
        bindTile(addTile(shortcuts), Binding.VOLUME_UP);
        bindTile(addTile(shortcuts), Binding.BRIGHTNESS_DOWN);
        bindTile(addTile(shortcuts), Binding.BRIGHTNESS_UP);
        bindTile(addTile(shortcuts), Binding.PERFORMANCE);
        bindTile(addTile(shortcuts), Binding.PERFORMANCE_CYCLE);
        refreshBindings();
        updateRemapControls();
    }

    private LinearLayout createActionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setBaselineAligned(false);
        if (getResources().getConfiguration().screenWidthDp < 600) {
            row.setOrientation(LinearLayout.VERTICAL);
        }
        return row;
    }

    private void addActionItem(LinearLayout row, View item) {
        boolean stacked = row.getOrientation() == LinearLayout.VERTICAL;
        LinearLayout.LayoutParams params = stacked
                ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) {
            if (stacked) params.topMargin = dp(8);
            else params.setMarginStart(dp(8));
        }
        row.addView(item, params);
    }

    private boolean swappedCaps() {
        return settings.getBoolean(OverlaySettings.REMAP_SWAPPED_CAPS, false);
    }

    private int physicalCapKey(ControllerRemap.Source source) {
        return swappedCaps() ? RemapButtonArt.pairedFaceKey(source.key) : source.key;
    }

    private void refreshPhysicalRemapArtwork() {
        physicalRemapArtwork = RemapButtonArt.withPhysicalCaps(remapArtwork, swappedCaps());
        Rp6Palette palette = new Rp6Palette(remapArtwork);
        remapDiagram.setConsoleImage(physicalRemapArtwork, palette);
        if (remapRearView != null) remapRearView.setColorFilter(palette.rearFilter());
        updateRemapControls();
        // The diagram is available even when the firmware mapping service is unavailable.
        if (controllerRemap == null) {
            outputButtons.forEach((source, button) -> showButtonArt(button, source, KeyEvent.KEYCODE_UNKNOWN));
        }
        refreshBindings();
    }

    private String outputLabel(ControllerRemap.Source source) {
        if (source == ControllerRemap.Source.M1 || source == ControllerRemap.Source.M2) return source.input;
        return keyLabel(physicalCapKey(source));
    }

    private Button createOutputButton(ControllerRemap.Source source) {
        Button button = createRemapBox();
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(38));
        button.setPadding(dp(6), dp(4), dp(6), dp(4));
        outputButtons.put(source, button);
        button.setEnabled(controllerRemap != null && controllerRemap.available());
        showButtonArt(button, source, KeyEvent.KEYCODE_UNKNOWN);
        button.setOnClickListener(view -> showOutputPicker(source));
        return button;
    }

    private void showButtonArt(Button button, ControllerRemap.Source source, int output) {
        String sourceLabel = outputLabel(source);
        String outputLabel = keyLabel(output);
        boolean rear = source == ControllerRemap.Source.M1 || source == ControllerRemap.Source.M2;
        RemapButtonArt art = new RemapButtonArt(remapArtwork, physicalCapKey(source), rear, sourceLabel,
                output, outputLabel, accent);
        LayerDrawable background = new LayerDrawable(new Drawable[]{controlBackground(true), art});
        background.setPaddingMode(LayerDrawable.PADDING_MODE_STACK);
        background.setLayerSize(1, dp(output == KeyEvent.KEYCODE_UNKNOWN ? 28 : 80), dp(28));
        background.setLayerGravity(1, Gravity.CENTER);
        button.setBackground(background);
        button.setContentDescription(output == KeyEvent.KEYCODE_UNKNOWN ? sourceLabel
                : getString(R.string.remap_output_description, sourceLabel, outputLabel));
    }

    private void showOutputPicker(ControllerRemap.Source source) {
        if (controllerRemap == null || !controllerRemap.available()) return;
        int[] outputs = ControllerRemap.OUTPUTS;
        String[] labels = new String[outputs.length];
        labels[0] = getString(R.string.remap_output_default,
                keyLabel(controllerRemap.layoutOutput(source, controllerRemap.swapEnabled())));
        for (int index = 1; index < labels.length; index++) labels[index] = keyLabel(outputs[index]);
        int selected = choiceIndex(controllerRemap.output(source), outputs);
        showPocketDialog(new AlertDialog.Builder(this)
                .setTitle(getString(R.string.remap_send_button, outputLabel(source)))
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    controllerRemap.setOutput(source, outputs[which]);
                    updateRemapControls();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private Button createRemapBox() {
        Button box = createControl();
        box.setTextSize(13);
        box.setMinHeight(dp(62));
        box.setPadding(dp(8), dp(6), dp(8), dp(6));
        box.setBackground(controlBackground(true));
        return box;
    }

    private void createDisplayColorPage() {
        addPageHeading(R.string.color_page);
        if (!DisplayColor.supported()) {
            addText(R.string.color_unavailable, 14, muted, false, 8);
            return;
        }
        if (displayColor == null) displayColor = new DisplayColor();
        addText(R.string.color_intro, 14, muted, false, 4);
        LinearLayout controls = performancePanel();
        int saturation = pendingSaturation >= 0 ? pendingSaturation
                : settings.getInt(DisplayColor.SATURATION, DisplayColor.NORMAL_SATURATION);
        int temperature = pendingTemperature != null ? pendingTemperature
                : settings.getInt(DisplayColor.TEMPERATURE, DisplayColor.NEUTRAL_TEMPERATURE);
        saturationSlider = addColorSlider(controls, R.string.color_saturation, saturation, false);
        temperatureSlider = addColorSlider(controls, R.string.color_temperature, temperature, true);
        addRow(controls, 14);

        LinearLayout preview = performancePanel();
        preview.addView(label(R.string.color_preview, 15, textColor, true));
        LinearLayout swatches = new LinearLayout(this);
        swatches.setBackground(rounded(surface, 9));
        swatches.setClipToOutline(true);
        int[] colors = {0xffe15356, 0xfff17e39, 0xfff4c64e, 0xff50ac6a,
                0xff40b4c2, 0xff467fe3, 0xff925dde, 0xffdf6ba2, 0xffbababa, 0xfffafafa};
        for (int color : colors) {
            View swatch = new View(this);
            swatch.setBackgroundColor(color);
            swatches.addView(swatch, new LinearLayout.LayoutParams(0, -1, 1));
        }
        LinearLayout.LayoutParams strip = new LinearLayout.LayoutParams(-1, dp(30));
        strip.topMargin = dp(8);
        preview.addView(swatches, strip);

        Button reset = createPageButton(R.string.color_restore, this::restoreDisplayColor);
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(-2, -2);
        button.topMargin = dp(8);
        preview.addView(reset, button);
        colorStatus = label("", 13, muted, false);
        colorStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        colorStatus.setVisibility(View.GONE);
        preview.addView(colorStatus);
        addRow(preview, 10);
    }

    private SeekBar addColorSlider(LinearLayout controls, int title, int initial, boolean temperature) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label(title, 15, textColor, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView caption = label(colorValue(initial, temperature), 13, accent, true);
        header.addView(caption);
        controls.addView(header);

        SeekBar slider = new SeekBar(this);
        slider.setMax(200);
        slider.setProgress(temperature ? initial + 100 : initial);
        slider.setProgressTintList(ColorStateList.valueOf(accent));
        slider.setThumbTintList(ColorStateList.valueOf(accent));
        slider.setKeyProgressIncrement(5);
        slider.setContentDescription(getText(title));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {}
            @Override public void onProgressChanged(SeekBar view, int progress, boolean fromUser) {
                int value = temperature ? progress - 100 : progress;
                caption.setText(colorValue(value, temperature));
                if (!fromUser) return;
                if (temperature) pendingTemperature = value;
                else pendingSaturation = value;
                scheduleDisplayColor();
            }
        });
        controls.addView(slider, new LinearLayout.LayoutParams(-1, dp(38)));
        if (temperature) {
            LinearLayout ends = new LinearLayout(this);
            ends.addView(label(R.string.color_cool, 12, muted, false), new LinearLayout.LayoutParams(0, -2, 1));
            ends.addView(label(R.string.color_warm, 12, muted, false));
            controls.addView(ends);
        }
        return slider;
    }

    private String colorValue(int value, boolean temperature) {
        if (!temperature) return getString(R.string.color_percent, value);
        if (value == 0) return getString(R.string.color_neutral);
        return getString(value < 0 ? R.string.color_cool_amount : R.string.color_warm_amount, Math.abs(value));
    }

    private void restoreDisplayColor() {
        saturationSlider.setProgress(DisplayColor.NORMAL_SATURATION);
        temperatureSlider.setProgress(100);
        pendingSaturation = DisplayColor.NORMAL_SATURATION;
        pendingTemperature = DisplayColor.NEUTRAL_TEMPERATURE;
        scheduleDisplayColor();
    }

    private void scheduleDisplayColor() {
        if (!applyingColor && !colorScheduled) {
            colorScheduled = colorHandler.postDelayed(applyColor, 75);
        }
    }

    private void applyPendingColor() {
        colorScheduled = false;
        int saturation = pendingSaturation;
        Integer temperature = pendingTemperature;
        if (saturation < 0 && temperature == null) return;
        pendingSaturation = -1;
        pendingTemperature = null;
        applyingColor = true;
        colorWorker.execute(() -> {
            DisplayColor.Result saturationResult = saturation < 0 ? DisplayColor.Result.APPLIED
                    : displayColor.setSaturation(saturation);
            DisplayColor.Result temperatureResult = temperature == null ? DisplayColor.Result.APPLIED
                    : displayColor.setTemperature(temperature);
            SharedPreferences.Editor saved = settings.edit();
            if (saturation >= 0 && saturationResult == DisplayColor.Result.APPLIED) {
                saved.putInt(DisplayColor.SATURATION, saturation);
            }
            if (temperature != null && temperatureResult == DisplayColor.Result.APPLIED) {
                saved.putInt(DisplayColor.TEMPERATURE, temperature);
            }
            saved.apply();
            colorHandler.post(() -> {
                applyingColor = false;
                if (isDestroyed()) return;
                if (colorStatus != null) {
                    int message = temperatureResult == DisplayColor.Result.SYSTEM_FILTER_ACTIVE
                            ? R.string.color_filter_active
                            : saturationResult == DisplayColor.Result.FAILED || temperatureResult == DisplayColor.Result.FAILED
                                    ? R.string.color_apply_failed : R.string.color_applied;
                    colorStatus.setText(message);
                    colorStatus.setVisibility(View.VISIBLE);
                    if (saturationResult == DisplayColor.Result.FAILED && pendingSaturation < 0) {
                        saturationSlider.setProgress(settings.getInt(DisplayColor.SATURATION, DisplayColor.NORMAL_SATURATION));
                    }
                    if (temperatureResult != DisplayColor.Result.APPLIED && pendingTemperature == null) {
                        temperatureSlider.setProgress(settings.getInt(DisplayColor.TEMPERATURE,
                                DisplayColor.NEUTRAL_TEMPERATURE) + 100);
                    }
                }
                if (pendingSaturation >= 0 || pendingTemperature != null) scheduleDisplayColor();
            });
        });
    }

    private void createLightingPage() {
        addPageHeading(R.string.lighting_page);
        if (!"Retroid Pocket 6".equals(Build.MODEL)) {
            addText(R.string.navigation_mode_unavailable, 14, muted, false, 4);
            return;
        }
        addText(R.string.lighting_page_hint, 14, muted, false, 4);
        permissionsPrompt = addControl();
        permissionsPrompt.setText(R.string.permissions_prompt);
        permissionsPrompt.setOnClickListener(view -> showPage(Page.SETTINGS));
        lighting = StickLighting.acquire(this, settings, new Handler(Looper.getMainLooper()));
        lighting.refreshSettings();
        lightingControls = new LinearLayout(this);
        lightingControls.setOrientation(LinearLayout.VERTICAL);
        NavigationPanel sticks = new NavigationPanel(this, settings, R.string.navigation_stick_lighting, 0);
        NavigationPanel effects = new NavigationPanel(this, settings, R.string.lighting_effects, 0);
        sticks.setBackground(rounded(sidebarColor, 14));
        effects.setBackground(rounded(sidebarColor, 14));
        lightingControls.addView(sticks, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams effectsLayout = new LinearLayout.LayoutParams(-1, -2);
        effectsLayout.topMargin = dp(10);
        lightingControls.addView(effects, effectsLayout);
        lighting.addControls(sticks, getResources().getConfiguration().screenWidthDp >= 620, effects);
        addRow(lightingControls, 12);
    }

    private void closeLightingPage() {
        if (lighting != null) {
            lighting.release();
            lighting = null;
            lightingControls = null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        View focused = lightingControls == null ? null : lightingControls.findFocus();
        if (focused != null && event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                focused.performClick();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void addHomeAndBackControls() {
        addText(R.string.navigation_page, 16, accent, true, 20);
        addText(R.string.navigation_intro, 14, muted, false, 4);
        addToggle(R.string.navigation_enable, NavigationShortcuts.ENABLED, false);
        Button controllerHome = createControl();
        addRow(controllerHome, 8);
        updateControllerHomeLabel(controllerHome);
        controllerHome.setOnClickListener(view -> recordControllerHome(controllerHome));

        TileGrid gestures = new TileGrid();
        gestures.add(createNavigationChoices(true));
        gestures.add(createNavigationChoices(false));
        addText(R.string.navigation_tap_hint, 13, muted, false, 8);

        if (Settings.System.getInt(getContentResolver(), NavigationShortcuts.RETROID_HOME_PROTECTION, -1) != -1) {
            homeProtection = addControl();
            updateHomeProtection();
            homeProtection.setOnClickListener(view -> openRetroidSettings());
            addText(R.string.navigation_home_protection_hint, 13, muted, false, 6);
        }
    }

    private void updateControllerHomeLabel(Button button) {
        int key = ControllerHomeShortcut.key(settings);
        String value = key == KeyEvent.KEYCODE_UNKNOWN ? getString(R.string.shortcut_unassigned)
                : getString(R.string.controller_home_assignment, keyLabel(key), ControllerHomeShortcut.label(settings));
        setChoiceLabel(button, R.string.controller_home_title, value);
    }

    private void recordControllerHome(Button button) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.controller_home_title)
                .setMessage(R.string.controller_home_record)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.controller_home_clear, (popup, which) -> {
                    ControllerHomeShortcut.clear(settings);
                    updateControllerHomeLabel(button);
                })
                .create();
        dialog.setOnKeyListener((popup, key, event) -> {
            if (!ControllerHomeShortcut.canRecord(event)) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                ControllerHomeShortcut.save(settings, event);
                updateControllerHomeLabel(button);
                dialog.dismiss();
            }
            return true;
        });
        OverlaySettings.recordingButton = true;
        recordingDialog = dialog;
        dialog.setOnDismissListener(popup -> {
            OverlaySettings.recordingButton = false;
            recordingDialog = null;
        });
        showPocketDialog(dialog);
    }

    private LinearLayout createNavigationChoices(boolean home) {
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setPadding(dp(10), dp(8), dp(10), dp(10));
        choices.setBackground(rounded(sidebarColor, 14));
        choices.addView(label(getText(home ? R.string.navigation_home : R.string.navigation_back),
                15, accent, true));
        addNavigationChoice(choices, R.string.navigation_tap,
                home ? NavigationShortcuts.HOME_TAP : NavigationShortcuts.BACK_TAP,
                home ? NavigationShortcuts.Action.HOME : NavigationShortcuts.Action.BACK);
        addNavigationChoice(choices, R.string.navigation_double,
                home ? NavigationShortcuts.HOME_DOUBLE : NavigationShortcuts.BACK_DOUBLE,
                home ? NavigationShortcuts.Action.RECENTS : NavigationShortcuts.Action.NONE);
        addNavigationChoice(choices, R.string.navigation_hold,
                home ? NavigationShortcuts.HOME_HOLD : NavigationShortcuts.BACK_HOLD,
                home ? NavigationShortcuts.Action.MENU : NavigationShortcuts.Action.NONE);
        return choices;
    }

    private void openRetroidSettings() {
        Intent intent = new Intent().setClassName("com.rp.settings", "com.ro.settings.activity.MainSettingsActivity");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void updateHomeProtection() {
        if (homeProtection != null) {
            int protection = Settings.System.getInt(getContentResolver(), NavigationShortcuts.RETROID_HOME_PROTECTION, -1);
            int status = protection < 0 ? R.string.navigation_home_protection_unavailable
                    : protection == 0 ? R.string.navigation_home_protection_off : R.string.navigation_home_protection_on;
            setChoiceLabel(homeProtection, R.string.navigation_home_protection, getString(status));
        }
    }

    private void addNavigationChoice(LinearLayout choices, int title, String key, NavigationShortcuts.Action fallback) {
        NavigationShortcuts.Action[] actions = NavigationShortcuts.Action.values();
        int[] values = new int[actions.length];
        String[] labels = new String[actions.length];
        for (int i = 0; i < actions.length; i++) {
            values[i] = i;
            labels[i] = getString(actions[i].label);
        }
        Button button = createControl();
        button.setTextSize(14);
        button.setMinHeight(dp(58));
        button.setBackground(controlBackground(true));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        if (choices.getChildCount() > 0) layout.topMargin = dp(6);
        choices.addView(button, layout);
        configureChoice(button, title, key, fallback.ordinal(), values, labels, null);
    }

    private void addKeyboardSettings() {
        addText(R.string.keyboard_name, 16, accent, true, 20);
        addText(R.string.keyboard_setup, 14, muted, false, 4);
        LinearLayout actions = createActionRow();
        addActionItem(actions, createPageButton(R.string.keyboard_enable,
                () -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        addActionItem(actions, createPageButton(R.string.keyboard_choose,
                () -> getSystemService(InputMethodManager.class).showInputMethodPicker()));
        addRow(actions, 8);
        EditText sample = new EditText(this);
        sample.setHint(R.string.keyboard_try);
        sample.setSingleLine(true);
        sample.setInputType(InputType.TYPE_CLASS_TEXT);
        sample.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        sample.setTextColor(textColor);
        sample.setHintTextColor(muted);
        addRow(sample, 8);
    }

    private void createSettingsPage() {
        addPageHeading(R.string.settings_page);
        addText(R.string.settings_intro, 14, muted, false, 4);
        addText(R.string.permissions_section, 16, accent, true, 20);
        shortcutPermission = addPermission(R.string.shortcuts_section, R.string.accessibility_detail,
                R.string.accessibility_open, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        brightnessPermission = addPermission(R.string.brightness_permission, R.string.brightness_detail,
                R.string.brightness_open, new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
        addText(R.string.appearance_section, 16, accent, true, 20);
        Button theme = createControl();
        addRow(theme, 8);
        configureChoice(theme, R.string.app_theme, OverlaySettings.THEME, OverlaySettings.DARK,
                new int[]{OverlaySettings.DARK, OverlaySettings.LIGHT, OverlaySettings.MATERIAL_YOU},
                getResources().getStringArray(R.array.app_themes), this::recreate);
        addText(R.string.theme_hint, 14, muted, false, 8);
        addToggle(R.string.interface_animations, OverlaySettings.UI_MOTION, true);
        addKeyboardSettings();
        addText(R.string.updates_section, 16, accent, true, 20);
        installPermission = addPermission(R.string.update_install_permission, R.string.update_install_detail,
                R.string.update_install_allow, installerSettings());
        addPageButton(R.string.update_check, () -> {
            showPage(Page.ABOUT);
            checkForUpdate();
        });
    }

    private void createAboutPage() {
        addPageHeading(R.string.about_page);
        LinearLayout identity = new LinearLayout(this);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.mipmap.ic_launcher);
        identity.addView(appIcon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), 0, 0, 0);
        details.addView(label(R.string.app_name, 20, textColor, true));
        details.addView(label(getString(R.string.about_version, installedPackage().versionName), 13, muted, false));
        identity.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        addRow(identity, 12);
        addText(R.string.about_description, 14, muted, false, 8);
        addText(R.string.about_compatibility, 14, muted, false, 8);
        addText(R.string.about_license, 14, muted, false, 8);

        addText(R.string.updates_section, 16, accent, true, 20);
        updateStatus = label(R.string.update_idle, 14, muted, false);
        addRow(updateStatus, 4);
        LinearLayout updates = createActionRow();
        checkUpdates = createPageButton(R.string.update_check, this::checkForUpdate);
        downloadUpdate = createPageButton(R.string.update_install, this::downloadUpdate);
        addActionItem(updates, checkUpdates);
        addActionItem(updates, downloadUpdate);
        addRow(updates, 8);
        refreshUpdateControls();

        addReleaseNotes();

        addText(R.string.about_project, 16, accent, true, 20);
        LinearLayout project = createActionRow();
        addActionItem(project, createPageButton(R.string.about_source, () -> openProjectLink(AppUpdate.PROJECT)));
        addActionItem(project, createPageButton(R.string.about_github_releases,
                () -> openProjectLink(AppUpdate.PROJECT + "/releases")));
        addActionItem(project, createPageButton(R.string.about_issues,
                () -> openProjectLink(AppUpdate.PROJECT + "/issues")));
        addRow(project, 8);
    }

    private void addReleaseNotes() {
        addText(R.string.about_releases, 16, accent, true, 20);
        LinearLayout notes = performancePanel();
        notes.addView(label(R.string.about_build_notes, 15, textColor, true));
        TextView changes = label(R.string.about_build_changes, 14, muted, false);
        changes.setLineSpacing(dp(3), 1f);
        notes.addView(changes);
        publishedNotesButton = createPageButton(R.string.about_load_published_notes, this::loadPublishedNotes);
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(-2, -2);
        action.topMargin = dp(8);
        notes.addView(publishedNotesButton, action);
        publishedNotesText = label("", 14, textColor, false);
        publishedNotesText.setLineSpacing(dp(3), 1f);
        publishedNotesText.setPadding(0, dp(8), 0, 0);
        publishedNotesText.setTextIsSelectable(true);
        notes.addView(publishedNotesText);
        addRow(notes, 8);
        refreshPublishedNotes();
    }

    private void loadPublishedNotes() {
        if (loadingNotes) return;
        loadingNotes = true;
        refreshPublishedNotes();
        updateWorker.execute(() -> {
            CharSequence result;
            try {
                result = formatPublishedNotes(AppUpdate.readReleaseNotes());
            } catch (Exception exception) {
                result = getText(R.string.about_notes_failed);
            }
            CharSequence notes = result;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                loadingNotes = false;
                publishedNotes = notes;
                refreshPublishedNotes();
            });
        });
    }

    private void refreshPublishedNotes() {
        if (publishedNotesText == null) return;
        publishedNotesButton.setEnabled(!loadingNotes);
        publishedNotesText.setVisibility(loadingNotes || publishedNotes != null ? View.VISIBLE : View.GONE);
        publishedNotesText.setText(loadingNotes ? getText(R.string.about_notes_loading) : publishedNotes);
    }

    private CharSequence formatPublishedNotes(AppUpdate.ReleaseNotes release) {
        SpannableStringBuilder text = new SpannableStringBuilder(getString(R.string.about_published_tag, release.tag));
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (release.body.isEmpty()) return text.append("\n\n").append(getText(R.string.about_notes_empty));
        text.append('\n');
        for (String line : release.body.split("\\r?\\n")) {
            boolean heading = line.matches("^#{1,6}\\s+.*");
            String clean = line.replaceFirst("^#{1,6}\\s+", "")
                    .replaceFirst("^\\s*[-*]\\s+", "• ")
                    .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1")
                    .replace("**", "").replace("`", "");
            int start = text.length();
            text.append('\n').append(clean);
            if (heading) text.setSpan(new StyleSpan(Typeface.BOLD), start + 1, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private void addPageButton(int title, Runnable action) {
        addRow(createPageButton(title, action), 6);
    }

    private Button createPageButton(int title, Runnable action) {
        Button button = createControl();
        button.setText(title);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void openProjectLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
            updateMessage = getText(R.string.about_no_browser);
            refreshUpdateControls();
        }
    }

    @SuppressWarnings("deprecation")
    private PackageInfo installedPackage() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Intent installerSettings() {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.fromParts("package", getPackageName(), null));
    }

    private void refreshUpdateControls() {
        if (updateStatus == null) return;
        updateStatus.setText(updateMessage == null ? getText(R.string.update_idle) : updateMessage);
        checkUpdates.setEnabled(!updateBusy);
        downloadUpdate.setEnabled(availableUpdate != null && !updateBusy);
    }

    private void checkForUpdate() {
        if (updateBusy) return;
        availableUpdate = null;
        downloadedUpdate = null;
        updateBusy = true;
        updateMessage = getText(R.string.update_checking);
        refreshUpdateControls();
        String currentVersion = installedPackage().versionName;
        updateWorker.execute(() -> {
            try {
                AppUpdate.Release latest = AppUpdate.check(currentVersion);
                finishUpdate(() -> {
                    availableUpdate = latest;
                    updateMessage = latest == null ? getText(R.string.update_current)
                            : getString(R.string.update_available, latest.version);
                });
            } catch (Exception exception) {
                android.util.Log.w("PocketOverlay", "Update check failed", exception);
                finishUpdate(() -> updateMessage = getText(R.string.update_check_failed));
            }
        });
    }

    private void downloadUpdate() {
        if (updateBusy || availableUpdate == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            showPocketDialog(new AlertDialog.Builder(this)
                    .setTitle(R.string.update_install_permission)
                    .setMessage(R.string.update_install_prompt)
                    .setPositiveButton(R.string.update_install_allow, (dialog, which) -> {
                        try {
                            startActivity(installerSettings());
                        } catch (ActivityNotFoundException ignored) {
                            updateMessage = getText(R.string.update_install_failed);
                            refreshUpdateControls();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null));
            return;
        }
        if (downloadedUpdate != null && downloadedUpdate.isFile()) {
            launchInstaller();
            return;
        }
        downloadedUpdate = null;
        updateBusy = true;
        updateMessage = getText(R.string.update_downloading);
        refreshUpdateControls();
        AppUpdate.Release release = availableUpdate;
        File cache = getCacheDir();
        PackageManager packages = getPackageManager();
        PackageInfo installed = installedPackage();
        updateWorker.execute(() -> {
            try {
                File apk = AppUpdate.download(cache, packages, installed, release);
                finishUpdate(() -> {
                    downloadedUpdate = apk;
                    updateMessage = getString(R.string.update_ready, release.version);
                    if (visible && page == Page.ABOUT) launchInstaller();
                });
            } catch (Exception exception) {
                android.util.Log.w("PocketOverlay", "Update download failed", exception);
                finishUpdate(() -> updateMessage = getText(R.string.update_download_failed));
            }
        });
    }

    private void finishUpdate(Runnable result) {
        runOnUiThread(() -> {
            if (isDestroyed() || isFinishing()) return;
            updateBusy = false;
            result.run();
            refreshUpdateControls();
        });
    }

    private void launchInstaller() {
        Uri apk = UpdateApkProvider.uri(this);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apk, UpdateApkProvider.MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            updateMessage = getText(R.string.update_install_failed);
            refreshUpdateControls();
        }
    }

    private PermissionCard addPermission(int title, int detail, int actionText, Intent intent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(rounded(surface, 12));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(title, 15, textColor, true);
        LinearLayout.LayoutParams nameLayout = new LinearLayout.LayoutParams(0, -2, 1);
        nameLayout.setMarginEnd(dp(8));
        heading.addView(name, nameLayout);
        TextView status = label(R.string.permission_needed, 12, textColor, true);
        status.setPadding(dp(10), dp(4), dp(10), dp(4));
        heading.addView(status, new LinearLayout.LayoutParams(-2, -2));
        card.addView(heading);

        TextView description = label(detail, 13, muted, false);
        description.setPadding(0, dp(4), 0, 0);
        card.addView(description);
        Button action = new Button(this);
        action.setText(actionText);
        action.setAllCaps(false);
        action.setOnClickListener(view -> startActivity(intent));
        card.addView(action, new LinearLayout.LayoutParams(-2, -2));
        addRow(card, 8);
        return new PermissionCard(status, action);
    }

    private View createSidebar(boolean compact) {
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(10), dp(14), dp(10), dp(14));
        sidebar.setBackgroundColor(sidebarColor);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        header.setGravity(compact ? Gravity.CENTER : Gravity.START | Gravity.CENTER_VERTICAL);
        header.setPaddingRelative(compact ? 0 : dp(8), dp(4), 0, dp(4));
        header.setMinimumHeight(dp(42));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.mipmap.ic_launcher);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int markSize = dp(26);
        header.addView(mark, new LinearLayout.LayoutParams(markSize, markSize));
        TextView brand = label(R.string.app_name, 15, textColor, true);
        brand.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams brandLayout = new LinearLayout.LayoutParams(-2, -2);
        if (compact) brandLayout.topMargin = dp(3);
        else brandLayout.setMarginStart(dp(8));
        header.addView(brand, brandLayout);
        sidebar.addView(header, new LinearLayout.LayoutParams(-1, -2));
        addSidebarDivider(sidebar, 9, 7);

        overlayTab = addTab(sidebar, R.string.overlay_page, Page.OVERLAY, compact);
        performanceTab = addTab(sidebar, R.string.performance_page, Page.PERFORMANCE, compact);
        perfModeTab = addTab(sidebar, R.string.perf_mode_page, Page.PERF_MODE, compact);
        remapTab = addTab(sidebar, R.string.remap_page, Page.REMAP, compact);
        lightingTab = addTab(sidebar, R.string.lighting_page, Page.LIGHTING, compact);
        colorTab = addTab(sidebar, R.string.color_page, Page.COLOR, compact);
        sidebar.addView(new Space(this), new LinearLayout.LayoutParams(1, dp(8), 1));
        addSidebarDivider(sidebar, 0, 6);
        settingsTab = addTab(sidebar, R.string.settings_page, Page.SETTINGS, compact);
        aboutTab = addTab(sidebar, R.string.about_page, Page.ABOUT, compact);

        ScrollView sideScroll = new ScrollView(this);
        sideScroll.setFillViewport(true);
        sideScroll.setVerticalScrollBarEnabled(false);
        sideScroll.setBackgroundColor(sidebarColor);
        sideScroll.addView(sidebar);
        return sideScroll;
    }

    private void addSidebarDivider(LinearLayout sidebar, int top, int bottom) {
        View divider = new View(this);
        divider.setBackgroundColor((muted & 0x00ffffff) | 0x36000000);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(1));
        layout.setMarginStart(dp(9));
        layout.setMarginEnd(dp(9));
        layout.topMargin = dp(top);
        layout.bottomMargin = dp(bottom);
        sidebar.addView(divider, layout);
    }

    private TextView addTab(LinearLayout sidebar, int title, Page destination, boolean compact) {
        TextView tab = label(title, 14, muted, true);
        ColorStateList ink = new ColorStateList(
                new int[][]{{android.R.attr.state_selected}, new int[0]}, new int[]{accent, muted});
        tab.setTextColor(ink);
        tab.setFocusable(true);
        tab.setGravity(compact ? Gravity.CENTER : Gravity.START | Gravity.CENTER_VERTICAL);
        tab.setMinHeight(dp(compact ? 48 : 40));
        tab.setPaddingRelative(dp(compact ? 8 : 12), dp(5), dp(8), dp(5));
        Drawable icon = getDrawable(destination.icon).mutate();
        int iconSize = dp(compact ? 16 : 18);
        icon.setBounds(0, 0, iconSize, iconSize);
        tab.setCompoundDrawableTintList(ink);
        tab.setCompoundDrawablePadding(dp(compact ? 5 : 12));
        if (compact) tab.setCompoundDrawablesRelative(null, icon, null, null);
        else tab.setCompoundDrawablesRelative(icon, null, null, null);
        StateListDrawable tabBackground = new StateListDrawable();
        GradientDrawable focused = rounded(selection, 14);
        focused.setStroke(dp(2), accent);
        GradientDrawable active = rounded(selection, 14);
        active.setStroke(dp(1), (accent & 0x00ffffff) | 0x52000000);
        tabBackground.addState(new int[]{android.R.attr.state_focused}, focused);
        tabBackground.addState(new int[]{android.R.attr.state_pressed}, rounded(selection, 14));
        tabBackground.addState(new int[]{android.R.attr.state_selected}, active);
        tabBackground.addState(new int[0], rounded(android.graphics.Color.TRANSPARENT, 14));
        if (UiMotion.enabled(this)) tabBackground.setExitFadeDuration(100);
        tab.setBackground(tabBackground);
        tab.setOnClickListener(view -> {
            if (page == destination) {
                scroll.smoothScrollTo(0, 0);
            } else {
                showPage(destination);
            }
        });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(3);
        sidebar.addView(tab, layout);
        return tab;
    }

    private void showBatteryIconOptions(Runnable refreshPreview) {
        String[] keys = {OverlaySettings.OVERLAY_BATTERY_PERCENT, OverlaySettings.OVERLAY_BATTERY_FILL,
                OverlaySettings.OVERLAY_CHARGING_INDICATOR};
        CharSequence[] labels = {getText(R.string.overlay_battery_percentage),
                getText(R.string.overlay_battery_fill), getText(R.string.overlay_charging_indicator)};
        boolean[] checked = {settings.getBoolean(keys[0], false), settings.getBoolean(keys[1], true),
                settings.getBoolean(keys[2], true)};
        showPocketDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.overlay_battery_icon)
                .setMultiChoiceItems(labels, checked, (dialog, index, enabled) -> {
                    settings.edit().putBoolean(keys[index], enabled).apply();
                    refreshPreview.run();
                })
                .setPositiveButton(android.R.string.ok, null)
                .create());
    }

    private Runnable addPreview(LinearLayout previews, boolean brightness, int steps, float level) {
        FrameLayout frame = new FrameLayout(this);
        previews.addView(frame, new LinearLayout.LayoutParams(-1, dp(60)));
        OverlayView preview = new OverlayView(this);
        preview.applyTheme(settings.getInt(OverlaySettings.THEME, OverlaySettings.DARK));

        frame.addView(preview, new FrameLayout.LayoutParams(0, 0, Gravity.CENTER));
        Runnable resize = () -> {
            float density = (float) getResources().getDisplayMetrics().densityDpi
                    / OverlayView.REFERENCE_DENSITY_DPI;
            int size = settings.getInt(OverlaySettings.SIZE, OverlaySettings.DEFAULT_SIZE);
            preview.update(brightness, level, steps, getString(R.string.preview_time), 68, brightness ? OverlayView.ChargeSpeed.FAST : OverlayView.ChargeSpeed.OFF, settings);
            int width = Math.min(frame.getWidth(), Math.round(preview.designWidth() * density * size / 100f));
            int height = Math.round(width * OverlayView.HEIGHT / preview.designWidth());
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) preview.getLayoutParams();
            if (params.width != width || params.height != height) {
                params.width = width;
                params.height = height;
                preview.setLayoutParams(params);
            }
        };
        frame.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> resize.run());
        return resize;
    }

    private Switch addToggle(int title, String key) {
        return addToggle(title, key, true);
    }

    private Switch addToggle(int title, String key, boolean defaultValue) {
        Switch toggle = createToggle(title, settings.getBoolean(key, defaultValue));
        addRow(toggle, 6);
        toggle.setOnCheckedChangeListener((button, checked) ->
                settings.edit().putBoolean(key, checked).apply());
        return toggle;
    }

    private Switch createToggle(int title, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextColor(textColor);
        toggle.setTextSize(15);
        toggle.setPadding(dp(14), dp(9), dp(14), dp(9));
        toggle.setMinHeight(dp(48));
        toggle.setBackground(controlBackground());
        toggle.setChecked(checked);
        return toggle;
    }

    private Button addChoice(TileGrid grid, int title, String key, int defaultValue, int[] values,
            String[] labels, Runnable onChange) {
        Button button = addTile(grid);
        configureChoice(button, title, key, defaultValue, values, labels, onChange);
        return button;
    }

    private void configureChoice(Button button, int title, String key, int defaultValue, int[] values,
            String[] labels, Runnable onChange) {
        int selected = choiceIndex(settings.getInt(key, defaultValue), values);
        setChoiceLabel(button, title, labels[selected]);
        button.setOnClickListener(view -> showPocketDialog(new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, choiceIndex(settings.getInt(key, defaultValue), values),
                        (dialog, index) -> {
                            settings.edit().putInt(key, values[index]).apply();
                            setChoiceLabel(button, title, labels[index]);
                            dialog.dismiss();
                            if (onChange != null) {
                                onChange.run();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .create()));
    }

    private final class TileGrid {
        private final boolean wide = getResources().getConfiguration().screenWidthDp >= 620;
        private LinearLayout row;
        private boolean hasFirst;

        void add(View tile) {
            if (!wide) {
                addRow(tile, 6);
                return;
            }
            if (!hasFirst) {
                row = new LinearLayout(SetupActivity.this);
                row.setBaselineAligned(false);
                addRow(row, 8);
                row.addView(tile, new LinearLayout.LayoutParams(0, -1, 1));
                row.addView(new Space(SetupActivity.this), new LinearLayout.LayoutParams(0, 1, 1));
            } else {
                row.removeViewAt(1);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
                params.setMarginStart(dp(8));
                row.addView(tile, params);
            }
            hasFirst = !hasFirst;
        }
    }

    private Button addTile(TileGrid grid) {
        Button button = createControl();
        button.setMinHeight(dp(64));
        button.setTextSize(14);
        grid.add(button);
        return button;
    }

    private void bindTile(Button button, Binding binding) {
        bindingButtons.put(binding, button);
        button.setOnClickListener(view -> recordButton(binding));
    }

    private Button createControl() {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setTextColor(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, new int[0]}, new int[]{muted, textColor}));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(14), dp(9), dp(14), dp(9));
        button.setMinHeight(dp(48));
        button.setBackground(controlBackground());
        return button;
    }

    private Button addControl() {
        Button button = createControl();
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2);
        layout.topMargin = dp(6);
        content.addView(button, layout);
        return button;
    }

    private void setChoiceLabel(Button button, int title, CharSequence value) {
        String heading = getString(title);
        if (button.getTag() instanceof String) heading = button.getTag() + " · " + heading;
        SpannableString text = new SpannableString(TextUtils.concat(heading, "\n", value));
        int valueStart = heading.length() + 1;
        text.setSpan(new ForegroundColorSpan(muted), 0, heading.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(0.86f), 0, heading.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (value.length() > 0) {
            text.setSpan(new StyleSpan(Typeface.BOLD), valueStart, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        button.setTypeface(Typeface.DEFAULT);
        button.setLineSpacing(dp(3), 1f);
        Drawable chevron = getDrawable(R.drawable.ic_setting_chevron).mutate();
        chevron.setTint(muted);
        button.setCompoundDrawablePadding(dp(10));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, chevron, null);
        button.setText(text);
    }

    private Button addDuration(TileGrid grid, int title, String key, int defaultValue, int min, int max) {
        Button button = addTile(grid);
        setChoiceLabel(button, title, getString(R.string.milliseconds, settings.getInt(key, defaultValue)));
        button.setOnClickListener(view -> {
            EditText field = new EditText(this);
            field.setInputType(InputType.TYPE_CLASS_NUMBER);
            field.setSingleLine(true);
            field.setText(Integer.toString(settings.getInt(key, defaultValue)));
            field.setSelectAllOnFocus(true);
            FrameLayout input = new FrameLayout(this);
            input.setPadding(dp(24), dp(8), dp(24), 0);
            input.addView(field, new FrameLayout.LayoutParams(-1, -2));
            AlertDialog dialog = showPocketDialog(new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(getString(R.string.duration_range, min, max))
                    .setView(input)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save, null)
                    .create());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(save -> {
                try {
                    int time = Integer.parseInt(field.getText().toString());
                    if (time >= min && time <= max) {
                        settings.edit().putInt(key, time).apply();
                        setChoiceLabel(button, title, getString(R.string.milliseconds, time));
                        dialog.dismiss();
                        return;
                    }
                } catch (NumberFormatException ignored) {
                    // The same range message covers empty or invalid input.
                }
                field.setError(getString(R.string.duration_range, min, max));
            });
        });
        return button;
    }

    private void recordButton(Binding binding) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(binding.title)
                .setMessage(R.string.record_button)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(binding.isPerformanceShortcut() ? R.string.performance_clear_shortcut
                        : R.string.restore_button, null)
                .create();
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            private final Set<Integer> pressed = new HashSet<>();
            private int first = KeyEvent.KEYCODE_UNKNOWN;
            private int second = KeyEvent.KEYCODE_UNKNOWN;
            private boolean tooMany;
            private boolean canceled;

            @Override
            public boolean onKey(DialogInterface window, int key, KeyEvent event) {
                if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE
                        || key == KeyEvent.KEYCODE_HOME || key == KeyEvent.KEYCODE_POWER
                        || key == KeyEvent.KEYCODE_UNKNOWN) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                        && pressed.add(key)) {
                    if (first == KeyEvent.KEYCODE_UNKNOWN) {
                        first = key;
                    } else if (second == KeyEvent.KEYCODE_UNKNOWN) {
                        second = key;
                    } else {
                        tooMany = true;
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP && pressed.remove(key)) {
                    canceled |= event.isCanceled();
                    if (pressed.isEmpty()) {
                        if (tooMany) {
                            dialog.setMessage(getString(R.string.shortcut_too_many_buttons));
                        } else if (!canceled) {
                            boolean combo = second != KeyEvent.KEYCODE_UNKNOWN;
                            if (assign(dialog, binding, combo ? first : KeyEvent.KEYCODE_UNKNOWN,
                                    combo ? second : first)) {
                                dialog.dismiss();
                            }
                        }
                        first = KeyEvent.KEYCODE_UNKNOWN;
                        second = KeyEvent.KEYCODE_UNKNOWN;
                        tooMany = false;
                        canceled = false;
                    }
                }
                return true;
            }
        });
        dialog.setOnDismissListener(ignored -> {
            OverlaySettings.recordingButton = false;
            recordingDialog = null;
        });
        recordingDialog = dialog;
        OverlaySettings.recordingButton = true;
        showPocketDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            int first = binding.defaultKey == KeyEvent.KEYCODE_UNKNOWN
                    ? KeyEvent.KEYCODE_UNKNOWN : OverlaySettings.DEFAULT_MODIFIER;
            if (assign(dialog, binding, first, binding.defaultKey)) {
                dialog.dismiss();
            }
        });
    }

    private boolean assign(AlertDialog dialog, Binding binding, int first, int key) {
        Binding previous = Binding.forKeys(settings, first, key);
        if (previous != null && previous != binding) {
            dialog.setMessage(getString(R.string.shortcut_in_use, bindingLabel(first, key), getString(previous.title)));
            return false;
        }
        binding.save(settings, first, key);
        refreshBindings();
        return true;
    }

    private void refreshBindings() {
        bindingButtons.forEach(this::showBinding);
        if (remapDiagram != null) remapDiagram.invalidate();
    }

    private int consoleArtworkResource() {
        int color = settings.getInt(OverlaySettings.REMAP_COLOR, 3);
        return CONSOLE_ARTWORK[Math.max(0, Math.min(color, CONSOLE_ARTWORK.length - 1))];
    }

    private void showBinding(Binding binding, Button button) {
        int key = binding.key(settings);
        int first = binding.firstKey(settings);
        String assignment = bindingLabel(first, key);
        if (key == KeyEvent.KEYCODE_UNKNOWN) {
            setChoiceLabel(button, binding.title, assignment);
            button.setContentDescription(null);
            return;
        }
        if (remapArtwork == null) {
            remapArtwork = BitmapFactory.decodeResource(getResources(), consoleArtworkResource());
        }
        boolean combo = first != KeyEvent.KEYCODE_UNKNOWN;
        int source = combo ? first : key;
        RemapButtonArt art = new RemapButtonArt(remapArtwork, source, false, keyLabel(source),
                combo ? key : KeyEvent.KEYCODE_UNKNOWN, keyLabel(key), accent, true);
        art.setBounds(0, 0, dp(combo ? 76 : 26), dp(26));
        SpannableString pictures = new SpannableString("\uFFFC");
        pictures.setSpan(new ImageSpan(art), 0, pictures.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        setChoiceLabel(button, binding.title, pictures);
        button.setContentDescription(getString(binding.title) + ", " + assignment);
    }

    private String bindingLabel(int first, int key) {
        return first == KeyEvent.KEYCODE_UNKNOWN ? keyLabel(key)
                : getString(R.string.shortcut_combo, keyLabel(first), keyLabel(key));
    }

    private String keyLabel(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "R3";
            case KeyEvent.KEYCODE_BUTTON_MODE: return getString(R.string.controller_guide);
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_MOVE_HOME: return getString(R.string.navigation_home);
            case KeyEvent.KEYCODE_BACK: return getString(R.string.navigation_back);
            case KeyEvent.KEYCODE_DPAD_UP: return "D-pad ↑";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "D-pad ↓";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "D-pad ←";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "D-pad →";
            case KeyEvent.KEYCODE_UNKNOWN: return getString(R.string.shortcut_unassigned);
            case KeyEvent.KEYCODE_BUTTON_SELECT: return getString(R.string.button_select);
            case KeyEvent.KEYCODE_BUTTON_START: return getString(R.string.button_start);
            default:
                return KeyEvent.keyCodeToString(key).replaceFirst("^KEYCODE_(BUTTON_)?", "")
                        .replace('_', ' ');
        }
    }

    private static int choiceIndex(int value, int[] choices) {
        for (int index = 0; index < choices.length; index++) {
            if (choices[index] == value) {
                return index;
            }
        }
        return 0;
    }

    private TextView label(int text, int size, int color, boolean bold) {
        return label(getText(text), size, color, bold);
    }

    private TextView label(CharSequence text, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) {
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return label;
    }

    private void addText(int text, int size, int color, boolean bold, int top) {
        addText(getText(text), size, color, bold, top);
    }

    private void addText(CharSequence text, int size, int color, boolean bold, int top) {
        addRow(label(text, size, color, bold), top);
    }

    private void addRow(View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        content.addView(view, params);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private StateListDrawable controlBackground() {
        return controlBackground(false);
    }

    private StateListDrawable controlBackground(boolean outlineSelection) {
        return controlBackground(outlineSelection, surface);
    }

    private StateListDrawable controlBackground(boolean outlineSelection, int idleColor) {
        StateListDrawable background = new StateListDrawable();
        GradientDrawable focused = rounded(selection, 10);
        focused.setStroke(dp(2), accent);
        background.addState(new int[]{android.R.attr.state_focused}, focused);
        background.addState(new int[]{android.R.attr.state_pressed}, rounded(selection, 10));
        GradientDrawable selected = rounded(selection, 10);
        if (outlineSelection) {
            selected.setStroke(dp(1), accent);
        }
        background.addState(new int[]{android.R.attr.state_selected}, selected);
        GradientDrawable idle = rounded(idleColor, 10);
        if (outlineSelection) {
            idle.setStroke(dp(1), selection);
        }
        background.addState(new int[0], idle);
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        if (lighting != null) lighting.refreshSettings();
        if (performanceStatus != null) {
            performanceStatus.setVisible(true);
        }
        updatePermissions();
        updateHomeProtection();
        updateRemapControls();
    }

    @Override
    protected void onPause() {
        visible = false;
        if (performanceStatus != null) {
            performanceStatus.setVisible(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (controllerRemap != null) {
            controllerRemap.removeListener(remapChanged);
            controllerRemap.release();
            controllerRemap = null;
        }
        colorHandler.removeCallbacks(applyColor);
        applyPendingColor();
        colorWorker.shutdown();
        updateWorker.shutdownNow();
        closeLightingPage();
        settings.unregisterOnSharedPreferenceChangeListener(performanceState);
        if (performanceStatus != null) {
            performanceStatus.close();
        }
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        if (recordingDialog != null) {
            recordingDialog.dismiss();
        }
        super.onStop();
    }

    private void updatePermissions() {
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        ComponentName service = new ComponentName(this, ShortcutService.class);
        boolean enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK).stream().anyMatch(info ->
                service.equals(ComponentName.unflattenFromString(info.getId())));
        boolean brightnessAllowed = Settings.System.canWrite(this);
        if (page == Page.SETTINGS) {
            showPermissionStatus(shortcutPermission, enabled);
            showPermissionStatus(brightnessPermission, brightnessAllowed);
            showPermissionStatus(installPermission, getPackageManager().canRequestPackageInstalls());
        } else if (permissionsPrompt != null) {
            boolean needsPermission = page == Page.LIGHTING ? !brightnessAllowed
                    : !enabled || ((page == Page.OVERLAY || page == Page.PERF_MODE) && !brightnessAllowed);
            permissionsPrompt.setVisibility(needsPermission ? View.VISIBLE : View.GONE);
        }
    }

    private void showPermissionStatus(PermissionCard card, boolean allowed) {
        int foreground = allowed
                ? (darkTheme ? 0xffa5e7b6 : 0xff165a2e)
                : (darkTheme ? 0xffffd58e : 0xff714600);
        int fill = allowed
                ? (darkTheme ? 0xff193b28 : 0xffd5f4dd)
                : (darkTheme ? 0xff493217 : 0xffffead0);
        card.status.setText(allowed ? R.string.permission_allowed : R.string.permission_needed);
        card.status.setTextColor(foreground);
        card.status.setBackground(rounded(fill, 16));
        card.action.setVisibility(allowed ? View.GONE : View.VISIBLE);
    }
}
