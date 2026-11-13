package com.akemi.wristbooks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
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
    private final Handler handler = new Handler(Looper.getMainLooper());

    public BookModel.Book currentBook;
    public int currentChapterIdx = 0;
    private String loadedFilePath = "";
    public float speed = 1.0f;

    public long sleepEndEpochMs = 0;
    public boolean sleepEndOfChapter = false;

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
        setupMediaSession();
        createNotifChannel();
    }

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "WristBooksSession");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { skipSeconds(15); }
            @Override public void onSkipToPrevious() { skipSeconds(-15); }
        });
        mediaSession.setActive(true);
    }

    private void createNotifChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("wb_play", "Riproduzione Audiolibri", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void updateNotification() {
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
                    .setOngoing(isPlaying())
                    .build();
            if (isPlaying()) startForeground(202, n);
            else {
                stopForeground(false);
                getSystemService(NotificationManager.class).notify(202, n);
            }
        }
        updateMediaSessionState();
    }

    private void updateMediaSessionState() {
        if (mediaSession == null) return;
        int state = isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        PlaybackState ps = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(state, getChapterPosMs(), speed)
                .build();
        mediaSession.setPlaybackState(ps);
    }

    public void loadBookChapter(BookModel.Book book, int chapIdx, long chapOffsetMs, boolean autoPlay) {
        if (book == null || book.chapters.isEmpty()) return;
        currentBook = book;
        currentChapterIdx = Math.max(0, Math.min(chapIdx, book.chapters.size() - 1));
        BookModel.Chapter ch = book.chapters.get(currentChapterIdx);

        try {
            if (mp == null || !ch.filePath.equals(loadedFilePath)) {
                releasePlayer();
                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
                mp.setDataSource(ch.filePath);
                mp.prepare();
                loadedFilePath = ch.filePath;
                mp.setOnCompletionListener(m -> onFileCompleted());
            }
            long targetFileMs = ch.startMs + Math.max(0, Math.min(chapOffsetMs, ch.durationMs));
            mp.seekTo((int) targetFileMs);
            applySpeedInternal();
            if (autoPlay) {
                mp.start();
                handler.removeCallbacks(ticker);
                handler.postDelayed(ticker, 1000);
            }
            saveProgress();
            updateNotification();
        } catch (Exception ignored) {}
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
        if (mp == null && currentBook != null) {
            loadBookChapter(currentBook, currentChapterIdx, getChapterPosMs(), true);
            return;
        }
        if (mp != null && !mp.isPlaying()) {
            // Riavvolge 3 secondi alla ripresa per non perdere il filo del discorso
            int pos = Math.max(0, mp.getCurrentPosition() - 3000);
            if (currentBook != null) {
                BookModel.Chapter ch = currentBook.chapters.get(currentChapterIdx);
                if (pos < ch.startMs) pos = (int) ch.startMs;
            }
            mp.seekTo(pos);
            mp.setVolume(1.0f, 1.0f);
            applySpeedInternal();
            mp.start();
            handler.removeCallbacks(ticker);
            handler.postDelayed(ticker, 1000);
            updateNotification();
        }
    }

    public void pause() {
        try {
            if (mp != null && mp.isPlaying()) mp.pause();
        } catch (Exception ignored) {}
        handler.removeCallbacks(ticker);
        saveProgress();
        updateNotification();
    }

    public void skipSeconds(int sec) {
        if (mp == null || currentBook == null) return;
        BookModel.Chapter ch = currentBook.chapters.get(currentChapterIdx);
        long newChapPos = getChapterPosMs() + (sec * 1000L);
        if (newChapPos < 0) {
            if (currentChapterIdx > 0) {
                BookModel.Chapter prev = currentBook.chapters.get(currentChapterIdx - 1);
                loadBookChapter(currentBook, currentChapterIdx - 1, Math.max(0, prev.durationMs - 15000), isPlaying());
            } else {
                seekChapterTo(0);
            }
        } else if (ch.durationMs > 0 && newChapPos >= ch.durationMs) {
            if (currentChapterIdx + 1 < currentBook.chapters.size()) {
                loadBookChapter(currentBook, currentChapterIdx + 1, 0, isPlaying());
            } else {
                seekChapterTo(ch.durationMs);
            }
        } else {
            seekChapterTo(newChapPos);
        }
    }

    public void seekChapterTo(long chapPosMs) {
        if (mp == null || currentBook == null) return;
        BookModel.Chapter ch = currentBook.chapters.get(currentChapterIdx);
        long clamped = Math.max(0, Math.min(chapPosMs, ch.durationMs));
        mp.seekTo((int) (ch.startMs + clamped));
        saveProgress();
    }

    public long getChapterPosMs() {
        if (mp == null || currentBook == null) return 0;
        try {
            BookModel.Chapter ch = currentBook.chapters.get(currentChapterIdx);
            return Math.max(0, mp.getCurrentPosition() - ch.startMs);
        } catch (Exception e) { return 0; }
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
            boolean wasPlaying = mp.isPlaying();
            mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed));
            if (!wasPlaying) mp.pause();
        } catch (Exception ignored) {}
    }

    public void cycleSleepTimer() {
        long now = System.currentTimeMillis();
        if (sleepEndEpochMs == 0 && !sleepEndOfChapter) {
            sleepEndEpochMs = now + 15 * 60_000L;
        } else if (sleepEndEpochMs > 0 && (sleepEndEpochMs - now) <= 16 * 60_000L) {
            sleepEndEpochMs = now + 30 * 60_000L;
        } else if (sleepEndEpochMs > 0 && (sleepEndEpochMs - now) <= 31 * 60_000L) {
            sleepEndEpochMs = now + 45 * 60_000L;
        } else if (sleepEndEpochMs > 0) {
            sleepEndEpochMs = 0;
            sleepEndOfChapter = true;
        } else {
            sleepEndEpochMs = 0;
            sleepEndOfChapter = false;
        }
    }

    public String getSleepLabel() {
        if (sleepEndOfChapter) return "Fine cap.";
        if (sleepEndEpochMs > 0) {
            long remMin = Math.max(1, (sleepEndEpochMs - System.currentTimeMillis() + 59999) / 60000);
            return remMin + "m";
        }
        return "Zzz";
    }

    private void checkChapterAndSleep() {
        if (mp == null || currentBook == null) return;
        BookModel.Chapter ch = currentBook.chapters.get(currentChapterIdx);
        long filePos = mp.getCurrentPosition();

        // Avanzamento automatico al capitolo successivo nei file M4B unici
        if (ch.durationMs > 0 && filePos >= ch.startMs + ch.durationMs - 250) {
            if (sleepEndOfChapter) {
                sleepEndOfChapter = false;
                if (currentChapterIdx + 1 < currentBook.chapters.size()) {
                    loadBookChapter(currentBook, currentChapterIdx + 1, 0, false);
                } else {
                    pause();
                }
                return;
            }
            if (currentChapterIdx + 1 < currentBook.chapters.size()) {
                currentChapterIdx++;
                saveProgress();
                updateNotification();
            }
        }

        if (sleepEndEpochMs > 0) {
            long left = sleepEndEpochMs - System.currentTimeMillis();
            if (left <= 0) {
                sleepEndEpochMs = 0;
                pause();
            } else if (left < 10_000L) {
                float vol = Math.max(0.1f, left / 10_000f);
                mp.setVolume(vol, vol);
            }
        }
    }

    public void saveProgress() {
        if (currentBook == null) return;
        SharedPreferences.Editor ed = getSharedPreferences("wb_progress", MODE_PRIVATE).edit();
        ed.putInt(currentBook.id + "_ch", currentChapterIdx);
        ed.putLong(currentBook.id + "_pos", getChapterPosMs());
        ed.putString("last_book_id", currentBook.id);
        ed.apply();
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
        handler.removeCallbacks(ticker);
        releasePlayer();
        if (mediaSession != null) mediaSession.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }
}
