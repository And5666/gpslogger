/*
 * Copyright (C) 2026 Jan-NiklasB
 *
 * This file is part of GPSLogger for Android.
 *
 * GPSLogger for Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * GPSLogger for Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GPSLogger for Android.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mendhak.gpslogger.senders.dawarich;

import androidx.work.ExistingWorkPolicy;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.mendhak.gpslogger.common.AppSettings;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.common.Strings;
import com.mendhak.gpslogger.common.Systems;
import com.mendhak.gpslogger.common.slf4j.Logs;
import com.mendhak.gpslogger.senders.FileSender;
import org.slf4j.Logger;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class DawarichManager extends FileSender {

    private static final Logger LOG = Logs.of(DawarichManager.class);

    public static final String UPLOAD_WORK_NAME = "DawarichUpload";

    private final PreferenceHelper preferenceHelper;

    public DawarichManager(PreferenceHelper preferenceHelper) {
        this.preferenceHelper = preferenceHelper;
    }

    public static String getBufferPath(String folder) {
        return folder + "/buffer.json";
    }

    // If an upload is already pending or running it sends the new locations too, so nothing new is scheduled
    public void uploadQueue() {
        if (!isAvailable()) {
            LOG.warn("Dawarich: server URL or API key is missing, not uploading");
            return;
        }

        HashMap<String, Object> dataMap = new HashMap<>();
        dataMap.put(DawarichWorker.BUFFER_PATH, getBufferPath(preferenceHelper.getGpsLoggerFolder()));
        Systems.startWorkManagerRequest(DawarichWorker.class, dataMap, UPLOAD_WORK_NAME, ExistingWorkPolicy.KEEP);
        LOG.info("Dawarich: upload scheduled{}, it starts once there's a working internet connection",
                preferenceHelper.shouldAutoSendOnWifiOnly() ? " (Wi-Fi only)" : "");
    }

    /**
     * A manual send, e.g. from the widget: unlike uploadQueue it doesn't wait out a retry delay
     * from an earlier failure. Leaves an upload that's running alone. Blocks, so call it off the
     * main thread.
     */
    public void uploadQueueNow() {
        if (!isAvailable()) {
            LOG.warn("Dawarich: server URL or API key is missing, not uploading");
            return;
        }
        try {
            for (WorkInfo info : WorkManager.getInstance(AppSettings.getInstance())
                    .getWorkInfosForUniqueWork(UPLOAD_WORK_NAME).get(5, TimeUnit.SECONDS)) {
                if (info.getState() == WorkInfo.State.RUNNING) {
                    LOG.info("Dawarich: an upload is already running");
                    return;
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not check the Dawarich upload state", e);
        }

        HashMap<String, Object> dataMap = new HashMap<>();
        dataMap.put(DawarichWorker.BUFFER_PATH, getBufferPath(preferenceHelper.getGpsLoggerFolder()));
        Systems.startWorkManagerRequest(DawarichWorker.class, dataMap, UPLOAD_WORK_NAME, ExistingWorkPolicy.REPLACE);
        LOG.info("Dawarich: sending now{}, it starts once there's a working internet connection",
                preferenceHelper.shouldAutoSendOnWifiOnly() ? " (Wi-Fi only)" : "");
    }

    // Dawarich doesn't upload files, an auto send or manual upload sends the queued locations instead
    @Override
    public void uploadFile(List<File> files) {
        uploadQueue();
    }

    @Override
    public boolean isAvailable() {
        return !Strings.isNullOrEmpty(preferenceHelper.getDawarichBaseUrl()) &&
                !Strings.isNullOrEmpty(preferenceHelper.getDawarichApikey());
    }

    @Override
    public boolean hasUserAllowedAutoSending() {
        return preferenceHelper.isDawarichAutoSendEnabled();
    }

    @Override
    public String getName() {
        return SenderNames.DAWARICH;
    }

    @Override
    public boolean accept(File file, String s) {
        return false;
    }
}
