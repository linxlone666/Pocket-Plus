package app.pocketoverlay;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

final class PerformanceHud {
    private static final char PLUG_PLACEHOLDER = '\ufffc';
    static final String FPS_HISTORY = "fps_history";
    static final int FPS_HISTORY_LENGTH = 30;

    private PerformanceHud() {}

    static void setup(TextView panel) {
        panel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        panel.setEllipsize(TextUtils.TruncateAt.END);
    }

    static void render(TextView panel, SharedPreferences settings, PerformanceReadings readings,
            Intent battery, Bundle sample, int metrics, int style, float scale, int opacity, int width) {
        Context context = panel.getContext();
        int theme = settings.getInt(OverlaySettings.THEME, OverlaySettings.DARK);
        boolean dark = OverlaySettings.usesDarkColors(context, theme);
        int background = OverlayView.panelColor(context, theme, dark);
        int alpha = Math.round(opacity * 255 / 100f);
        int fill = (alpha << 24) | (background & 0x00ffffff);
        if (panel.getBackground() instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) panel.getBackground();
            if (shape.getColor().getDefaultColor() != fill) {
                shape.setColor(fill);
            }
        } else {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(fill);
            shape.setCornerRadius(dp(context, 8));
            panel.setBackground(shape);
        }
        panel.setTextColor(OverlayView.statusColor(context, theme, dark));
        panel.setShadowLayer(opacity == 0 ? dp(context, 2) : 0, 0, 0, 0xff000000);

