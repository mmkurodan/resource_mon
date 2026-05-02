package com.micklab.resource_mon;

import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.text.format.DateUtils;
import android.text.format.Formatter;

public final class MonitorForegroundService extends Service {
    private static final String CHANNEL_ID = "monitoring";
    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_START = "com.micklab.resource_mon.action.START";
    private static final String ACTION_STOP = "com.micklab.resource_mon.action.STOP";

    public static void start(Context context) {
        Intent intent = new Intent(context, MonitorForegroundService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, MonitorForegroundService.class);
        intent.setAction(ACTION_STOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private MetricsSampler sampler;
    private MonitorStore monitorStore;

    private final MetricsSampler.Listener listener = new MetricsSampler.Listener() {
        @Override
        public void onSample(MetricsSampler.MetricsSnapshot snapshot) {
            monitorStore.appendSample(snapshot);
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, buildNotification(snapshot));
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        monitorStore = MonitorStore.get(this);
        sampler = new MetricsSampler(this, 1000L);
        sampler.addListener(listener);
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            monitorStore.setMonitoringEnabled(false);
            stopSampler();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!monitorStore.isMonitoringEnabled() && action == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        monitorStore.setMonitoringEnabled(true);
        startForeground(NOTIFICATION_ID, buildNotification(monitorStore.getLatestSample()));
        sampler.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopSampler();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopSampler() {
        if (sampler != null) {
            sampler.stop();
        }
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }
        NotificationChannel existing = notificationManager.getNotificationChannel(CHANNEL_ID);
        if (existing != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Resource monitor",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Background resource monitoring");
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(MetricsSampler.MetricsSnapshot snapshot) {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                1,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent stopIntent = PendingIntent.getService(
                this,
                2,
                new Intent(this, MonitorForegroundService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent detailIntent = PendingIntent.getActivity(
                this,
                3,
                new Intent(this, ProcessDetailsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String title = "Resource monitor recording";
        String contentText = snapshot == null
                ? "Starting background monitoring..."
                : "CPU " + formatPercent(snapshot.cpuUsagePercent)
                + " | RAM " + formatPercent(snapshot.ramUsagePercent())
                + " | Cached " + Formatter.formatShortFileSize(this, snapshot.memoryDetails.cacheLikeBytes());

        String bigText = snapshot == null
                ? "Waiting for the first sample."
                : "CPU usage: " + formatPercent(snapshot.cpuUsagePercent)
                + " | CPU avg freq: " + String.format(java.util.Locale.US, "%.0f MHz", snapshot.cpuAverageMhz)
                + "\nRAM: " + Formatter.formatShortFileSize(this, snapshot.memoryDetails.usedBytes)
                + " / " + Formatter.formatShortFileSize(this, snapshot.memoryDetails.totalBytes)
                + " | Available: " + Formatter.formatShortFileSize(this, snapshot.memoryDetails.availableBytes)
                + " | Cached: " + Formatter.formatShortFileSize(this, snapshot.memoryDetails.cachedBytes)
                + "\nRecorded: " + DateUtils.getRelativeTimeSpanString(
                        snapshot.timestampMillis,
                        System.currentTimeMillis(),
                        DateUtils.SECOND_IN_MILLIS);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(title)
                .setContentText(contentText)
                .setStyle(new Notification.BigTextStyle().bigText(bigText))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Action.Builder(
                        android.R.drawable.ic_menu_recent_history,
                        "Details",
                        detailIntent).build())
                .addAction(new Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop",
                        stopIntent).build());
        return builder.build();
    }

    private String formatPercent(double value) {
        return String.format(java.util.Locale.US, "%.1f%%", Math.max(0d, value));
    }
}
