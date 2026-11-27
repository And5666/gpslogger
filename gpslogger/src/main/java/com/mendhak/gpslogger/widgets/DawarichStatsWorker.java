package com.mendhak.gpslogger.widgets;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.common.Strings;
import com.mendhak.gpslogger.common.slf4j.Logs;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches the last seven days of stats from Dawarich for the stats widget, using the server
 * and API key from GPSLogger's Dawarich settings.
 * <ul>
 *   <li>{@code GET /api/v1/insights?year=} for the daily distances and current streak</li>
 *   <li>{@code GET /api/v1/points?start_at=&per_page=1} for point counts: with one point per
 *   page, the X-Total-Pages header is the number of points</li>
 * </ul>
 */
public class DawarichStatsWorker extends Worker {

    private static final Logger LOG = Logs.of(DawarichStatsWorker.class);

    static final String PERIODIC_WORK_NAME = "DawarichStatsWidgetPeriodic";
    static final String REFRESH_WORK_NAME = "DawarichStatsWidgetRefresh";

    public DawarichStatsWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    private static Constraints networkConstraint() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    static void schedulePeriodic(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(DawarichStatsWorker.class, 30, TimeUnit.MINUTES)
                .setConstraints(networkConstraint())
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    static void cancelAll(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME);
        WorkManager.getInstance(context).cancelUniqueWork(REFRESH_WORK_NAME);
    }

    /** Refreshes the stats widgets soon, if there are any */
    public static void refreshSoon(Context context, long delaySeconds) {
        if (!DawarichStatsWidgetProvider.hasWidgets(context)) {
            return;
        }
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DawarichStatsWorker.class)
                .setConstraints(networkConstraint())
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(REFRESH_WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        PreferenceHelper prefs = PreferenceHelper.getInstance();
        String baseUrl = prefs.getDawarichBaseUrl();
        String apiKey = prefs.getDawarichApikey();

        if (Strings.isNullOrEmpty(baseUrl) || Strings.isNullOrEmpty(apiKey)) {
            WidgetState.setStatsError(context, context.getString(R.string.widget_stats_not_configured));
            DawarichStatsWidgetProvider.finishRefresh(context);
            return Result.success();
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        try {
            Calendar today = Calendar.getInstance();
            int year = today.get(Calendar.YEAR);
            JSONObject thisYear = getJson(client, baseUrl, apiKey, "/api/v1/insights", "year", String.valueOf(year));

            // The week can start in last year
            Calendar weekStart = startOfDay((Calendar) today.clone());
            weekStart.add(Calendar.DAY_OF_MONTH, -(DawarichWeekStats.DAYS - 1));
            JSONObject lastYear = weekStart.get(Calendar.YEAR) != year
                    ? getJson(client, baseUrl, apiKey, "/api/v1/insights", "year", String.valueOf(year - 1))
                    : null;

            long todayStart = startOfDay((Calendar) today.clone()).getTimeInMillis() / 1000;
            long pointsToday = countPoints(client, baseUrl, apiKey, todayStart);
            long pointsWeek = countPoints(client, baseUrl, apiKey, weekStart.getTimeInMillis() / 1000);

            DawarichWeekStats stats = DawarichWeekStats.fromInsights(today, thisYear, lastYear,
                    pointsToday, pointsWeek, System.currentTimeMillis());
            WidgetState.setStats(context, stats);
            LOG.debug("Dawarich stats widget updated");
        } catch (HttpError e) {
            LOG.warn("Dawarich stats widget: {} returned HTTP {}", e.path, e.code);
            WidgetState.setStatsError(context, messageFor(context, e.code));
        } catch (IOException | JSONException | RuntimeException e) {
            LOG.warn("Dawarich stats widget: could not get stats: {}", e.getMessage());
            WidgetState.setStatsError(context, context.getString(R.string.widget_stats_unreachable));
        }

        DawarichStatsWidgetProvider.finishRefresh(context);
        return Result.success();
    }

    private static String messageFor(Context context, int code) {
        if (code == 401) {
            return context.getString(R.string.widget_stats_unauthorized);
        }
        if (code == 404) {
            return context.getString(R.string.widget_stats_too_old);
        }
        return context.getString(R.string.widget_stats_http_error, code);
    }

    private static Calendar startOfDay(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static final class HttpError extends IOException {
        final int code;
        final String path;

        HttpError(String path, int code) {
            super(path + " returned HTTP " + code);
            this.path = path;
            this.code = code;
        }
    }

    private static Request request(String baseUrl, String apiKey, String path, String... query) {
        HttpUrl base = HttpUrl.parse(baseUrl + path);
        if (base == null) {
            throw new IllegalArgumentException("Invalid Dawarich URL");
        }
        HttpUrl.Builder url = base.newBuilder();
        for (int i = 0; i + 1 < query.length; i += 2) {
            url.addQueryParameter(query[i], query[i + 1]);
        }
        return new Request.Builder()
                .url(url.build())
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Accept", "application/json")
                .get()
                .build();
    }

    private static JSONObject getJson(OkHttpClient client, String baseUrl, String apiKey, String path, String... query)
            throws IOException, JSONException {
        try (Response response = client.newCall(request(baseUrl, apiKey, path, query)).execute()) {
            if (!response.isSuccessful()) {
                throw new HttpError(path, response.code());
            }
            return new JSONObject(response.body() == null ? "" : response.body().string());
        }
    }

    /** Points since the given time, or -1 if Dawarich didn't say */
    private static long countPoints(OkHttpClient client, String baseUrl, String apiKey, long startAtSeconds) throws IOException {
        String path = "/api/v1/points";
        try (Response response = client.newCall(request(baseUrl, apiKey, path,
                "start_at", String.valueOf(startAtSeconds), "per_page", "1", "slim", "true")).execute()) {
            if (!response.isSuccessful()) {
                throw new HttpError(path, response.code());
            }
            String total = response.header("X-Total-Pages");
            try {
                return total == null ? -1 : Long.parseLong(total.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }
}
