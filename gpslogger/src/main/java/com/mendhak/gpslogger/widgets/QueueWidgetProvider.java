package com.mendhak.gpslogger.widgets;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.mendhak.gpslogger.GpsLoggingService;
import com.mendhak.gpslogger.GpsMainActivity;
import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.IntentConstants;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.common.SerializableFIFOBuffer;
import com.mendhak.gpslogger.common.SerializableLocation;
import com.mendhak.gpslogger.common.Session;
import com.mendhak.gpslogger.common.Strings;
import com.mendhak.gpslogger.common.slf4j.Logs;
import com.mendhak.gpslogger.senders.dawarich.DawarichManager;
import com.mendhak.gpslogger.senders.dawarich.DawarichWorker;

import org.slf4j.Logger;

import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Home screen widget for GPSLogger's Dawarich queue: how many points are waiting, a Send now
 * button, and a switch for smart mode's deep sleep (shown when smart mode is on).
 */
public class QueueWidgetProvider extends AppWidgetProvider {

    private static final Logger LOG = Logs.of(QueueWidgetProvider.class);

    static final String ACTION_SEND_NOW = "com.mendhak.gpslogger.widgets.QUEUE_SEND_NOW";
    static final String ACTION_TOGGLE_DEEP_SLEEP = "com.mendhak.gpslogger.widgets.QUEUE_TOGGLE_DEEP_SLEEP";

