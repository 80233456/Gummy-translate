package com.gummytranslate.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Build;
import android.content.pm.PackageManager;

/**
 * Keeps an active classroom process and CPU alive while the microphone and WebSocket are running.
 * The translation engine will move into this service in the next 0.4 engineering milestone.
 */
public class ClassroomSessionService extends Service {
    private static final String CHANNEL_ID = "classroom_session";
    private static final int NOTIFICATION_ID = 401;
    private static volatile ClassroomSessionService activeInstance;
    private PowerManager.WakeLock wakeLock;
    private String currentStatus = "正在准备实时翻译";

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, ClassroomSessionService.class));
    }

    public static void updateStatus(String status) {
        ClassroomSessionService instance = activeInstance;
        if (instance != null) instance.showNotification(status);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ClassroomSessionService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        activeInstance = this;
        createNotificationChannel();
        acquireWakeLock();
        startForeground(NOTIFICATION_ID, buildNotification(currentStatus));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification(currentStatus));
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "正在上课",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持实时翻译在锁屏和后台继续运行");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void acquireWakeLock() {
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "GummyTranslate:ClassroomSession");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private Notification buildNotification(String status) {
        Intent open = new Intent(this, LiveActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Gummy 外教翻译 · 正在上课")
                .setContentText(status)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void showNotification(String status) {
        currentStatus = status;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(status));
    }

    @Override public void onDestroy() {
        activeInstance = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
