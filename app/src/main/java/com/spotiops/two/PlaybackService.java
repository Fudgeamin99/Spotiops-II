package com.spotiops.two;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Keeps music playing while the app is in the background and shows the
 * playback notification with lock-screen and headset controls.
 */
public class PlaybackService extends Service {

    static final String CHANNEL = "playback";
    static final int NOTIFICATION_ID = 7;

    static PlaybackService instance;

    static String title = "";
    static String artist = "";
    static String album = "";
    static String artUrl = "";
    static boolean playing = false;

    private MediaSession session;
    private Bitmap art;
    private String artLoadedFor = "";
    private String artLoading = "";
    private boolean inForeground = false;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final Handler main = new Handler(Looper.getMainLooper());

    static void update(Context context, String t, String a, String al, String u, boolean p) {
        title = t == null ? "" : t;
        artist = a == null ? "" : a;
        album = al == null ? "" : al;
        artUrl = u == null ? "" : u;
        playing = p;
        if (instance != null) {
            instance.refresh();
            return;
        }
        if (!p) return; // only start the service once something actually plays
        Intent i = new Intent(context, PlaybackService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
        } catch (Exception ignored) {
        }
    }

    static void stop() {
        if (instance != null) instance.shutdown();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setDescription("Shows what's playing, with playback controls");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpotiopsII:playback");
        wakeLock.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            @SuppressWarnings("deprecation")
            int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "SpotiopsII:playback");
            wifiLock.setReferenceCounted(false);
        }

        session = new MediaSession(this, "SpotiopsII");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MainActivity.command("play"); }
            @Override public void onPause() { MainActivity.command("pause"); }
            @Override public void onSkipToNext() { MainActivity.command("next"); }
            @Override public void onSkipToPrevious() { MainActivity.command("prev"); }
            @Override public void onStop() { MainActivity.command("pause"); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String cmd = intent != null ? intent.getStringExtra("cmd") : null;
        if (cmd != null) MainActivity.command(cmd);
        refresh();
        return START_NOT_STICKY;
    }

    void refresh() {
        PlaybackState state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f)
                .build();
        session.setPlaybackState(state);

        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album);
        boolean artReady = art != null && artUrl.equals(artLoadedFor);
        if (artReady) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(md.build());

        Notification n = buildNotification(artReady);
        if (!inForeground) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            inForeground = true;
        } else {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID, n);
        }

        if (playing) {
            if (!wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L);
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } else {
            if (wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        }

        if (!artUrl.isEmpty() && !artUrl.equals(artLoadedFor) && !artUrl.equals(artLoading)) loadArt(artUrl);
    }

    private Notification buildNotification(boolean artReady) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        b.setSmallIcon(R.drawable.ic_stat_note)
                .setContentTitle(title)
                .setContentText(artist)
                .setSubText(album)
                .setContentIntent(content)
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(0xFFFF7A1A)
                .addAction(action(android.R.drawable.ic_media_previous, "Previous", "prev", 1))
                .addAction(playing
                        ? action(android.R.drawable.ic_media_pause, "Pause", "toggle", 2)
                        : action(android.R.drawable.ic_media_play, "Play", "toggle", 2))
                .addAction(action(android.R.drawable.ic_media_next, "Next", "next", 3))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (artReady) b.setLargeIcon(art);
        return b.build();
    }

    private Notification.Action action(int iconRes, String label, String cmd, int requestCode) {
        Intent i = new Intent(this, PlaybackService.class).putExtra("cmd", cmd);
        PendingIntent pi = PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(this, iconRes), label, pi).build();
    }

    private void loadArt(final String url) {
        artLoading = url;
        new Thread(() -> {
            Bitmap bmp = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                InputStream in = conn.getInputStream();
                bmp = BitmapFactory.decodeStream(in);
                in.close();
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
            final Bitmap result = bmp;
            main.post(() -> {
                if (instance != PlaybackService.this) return;
                artLoading = "";
                if (result != null && url.equals(artUrl)) {
                    art = result;
                    artLoadedFor = url;
                    refresh();
                }
            });
        }).start();
    }

    void shutdown() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        inForeground = false;
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The app was swiped away from recents: stop the music like most players do.
        MainActivity.command("pause");
        shutdown();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (session != null) session.release();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
