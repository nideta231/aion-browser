package com.nath.aion.webapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.mozilla.geckoview.MediaSession;

/**
 * Keeps the app alive while the site plays audio in the background (for example YT Music with the
 * screen off), and shows play/pause/next controls in the notification.
 */
public class MediaPlaybackService extends Service {
    private static final String CHANNEL = "playback";
    private static final String ACTION_UPDATE = "update";
    private static final String ACTION_TOGGLE = "toggle";
    private static final String ACTION_NEXT = "next";
    private static final String ACTION_PREVIOUS = "previous";
    private static final int NOTIFICATION_ID = 1;

    private static MediaSession mediaSession;
    private static boolean playing;
    private static String title = "";
    private static String artist = "";
    private static boolean running;

    static void attach(MediaSession session) {
        mediaSession = session;
    }

    static void start(Context context, boolean isPlaying) {
        playing = isPlaying;
        Intent intent = new Intent(context, MediaPlaybackService.class).setAction(ACTION_UPDATE);
        if (isPlaying || running) {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        }
    }

    static void updateMetadata(Context context, String newTitle, String newArtist) {
        title = newTitle == null ? "" : newTitle;
        artist = newArtist == null ? "" : newArtist;
        if (running) start(context, playing);
    }

    static void stop(Context context) {
        playing = false;
        if (running) context.stopService(new Intent(context, MediaPlaybackService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (mediaSession != null && action != null) {
            switch (action) {
                case ACTION_TOGGLE:
                    if (playing) mediaSession.pause(); else mediaSession.play();
                    break;
                case ACTION_NEXT:
                    mediaSession.nextTrack();
                    break;
                case ACTION_PREVIOUS:
                    mediaSession.previousTrack();
                    break;
                default:
                    break;
            }
        }
        running = true;
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        if (!playing && ACTION_UPDATE.equals(action)) stopForeground(STOP_FOREGROUND_DETACH);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private PendingIntent action(String name, int code) {
        Intent intent = new Intent(this, MediaPlaybackService.class).setAction(name);
        return PendingIntent.getService(this, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, WebAppActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        CharSequence label = getApplicationInfo().loadLabel(getPackageManager());
        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.isEmpty() ? label : title)
                .setContentText(artist.isEmpty() ? label : artist)
                .setContentIntent(content)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "Previous", action(ACTION_PREVIOUS, 1)).build())
                .addAction(new Notification.Action.Builder(null, playing ? "Pause" : "Play",
                        action(ACTION_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(null, "Next", action(ACTION_NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2));
        return builder.build();
    }
}
