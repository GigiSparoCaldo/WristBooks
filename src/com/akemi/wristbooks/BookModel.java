package com.akemi.wristbooks;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BookModel {
    public static class Chapter {
        public String title;
        public String filePath;
        public long startMs;
        public long durationMs;

        public Chapter(String title, String filePath, long startMs, long durationMs) {
            this.title = title;
            this.filePath = filePath;
            this.startMs = startMs;
            this.durationMs = Math.max(0, durationMs);
        }
    }

    public static class Book {
        public String id;
        public String title;
        public String author;
        public String folderOrFilePath;
        public Bitmap cover;
        public List<Chapter> chapters = new ArrayList<>();
        public long totalDurationMs;

        public long getElapsedBookMs(int chapIdx, long chapPosMs) {
            long sum = 0;
            for (int i = 0; i < chapIdx && i < chapters.size(); i++) {
                sum += chapters.get(i).durationMs;
            }
            return Math.min(totalDurationMs, sum + Math.max(0, chapPosMs));
        }

        public int getPercent(int chapIdx, long chapPosMs) {
            if (totalDurationMs <= 0) return 0;
            long el = getElapsedBookMs(chapIdx, chapPosMs);
            return (int) Math.min(100, Math.round((el * 100.0) / totalDurationMs));
        }
    }

    public static String fmtChapterTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    public static String fmtBookHours(long ms) {
        if (ms < 0) ms = 0;
        long totalMin = ms / 60000;
        long h = totalMin / 60;
        long m = totalMin % 60;
        if (h > 0) return String.format(Locale.US, "%d h %02d min", h, m);
        return String.format(Locale.US, "%d min", m);
    }
}