        boolean grid = style == OverlaySettings.PERFORMANCE_TOP_GRID;
        boolean inline = grid || style == OverlaySettings.PERFORMANCE_INLINE;
        if (panel.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) panel.getBackground()).setCornerRadius(grid ? 0 : dp(context, 8));
        }
        boolean fullList = style == OverlaySettings.PERFORMANCE_FULL_LIST;
        int ramFormat = OverlaySettings.ramFormat(settings);
        int fanFormat = OverlaySettings.fanFormat(settings);
        List<PerformanceGroup> groups = PerformanceGroup.order(settings);
        int labelWidth = inline ? 0 : labelWidth(context, groups, metrics, fullList, sample);
        SpannableStringBuilder text = new SpannableStringBuilder();
        boolean showFpsGraph = PerformanceMetric.FPS.selected(metrics)
                && settings.getBoolean(OverlaySettings.PERFORMANCE_FPS_GRAPH, false);
        float[] history = showFpsGraph ? sample.getFloatArray(FPS_HISTORY) : null;
        FpsGraph graph = showFpsGraph ? new FpsGraph(history, color(PerformanceMetric.FPS, dark)) : null;
        if (grid) {
            appendGrid(text, context, readings, battery, sample, groups, metrics, dark, ramFormat, fanFormat, graph);
        } else {
            appendStandard(text, context, readings, battery, sample, groups, metrics, inline, fullList,
                    labelWidth, dark, ramFormat, fanFormat, graph);
        }
        if (text.length() == 0) {
            text.append(context.getString(R.string.performance_preview_empty));
        }
        int lines = inline ? 1 : Integer.MAX_VALUE;
        if (panel.getMaxLines() != lines) {
            panel.setMaxLines(lines);
            panel.setHorizontallyScrolling(inline);
        }
        float size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12 * scale / 100f,
                context.getResources().getDisplayMetrics());
        if (inline && width > 0) {
            int available = Math.max(1, width - panel.getPaddingLeft() - panel.getPaddingRight() - dp(context, 3));
            float minimum = Math.min(size, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, grid ? 5.5f : 8,
                    context.getResources().getDisplayMetrics()));
            TextPaint paint = new TextPaint(panel.getPaint());
            // Measure again at the fitted size because hinted glyph widths do not scale linearly.
            for (int attempt = 0; attempt < 4 && size > minimum; attempt++) {
                paint.setTextSize(size);
                float measured = Layout.getDesiredWidth(text, paint);
                if (measured <= available) {
                    break;
                }
                size = Math.max(minimum, size * available / measured - 0.5f);
            }
        }
        if (Math.abs(panel.getTextSize() - size) > 0.1f) {
            panel.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        }
        int presentation = Objects.hash(dark, ramFormat, fanFormat, style, showFpsGraph, Arrays.hashCode(history));
        if (!TextUtils.equals(panel.getText(), text) || !Integer.valueOf(presentation).equals(panel.getTag())) {
            panel.setText(text);
            panel.setTag(presentation);
        }
    }

    private static void appendStandard(SpannableStringBuilder text, Context context,
            PerformanceReadings readings, Intent battery, Bundle sample, List<PerformanceGroup> groups,
            int metrics, boolean inline, boolean fullList, int labelWidth, boolean dark, int ramFormat, int fanFormat,
            FpsGraph graph) {
        boolean showPlug = PerformanceReadings.poweredWithoutCharging(battery);
        for (PerformanceGroup group : groups) {
            if (group == PerformanceGroup.CHARGE) {
                SpannableStringBuilder values = new SpannableStringBuilder();
                boolean hasPowerOrRuntime = false;
                for (PerformanceMetric metric : PerformanceMetric.BATTERY_READINGS) {
                    if (metric.selected(metrics)) {
                        boolean isPowerOrRuntime = metric == PerformanceMetric.POWER
                                || metric == PerformanceMetric.BATTERY_TIME;
                        if (values.length() > 0) {
                            if (!inline && isPowerOrRuntime && !hasPowerOrRuntime) {
                                values.append('\n');
                                appendSpaces(values, labelWidth + 1);
                            } else {
                                values.append(inline ? " " : "  ");
                            }
                        }
                        if (metric == PerformanceMetric.POWER && showPlug) {
                            appendPlug(values);
                        } else {
                            String value = readings.value(metric, battery, sample);
                            if (inline) {
                                values.append(value);
                            } else if (metric == PerformanceMetric.BATTERY) {
                                appendUsage(values, context, value);
                            } else if (values.length() == 0 || (isPowerOrRuntime && !hasPowerOrRuntime)) {
                                appendNumber(values, value);
                            } else {
                                values.append(value);
                            }
                        }
                        hasPowerOrRuntime |= isPowerOrRuntime;
                    }
                }
                if (values.length() > 0) {
                    appendLabel(text, label(context, PerformanceMetric.BATTERY), PerformanceMetric.BATTERY,
                            inline, dark, labelWidth);
                    text.append(values);
                }
                continue;
            }
            PerformanceMetric primary = group.primary;
            PerformanceMetric secondary = group.secondary;
            boolean showPrimary = primary.selected(metrics);
            boolean showSecondary = secondary != null && secondary.selected(metrics);
            if (showPrimary) {
                appendLabel(text, label(context, primary), primary, inline, dark, labelWidth);
                String value = readings.value(primary, battery, sample);
                if (primary == PerformanceMetric.CPU || primary == PerformanceMetric.GPU
                        || (primary == PerformanceMetric.MEMORY
                                && ramFormat == OverlaySettings.RAM_PERCENTAGE)) {
                    appendUsage(text, context, value);
                } else if (!inline && primary == PerformanceMetric.FAN
                        && fanFormat != OverlaySettings.FAN_RPM_ONLY) {
                    int separator = value.indexOf(' ');
                    if (separator >= 0) {
                        appendUsage(text, context, value.substring(0, separator));
                        text.append(value.substring(separator));
                    } else {
                        appendUsage(text, context, value);
                    }
                } else if (inline) {
                    text.append(value);
                } else {
                    appendNumber(text, value);
                }
                if (primary == PerformanceMetric.FPS && graph != null) {
                    text.append(' ');
                    int start = text.length();
                    text.append(PLUG_PLACEHOLDER);
                    text.setSpan(graph, start, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (fullList && primary == PerformanceMetric.GPU) {
                    text.append("  ");
                    appendClock(text, context, sample.getInt(PerformanceStats.GPU_MHZ, -1));
                }
            }
            if (showSecondary) {
                String value = readings.value(secondary, battery, sample);
                if (showPrimary) {
                    text.append(inline ? " " : "  ").append(value);
                } else {
                    appendLabel(text, label(context, secondary), secondary, inline, dark, labelWidth);
                    if (inline) {
                        text.append(value);
                    } else {
                        appendNumber(text, value);
                    }
                }
            }
            if (fullList && showPrimary && primary == PerformanceMetric.CPU) {
                appendCpuCores(text, context, sample, dark, labelWidth);
            }
        }
    }

    private static void appendGrid(SpannableStringBuilder text, Context context, PerformanceReadings readings,
            Intent battery, Bundle sample, List<PerformanceGroup> groups, int metrics, boolean dark,
            int ramFormat, int fanFormat, FpsGraph graph) {
        boolean showPlug = PerformanceReadings.poweredWithoutCharging(battery);
        for (PerformanceGroup group : groups) {
            StringBuilder value = new StringBuilder();
            int plugIndex = -1;
            StringBuilder reserve = new StringBuilder();
            PerformanceMetric[] members = group == PerformanceGroup.CHARGE ? PerformanceMetric.BATTERY_READINGS
                    : group.secondary == null ? new PerformanceMetric[]{group.primary}
                    : new PerformanceMetric[]{group.primary, group.secondary};
            for (PerformanceMetric metric : members) {
                if (!metric.selected(metrics)) continue;
                if (value.length() > 0) {
                    value.append(' ');
                    reserve.append(' ');
                }
                if (metric == PerformanceMetric.POWER && showPlug) {
                    plugIndex = value.length();
                    value.append(PLUG_PLACEHOLDER);
                } else {
                    value.append(readings.value(metric, battery, sample));
                }
                reserve.append(gridTemplate(metric, ramFormat, fanFormat));
            }
            if (value.length() == 0) continue;
            String name = label(context, group.primary);
            int start = text.length();
            text.append(name).append(' ').append(value);
            boolean batteryCell = group == PerformanceGroup.CHARGE;
            FpsGraph cellGraph = group.primary == PerformanceMetric.FPS ? graph : null;
            if (cellGraph != null) text.append(PLUG_PLACEHOLDER);
            text.setSpan(new GridCell(name, value.toString(), reserve.toString(), color(group.primary, dark),
                    batteryCell, plugIndex, start != 0, cellGraph), start, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static String gridTemplate(PerformanceMetric metric, int ram, int fan) {
        switch (metric) {
            case FPS: return "000";
            case REFRESH_RATE: return "000 Hz";
            case CPU_TEMPERATURE:
            case GPU_TEMPERATURE:
            case BATTERY_TEMPERATURE: return "00.0 °C";
            case BATTERY_TIME: return "~00h 00m";
            case POWER: return "-00.00 W";
            case MEMORY:
                if (ram == OverlaySettings.RAM_USED_ONLY) return "00.0 GiB";
                if (ram == OverlaySettings.RAM_USED_TOTAL) return "00.0 / 00.0 GiB";
                return "100%";
            case FAN:
                if (fan == OverlaySettings.FAN_RPM_ONLY) return "0000 RPM";
                if (fan == OverlaySettings.FAN_PERCENTAGE_AND_RPM) return "100% 0000 RPM";
                return "100%";
            default: return "100%";
        }
    }

    private static final class GridCell extends ReplacementSpan {
        private final String label, value, reserve;
        private final int color;
        private final boolean battery, divider;
        private final int plugIndex;
        private final FpsGraph graph;

        GridCell(String label, String value, String reserve, int color, boolean battery, int plugIndex,
                boolean divider, FpsGraph graph) {
            this.graph = graph;
            this.label = label;
            this.value = value;
            this.reserve = reserve;
            this.color = color;
            this.battery = battery;
            this.plugIndex = plugIndex;
            this.divider = divider;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            float size = paint.getTextSize();
            if (metrics != null) paint.getFontMetricsInt(metrics);
            float valueWidth = plugIndex < 0 ? paint.measureText(value)
                    : paint.measureText(value, 0, plugIndex) + size
                            + paint.measureText(value, plugIndex + 1, value.length());
            float graphWidth = graph == null ? 0 : graph.width(paint) + size * .5f;
            return (int) Math.ceil(paint.measureText(label) + Math.max(valueWidth,
                    paint.measureText(reserve)) + size * 1.3f + graphWidth);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                int bottom, Paint paint) {
            float size = paint.getTextSize();
            int ink = paint.getColor();
            if (divider) {
                paint.setColor((ink & 0xffffff) | 0x64000000);
                canvas.drawRect(x, top + size * .08f, x + Math.max(1, size * .06f),
                        bottom - size * .08f, paint);
            }
            paint.setColor(color);
            canvas.drawText(label, x + size * .43f, y, paint);
            paint.setColor(ink);
            if (battery) {
                float left = x + size * .88f + paint.measureText(label);
                if (plugIndex < 0) {
                    canvas.drawText(value, left, y, paint);
                } else {
                    canvas.drawText(value, 0, plugIndex, left, y, paint);
                    float plugX = left + paint.measureText(value, 0, plugIndex);
                    drawPlug(canvas, plugX, y - size * .72f, size, paint);
                    canvas.drawText(value, plugIndex + 1, value.length(), plugX + size, y, paint);
                }
            } else {
                float right = x + getSize(paint, text, start, end, null) - size * .43f;
                if (graph != null) {
                    right -= graph.width(paint);
                    graph.drawLine(canvas, right, y, paint);
                    right -= size * .5f;
                }
                canvas.drawText(value, right - paint.measureText(value), y, paint);
            }
        }
    }

    private static final class FpsGraph extends ReplacementSpan {
        private final float[] history;
        private final int color;
        private final Path line = new Path();

        FpsGraph(float[] history, int color) {
            this.history = history;
            this.color = color;
        }

        float width(Paint paint) {
            return paint.getTextSize() * 4.5f;
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            if (metrics != null) paint.getFontMetricsInt(metrics);
            return (int) Math.ceil(width(paint));
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                int bottom, Paint paint) {
            drawLine(canvas, x, y, paint);
        }

        void drawLine(Canvas canvas, float left, int baseline, Paint paint) {
            float size = paint.getTextSize();
            float bottom = baseline - size * .08f;
            float width = width(paint);
            int previousColor = paint.getColor();
            Paint.Style previousStyle = paint.getStyle();
            float previousWidth = paint.getStrokeWidth();
            paint.setColor((previousColor & 0xffffff) | 0x55000000);
            canvas.drawRect(left, bottom, left + width, bottom + Math.max(1, size * .055f), paint);
            if (history != null) {
                float max = 60f;
                for (float fps : history) if (Float.isFinite(fps)) max = Math.max(max, fps);
                line.reset();
                float step = width / (FPS_HISTORY_LENGTH - 1);
                float x = left + width - (history.length - 1) * step;
                boolean connected = false;
                for (float fps : history) {
                    if (Float.isFinite(fps)) {
                        float y = bottom - Math.max(0, fps) / max * size * .70f;
                        if (connected) line.lineTo(x, y);
                        else line.moveTo(x, y);
                        connected = true;
                    } else {
                        connected = false;
                    }
                    x += step;
                }
                paint.setColor(color);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1, size * .075f));
                canvas.drawPath(line, paint);
            }
            paint.setColor(previousColor);
            paint.setStyle(previousStyle);
            paint.setStrokeWidth(previousWidth);
        }
    }

    static CharSequence plugValue() {
        SpannableStringBuilder text = new SpannableStringBuilder();
        appendPlug(text);
        return text;
    }

    private static void appendPlug(SpannableStringBuilder text) {
        int start = text.length();
        text.append(PLUG_PLACEHOLDER);
        text.setSpan(new PlugIcon(), start, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static final class PlugIcon extends ReplacementSpan {
        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            // Live status uses the icon on a line by itself.
            if (metrics != null) paint.getFontMetricsInt(metrics);
            return (int) Math.ceil(paint.getTextSize());
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                int bottom, Paint paint) {
            drawPlug(canvas, x, y - paint.getTextSize() * .72f, paint.getTextSize(), paint);
        }
    }

    private static void drawPlug(Canvas canvas, float x, float y, float size, Paint paint) {
        float thickness = Math.max(1, size * .085f);
        canvas.drawRect(x + .14f * size, y, x + .14f * size + thickness, y + .30f * size, paint);
        canvas.drawRect(x + .57f * size, y, x + .57f * size + thickness, y + .30f * size, paint);
        canvas.drawRoundRect(x + .05f * size, y + .26f * size, x + .76f * size, y + .62f * size,
                .11f * size, .11f * size, paint);
        canvas.drawRect(x + .36f * size, y + .56f * size, x + .36f * size + thickness, y + .82f * size, paint);
    }

    static int color(PerformanceMetric metric, boolean dark) {
        switch (metric) {
            case FPS: return dark ? 0xffff9c91 : 0xff9e372b;
            case CPU:
            case CPU_TEMPERATURE: return dark ? 0xff93caff : 0xff1e5594;
            case GPU:
            case GPU_TEMPERATURE: return dark ? 0xff91e1af : 0xff256744;
            case MEMORY: return dark ? 0xffe5a9ff : 0xff783e91;
            case BATTERY:
            case BATTERY_TEMPERATURE:
            case BATTERY_TIME: return dark ? 0xffffc29b : 0xff8c4c21;
            case POWER: return dark ? 0xffffde83 : 0xff765600;
            default: return dark ? 0xffa7e1df : 0xff2a6069;
        }
    }

    private static String label(Context context, PerformanceMetric metric) {
        int label = metric == PerformanceMetric.BATTERY ? R.string.performance_batt
                : metric == PerformanceMetric.REFRESH_RATE ? R.string.performance_display
                : metric == PerformanceMetric.FAN ? R.string.performance_fan
                : metric.title;
        return context.getString(label);
    }

    private static int labelWidth(Context context, List<PerformanceGroup> groups, int metrics,
            boolean fullList, Bundle sample) {
        int width = 0;
        for (PerformanceGroup group : groups) {
            if (group == PerformanceGroup.CHARGE) {
                for (PerformanceMetric metric : PerformanceMetric.BATTERY_READINGS) {
                    if (metric.selected(metrics)) {
                        width = Math.max(width, label(context, PerformanceMetric.BATTERY).length());
                        break;
                    }
                }
            } else if (group.primary.selected(metrics)) {
                width = Math.max(width, label(context, group.primary).length());
            } else if (group.secondary != null && group.secondary.selected(metrics)) {
                width = Math.max(width, label(context, group.secondary).length());
            }
        }
        if (fullList && PerformanceMetric.CPU.selected(metrics)) {
            int[] ids = sample.getIntArray(PerformanceStats.CPU_CORE_IDS);
            if (ids != null) {
                for (int id : ids) {
                    width = Math.max(width, context.getString(R.string.performance_cpu_core, id).length());
                }
            }
        }
        return width;
    }

    private static void appendLabel(SpannableStringBuilder text, String label, PerformanceMetric metric,
            boolean inline, boolean dark, int labelWidth) {
        if (text.length() != 0) {
            text.append(inline ? " │ " : "\n");
        }
        int start = text.length();
        text.append(label);
        text.setSpan(new ForegroundColorSpan(color(metric, dark)), start, text.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        appendSpaces(text, 1 + Math.max(0, labelWidth - label.length()));
    }

    private static void appendSpaces(SpannableStringBuilder text, int count) {
        for (int index = 0; index < count; index++) {
            text.append(' ');
        }
    }

    private static void appendUsage(SpannableStringBuilder text, Context context, String value) {
        appendRightAligned(text, value, context.getString(R.string.performance_percent, 100));
    }

    private static void appendNumber(SpannableStringBuilder text, String value) {
        if ("—".equals(value)) {
            appendRightAligned(text, value, "000");
            return;
        }
        char first = value.charAt(0);
        int digitsStart = first == '+' || first == '-' || first == '~' ? 1 : 0;
        int digitsEnd = digitsStart;
        while (digitsEnd < value.length() && Character.isDigit(value.charAt(digitsEnd))) {
            digitsEnd++;
        }
        if (digitsEnd == digitsStart) {
            text.append(value);
            return;
        }
        appendRightAligned(text, value.substring(0, digitsEnd), "000");
        text.append(value, digitsEnd, value.length());
    }

    private static void appendRightAligned(SpannableStringBuilder text, String value, String template) {
        int start = text.length();
        text.append(value);
        text.setSpan(new RightAlignedWidth(template), start, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void appendClock(SpannableStringBuilder text, Context context, int mhz) {
        text.append(mhz < 0 ? "—" : context.getString(R.string.performance_mhz, mhz));
    }

    private static final class RightAlignedWidth extends ReplacementSpan {
        private final String maximum;

        RightAlignedWidth(String maximum) {
            this.maximum = maximum;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            return (int) Math.ceil(Math.max(paint.measureText(maximum), paint.measureText(text, start, end)));
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                int bottom, Paint paint) {
            float gap = getSize(paint, text, start, end, null) - paint.measureText(text, start, end);
            canvas.drawText(text, start, end, x + gap, y, paint);
        }
    }

    private static void appendCpuCores(SpannableStringBuilder text, Context context, Bundle sample,
            boolean dark, int labelWidth) {
        int[] ids = sample.getIntArray(PerformanceStats.CPU_CORE_IDS);
        int[] loads = sample.getIntArray(PerformanceStats.CPU_CORE_LOADS);
        int[] clocks = sample.getIntArray(PerformanceStats.CPU_CORE_MHZ);
        if (ids == null || loads == null || clocks == null) {
            return;
        }
        for (int index = 0; index < ids.length; index++) {
            appendLabel(text, context.getString(R.string.performance_cpu_core, ids[index]),
                    PerformanceMetric.CPU, false, dark, labelWidth);
            String load = loads[index] < 0 ? "—" : context.getString(R.string.performance_percent, loads[index]);
            appendUsage(text, context, load);
            text.append("  ");
            appendClock(text, context, clocks[index]);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
