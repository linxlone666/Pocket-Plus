package app.pocketoverlay;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.view.View;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PerformanceStatus {
    private final Context context;
    private final Map<PerformanceMetric, TextView> values;
    private final PerformanceStats stats = new PerformanceStats();
    private final PerformanceReadings readings;
    private final SharedPreferences settings;
    private final TextView preview;
    private final View batteryCard;
    private Bundle latest = Bundle.EMPTY;
    private Intent battery;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::refresh;
    private boolean visible;
    private boolean sampling;

    PerformanceStatus(Context context, Map<PerformanceMetric, TextView> values, TextView preview, View batteryCard) {
        this.context = context;
        this.values = values;
        this.preview = preview;
        this.batteryCard = batteryCard;
        settings = OverlaySettings.open(context);
        readings = new PerformanceReadings(context);
    }

    void setVisible(boolean visible) {
        this.visible = visible;
        handler.removeCallbacks(refresh);
        if (visible) {
            refresh();
        }
    }

    private void refresh() {
        if (!sampling) {
            sampling = true;
            boolean fullList = settings.getInt(OverlaySettings.PERFORMANCE_LAYOUT, OverlaySettings.PERFORMANCE_LIST)
                    == OverlaySettings.PERFORMANCE_FULL_LIST;
            boolean readFanRpm = OverlaySettings.fanFormat(settings) != OverlaySettings.FAN_PERCENTAGE;
            worker.execute(() -> {
                // The dashboard is foreground here, so there is no game surface to sample.
                Bundle sample = stats.sample(PerformanceMetric.SAMPLED & ~PerformanceMetric.FPS.bit, fullList, readFanRpm);
                handler.post(() -> {
                    sampling = false;
                    if (visible) {
                        battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                        latest = sample;
                        render();
                    }
                });
            });
        }
        handler.postDelayed(refresh, 1000);
    }

    void render() {
        int metrics = settings.getInt(OverlaySettings.PERFORMANCE_METRICS, PerformanceMetric.DEFAULT);
        batteryCard.setVisibility((metrics & PerformanceMetric.BATTERY_MASK) != 0 ? View.VISIBLE : View.GONE);
        values.forEach((metric, view) -> {
            if ((metric.bit & PerformanceMetric.BATTERY_MASK) != 0) {
                ((View) view.getParent()).setVisibility(metric.selected(metrics) ? View.VISIBLE : View.GONE);
                if (!metric.selected(metrics)) return;
            }
            if (metric == PerformanceMetric.POWER && PerformanceReadings.poweredWithoutCharging(battery)) {
                CharSequence plug = PerformanceHud.plugValue();
                if (!android.text.TextUtils.equals(plug, view.getText())) view.setText(plug);
                return;
            }
            String value = readings.value(metric, battery, latest);
            if (metric == PerformanceMetric.CPU || metric == PerformanceMetric.GPU) {
                PerformanceMetric temperature = metric == PerformanceMetric.CPU
                        ? PerformanceMetric.CPU_TEMPERATURE : PerformanceMetric.GPU_TEMPERATURE;
                value = context.getString(R.string.performance_component_values, value,
                        readings.value(temperature, battery, latest));
            }
            if (!value.contentEquals(view.getText())) {
                view.setText(value);
            }
        });
        int width = ((View) preview.getParent()).getWidth();
        draw(preview,
                settings.getInt(OverlaySettings.PERFORMANCE_METRICS, PerformanceMetric.DEFAULT),
                settings.getInt(OverlaySettings.PERFORMANCE_LAYOUT, OverlaySettings.PERFORMANCE_LIST),
                settings.getInt(OverlaySettings.PERFORMANCE_SCALE, 100),
                settings.getInt(OverlaySettings.PERFORMANCE_OPACITY, OverlaySettings.PERFORMANCE_DEFAULT_OPACITY),
                width);
    }

    void draw(TextView target, int metrics, int layout, int scale, int opacity, int width) {
        PerformanceHud.render(target, settings, readings, battery, latest, metrics, layout, scale, opacity, width);
    }

    void close() {
        setVisible(false);
        worker.shutdownNow();
    }
}
