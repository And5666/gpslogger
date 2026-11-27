package com.mendhak.gpslogger.widgets;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Draws the seven-day bar chart for the stats widget. Widgets can't host custom views, so the
 * chart goes onto the widget as a bitmap.
 */
final class WeekBarsRenderer {

    private WeekBarsRenderer() { }

    static Bitmap render(DawarichWeekStats stats, int widthPx, int heightPx, float density,
                         int barColor, int todayColor, int labelColor, int emptyColor) {
        widthPx = Math.max(1, widthPx);
        heightPx = Math.max(1, heightPx);
        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(labelColor);
        labelPaint.setTextSize(11 * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        Paint valuePaint = new Paint(labelPaint);
        valuePaint.setTextSize(10 * density);

        Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        float labelHeight = 16 * density;
        float valueHeight = 14 * density;
        float chartTop = valueHeight;
        float chartBottom = heightPx - labelHeight;
        float chartHeight = Math.max(1, chartBottom - chartTop);

        int days = DawarichWeekStats.DAYS;
        float slot = widthPx / (float) days;
        float barWidth = Math.min(slot * 0.55f, 28 * density);
        float radius = Math.min(barWidth / 2, 6 * density);

        long max = 0;
        int maxIndex = -1;
        for (int i = 0; i < days; i++) {
            if (stats.dailyMeters[i] > max) {
                max = stats.dailyMeters[i];
                maxIndex = i;
            }
        }

        SimpleDateFormat parse = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        SimpleDateFormat dayName = new SimpleDateFormat("EEE", Locale.getDefault());

        for (int i = 0; i < days; i++) {
            float cx = slot * i + slot / 2;
            boolean today = i == days - 1;
            long meters = stats.dailyMeters[i];

            float barHeight = max > 0 ? chartHeight * meters / (float) max : 0;
            // Days with no distance still get a stub, so the week reads as seven days
            float minHeight = 3 * density;
            barPaint.setColor(meters > 0 ? (today ? todayColor : barColor) : emptyColor);
            float top = chartBottom - Math.max(minHeight, barHeight);
            canvas.drawRoundRect(new RectF(cx - barWidth / 2, top, cx + barWidth / 2, chartBottom), radius, radius, barPaint);

            // Label the biggest day and today with their distance
            if (meters > 0 && (i == maxIndex || today)) {
                valuePaint.setTypeface(today ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                canvas.drawText(shortDistance(stats, meters), cx, top - 3 * density, valuePaint);
            }

            String label;
            try {
                Date date = parse.parse(stats.dates[i]);
                label = date == null ? "" : dayName.format(date);
            } catch (ParseException e) {
                label = "";
            }
            if (label.length() > 3) {
                label = label.substring(0, 3);
            }
            labelPaint.setTypeface(today ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            canvas.drawText(label, cx, heightPx - 4 * density, labelPaint);
        }
        return bitmap;
    }

    private static String shortDistance(DawarichWeekStats stats, long meters) {
        double v = stats.toUnit(meters);
        return v >= 10 ? String.format(Locale.getDefault(), "%.0f", v) : String.format(Locale.getDefault(), "%.1f", v);
    }
}
