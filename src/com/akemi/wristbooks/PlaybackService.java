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
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
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
    private AudioDeviceCallback deviceCallback;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            checkChapterAndSleep();
            if (isPlaying()) {
                saveProgress();
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        speed = getSharedPreferences("wb_prefs", MODE_PRIVATE).getFloat("speed", 1.0f);
        createNotifChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
    }

    private void deactivateWatchersAndSession() {
        try {
            if (noisyReceiver != null) { unregisterReceiver(noisyReceiver); noisyReceiver = null; }
        } catch (Exception ignored) { noisyReceiver = null; }

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
        if (currentBook == null) return;
        BookModel.Chapter ch = currentBook.chapters.get(currentChapterIdx);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        if (Build.VERSION.SDK_INT >= 26) {
            Notification n = new Notification.Builder(this, "wb_play")
                    .setContentTitle(currentBook.title)
                    .setContentText(ch.title)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(202, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(202, n);
        }
        if (mediaSession != null) {
            PlaybackState ps = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(PlaybackState.STATE_PLAYING, getChapterPosMs(), speed)
                    .build();
            mediaSession.setPlaybackState(ps);
        }
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
                handler.removeCallbacks(ticker);
                handler.postDelayed(ticker, 1000);
            }
            saveProgress();
        } catch (Exception ignored) {}
    }

    private void requestAudioFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).build();
            am.requestAudioFocus(focusRequest);
        } else {
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            am.abandonAudioFocusRequest(focusRequest);
        } else {
            am.abandonAudioFocus(null);
        }
    }

    private void onFileCompleted() {
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
            handler.removeCallbacks(ticker);
            handler.postDelayed(ticker, 1000);
        }
    }

    public void pause() {
        pausedChapterPosMs = getChapterPosMs();
        try { if (mp != null && mp.isPlaying()) mp.pause(); } catch (Exception ignored) {}
        handler.removeCallbacks(ticker);
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
        saveProgress();
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
    }

    private void applySpeedInternal() {
        if (mp == null) return;
        try {
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
    }

    public String getSleepLabel() {
        if (sleepEndOfChapter) return "Fine cap.";
        if (sleepEndEpochMs > 0) return Math.max(1, (sleepEndEpochMs - System.currentTimeMillis() + 59999) / 60000) + "m";
        return "Zzz";
    }

    private void checkChapterAndSleep() {
        if (mp == null || currentBook == null) return;
        BookModel.Chapter ch = currentBook.chapters.get(currentChapterIdx);
        long filePos = mp.getCurrentPosition();
        if (ch.durationMs > 0 && filePos >= ch.startMs + ch.durationMs - 250) {
            if (sleepEndOfChapter) {
                sleepEndOfChapter = false;
                if (currentChapterIdx + 1 < currentBook.chapters.size()) { currentChapterIdx++; pausedChapterPosMs = 0; saveProgress(); }
                pause();
                return;
            }
            if (currentChapterIdx + 1 < currentBook.chapters.size()) { currentChapterIdx++; saveProgress(); promoteForeground(); }
        }
        if (sleepEndEpochMs > 0) {
            long left = sleepEndEpochMs - System.currentTimeMillis();
            if (left <= 0) { sleepEndEpochMs = 0; pause(); }
            else if (left < 10_000L) { float v = Math.max(0.1f, left / 10_000f); mp.setVolume(v, v); }
        }
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
