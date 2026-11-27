package app.pocketoverlay;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class NamedPresets {
    private static final String HUD_PREFIX = "named_hud_preset_";
    private static final String REMAP_PREFIX = "named_remap_preset_";
    private static final String[] HUD_KEYS = {
            OverlaySettings.PERFORMANCE_METRICS, OverlaySettings.PERFORMANCE_LAYOUT,
            OverlaySettings.PERFORMANCE_POSITION, OverlaySettings.PERFORMANCE_X, OverlaySettings.PERFORMANCE_Y,
            OverlaySettings.PERFORMANCE_OPACITY, OverlaySettings.PERFORMANCE_SCALE, OverlaySettings.PERFORMANCE_ORDER,
            OverlaySettings.PERFORMANCE_LOCKED, OverlaySettings.PERFORMANCE_RAM_FORMAT, OverlaySettings.PERFORMANCE_FAN_FORMAT,
            OverlaySettings.PERFORMANCE_BATTERY_FIELDS, OverlaySettings.PERFORMANCE_FPS_GRAPH
    };

    private final SharedPreferences settings;
    private final String prefix;

    NamedPresets(SharedPreferences settings, boolean remap) {
        this.settings = settings;
        prefix = remap ? REMAP_PREFIX : HUD_PREFIX;
    }

    private String selectionKey() {
        return "selected_" + prefix;
    }

    String selectedName() {
        String name = settings.getString(selectionKey(), null);
        return name != null && settings.contains(prefix + name) ? name : null;
    }

    void select(String name) {
        settings.edit().putString(selectionKey(), name).apply();
    }

    List<String> names() {
        List<String> names = new ArrayList<>();
        for (String key : settings.getAll().keySet()) {
            if (key.startsWith(prefix)) names.add(key.substring(prefix.length()));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo));
        return names;
    }

    JSONObject read(String name) {
        String encoded = settings.getString(prefix + name, "");
        try {
            return new JSONObject(encoded);
        } catch (JSONException ignored) {
            return null;
        }
    }

    boolean save(String name, JSONObject values) {
        if (name.isEmpty() || names().stream().anyMatch(name::equalsIgnoreCase)) return false;
        write(name, values);
        return true;
    }

    boolean update(String name, JSONObject values) {
        if (!settings.contains(prefix + name)) return false;
        write(name, values);
        return true;
    }

    private void write(String name, JSONObject values) {
        settings.edit().putString(prefix + name, values.toString()).putString(selectionKey(), name).apply();
    }

    boolean rename(String oldName, String newName) {
        String snapshot = settings.getString(prefix + oldName, null);
        if (snapshot == null || newName.isEmpty()
                || names().stream().anyMatch(name -> !name.equals(oldName) && name.equalsIgnoreCase(newName))) {
            return false;
        }
        if (oldName.equals(newName)) return true;
        SharedPreferences.Editor edit = settings.edit().remove(prefix + oldName).putString(prefix + newName, snapshot);
        if (oldName.equals(selectedName())) edit.putString(selectionKey(), newName);
        edit.apply();
        return true;
    }

    void delete(String name) {
        SharedPreferences.Editor edit = settings.edit().remove(prefix + name);
        if (name.equals(selectedName())) edit.remove(selectionKey());
        edit.apply();
    }

    static JSONObject captureHud(SharedPreferences settings) {
        Map<String, ?> stored = settings.getAll();
        Map<String, Object> preset = new HashMap<>();
        for (String key : HUD_KEYS) {
            if (stored.containsKey(key)) preset.put(key, stored.get(key));
        }
        preset.put(OverlaySettings.PERFORMANCE_RAM_FORMAT, OverlaySettings.ramFormat(settings));
        preset.put(OverlaySettings.PERFORMANCE_FAN_FORMAT, OverlaySettings.fanFormat(settings));
        return new JSONObject(preset);
    }

    static void applyHud(SharedPreferences settings, JSONObject values) {
        SharedPreferences.Editor edit = settings.edit();
        for (String key : HUD_KEYS) {
            Object value = values.opt(key);
            if (value instanceof Boolean) edit.putBoolean(key, (boolean) value);
            else if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Number) {
                if (OverlaySettings.PERFORMANCE_X.equals(key) || OverlaySettings.PERFORMANCE_Y.equals(key)) {
                    edit.putFloat(key, ((Number) value).floatValue());
                } else {
                    edit.putInt(key, ((Number) value).intValue());
                }
            } else edit.remove(key);
        }
        edit.apply();
    }
}
