package app.pocketoverlay;

import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;

final class ControllerHomeShortcut {
    private static final String DEVICE = "controller_home_device";
    private static final String KEY = "controller_home_key";
    private static final String SCAN = "controller_home_scan";
    private static final String LABEL = "controller_home_label";

    private ControllerHomeShortcut() {}

    static boolean canRecord(KeyEvent event) {
        InputDevice device = event.getDevice();
        return device != null && !device.isVirtual()
                && (device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                        || device.supportsSource(InputDevice.SOURCE_JOYSTICK))
                && event.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN
                && event.getKeyCode() != KeyEvent.KEYCODE_POWER;
    }

    static void save(SharedPreferences settings, KeyEvent event) {
        InputDevice device = event.getDevice();
        settings.edit().putString(DEVICE, device.getDescriptor()).putString(LABEL, device.getName())
                .putInt(SCAN, event.getScanCode()).putInt(KEY, event.getKeyCode()).apply();
    }

    static boolean matches(SharedPreferences settings, KeyEvent event) {
        if (!canRecord(event) || !event.getDevice().getDescriptor().equals(settings.getString(DEVICE, ""))) {
            return false;
        }
        int scan = settings.getInt(SCAN, 0);
        return scan != 0 && event.getScanCode() != 0
                ? scan == event.getScanCode()
                : event.getKeyCode() == key(settings);
    }

    static int key(SharedPreferences settings) {
        return settings.getInt(KEY, KeyEvent.KEYCODE_UNKNOWN);
    }

    static String label(SharedPreferences settings) {
        return settings.getString(LABEL, "");
    }

    static void clear(SharedPreferences settings) {
        settings.edit().remove(DEVICE).remove(KEY).remove(SCAN).remove(LABEL).apply();
    }
}
