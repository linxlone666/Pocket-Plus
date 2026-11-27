package app.pocketoverlay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

final class NavigationShortcuts {
    static final String RETROID_HOME_PROTECTION = "prevent_press_home_accidentally";
    static final String ENABLED = "navigation_shortcuts_enabled";
    static final String HOME_TAP = "navigation_home_tap";
    static final String HOME_DOUBLE = "navigation_home_double";
    static final String HOME_HOLD = "navigation_home_hold";
    static final String BACK_TAP = "navigation_back_tap";
    static final String BACK_DOUBLE = "navigation_back_double";
    static final String BACK_HOLD = "navigation_back_hold";

    enum Action {
        NONE(R.string.navigation_none),
        HOME(R.string.navigation_home),
        BACK(R.string.navigation_back),
        RECENTS(R.string.navigation_recents),
        MENU(R.string.navigation_menu),
        SCREENSHOT(R.string.navigation_screenshot),
        MUTE(R.string.navigation_mute),
        PERFORMANCE(R.string.performance_shortcut),
        CYCLE_LAYOUT(R.string.performance_cycle_shortcut),
        QUICK_SETTINGS(R.string.navigation_quick_settings),
        CLUSTER_TUNE(R.string.navigation_cluster_tune);

        final int label;

        Action(int label) {
            this.label = label;
        }

        static Action read(SharedPreferences settings, String key, Action fallback) {
            int value = settings.getInt(key, fallback.ordinal());
            Action[] actions = values();
            return value >= 0 && value < actions.length ? actions[value] : fallback;
        }
    }

    private final AccessibilityService service;
    private final SharedPreferences settings;
    private final Handler handler;
    private final PowerManager power;
    private final KeyguardManager keyguard;
    private final Gesture home = new Gesture();
    private final Gesture back = new Gesture();
    private final WindowManager windows;
    private final ClusterTuneControls clusterTuneControls;
    private final Runnable openRecentsPanel = this::openRecentsPanel;
    private final Set<Integer> consumedPanelKeys = new HashSet<>();
    private NavigationPanel panel;
    private String droidDeckPackage;
    private boolean recentsPanel;
    private boolean recentsPanelPending;
    private RetroidModePicker modePicker;
    private QuickFolder quickFolder;
    private Runnable[] quickOptionActions;
    private StickLighting lighting;
    private boolean lightingOpen;
    private boolean applyingMode;
    private SeekBar fanSpeedSlider;
    private int pendingFanSpeed = -1;
    private boolean writingFanSpeed;
    private final Runnable saveFanSpeed = this::saveFanSpeed;
    private boolean sendingGesture;
    private boolean openingRecent;
    private Object clusterTuneRequest;
    private long recentGestureEndedAt;
    private long recentOverviewReadyAt;
    private long recentTouchReadyAt;
    private boolean browsingRecent;
    private boolean lastRecentGestureWasBrowse;
    private boolean retriedRecentBrowse;
    private final ArrayDeque<RecentInput> recentInputs = new ArrayDeque<>();
    private final Runnable processRecentInputs = this::processRecentInputs;

    private enum QuickFolder {
        HUD(R.string.navigation_quick_group_hud, R.string.navigation_quick_folder_hud,
                R.drawable.ic_nav_performance),
        DEVICE(R.string.navigation_quick_group_device, R.string.navigation_quick_folder_device,
                R.drawable.ic_nav_adjust),
        TOOLS(R.string.navigation_quick_group_tools, R.string.navigation_quick_folder_tools,
                R.drawable.ic_settings);

        final int title;
        final int folderLabel;
        final int icon;

        QuickFolder(int title, int folderLabel, int icon) {
            this.title = title;
            this.folderLabel = folderLabel;
            this.icon = icon;
        }
    }

    private enum RecentInput { PREVIOUS, NEXT, OPEN, DISMISS, CLEAR_ALL }

    NavigationShortcuts(AccessibilityService service, SharedPreferences settings, Handler handler) {
        this.service = service;
        this.settings = settings;
        this.handler = handler;
        power = service.getSystemService(PowerManager.class);
        keyguard = service.getSystemService(KeyguardManager.class);
        windows = service.getSystemService(WindowManager.class);
        if ("Retroid Pocket 6".equals(Build.MODEL)) {
            lighting = StickLighting.acquire(service, settings, handler);
        }
        clusterTuneControls = new ClusterTuneControls(handler, this::screenSize,
                () -> ready() && panel == null, (x, y) -> gesture(x, y, x, y, 45));
    }

