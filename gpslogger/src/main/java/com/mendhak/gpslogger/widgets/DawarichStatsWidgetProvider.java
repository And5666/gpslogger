package com.mendhak.gpslogger.widgets;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.common.Strings;

import java.util.Date;
import java.util.Locale;

/**
 * Home screen widget with the last seven days from Dawarich: distance, points, current streak
 * and a bar per day. Refreshes every 30 minutes, after GPSLogger sends points, and on tap.
 */
public class DawarichStatsWidgetProvider extends AppWidgetProvider {

    static final String ACTION_REFRESH = "com.mendhak.gpslogger.widgets.DAWARICH_STATS_REFRESH";

    private static volatile boolean refreshing = false;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            render(context, manager, id);
        }
        DawarichStatsWorker.schedulePeriodic(context);
        startRefresh(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle newOptions) {
        // Resized: redraw the chart to fit
        render(context, manager, id);
    }

    @Override
    public void onDisabled(Context context) {
        DawarichStatsWorker.cancelAll(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            startRefresh(context);
        }
    }

    private static void startRefresh(Context context) {
        refreshing = true;
        updateAll(context);
        DawarichStatsWorker.refreshSoon(context, 0);
    }

    static boolean hasWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        return manager != null && manager.getAppWidgetIds(new ComponentName(context, DawarichStatsWidgetProvider.class)).length > 0;
    }

    /** Called by the worker once it has saved new stats or an error */
    static void finishRefresh(Context context) {
        refreshing = false;
        updateAll(context);
    }

    /** Redraws every stats widget from the cached stats */
    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) {
            return;
        }
        for (int id : manager.getAppWidgetIds(new ComponentName(context, DawarichStatsWidgetProvider.class))) {
            render(context, manager, id);
        }
    }

    private static String formatTime(Context context, long millis) {
        String time = DateFormat.getTimeFormat(context).format(new Date(millis));
        if (DateUtils.isToday(millis)) {
            return time;
        }
        return DateFormat.getMediumDateFormat(context).format(new Date(millis)) + " " + time;
    }

    private static String formatCount(long count) {
        return String.format(Locale.getDefault(), "%,d", count);
    }

    private static void render(Context context, AppWidgetManager manager, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_dawarich_stats);

        DawarichWeekStats stats = WidgetState.getStats(context);
        String error = WidgetState.getStatsError(context);

        // A cached week from another day would put the bars on the wrong days
        if (stats != null && !DateUtils.isToday(stats.fetchedAt)) {
            String[] dates = DawarichWeekStats.lastSevenDates(java.util.Calendar.getInstance());
            if (!dates[DawarichWeekStats.DAYS - 1].equals(stats.dates[DawarichWeekStats.DAYS - 1])) {
                stats = null;
            }
        }

        if (stats != null) {
            views.setTextViewText(R.id.widget_stats_week_distance, stats.formatDistance(stats.weekMeters()));
            views.setTextViewText(R.id.widget_stats_streak, stats.currentStreak > 0
                    ? context.getString(R.string.widget_stats_streak, stats.currentStreak)
                    : context.getString(R.string.widget_stats_no_streak));
            views.setTextViewText(R.id.widget_stats_week_points, stats.pointsWeek >= 0
                    ? context.getString(R.string.widget_stats_points, formatCount(stats.pointsWeek)) : "");
            views.setTextViewText(R.id.widget_stats_today, stats.pointsToday >= 0
                    ? context.getString(R.string.widget_stats_today, stats.formatDistance(stats.todayMeters()), formatCount(stats.pointsToday))
                    : context.getString(R.string.widget_stats_today_no_points, stats.formatDistance(stats.todayMeters())));

            Bundle options = manager.getAppWidgetOptions(id);
            float density = context.getResources().getDisplayMetrics().density;
            int widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
            int heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110);
            // Room left for the chart after the padding and the text rows above and below it
            int chartWidthDp = Math.max(120, widthDp - 32);
            int chartHeightDp = Math.max(36, heightDp - 112);
            views.setImageViewBitmap(R.id.widget_stats_bars, WeekBarsRenderer.render(stats,
                    (int) (chartWidthDp * density), (int) (chartHeightDp * density), density,
                    ContextCompat.getColor(context, R.color.widget_chart_bar),
                    ContextCompat.getColor(context, R.color.widget_chart_today),
                    ContextCompat.getColor(context, R.color.widget_chart_label),
                    ContextCompat.getColor(context, R.color.widget_chart_empty)));
        } else {
            views.setTextViewText(R.id.widget_stats_week_distance, "–");
            views.setTextViewText(R.id.widget_stats_streak, "");
            views.setTextViewText(R.id.widget_stats_week_points, "");
            views.setTextViewText(R.id.widget_stats_today, "");
            views.setImageViewBitmap(R.id.widget_stats_bars, null);
        }

        String footer;
        if (refreshing) {
            footer = context.getString(R.string.widget_stats_updating);
        } else if (error != null && stats != null) {
            footer = context.getString(R.string.widget_stats_stale, error, formatTime(context, stats.fetchedAt));
        } else if (error != null) {
            footer = error;
        } else if (stats != null) {
            footer = context.getString(R.string.widget_stats_updated, formatTime(context, stats.fetchedAt));
        } else {
            footer = context.getString(R.string.widget_stats_loading);
        }
        views.setTextViewText(R.id.widget_stats_updated, footer);

        // The refresh symbol refreshes, anywhere else opens Dawarich
        Intent refresh = new Intent(context, DawarichStatsWidgetProvider.class).setAction(ACTION_REFRESH);
        views.setOnClickPendingIntent(R.id.widget_stats_refresh, PendingIntent.getBroadcast(context, 0, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        String baseUrl = PreferenceHelper.getInstance().getDawarichBaseUrl();
        Intent open = Strings.isNullOrEmpty(baseUrl) ? refresh
                : new Intent(Intent.ACTION_VIEW, Uri.parse(baseUrl));
        views.setOnClickPendingIntent(R.id.widget_stats_root, Strings.isNullOrEmpty(baseUrl)
                ? PendingIntent.getBroadcast(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        manager.updateAppWidget(id, views);
    }
}
