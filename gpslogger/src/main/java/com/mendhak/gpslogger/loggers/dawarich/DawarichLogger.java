package com.mendhak.gpslogger.loggers.dawarich;

import android.location.Location;
import com.mendhak.gpslogger.common.*;
import com.mendhak.gpslogger.loggers.FileLogger;
import com.mendhak.gpslogger.senders.dawarich.DawarichManager;
import com.mendhak.gpslogger.widgets.QueueWidgetProvider;

public class DawarichLogger implements FileLogger {

    private static final org.slf4j.Logger LOG = com.mendhak.gpslogger.common.slf4j.Logs.of(DawarichLogger.class);

    private final SerializableFIFOBuffer<SerializableLocation> buffer;

    public DawarichLogger (String persistenceFilePath) {
        this.buffer = SerializableFIFOBuffer.getInstance(DawarichManager.getBufferPath(persistenceFilePath));
    }

    @Override
    public void write(Location loc) throws Exception {
        if (!Session.getInstance().hasDescription()) {
            annotate("", loc);
        }
    }

    @Override
    public void annotate(String description, Location loc) throws Exception {
        PreferenceHelper preferenceHelper = PreferenceHelper.getInstance();
        SerializableLocation sloc = new SerializableLocation(loc);

        if(!Systems.isNetworkAvailable((AppSettings.getInstance())) && preferenceHelper.shouldDawarichLoggingDiscardOfflineLocations()) {
            LOG.info("Dawarich: offline and 'Discard offline locations' is on, so this point was not queued");
            return;
        }

        BatteryInfo batteryInfo = Systems.getBatteryInfo(AppSettings.getInstance());
        sloc.setBatteryLevel(batteryInfo.BatteryLevel);
        sloc.setBatteryCharging(batteryInfo.IsCharging);
        sloc.setMotion(preferenceHelper.getDawarichMotion());
        sloc.setActivity(preferenceHelper.getDawarichActivity());

        this.buffer.push(sloc);
        QueueWidgetProvider.refresh(AppSettings.getInstance());

        int queued = this.buffer.getSize();
        int minimum = preferenceHelper.getDawarichBatchMin();
        if (queued >= minimum) {
            new DawarichManager(preferenceHelper).uploadQueue();
        } else {
            LOG.info("Dawarich: {} of {} points queued, uploading when the minimum is reached", queued, minimum);
        }

    }

    @Override
    public String getName() {
        return "DAWARICH";
    }
}
