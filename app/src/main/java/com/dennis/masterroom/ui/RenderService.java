package com.dennis.masterroom.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import com.dennis.masterroom.R;

/** Keeps a render or save going with a notification while you use something else. */
public final class RenderService extends Service {
    private static final String WORKING = "render", DONE = "finished";
    private static final int WORK_ID = 1, DONE_ID = 2;

    private static NotificationManager manager(Context c) {
        NotificationManager m = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        m.createNotificationChannel(new NotificationChannel(WORKING, "Mastering in progress", NotificationManager.IMPORTANCE_LOW));
        m.createNotificationChannel(new NotificationChannel(DONE, "Mastering finished", NotificationManager.IMPORTANCE_DEFAULT));
        return m;
    }

    private static Notification.Builder base(Context c, String channel, String text) {
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(c, channel).setSmallIcon(R.drawable.ic_stat).setContentTitle("Master Room")
                .setContentText(text).setContentIntent(tap);
    }

    private static Notification working(Context c, String text, int percent) {
        return base(c, WORKING, text).setOngoing(true).setOnlyAlertOnce(true).setProgress(100, percent, percent < 0).build();
    }

    static void begin(Context c) {
        c.startForegroundService(new Intent(c, RenderService.class));
    }

    static void update(Context c, String text, int percent) {
        manager(c).notify(WORK_ID, working(c, text, percent));
    }

    static void end(Context c, String result) {
        c.stopService(new Intent(c, RenderService.class));
        if (result != null) manager(c).notify(DONE_ID, base(c, DONE, result).setAutoCancel(true).build());
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        manager(this);
        startForeground(WORK_ID, working(this, "Starting", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
