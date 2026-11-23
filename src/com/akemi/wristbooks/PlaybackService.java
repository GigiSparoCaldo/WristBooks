package com.akemi.wristbooks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

public class PlaybackService extends Service {
    public class LocalBinder extends Binder {
        public PlaybackService getService() { return PlaybackService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private MediaPlayer mp;
    private MediaSession mediaSession;
    private AudioFocusRequest focusRequest;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public BookModel.Book currentBook;
    public int currentChapterIdx = 0;
    private long pausedChapterPosMs = 0;
    private String loadedFilePath = "";
    private boolean lastUsedBt = false;
    public float speed = 1.0f;

    public long sleepEndEpochMs = 0;
    public boolean sleepEndOfChapter = false;

    private BroadcastReceiver noisyReceiver;
    private BroadcastReceiver systemStateReceiver;
    private int initialInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL;
    private AudioDeviceCallback deviceCallback;
    private SensorEventListener offBodyListener;

    private final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (isPlaying()) pause();
        }
    };

    private final Runnable backupSaveTask = new Runnable() {
        @Override
        public void run() {
            if (isPlaying()) {
                saveProgress();
                handler.postDelayed(this, 180_000L);
            }
        }
    };

    private final Runnable chapterBoundaryTask = () -> handleChapterBoundary();
    private final Runnable sleepTimerTask = () -> handleSleepTimer();