    // Reading the queue and the upload state can block, so it's done off the main thread, one at a time
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refresh(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_SEND_NOW.equals(action)) {
            PendingResult pending = goAsync();
            Context app = context.getApplicationContext();
            EXECUTOR.execute(() -> {
                try {
                    LOG.info("Dawarich: Send now pressed on the widget");
                    new DawarichManager(PreferenceHelper.getInstance()).uploadQueueNow();
                    renderAll(app);
                } finally {
                    pending.finish();
                }
            });
        } else if (ACTION_TOGGLE_DEEP_SLEEP.equals(action)) {
            PreferenceHelper prefs = PreferenceHelper.getInstance();
            boolean enable = !prefs.isSmartDeepSleepEnabled();
            prefs.setSmartDeepSleepEnabled(enable);
            LOG.info("Smart mode: deep sleep turned {} from the widget", enable ? "on" : "off");
            notifyService(context);
            refresh(context);
        }
    }

    /** Lets a running logging session reschedule its next stationary check */
    private static void notifyService(Context context) {
        if (!Session.getInstance().isStarted()) {
            return;
        }
        try {
            Intent serviceIntent = new Intent(context, GpsLoggingService.class);
            serviceIntent.putExtra(IntentConstants.SMART_SETTINGS_CHANGED, true);
            ContextCompat.startForegroundService(context, serviceIntent);
        } catch (RuntimeException e) {
            // The next check picks up the new setting anyway
            LOG.warn("Could not tell the logging service about the deep sleep change: {}", e.getMessage());
        }
    }

    /** Redraws the queue widgets, if there are any. Safe to call from any thread. */
    public static void refresh(Context context) {
        Context app = context.getApplicationContext();
        if (!hasWidgets(app)) {
            return;
        }
        EXECUTOR.execute(() -> renderAll(app));
    }

    private static boolean hasWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        return manager != null && manager.getAppWidgetIds(new ComponentName(context, QueueWidgetProvider.class)).length > 0;
    }

    private static String formatTime(Context context, long millis) {
        String time = DateFormat.getTimeFormat(context).format(new Date(millis));
        return DateUtils.isToday(millis) ? time
                : DateFormat.getMediumDateFormat(context).format(new Date(millis)) + " " + time;
    }

    private static int queuedPoints(PreferenceHelper prefs) {
        String path = DawarichManager.getBufferPath(prefs.getGpsLoggerFolder());
        if (!new File(path).exists()) {
            return 0;
        }
        SerializableFIFOBuffer<SerializableLocation> buffer = SerializableFIFOBuffer.getInstance(path);
        return buffer.getSize();
    }

    private static WorkInfo.State uploadState(Context context) {
        try {
            // WorkManager still says RUNNING while the worker refreshes the widget on its way out,
            // so ask the worker itself
            if (DawarichWorker.isUploading()) {
                return WorkInfo.State.RUNNING;
            }
            List<WorkInfo> infos = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(DawarichManager.UPLOAD_WORK_NAME).get(3, TimeUnit.SECONDS);
            for (WorkInfo info : infos) {
                if (info.getState() == WorkInfo.State.ENQUEUED || info.getState() == WorkInfo.State.BLOCKED) {
                    return WorkInfo.State.ENQUEUED;
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not read the Dawarich upload state", e);
        }
        return null;
    }

    private static void renderAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) {
            return;
        }
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, QueueWidgetProvider.class));
        if (ids.length == 0) {
            return;
        }
        RemoteViews views = build(context);
        for (int id : ids) {
            manager.updateAppWidget(id, views);
        }
    }

    private static RemoteViews build(Context context) {
        PreferenceHelper prefs = PreferenceHelper.getInstance();
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_queue);

        int count = queuedPoints(prefs);
        views.setTextViewText(R.id.widget_queue_count, String.format(Locale.getDefault(), "%,d", count));
        views.setTextViewText(R.id.widget_queue_label, context.getString(count == 1
                ? R.string.widget_queue_point_waiting : R.string.widget_queue_points_waiting));

        String status;
        if (Strings.isNullOrEmpty(prefs.getDawarichBaseUrl()) || Strings.isNullOrEmpty(prefs.getDawarichApikey())) {
            status = context.getString(R.string.widget_queue_not_configured);
        } else {
            WorkInfo.State state = uploadState(context);
            long lastSent = WidgetState.getLastSent(context);
            long lastFailed = WidgetState.getLastFailed(context);
            if (state == WorkInfo.State.RUNNING) {
                status = context.getString(R.string.widget_queue_sending);
            } else if (state == WorkInfo.State.ENQUEUED && count > 0) {
                status = context.getString(R.string.widget_queue_waiting);
            } else if (lastFailed > lastSent) {
                status = context.getString(R.string.widget_queue_last_failed, formatTime(context, lastFailed));
            } else if (lastSent > 0) {
                status = context.getString(R.string.widget_queue_last_sent, formatTime(context, lastSent));
            } else {
                status = context.getString(R.string.widget_queue_nothing_sent);
            }
            if (!prefs.shouldLogToDawarich()) {
                status = context.getString(R.string.widget_queue_logging_off) + " · " + status;
            }
        }
        views.setTextViewText(R.id.widget_queue_status, status);

        views.setOnClickPendingIntent(R.id.widget_queue_send, PendingIntent.getBroadcast(context, 0,
                new Intent(context, QueueWidgetProvider.class).setAction(ACTION_SEND_NOW),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        if (prefs.isSmartStationaryModeEnabled()) {
            boolean enabled = prefs.isSmartDeepSleepEnabled();
            boolean sleeping = enabled && Session.getInstance().isStarted() && Session.getInstance().isSmartDeepSleep();
            views.setViewVisibility(R.id.widget_queue_deep_sleep, View.VISIBLE);
            views.setTextViewText(R.id.widget_queue_deep_sleep, context.getString(sleeping ? R.string.widget_deep_sleep_active
                    : enabled ? R.string.widget_deep_sleep_on : R.string.widget_deep_sleep_off));
            views.setInt(R.id.widget_queue_deep_sleep, "setBackgroundResource",
                    enabled ? R.drawable.widget_button_filled : R.drawable.widget_button_outline);
            views.setTextColor(R.id.widget_queue_deep_sleep, ContextCompat.getColor(context,
                    enabled ? R.color.widget_on_accent : R.color.widget_accent));
            views.setOnClickPendingIntent(R.id.widget_queue_deep_sleep, PendingIntent.getBroadcast(context, 1,
                    new Intent(context, QueueWidgetProvider.class).setAction(ACTION_TOGGLE_DEEP_SLEEP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            views.setViewVisibility(R.id.widget_queue_deep_sleep, View.GONE);
        }

        Intent open = new Intent(context, GpsMainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        views.setOnClickPendingIntent(R.id.widget_queue_root, PendingIntent.getActivity(context, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return views;
    }
}
