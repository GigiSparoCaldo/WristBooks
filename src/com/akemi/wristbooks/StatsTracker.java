package com.akemi.wristbooks;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

public class StatsTracker {
    private static final String PREF = "wb_stats";
    private static long activeStartMs = 0L;
    private static String activeBookId = "";

    public static class Entry {
        public final String label;
        public final long ms;
        public final boolean highlight;
        public Entry(String label, long ms, boolean highlight) {
            this.label = label;
            this.ms = ms;
            this.highlight = highlight;
        }
    }

    public static synchronized void onPlayStart(Context ctx, String bookId) {
        if (activeStartMs > 0L) flush(ctx);
        activeStartMs = System.currentTimeMillis();
        activeBookId = (bookId != null) ? bookId : "";
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putInt("sessions", sp.getInt("sessions", 0) + 1).apply();
    }

    public static synchronized void flush(Context ctx) {
        if (activeStartMs <= 0L) return;
        long now = System.currentTimeMillis();
        long delta = now - activeStartMs;
        activeStartMs = now;
        if (delta < 1000L || delta > 6 * 3600_000L) return;
        recordDelta(ctx, now, delta, activeBookId);
    }

    public static synchronized void onPauseOrStop(Context ctx) {
        flush(ctx);
        activeStartMs = 0L;
    }

    public static synchronized void onChapterDone(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putInt("chapters_done", sp.getInt("chapters_done", 0) + 1).apply();
    }

    private static void recordDelta(Context ctx, long nowMs, long deltaMs, String bookId) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            long total = sp.getLong("total_ms", 0L) + deltaMs;
            String dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(nowMs));
            String monthKey = dayKey.substring(0, 7);
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(nowMs);
            int hour = cal.get(Calendar.HOUR_OF_DAY);

            JSONObject days = new JSONObject(sp.getString("days_json", "{}"));
            long newDayMs = days.optLong(dayKey, 0L) + deltaMs;
            days.put(dayKey, newDayMs);

            JSONObject months = new JSONObject(sp.getString("months_json", "{}"));
            months.put(monthKey, months.optLong(monthKey, 0L) + deltaMs);

            JSONObject hours = new JSONObject(sp.getString("hours_json", "{}"));
            String hk = String.valueOf(hour);
            hours.put(hk, hours.optLong(hk, 0L) + deltaMs);

            JSONObject books = new JSONObject(sp.getString("books_json", "{}"));
            if (bookId != null && !bookId.isEmpty()) {
                books.put(bookId, books.optLong(bookId, 0L) + deltaMs);
            }

            long bestMs = sp.getLong("best_day_ms", 0L);
            String bestDate = sp.getString("best_day_date", "");
            if (newDayMs >= bestMs) {
                bestMs = newDayMs;
                bestDate = dayKey;
            }

            sp.edit()
                    .putLong("total_ms", total)
                    .putString("days_json", days.toString())
                    .putString("months_json", months.toString())
                    .putString("hours_json", hours.toString())
                    .putString("books_json", books.toString())
                    .putLong("best_day_ms", bestMs)
                    .putString("best_day_date", bestDate)
                    .apply();
        } catch (Exception ignored) {}
    }

    public static long getTotalMs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("total_ms", 0L);
    }

    public static int getChaptersDone(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("chapters_done", 0);
    }

    public static long getBestDayMs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("best_day_ms", 0L);
    }

    public static String getBestDayDate(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("best_day_date", "—");
    }

    public static long getTodayMs(Context ctx) {
        try {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            JSONObject days = new JSONObject(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("days_json", "{}"));
            return days.optLong(today, 0L);
        } catch (Exception e) { return 0L; }
    }

    public static long getThisMonthMs(Context ctx) {
        try {
            String m = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
            JSONObject months = new JSONObject(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("months_json", "{}"));
            return months.optLong(m, 0L);
        } catch (Exception e) { return 0L; }
    }

    public static int getStreakDays(Context ctx) {
        try {
            JSONObject days = new JSONObject(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("days_json", "{}"));
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar cal = Calendar.getInstance();
            int streak = 0;
            for (int i = 0; i < 365; i++) {
                String k = fmt.format(cal.getTime());
                if (days.optLong(k, 0L) >= 60_000L) {
                    streak++;
                } else if (i > 0) {
                    break;
                }
                cal.add(Calendar.DAY_OF_YEAR, -1);
            }
            return streak;
        } catch (Exception e) { return 0; }
    }

    public static List<Entry> getLastDays(Context ctx, int count) {
        List<Entry> out = new ArrayList<>();
        String[] dow = {"Dom", "Lun", "Mar", "Mer", "Gio", "Ven", "Sab"};
        try {
            JSONObject days = new JSONObject(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("days_json", "{}"));
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -(count - 1));
            for (int i = 0; i < count; i++) {
                String k = fmt.format(cal.getTime());
                String lbl = (count <= 7) ? dow[cal.get(Calendar.DAY_OF_WEEK) - 1] : String.valueOf(cal.get(Calendar.DAY_OF_MONTH));
                out.add(new Entry(lbl, days.optLong(k, 0L), i == count - 1));
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static List<Entry> getLastMonths(Context ctx, int count) {
        List<Entry> out = new ArrayList<>();
        String[] mNames = {"Gen", "Feb", "Mar", "Apr", "Mag", "Giu", "Lug", "Ago", "Set", "Ott", "Nov", "Dic"};
        try {
            JSONObject months = new JSONObject(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("months_json", "{}"));
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM", Locale.US);
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.DAY_OF_MONTH, 1);
            cal.add(Calendar.MONTH, -(count - 1));
            for (int i = 0; i < count; i++) {
                String k = fmt.format(cal.getTime());
                out.add(new Entry(mNames[cal.get(Calendar.MONTH)], months.optLong(k, 0L), i == count - 1));
                cal.add(Calendar.MONTH, 1);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static long[] getHourly24(Context ctx) {
        long[] res = new long[24];
        try {
            JSONObject hours = new JSONObject(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("hours_json", "{}"));
            for (int h = 0; h < 24; h++) res[h] = hours.optLong(String.valueOf(h), 0L);
        } catch (Exception ignored) {}
        return res;
    }

    public static long getBookMs(Context ctx, String bookId) {
        try {
            JSONObject books = new JSONObject(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("books_json", "{}"));
            return books.optLong(bookId, 0L);
        } catch (Exception e) { return 0L; }
    }

    public static String fmtDur(long ms) {
        long totalMin = ms / 60_000L;
        if (totalMin < 1) return (ms > 0) ? "<1m" : "0m";
        long h = totalMin / 60L;
        long m = totalMin % 60L;
        if (h == 0) return m + "m";
        return h + "h " + (m < 10 ? "0" + m : m) + "m";
    }

    public static String fmtShort(long ms) {
        long min = ms / 60_000L;
        if (min <= 0) return "";
        if (min < 60) return min + "m";
        return String.format(Locale.US, "%.1fh", min / 60.0);
    }
}