    void onWindowChanged(CharSequence packageName, CharSequence className) {
        if (packageName == null || className == null) {
            return;
        }
        String owner = packageName.toString();
        String windowClass = className.toString();
        if ((recentsPanel || recentsPanelPending) && isHomeActivity(owner, windowClass)) {
            closePanel();
        }
        // DroidDeck's compatibility builds keep the Activity namespace but use another app ID.
        if ("com.droiddeck.launcher".equals(owner) || "com.steamdeck.launcher".equals(owner)
                || windowClass.startsWith("com.droiddeck.launcher.")) {
            droidDeckPackage = owner;
            back.cancelPending();
        } else if (!owner.equals(droidDeckPackage)
                && !"com.android.systemui".equals(owner)
                && (!service.getPackageName().equals(owner)
                        || SetupActivity.class.getName().equals(windowClass))) {
            droidDeckPackage = null;
        }
    }

    private boolean isHomeActivity(String owner, String windowClass) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(owner);
        for (ResolveInfo result : service.getPackageManager()
                .queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)) {
            ActivityInfo activity = result.activityInfo;
            if (windowClass.equals(activity.name) || windowClass.equals(activity.targetActivity)) {
                return true;
            }
        }
        return false;
    }

    boolean passesBackToDroidDeck(KeyEvent event) {
        return droidDeckPackage != null
                && (event.getScanCode() == 158 || event.getKeyCode() == KeyEvent.KEYCODE_BACK);
    }

    boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP && consumedPanelKeys.remove(key)) {
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0
                && consumedPanelKeys.contains(key)) {
            return true;
        }
        InputDevice device = event.getDevice();
        if (device == null || device.getVendorId() != 0x2022 || (device.getProductId() != 0x3001 && device.getProductId() != 0x3002)
                || event.getScanCode() == 0) {
            return false;
        }
        // Stock RP6 firmware reports the physical Home scan code as MOVE_HOME.
        boolean isHome = event.getScanCode() == 102 || event.getKeyCode() == KeyEvent.KEYCODE_HOME;
        boolean isBack = event.getScanCode() == 158 || event.getKeyCode() == KeyEvent.KEYCODE_BACK;

        Gesture gesture = isHome ? home : back;
        if ((isHome || isBack) && gesture.down) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                gesture.release(event.isCanceled());
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && clusterTuneControls.handleKey(key, isBack)) {
            consumedPanelKeys.add(key);
            return true;
        }
        if ((panel != null || recentsPanelPending) && handlePanelKey(event, isHome, isBack)) {
            consumedPanelKeys.add(key);
            return true;
        }
        if (passesBackToDroidDeck(event)) {
            return false;
        }
        if ((!isHome && !isBack) || !ready() || panel != null || event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        if (event.getRepeatCount() != 0) {
            return false;
        }
        Action tap = Action.read(settings, isHome ? HOME_TAP : BACK_TAP, isHome ? Action.HOME : Action.BACK);
        Action doubleTap = Action.read(settings, isHome ? HOME_DOUBLE : BACK_DOUBLE,
                isHome ? Action.RECENTS : Action.NONE);
        Action hold = Action.read(settings, isHome ? HOME_HOLD : BACK_HOLD, isHome ? Action.MENU : Action.NONE);
        if (!isHome && tap == Action.BACK && doubleTap == Action.NONE && hold == Action.NONE) {
            return false;
        }
        gesture.press(tap, doubleTap, hold);
        return true;
    }

    private boolean ready() {
        return settings.getBoolean(ENABLED, false) && power.isInteractive()
                && !keyguard.isKeyguardLocked() && !OverlaySettings.recordingButton;
    }

    void clear() {
        clusterTuneRequest = null;
        clusterTuneControls.stop();
        home.reset();
        back.reset();
        closePanel();
        if (lighting != null) {
            lighting.release();
            lighting = null;
        }
        consumedPanelKeys.clear();
    }

    private final class Gesture {
        private boolean down;
        private boolean held;
        private boolean waitingForSecond;
        private boolean second;
        private Action tap = Action.NONE;
        private Action doubleTap = Action.NONE;
        private Action hold = Action.NONE;
        private final Runnable finishTap = () -> {
            waitingForSecond = false;
            run(tap);
        };
        private final Runnable finishHold = () -> {
            held = true;
            run(hold);
        };

        void press(Action tap, Action doubleTap, Action hold) {
            handler.removeCallbacks(finishTap);
            second = waitingForSecond;
            waitingForSecond = false;
            down = true;
            held = false;
            this.tap = tap;
            this.doubleTap = doubleTap;
            this.hold = hold;
            if (hold != Action.NONE) {
                handler.postDelayed(finishHold, ViewConfiguration.getLongPressTimeout());
            }
        }

        void release(boolean canceled) {
            down = false;
            handler.removeCallbacks(finishHold);
            if (canceled || held || !ready()) {
                return;
            }
            if (second) {
                run(doubleTap);
            } else if (doubleTap == Action.NONE) {
                run(tap);
            } else {
                waitingForSecond = true;
                handler.postDelayed(finishTap, ViewConfiguration.getDoubleTapTimeout());
            }
        }

        void cancelPending() {
            handler.removeCallbacks(finishTap);
            handler.removeCallbacks(finishHold);
            waitingForSecond = false;
            held = down;
        }

        void reset() {
            cancelPending();
            down = false;
        }
    }

    private void run(Action action) {
        if (!ready()) {
            return;
        }
        clusterTuneRequest = null;
        switch (action) {
            case HOME:
                openHome();
                break;
            case BACK:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                break;
            case RECENTS:
                showRecents();
                break;
            case MENU:
                showQuickActions();
                break;
            case SCREENSHOT:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
                break;
            case MUTE:
                service.getSystemService(AudioManager.class).adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                break;
            case PERFORMANCE:
                settings.edit().putBoolean(OverlaySettings.PERFORMANCE_ENABLED,
                        !settings.getBoolean(OverlaySettings.PERFORMANCE_ENABLED, false)).apply();
                break;
            case CYCLE_LAYOUT:
                OverlaySettings.cyclePerformanceLayout(settings);
                break;
            case QUICK_SETTINGS:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
                break;
            case CLUSTER_TUNE:
                openClusterTune();
                break;
            case NONE:
                break;
        }
    }

    private void openClusterTune() {
        Intent directPicker = new Intent("app.pocketoverlay.clustertune.OPEN_PROFILE_PICKER")
                .setPackage("app.pocketoverlay.clustertune")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            service.startActivity(directPicker);
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Official ClusterTune exposes its picker through the edge handle.
        }

        Object request = new Object();
        clusterTuneRequest = request;
        Point display = screenSize();
        new Thread(() -> {
            Rect handle = ClusterTuneShortcut.findHandle();
            handler.post(() -> {
                if (clusterTuneRequest != request || !ready() || panel != null || !display.equals(screenSize())) {
                    return;
                }
                clusterTuneRequest = null;
                if (handle == null || !handle.intersect(0, 0, display.x, display.y)
                        || handle.left > dp(4) || handle.right > dp(40)) {
                    Toast.makeText(service, R.string.navigation_cluster_tune_missing, Toast.LENGTH_LONG).show();
                    return;
                }
                float y = handle.exactCenterY() / display.y;
                float x = handle.exactCenterX() / display.x;
                float end = Math.min(display.x - 1, handle.centerX() + dp(96)) / (float) display.x;
                if (gesture(x, y, end, y, 180)) {
                    clusterTuneControls.start(display);
                }
            });
        }, "cluster-tune-handle").start();
    }

    private void openHome() {
        Intent launcher = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            service.startActivity(launcher);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }
    }

    private void showQuickActions() {
        quickFolder = null;
        modePicker = null;
        NavigationPanel quick = quickPanel(R.string.navigation_menu);
        QuickFolder[] folders = QuickFolder.values();
        int[] labels = new int[folders.length];
        int[] icons = new int[folders.length];
        quickOptionActions = new Runnable[folders.length];
        for (int i = 0; i < folders.length; i++) {
            QuickFolder folder = folders[i];
            labels[i] = folder.folderLabel;
            icons[i] = folder.icon;
            quickOptionActions[i] = () -> showQuickFolder(folder);
        }
        quick.setBackAction(R.string.navigation_quick_close, this::closePanel);
        quick.addFolderRow(ACTION_KEY_LABELS, labels, icons, quickOptionActions);
        presentQuickPanel(quick);
        quick.requestFocus();
    }

    private void showQuickFolder(QuickFolder folder) {
        quickFolder = folder;
        modePicker = null;
        NavigationPanel quick = quickPanel(folder.title);
        quick.setBackAction(R.string.navigation_back, this::showQuickActions);
        int[] labels;
        int[] statuses;
        switch (folder) {
            case HUD:
                boolean pinned = settings.getBoolean(OverlaySettings.PERFORMANCE_LOCKED, false);
                labels = new int[]{R.string.navigation_quick_hud,
                        pinned ? R.string.navigation_quick_unpin : R.string.navigation_quick_pin,
                        R.string.navigation_quick_cycle};
                statuses = new int[]{onOffLabel(settings.getBoolean(OverlaySettings.PERFORMANCE_ENABLED, false)),
                        pinned ? R.string.navigation_quick_pinned : R.string.navigation_quick_movable,
                        currentHudLayoutLabel()};
                quickOptionActions = new Runnable[]{() -> selectQuick(Action.PERFORMANCE), this::toggleHudPin,
                        () -> selectQuick(Action.CYCLE_LAYOUT)};
                break;
            case DEVICE:
                labels = new int[]{R.string.navigation_fan_mode, R.string.navigation_performance_mode,
                        R.string.navigation_stick_lighting};
                if (lighting != null) lighting.refreshSettings();
                statuses = new int[]{currentModeLabel(RetroidModePicker.FAN),
                        currentModeLabel(RetroidModePicker.PERFORMANCE),
                        lighting == null ? 0 : onOffLabel(lighting.isOn())};
                quickOptionActions = new Runnable[]{() -> showModePicker(RetroidModePicker.FAN),
                        () -> showModePicker(RetroidModePicker.PERFORMANCE), this::showStickLighting};
                break;
            case TOOLS:
                labels = new int[]{R.string.navigation_screenshot, R.string.navigation_quick_mute,
                        R.string.navigation_quick_settings};
                AudioManager audio = service.getSystemService(AudioManager.class);
                statuses = new int[]{0, audio.isStreamMute(AudioManager.STREAM_MUSIC)
                        ? R.string.navigation_quick_muted : R.string.navigation_quick_unmuted, 0};
                quickOptionActions = new Runnable[]{() -> selectQuick(Action.SCREENSHOT),
                        () -> selectQuick(Action.MUTE), () -> selectQuick(Action.QUICK_SETTINGS)};
                break;
            default:
                throw new AssertionError(folder);
        }
        quick.addStatusChoiceRow(ACTION_KEY_LABELS, labels, statuses, quickOptionActions);
        presentQuickPanel(quick);
        quick.requestFocus();
    }

    private int onOffLabel(boolean on) {
        return on ? R.string.navigation_quick_on : R.string.navigation_fan_off;
    }

    private int currentHudLayoutLabel() {
        switch (settings.getInt(OverlaySettings.PERFORMANCE_LAYOUT, OverlaySettings.PERFORMANCE_LIST)) {
            case OverlaySettings.PERFORMANCE_INLINE: return R.string.performance_inline;
            case OverlaySettings.PERFORMANCE_FULL_LIST: return R.string.performance_full_list;
            case OverlaySettings.PERFORMANCE_TOP_GRID: return R.string.performance_top_grid;
            default: return R.string.performance_list;
        }
    }

    private int currentMode(RetroidModePicker picker) {
        return Settings.System.getInt(service.getContentResolver(), picker.setting, -1);
    }

    private int currentModeLabel(RetroidModePicker picker) {
        int value = currentMode(picker);
        for (int i = 0; i < picker.values.length; i++) {
            if (picker.values[i] == value) return picker.labels[i];
        }
        return R.string.navigation_mode_unknown;
    }

    private NavigationPanel quickPanel(int title) {
        lightingOpen = false;
        fanSpeedSlider = null;
        if (panel == null) {
            return new NavigationPanel(service, settings, title, 0, true);
        }
        panel.setContent(title, 0);
        return panel;
    }

    private void presentQuickPanel(NavigationPanel content) {
        if (panel == null) {
            content.setControls(this::closeOnOutsideTouch, null);
            showPanel(content, false);
        } else {
            WindowManager.LayoutParams layout = (WindowManager.LayoutParams) content.getLayoutParams();
            int width = panelWidth(false);
            if (layout.width != width) {
                layout.width = width;
                windows.updateViewLayout(content, layout);
            }
            content.animateContentChange();
        }
    }

    private static final int[] ACTION_KEYS = {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1};
    private static final int[] ACTION_KEY_LABELS = {R.string.navigation_key_a, R.string.navigation_key_x,
            R.string.navigation_key_y, R.string.navigation_key_l1, R.string.navigation_key_r1};

    private static final int CUSTOM_FAN_MODE = 6;
    private static final int SMART_FAN_MODE = 4;
    private static final String FAN_CURVE_ACTION = "action_fan_temp_control_curve_config";
    private static final String FAN_SPEED_SETTING = "fan_speed";

    private enum RetroidModePicker {
        FAN(R.string.navigation_fan_mode, PerformanceProfiles.Control.FAN),
        PERFORMANCE(R.string.navigation_performance_mode, PerformanceProfiles.Control.PERFORMANCE);

        final String setting;
        final int title;
        final int[] values;
        final int[] labels;

        RetroidModePicker(int title, PerformanceProfiles.Control control) {
            this.setting = control.setting;
            this.title = title;
            this.values = control.values;
            this.labels = control.labels;
        }
    }

    private void toggleHudPin() {
        boolean pinned = settings.getBoolean(OverlaySettings.PERFORMANCE_LOCKED, false);
        settings.edit().putBoolean(OverlaySettings.PERFORMANCE_LOCKED, !pinned).apply();
        showQuickFolder(QuickFolder.HUD);
    }

    private void showModePicker(RetroidModePicker picker) {
        if (!"Retroid Pocket 6".equals(Build.MODEL)) {
            Toast.makeText(service, R.string.navigation_mode_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        updateModePicker(picker, currentMode(picker));
    }

    private void updateModePicker(RetroidModePicker picker, int current) {
        boolean customFan = picker == RetroidModePicker.FAN && current == CUSTOM_FAN_MODE;
        NavigationPanel choices = quickPanel(picker.title);
        choices.setBackAction(R.string.navigation_back, this::returnFromQuickPicker);
        for (int start = 0; start < picker.values.length; start += 3) {
            int count = Math.min(3, picker.values.length - start);
            int[] keys = new int[count];
            int[] labels = new int[count];
            Runnable[] actions = new Runnable[count];
            int selected = -1;
            for (int i = 0; i < count; i++) {
                int index = start + i;
                keys[i] = ACTION_KEY_LABELS[index];
                labels[i] = picker.labels[index];
                actions[i] = () -> selectMode(picker, index);
                if (picker.values[index] == current) {
                    selected = i;
                }
            }
            choices.addChoiceRow(keys, labels, actions, selected);
        }
        if (picker == RetroidModePicker.FAN && current == SMART_FAN_MODE) {
            choices.addChoiceRow(new int[]{R.string.navigation_key_start},
                    new int[]{R.string.navigation_edit_fan_curve}, new Runnable[]{this::openSmartFanCurve}, -1);
        }
        if (customFan) {
            int speed = pendingFanSpeed >= 0 ? pendingFanSpeed : readFanSpeed();
            fanSpeedSlider = choices.addPercentSlider(R.string.navigation_fan_speed,
                    R.string.navigation_fan_speed_value, speed, this::queueFanSpeed);
        }
        presentQuickPanel(choices);
        modePicker = picker;
        if (fanSpeedSlider != null) {
            fanSpeedSlider.requestFocus();
        } else {
            choices.focusCurrentChoice();
        }
    }

    private void openSmartFanCurve() {
        if (currentMode(RetroidModePicker.FAN) != SMART_FAN_MODE) {
            showModePicker(RetroidModePicker.FAN);
            return;
        }
        Intent editor = new Intent(FAN_CURVE_ACTION).setPackage("com.rp.settings")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            service.startActivity(editor);
            closePanel();
        } catch (ActivityNotFoundException | SecurityException ignored) {
            Toast.makeText(service, R.string.navigation_fan_curve_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void selectMode(RetroidModePicker picker, int index) {
        if (applyingMode) {
            return;
        }
        if (!Settings.System.canWrite(service)) {
            Toast.makeText(service, R.string.navigation_mode_permission, Toast.LENGTH_SHORT).show();
            return;
        }
        int value = picker.values[index];
        NavigationPanel source = panel;
        applyingMode = true;
        new Thread(() -> {
            writeRetroidSetting(picker.setting, value);
            // Retroid can adjust the requested mode after its settings observers run.
            handler.postDelayed(() -> {
                applyingMode = false;
                if (panel == source && modePicker == picker) {
                    int current = currentMode(picker);
                    updateModePicker(picker, current);
                    if (current != value) {
                        Toast.makeText(service, R.string.navigation_mode_write_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            }, 250);
        }, "retroid-mode").start();
    }

    private int readFanSpeed() {
        int saved = Settings.System.getInt(service.getContentResolver(), FAN_SPEED_SETTING, 100);
        return Math.max(0, Math.min(100, saved));
    }

    private void queueFanSpeed(int speed) {
        pendingFanSpeed = speed;
        handler.removeCallbacks(saveFanSpeed);
        handler.postDelayed(saveFanSpeed, 180);
    }

    private void saveFanSpeed() {
        if (writingFanSpeed || pendingFanSpeed < 0) {
            return;
        }
        int speed = pendingFanSpeed;
        pendingFanSpeed = -1;
        if (!Settings.System.canWrite(service)) {
            if (fanSpeedSlider != null) {
                fanSpeedSlider.setProgress(readFanSpeed());
            }
            Toast.makeText(service, R.string.navigation_mode_permission, Toast.LENGTH_SHORT).show();
            return;
        }
        writingFanSpeed = true;
        new Thread(() -> {
            writeRetroidSetting(FAN_SPEED_SETTING, speed);
            handler.post(() -> {
                writingFanSpeed = false;
                if (pendingFanSpeed >= 0) {
                    handler.removeCallbacks(saveFanSpeed);
                    handler.post(saveFanSpeed);
                } else if (fanSpeedSlider != null) {
                    int saved = readFanSpeed();
                    fanSpeedSlider.setProgress(saved);
                    if (saved != speed) {
                        Toast.makeText(service, R.string.navigation_fan_speed_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }, "retroid-fan-speed").start();
    }

    private void writeRetroidSetting(String setting, int value) {
        new RetroidFirmware().writeSystemSetting(service, setting, value);
    }

    private void showStickLighting() {
        if (!"Retroid Pocket 6".equals(Build.MODEL)) {
            Toast.makeText(service, R.string.navigation_mode_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        NavigationPanel lights = quickPanel(R.string.navigation_stick_lighting);
        modePicker = null;
        if (lighting == null) lighting = StickLighting.acquire(service, settings, handler);
        lighting.refreshSettings();
        LightingColorWheel wheel = lighting.addControls(lights,
                service.getResources().getConfiguration().screenWidthDp >= 560);
        lights.setBackAction(R.string.navigation_back, this::returnFromQuickPicker);
        lightingOpen = true;
        presentQuickPanel(lights);
        wheel.requestFocus();
    }

    private void selectQuick(Action action) {
        if (action == Action.PERFORMANCE || action == Action.CYCLE_LAYOUT) {
            run(action);
            showQuickFolder(QuickFolder.HUD);
            return;
        }
        closePanel();
        handler.postDelayed(() -> run(action), action == Action.SCREENSHOT ? 250 : 70);
    }

    private void showRecents() {
        closePanel();
        if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)) {
            recentsPanelPending = true;
            handler.postDelayed(openRecentsPanel, 280);
        }
    }

    private void openRecentsPanel() {
        recentsPanelPending = false;
        if (!ready() || panel != null) {
            recentInputs.clear();
            return;
        }
        NavigationPanel recent = new NavigationPanel(service, settings, R.string.navigation_recents, 0, true);
        recent.setBackAction(R.string.navigation_home, this::exitRecentsToHome);
        if (panelWidth(true) >= dp(430)) {
            recent.addActionRow(new int[]{R.string.navigation_key_left, R.string.navigation_key_right,
                            R.string.navigation_key_a, R.string.navigation_key_y, R.string.navigation_key_select},
                    new int[]{R.string.navigation_previous, R.string.navigation_next, R.string.navigation_open_app,
                            R.string.navigation_dismiss_app, R.string.navigation_clear_all},
                    new Runnable[]{() -> queueRecentTouch(RecentInput.PREVIOUS), () -> queueRecentTouch(RecentInput.NEXT),
                            () -> queueRecentTouch(RecentInput.OPEN),
                            () -> queueRecentTouch(RecentInput.DISMISS), () -> queueRecentTouch(RecentInput.CLEAR_ALL)});
        } else {
            recent.addActionRow(new int[]{R.string.navigation_key_left, R.string.navigation_key_right,
                            R.string.navigation_key_a},
                    new int[]{R.string.navigation_previous, R.string.navigation_next, R.string.navigation_open_app},
                    new Runnable[]{() -> queueRecentTouch(RecentInput.PREVIOUS), () -> queueRecentTouch(RecentInput.NEXT),
                            () -> queueRecentTouch(RecentInput.OPEN)});
            recent.addActionRow(new int[]{R.string.navigation_key_y, R.string.navigation_key_select},
                    new int[]{R.string.navigation_dismiss_app, R.string.navigation_clear_all},
                    new Runnable[]{() -> queueRecentTouch(RecentInput.DISMISS), () -> queueRecentTouch(RecentInput.CLEAR_ALL)});
        }
        recent.setControls(this::closeOnOutsideTouch, this::browseRecents);
        showPanel(recent, true);
        recentOverviewReadyAt = SystemClock.uptimeMillis() + 140;
        processRecentInputs();
    }

    private void returnFromQuickPicker() {
        if (quickFolder == null) showQuickActions();
        else showQuickFolder(quickFolder);
    }

    private int panelWidth(boolean recents) {
        int preferred = recents ? 448 : lightingOpen ? 485 : 410;
        return Math.min(screenSize().x - dp(8) * 2, dp(preferred));
    }

    private boolean hasDpadAxis() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (ControllerRemap.isBuiltInController(device)
                    && device.getMotionRange(MotionEvent.AXIS_HAT_X, InputDevice.SOURCE_JOYSTICK) != null) {
                // Android delivers D-pad axis motion to the focused window.
                return true;
            }
        }
        return false;
    }

    private void showPanel(NavigationPanel next, boolean recents) {
        int margin = dp(8);
        int width = panelWidth(recents);
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(width,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        if (recents && !hasDpadAxis()) {
            layout.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        layout.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        layout.y = margin;
        if (UiMotion.enabled(service)) layout.windowAnimations = R.style.PocketPanelMotion;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            layout.setFitInsetsTypes(WindowInsets.Type.displayCutout());
        }
        windows.addView(next, layout);
        panel = next;
        recentsPanel = recents;
        next.requestFocus();
    }

    private void closeOnOutsideTouch() {
        if (!sendingGesture) {
            closePanel();
        }
    }

    private void closePanel() {
        handler.removeCallbacks(openRecentsPanel);
        recentsPanelPending = false;
        handler.removeCallbacks(processRecentInputs);
        recentInputs.clear();
        recentGestureEndedAt = 0;
        recentOverviewReadyAt = 0;
        recentTouchReadyAt = 0;
        browsingRecent = false;
        lastRecentGestureWasBrowse = false;
        retriedRecentBrowse = false;
        openingRecent = false;
        if (panel != null) {
            windows.removeViewImmediate(panel);
            panel = null;
        }
        recentsPanel = false;
        modePicker = null;
        quickFolder = null;
        quickOptionActions = null;
        lightingOpen = false;
        fanSpeedSlider = null;
    }

    private boolean handlePanelKey(KeyEvent event, boolean isHome, boolean isBack) {
        int key = event.getKeyCode();
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        if (isBack || key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getRepeatCount() == 0) {
                if (recentsPanel || recentsPanelPending) {
                    exitRecentsToHome();
                } else if (modePicker != null || lightingOpen) {
                    returnFromQuickPicker();
                } else if (quickFolder != null) {
                    showQuickActions();
                } else {
                    closePanel();
                }
            }
            return true;
        }
        if (isHome) {
            if ((recentsPanel || recentsPanelPending) && event.getRepeatCount() == 0) {
                exitRecentsToHome();
            }
            return true;
        }
        if (recentsPanel || recentsPanelPending) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (event.getRepeatCount() == 0) {
                        browseRecents(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    }
                    return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (event.getRepeatCount() == 0) {
                        openCenteredRecent();
                    }
                    return true;
                case KeyEvent.KEYCODE_BUTTON_Y:
                    if (event.getRepeatCount() == 0) {
                        dismissCenteredRecent();
                    }
                    return true;
                case KeyEvent.KEYCODE_BUTTON_SELECT:
                    if (event.getRepeatCount() == 0) clearRecentApps();
                    return true;
                default:
                    return false;
            }
        }
        if (lightingOpen) {
            if (key == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.getRepeatCount() == 0) {
                    panel.confirmChoice();
                }
                return true;
            }
            return false;
        }
        if (modePicker != null) {
            if (modePicker == RetroidModePicker.FAN && key == KeyEvent.KEYCODE_BUTTON_START
                    && currentMode(modePicker) == SMART_FAN_MODE) {
                if (event.getRepeatCount() == 0) openSmartFanCurve();
                return true;
            }
            for (int i = 0; i < modePicker.values.length; i++) {
                if (key == ACTION_KEYS[i]) {
                    if (event.getRepeatCount() == 0) {
                        selectMode(modePicker, i);
                    }
                    return true;
                }
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (event.getRepeatCount() == 0) {
                    panel.confirmChoice();
                }
                return true;
            }
            return false;
        }
        if (quickOptionActions != null) {
            for (int i = 0; i < quickOptionActions.length; i++) {
                if (key == ACTION_KEYS[i]) {
                    if (event.getRepeatCount() == 0) {
                        quickOptionActions[i].run();
                    }
                    return true;
                }
            }
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (event.getRepeatCount() == 0) {
                panel.confirmChoice();
            }
            return true;
        }
        return false;
    }

    private void exitRecentsToHome() {
        closePanel();
        if (ready() && !service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
            openHome();
        }
    }

    private void openCenteredRecent() {
        queueRecentInput(RecentInput.OPEN);
    }

    private void dismissCenteredRecent() {
        queueRecentInput(RecentInput.DISMISS);
    }

    private void clearRecentApps() {
        queueRecentInput(RecentInput.CLEAR_ALL);
    }

    private void browseRecents(int direction) {
        queueRecentInput(direction < 0 ? RecentInput.PREVIOUS : RecentInput.NEXT);
    }

    private void queueRecentTouch(RecentInput input) {
        // Touching another button during an injected swipe can cancel it and steal that tap.
        recentTouchReadyAt = SystemClock.uptimeMillis() + 180;
        queueRecentInput(input);
    }

    private void queueRecentInput(RecentInput input) {
        if ((recentsPanel || recentsPanelPending) && !openingRecent) {
            recentInputs.addLast(input);
            processRecentInputs();
        }
    }

    private void processRecentInputs() {
        handler.removeCallbacks(processRecentInputs);
        if (!recentsPanel || !ready() || openingRecent || sendingGesture || recentInputs.isEmpty()) {
            return;
        }
        RecentInput input = recentInputs.peekFirst();
        boolean browse = input == RecentInput.PREVIOUS || input == RecentInput.NEXT;
        // Consecutive horizontal swipes can start as the previous card finishes snapping.
        // Opening or closing a card still needs the full settling time.
        long settle = browse ? (lastRecentGestureWasBrowse ? 185 : 420) : 760;
        long earliest = Math.max(Math.max(recentOverviewReadyAt, recentTouchReadyAt),
                recentGestureEndedAt + settle);
        if (SystemClock.uptimeMillis() < earliest) {
            handler.postAtTime(processRecentInputs, earliest);
            return;
        }

        if (browse) {
            float offset = input == RecentInput.PREVIOUS ? -.14f : .14f;
            browsingRecent = gesture(.5f + offset, .48f, .5f - offset, .48f, 160);
            if (!browsingRecent) finishRecentBrowse(false);
            return;
        }

        recentInputs.removeFirst();
        lastRecentGestureWasBrowse = false;
        boolean sent;
        if (input == RecentInput.DISMISS) {
            sent = gesture(.5f, .48f, .5f, .1f, 200);
        } else if (input == RecentInput.CLEAR_ALL) {
            Point size = screenSize();
            if (!"Retroid Pocket 6".equals(Build.MODEL) || size.x <= size.y) {
                Toast.makeText(service, R.string.navigation_clear_all_unavailable, Toast.LENGTH_SHORT).show();
                processRecentInputs();
                return;
            }
            // Retroid shifts the overview toolbar up when the three-button bar is present.
            int navigationMode = Settings.Secure.getInt(service.getContentResolver(), "navigation_mode", 2);
            float toolbarY = navigationMode == 0 ? .795f : .898f;
            sent = gesture(.60f, toolbarY, .60f, toolbarY, 60);
        } else {
            sent = gesture(.5f, .48f, .502f, .48f, 85);
        }
        if (!sent) {
            recentGestureEndedAt = SystemClock.uptimeMillis();
            processRecentInputs();
        } else if (input == RecentInput.OPEN || input == RecentInput.CLEAR_ALL) {
            openingRecent = true;
            recentInputs.clear();
        }
    }

    private void finishRecentBrowse(boolean completed) {
        browsingRecent = false;
        lastRecentGestureWasBrowse = completed;
        // A touch can cancel the injected swipe. Retry that step once before advancing the queue.
        if (completed || retriedRecentBrowse) {
            recentInputs.removeFirst();
            retriedRecentBrowse = false;
        } else {
            retriedRecentBrowse = true;
        }
        recentGestureEndedAt = SystemClock.uptimeMillis();
        processRecentInputs();
    }

    private void finishRecentGesture(NavigationPanel source, boolean completed) {
        sendingGesture = false;
        if (panel != source || !recentsPanel) {
            processRecentInputs();
        } else if (browsingRecent) {
            finishRecentBrowse(completed);
        } else if (!completed) {
            openingRecent = false;
            recentGestureEndedAt = SystemClock.uptimeMillis();
            processRecentInputs();
        } else if (openingRecent) {
            // Keep focus through Quickstep's launch animation.
            handler.postDelayed(() -> {
                if (panel == source) {
                    closePanel();
                }
            }, 320);
        } else {
            recentGestureEndedAt = SystemClock.uptimeMillis();
            processRecentInputs();
        }
    }

    private boolean gesture(float x1, float y1, float x2, float y2, int duration) {
        if (!ready() || sendingGesture) {
            return false;
        }
        Point size = screenSize();
        Path path = new Path();
        path.moveTo(x1 * size.x, y1 * size.y);
        path.lineTo(x2 * size.x, y2 * size.y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        NavigationPanel source = panel;
        sendingGesture = service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription description) {
                finishRecentGesture(source, true);
            }

            @Override
            public void onCancelled(GestureDescription description) {
                finishRecentGesture(source, false);
            }
        }, handler);
        return sendingGesture;
    }

    @SuppressWarnings("deprecation")
    private Point screenSize() {
        Point size = new Point();
        windows.getDefaultDisplay().getRealSize(size);
        return size;
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }
}