    @Override
    public void onCreate() {
        super.onCreate();
        speed = getSharedPreferences("wb_prefs", MODE_PRIVATE).getFloat("speed", 1.0f);
        createNotifChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            if ("WB_REW".equals(act)) skipSeconds(-15);
            else if ("WB_PAUSE".equals(act)) pause();
            else if ("WB_FWD".equals(act)) skipSeconds(15);
        }
        return START_NOT_STICKY;
    }

    private void activateWatchersAndSession() {
        if (mediaSession == null) {
            mediaSession = new MediaSession(this, "WristBooksSession");
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { resume(); }
                @Override public void onPause() { pause(); }
                @Override public void onSkipToNext() { skipSeconds(15); }
                @Override public void onSkipToPrevious() { skipSeconds(-15); }
                @Override public void onSeekTo(long pos) { seekChapterTo(pos); }
            });
            mediaSession.setActive(true);
        }
        if (noisyReceiver == null) {
            noisyReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent it) {
                    if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(it.getAction())) pause();
                }
            };
            registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        }
        if (systemStateReceiver == null) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) initialInterruptionFilter = nm.getCurrentInterruptionFilter();
            systemStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent it) {
                    String act = it.getAction();
                    if (NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(act)) {
                        NotificationManager m = getSystemService(NotificationManager.class);
                        if (m != null) {
                            int cur = m.getCurrentInterruptionFilter();
                            if (cur != NotificationManager.INTERRUPTION_FILTER_ALL
                                    && cur != initialInterruptionFilter && isPlaying()) pause();
                        }
                    } else if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(act)) {
                        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                        if (pm != null && pm.isPowerSaveMode() && isPlaying()) pause();
                    } else if (Intent.ACTION_SCREEN_ON.equals(act)) {
                        if (isPlaying()) { saveProgress(); updatePlaybackState(); }
                    }
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
            f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            f.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(systemStateReceiver, f);
        }
        if (Build.VERSION.SDK_INT >= 28 && deviceCallback == null) {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                deviceCallback = new AudioDeviceCallback() {
                    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] a) { switchRouteIfPlaying(); }
                    @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] r) { switchRouteIfPlaying(); }
                };
                am.registerAudioDeviceCallback(deviceCallback, handler);
            }
        }
        if (offBodyListener == null) {
            SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
            if (sm != null) {
                Sensor s = sm.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);
                if (s != null) {
                    offBodyListener = new SensorEventListener() {
                        @Override public void onSensorChanged(SensorEvent ev) {
                            if (ev.values != null && ev.values.length > 0 && ev.values[0] == 0.0f && isPlaying()) pause();
                        }
                        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
                    };
                    sm.registerListener(offBodyListener, s, SensorManager.SENSOR_DELAY_NORMAL, handler);
                }
            }
        }
    }

    private void deactivateWatchersAndSession() {
        try { if (noisyReceiver != null) { unregisterReceiver(noisyReceiver); noisyReceiver = null; } }
        catch (Exception ignored) { noisyReceiver = null; }
        try { if (systemStateReceiver != null) { unregisterReceiver(systemStateReceiver); systemStateReceiver = null; } }
        catch (Exception ignored) { systemStateReceiver = null; }
        if (offBodyListener != null) {
            try {
                SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
                if (sm != null) sm.unregisterListener(offBodyListener);
            } catch (Exception ignored) {}
            offBodyListener = null;
        }
        if (Build.VERSION.SDK_INT >= 28 && deviceCallback != null) {
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null) am.unregisterAudioDeviceCallback(deviceCallback);
            } catch (Exception ignored) {}
            deviceCallback = null;
        }
        if (mediaSession != null) {
            try { mediaSession.setActive(false); mediaSession.release(); } catch (Exception ignored) {}
            mediaSession = null;
        }
        abandonAudioFocus();
    }

    private void createNotifChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("wb_play", "Riproduzione Audiolibri", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void promoteForeground() {
        if (currentBook == null || mediaSession == null) return;
        BookModel.Chapter ch = currentBook.chapters.get(currentChapterIdx);

        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, ch.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentBook.author)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, currentBook.title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, ch.durationMs);
        if (currentBook.cover != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentBook.cover);
        mediaSession.setMetadata(mb.build());
        updatePlaybackState();

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent piRew = PendingIntent.getService(this, 1, new Intent(this, PlaybackService.class).setAction("WB_REW"), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent piPause = PendingIntent.getService(this, 2, new Intent(this, PlaybackService.class).setAction("WB_PAUSE"), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent piFwd = PendingIntent.getService(this, 3, new Intent(this, PlaybackService.class).setAction("WB_FWD"), PendingIntent.FLAG_IMMUTABLE);

        if (Build.VERSION.SDK_INT >= 26) {
            Notification n = new Notification.Builder(this, "wb_play")
                    .setContentTitle(ch.title)
                    .setContentText(currentBook.title)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(pi)
                    .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_rew), "-15s", piRew).build())
                    .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Pausa", piPause).build())
                    .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_ff), "+15s", piFwd).build())
                    .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(202, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(202, n);
        }
    }

    private void updatePlaybackState() {
        if (mediaSession == null) return;
        PlaybackState ps = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(PlaybackState.STATE_PLAYING, getChapterPosMs(), speed)
                .build();
        mediaSession.setPlaybackState(ps);
    }

    private boolean hasBluetoothAudio() {
        if (Build.VERSION.SDK_INT < 28) return false;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || t == AudioDeviceInfo.TYPE_BLE_SPEAKER || t == AudioDeviceInfo.TYPE_HEARING_AID) return true;
        }
        return false;
    }

    private void routeAudioOutput(MediaPlayer player, boolean useBt) {
        if (player == null || Build.VERSION.SDK_INT < 28) return;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (useBt && (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || t == AudioDeviceInfo.TYPE_BLE_SPEAKER || t == AudioDeviceInfo.TYPE_HEARING_AID)) {
                player.setPreferredDevice(d);
                return;
            }
            if (!useBt && t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                player.setPreferredDevice(d);
                return;
            }
        }
    }

    private void switchRouteIfPlaying() {
        if (isPlaying() && currentBook != null && hasBluetoothAudio() != lastUsedBt) {
            loadBookChapter(currentBook, currentChapterIdx, getChapterPosMs(), true);
        }
    }

    public void loadBookChapter(BookModel.Book book, int chapIdx, long chapOffsetMs, boolean autoPlay) {
        if (book == null || book.chapters.isEmpty()) return;
        currentBook = book;
        currentChapterIdx = Math.max(0, Math.min(chapIdx, book.chapters.size() - 1));
        BookModel.Chapter ch = book.chapters.get(currentChapterIdx);
        pausedChapterPosMs = Math.max(0, Math.min(chapOffsetMs, ch.durationMs));
        boolean useBt = hasBluetoothAudio();

        try {
            if (mp == null || !ch.filePath.equals(loadedFilePath) || useBt != lastUsedBt) {
                releasePlayer();
                mp = new MediaPlayer();
                int usage = useBt ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
                mp.setDataSource(ch.filePath);
                mp.prepare();
                routeAudioOutput(mp, useBt);
                loadedFilePath = ch.filePath;
                lastUsedBt = useBt;
                mp.setOnCompletionListener(m -> onFileCompleted());
            }
            mp.seekTo((int) (ch.startMs + pausedChapterPosMs));
            applySpeedInternal();
            if (autoPlay) {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(new Intent(this, PlaybackService.class));
                activateWatchersAndSession();
                requestAudioFocus();
                promoteForeground();
                mp.start();
                cancelZeroPolling();
                scheduleZeroPolling();
                handler.postDelayed(backupSaveTask, 180_000L);
            }
            saveProgress();
        } catch (Exception ignored) {}
    }

    private void requestAudioFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener, handler)
                        .build();
            }
            am.requestAudioFocus(focusRequest);
        } else {
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
        else am.abandonAudioFocus(focusListener);
    }

    private void onFileCompleted() {
        if (sleepEndOfChapter) {
            sleepEndOfChapter = false;
            if (currentBook != null && currentChapterIdx + 1 < currentBook.chapters.size()) {
                currentChapterIdx++;
                pausedChapterPosMs = 0;
                saveProgress();
            }
            pause();
            return;
        }
        if (currentBook != null && currentChapterIdx + 1 < currentBook.chapters.size()) {
            loadBookChapter(currentBook, currentChapterIdx + 1, 0, true);
        } else {
            pause();
        }
    }

    public boolean isPlaying() {
        try { return mp != null && mp.isPlaying(); } catch (Exception e) { return false; }
    }

    public void togglePlayPause() {
        if (isPlaying()) pause();
        else resume();
    }

    public void resume() {
        if (currentBook == null) return;
        if (mp == null) {
            loadBookChapter(currentBook, currentChapterIdx, Math.max(0, pausedChapterPosMs - 3000), true);
            return;
        }
        if (!mp.isPlaying()) {
            int pos = Math.max(0, mp.getCurrentPosition() - 3000);
            BookModel.Chapter ch = currentBook.chapters.get(currentChapterIdx);
            if (pos < ch.startMs) pos = (int) ch.startMs;
            mp.seekTo(pos);
            mp.setVolume(1.0f, 1.0f);
            applySpeedInternal();
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(new Intent(this, PlaybackService.class));
            activateWatchersAndSession();
            requestAudioFocus();
            promoteForeground();
            mp.start();
            cancelZeroPolling();
            scheduleZeroPolling();
            handler.postDelayed(backupSaveTask, 180_000L);
        }
    }

    public void pause() {
        pausedChapterPosMs = getChapterPosMs();
        try { if (mp != null && mp.isPlaying()) mp.pause(); } catch (Exception ignored) {}
        cancelZeroPolling();
        saveProgress();
        releasePlayer();
        deactivateWatchersAndSession();
        stopForeground(true);
        stopSelf();
    }

    public void forceStopAll() {
        sleepEndEpochMs = 0;
        sleepEndOfChapter = false;
        pause();
    }

    private void cancelZeroPolling() {
        handler.removeCallbacks(chapterBoundaryTask);
        handler.removeCallbacks(sleepTimerTask);
        handler.removeCallbacks(backupSaveTask);
    }

    private void scheduleZeroPolling() {
        handler.removeCallbacks(chapterBoundaryTask);
        handler.removeCallbacks(sleepTimerTask);
        if (!isPlaying() || currentBook == null) return;

        BookModel.Chapter ch = currentBook.chapters.get(currentChapterIdx);
        if (ch.durationMs > 0) {
            long remAudioMs = Math.max(0, ch.durationMs - getChapterPosMs());
            long wallMs = (long) (remAudioMs / Math.max(0.5f, speed));
            long delay = (wallMs > 350L) ? (wallMs - 250L) : Math.max(50L, wallMs);
            handler.postDelayed(chapterBoundaryTask, delay);
        }

        if (sleepEndEpochMs > 0) {
            long left = sleepEndEpochMs - System.currentTimeMillis();
            if (left <= 0) { sleepEndEpochMs = 0; pause(); }
            else if (left <= 10_000L) {
                float v = Math.max(0.1f, left / 10_000f);
                if (mp != null) mp.setVolume(v, v);
                handler.postDelayed(sleepTimerTask, Math.min(1000L, left));
            } else {
                handler.postDelayed(sleepTimerTask, left - 10_000L);
            }
        }
    }

    private void handleChapterBoundary() {
        if (!isPlaying() || mp == null || currentBook == null) return;
        BookModel.Chapter ch = currentBook.chapters.get(currentChapterIdx);
        long chPos = getChapterPosMs();
        if (ch.durationMs > 0 && chPos >= ch.durationMs - 350L) {
            if (sleepEndOfChapter) {
                sleepEndOfChapter = false;
                if (currentChapterIdx + 1 < currentBook.chapters.size()) {
                    currentChapterIdx++;
                    pausedChapterPosMs = 0;
                    saveProgress();
                }
                pause();
                return;
            }
            if (currentChapterIdx + 1 < currentBook.chapters.size()) {
                BookModel.Chapter next = currentBook.chapters.get(currentChapterIdx + 1);
                if (next.filePath.equals(ch.filePath)) {
                    currentChapterIdx++;
                    saveProgress();
                    promoteForeground();
                    scheduleZeroPolling();
                } else {
                    handler.postDelayed(chapterBoundaryTask, 150L);
                }
                return;
            }
        }
        scheduleZeroPolling();
    }

    private void handleSleepTimer() {
        if (!isPlaying() || sleepEndEpochMs <= 0) return;
        long left = sleepEndEpochMs - System.currentTimeMillis();
        if (left <= 0) { sleepEndEpochMs = 0; pause(); }
        else if (left <= 10_000L) {
            float v = Math.max(0.1f, left / 10_000f);
            if (mp != null) mp.setVolume(v, v);
            handler.postDelayed(sleepTimerTask, Math.min(1000L, left));
        } else {
            handler.postDelayed(sleepTimerTask, left - 10_000L);
        }
    }

    public void skipSeconds(int sec) {
        if (currentBook == null) return;
        BookModel.Chapter ch = currentBook.chapters.get(currentChapterIdx);
        long newPos = getChapterPosMs() + (sec * 1000L);
        if (newPos < 0) {
            if (currentChapterIdx > 0) {
                BookModel.Chapter prev = currentBook.chapters.get(currentChapterIdx - 1);
                loadBookChapter(currentBook, currentChapterIdx - 1, Math.max(0, prev.durationMs - 15000), isPlaying());
            } else seekChapterTo(0);
        } else if (ch.durationMs > 0 && newPos >= ch.durationMs) {
            if (currentChapterIdx + 1 < currentBook.chapters.size()) loadBookChapter(currentBook, currentChapterIdx + 1, 0, isPlaying());
            else seekChapterTo(ch.durationMs);
        } else seekChapterTo(newPos);
    }

    public void seekChapterTo(long chapPosMs) {
        if (currentBook == null) return;
        BookModel.Chapter ch = currentBook.chapters.get(currentChapterIdx);
        pausedChapterPosMs = Math.max(0, Math.min(chapPosMs, ch.durationMs));
        if (mp != null) mp.seekTo((int) (ch.startMs + pausedChapterPosMs));
        updatePlaybackState();
        saveProgress();
        scheduleZeroPolling();
    }

    public long getChapterPosMs() {
        if (mp == null || currentBook == null) return pausedChapterPosMs;
        try {
            BookModel.Chapter ch = currentBook.chapters.get(currentChapterIdx);
            return Math.max(0, mp.getCurrentPosition() - ch.startMs);
        } catch (Exception e) { return pausedChapterPosMs; }
    }

    public void cycleSpeed() {
        if (speed < 0.9f) speed = 1.0f;
        else if (speed < 1.1f) speed = 1.2f;
        else if (speed < 1.35f) speed = 1.5f;
        else if (speed < 1.65f) speed = 1.75f;
        else if (speed < 1.9f) speed = 2.0f;
        else speed = 0.8f;
        getSharedPreferences("wb_prefs", MODE_PRIVATE).edit().putFloat("speed", speed).apply();
        applySpeedInternal();
        updatePlaybackState();
        scheduleZeroPolling();
    }

    private void applySpeedInternal() {
        if (mp == null) return;
        try {
            float cur = mp.getPlaybackParams().getSpeed();
            if (Math.abs(cur - speed) < 0.01f) return;
            boolean was = mp.isPlaying();
            mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed));
            if (!was) mp.pause();
        } catch (Exception ignored) {}
    }

    public void cycleSleepTimer() {
        long now = System.currentTimeMillis();
        if (sleepEndEpochMs == 0 && !sleepEndOfChapter) sleepEndEpochMs = now + 15 * 60_000L;
        else if (sleepEndEpochMs > 0 && (sleepEndEpochMs - now) <= 16 * 60_000L) sleepEndEpochMs = now + 30 * 60_000L;
        else if (sleepEndEpochMs > 0 && (sleepEndEpochMs - now) <= 31 * 60_000L) sleepEndEpochMs = now + 45 * 60_000L;
        else if (sleepEndEpochMs > 0) { sleepEndEpochMs = 0; sleepEndOfChapter = true; }
        else { sleepEndEpochMs = 0; sleepEndOfChapter = false; }
        if (sleepEndEpochMs == 0 && mp != null) mp.setVolume(1.0f, 1.0f);
        scheduleZeroPolling();
    }

    public String getSleepLabel() {
        if (sleepEndOfChapter) return "Fine cap.";
        if (sleepEndEpochMs > 0) return Math.max(1, (sleepEndEpochMs - System.currentTimeMillis() + 59999) / 60000) + "m";
        return "Zzz";
    }

    public void saveProgress() {
        if (currentBook == null) return;
        getSharedPreferences("wb_progress", MODE_PRIVATE).edit()
                .putInt(currentBook.id + "_ch", currentChapterIdx)
                .putLong(currentBook.id + "_pos", getChapterPosMs())
                .putString("last_book_id", currentBook.id).apply();
    }

    private void releasePlayer() {
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
        loadedFilePath = "";
    }

    @Override
    public void onDestroy() {
        saveProgress();
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        deactivateWatchersAndSession();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }
}
