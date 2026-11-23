package com.akemi.wristbooks;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StatsView {
    public static void show(final MainActivity app, final List<BookModel.Book> library, final int tab) {
        StatsTracker.flush(app);
        final WatchUi ui = app.ui;
        app.screenMode = 3;
        app.uiHandler.removeCallbacksAndMessages(null);
        ui.clear();

        LinearLayout top = new LinearLayout(app);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button bBack = ui.chip("←", "#181B22", "#272C37", "#2E3442", "#FFFFFF", 13f);
        bBack.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        bBack.setOnClickListener(v -> app.showLibrary());
        top.addView(bBack);

        TextView tvTitle = new TextView(app);
        tvTitle.setText("Statistiche");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(15f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(8);
        tvTitle.setLayoutParams(tlp);
        top.addView(tvTitle);
        ui.box.addView(top);

        // Card Riepilogo Totale / Oggi / Mese / Streak
        LinearLayout kpi = ui.card();
        LinearLayout r1 = new LinearLayout(app);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        addMetricBox(app, r1, "OGGI", StatsTracker.fmtDur(StatsTracker.getTodayMs(app)), "#EF4444");
        addMetricBox(app, r1, "QUESTO MESE", StatsTracker.fmtDur(StatsTracker.getThisMonthMs(app)), "#38BDF8");
        kpi.addView(r1);

        LinearLayout r2 = new LinearLayout(app);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2lp.topMargin = ui.dp(8);
        r2.setLayoutParams(r2lp);
        addMetricBox(app, r2, "DA SEMPRE", StatsTracker.fmtDur(StatsTracker.getTotalMs(app)), "#34D399");
        addMetricBox(app, r2, "SERIE / CAP.", StatsTracker.getStreakDays(app) + "g • " + StatsTracker.getChaptersDone(app), "#FBBF24");
        kpi.addView(r2);
        ui.box.addView(kpi);

        // Tabs: 0=GIORNI, 1=MESI, 2=ORARI, 3=LIBRI
        String[] labels = {"GIORNI", "MESI", "ORARI", "LIBRI"};
        LinearLayout tabs = new LinearLayout(app);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.setMargins(0, ui.dp(4), 0, ui.dp(6));
        tabs.setLayoutParams(tabLp);

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean sel = (tab == i);
            Button b = ui.chip(labels[i],
                    sel ? "#261214" : "#111318", "#2E1619", sel ? "#EF4444" : "#222631",
                    sel ? "#EF4444" : "#94A3B8", 9.5f);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ui.dp(30), 1f);
            if (i > 0) blp.leftMargin = ui.dp(4);
            b.setLayoutParams(blp);
            b.setOnClickListener(v -> show(app, library, idx));
            tabs.addView(b);
        }
        ui.box.addView(tabs);

        if (tab == 0) renderDaysTab(app);
        else if (tab == 1) renderMonthsTab(app);
        else if (tab == 2) renderHoursTab(app);
        else renderBooksTab(app, library);
    }

    private static void addMetricBox(MainActivity app, LinearLayout row, String title, String val, String color) {
        LinearLayout col = new LinearLayout(app);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        app.ui.text(col, title, 9f, "#94A3B8", true);
        app.ui.text(col, val, 13.5f, color, true);
        row.addView(col);
    }

    private static void renderDaysTab(MainActivity app) {
        List<StatsTracker.Entry> week = StatsTracker.getLastDays(app, 7);
        long weekSum = 0;
        for (StatsTracker.Entry e : week) weekSum += e.ms;

        LinearLayout c1 = app.ui.card();
        app.ui.text(c1, "Ultimi 7 giorni: " + StatsTracker.fmtDur(weekSum), 12f, "#F8FAFC", true);
        app.ui.text(c1, "Media: " + StatsTracker.fmtDur(weekSum / 7L) + "/giorno", 10.5f, "#94A3B8", false);
        BarChartView chart7 = new BarChartView(app, week, Color.parseColor("#38BDF8"), true);
        chart7.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.ui.dp(105)));
        c1.addView(chart7);
        app.ui.box.addView(c1);

        List<StatsTracker.Entry> d30 = StatsTracker.getLastDays(app, 30);
        long sum30 = 0;
        for (StatsTracker.Entry e : d30) sum30 += e.ms;

        LinearLayout c2 = app.ui.card();
        app.ui.text(c2, "Ultimi 30 giorni: " + StatsTracker.fmtDur(sum30), 12f, "#F8FAFC", true);
        app.ui.text(c2, "Record: " + StatsTracker.fmtDur(StatsTracker.getBestDayMs(app)) + " (" + StatsTracker.getBestDayDate(app) + ")", 10.5f, "#FBBF24", false);
        BarChartView chart30 = new BarChartView(app, d30, Color.parseColor("#34D399"), false);
        chart30.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.ui.dp(80)));
        c2.addView(chart30);
        app.ui.box.addView(c2);
    }

    private static void renderMonthsTab(MainActivity app) {
        List<StatsTracker.Entry> months = StatsTracker.getLastMonths(app, 6);
        long sum6 = 0;
        for (StatsTracker.Entry e : months) sum6 += e.ms;

        LinearLayout c = app.ui.card();
        app.ui.text(c, "Ultimi 6 mesi: " + StatsTracker.fmtDur(sum6), 12f, "#F8FAFC", true);
        app.ui.text(c, "Media mensile: " + StatsTracker.fmtDur(sum6 / 6L), 10.5f, "#94A3B8", false);
        BarChartView chart = new BarChartView(app, months, Color.parseColor("#38BDF8"), true);
        chart.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.ui.dp(115)));
        c.addView(chart);
        app.ui.box.addView(c);
    }

    private static void renderHoursTab(MainActivity app) {
        long[] h24 = StatsTracker.getHourly24(app);
        long morning = 0, afternoon = 0, evening = 0, night = 0, total = 0;
        int peakHour = 0;
        for (int h = 0; h < 24; h++) {
            total += h24[h];
            if (h24[h] > h24[peakHour]) peakHour = h;
            if (h >= 6 && h < 12) morning += h24[h];
            else if (h >= 12 && h < 18) afternoon += h24[h];
            else if (h >= 18 && h < 23) evening += h24[h];
            else night += h24[h];
        }

        LinearLayout c = app.ui.card();
        String peakStr = (total > 0) ? String.format("%02d:00 - %02d:59", peakHour, peakHour) : "—";
        app.ui.text(c, "Orari di ascolto (24h)", 12f, "#F8FAFC", true);
        app.ui.text(c, "Ora preferita: " + peakStr, 10.5f, "#EF4444", true);

        Hourly24View hv = new Hourly24View(app, h24);
        hv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.ui.dp(95)));
        c.addView(hv);

        long div = Math.max(1L, total);
        app.ui.text(c, "🌅 Mattina (06-12): " + Math.round((morning * 100f) / div) + "%  (" + StatsTracker.fmtDur(morning) + ")", 10.5f, "#CBD5E1", false);
        app.ui.text(c, "☀️ Pomeriggio (12-18): " + Math.round((afternoon * 100f) / div) + "%  (" + StatsTracker.fmtDur(afternoon) + ")", 10.5f, "#CBD5E1", false);
        app.ui.text(c, "🌆 Sera (18-23): " + Math.round((evening * 100f) / div) + "%  (" + StatsTracker.fmtDur(evening) + ")", 10.5f, "#CBD5E1", false);
        app.ui.text(c, "🌙 Notte (23-06): " + Math.round((night * 100f) / div) + "%  (" + StatsTracker.fmtDur(night) + ")", 10.5f, "#CBD5E1", false);
        app.ui.box.addView(c);
    }

    private static void renderBooksTab(MainActivity app, List<BookModel.Book> library) {
        List<StatsTracker.Entry> rows = new ArrayList<>();
        long maxMs = 1L;
        for (BookModel.Book b : library) {
            long ms = StatsTracker.getBookMs(app, b.id);
            if (ms > maxMs) maxMs = ms;
            rows.add(new StatsTracker.Entry(b.title, ms, false));
        }
        Collections.sort(rows, (a, b) -> Long.compare(b.ms, a.ms));

        if (rows.isEmpty()) {
            app.ui.text(app.ui.box, "Nessun audiolibro presente.", 12f, "#94A3B8", false);
            return;
        }
        for (StatsTracker.Entry e : rows) {
            LinearLayout c = app.ui.card();
            TextView tvT = app.ui.text(c, e.label, 12f, "#F8FAFC", true);
            tvT.setMaxLines(1);
            tvT.setEllipsize(TextUtils.TruncateAt.END);
            app.ui.text(c, "Ascoltato: " + StatsTracker.fmtDur(e.ms), 11f, "#38BDF8", true);

            View barBg = new View(app);
            barBg.setBackground(app.ui.roundBox("#1E2430", null, 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.ui.dp(6));
            lp.topMargin = app.ui.dp(4);
            barBg.setLayoutParams(lp);
            c.addView(barBg);
            app.ui.box.addView(c);
        }
    }

    static class BarChartView extends View {
        private final List<StatsTracker.Entry> items;
        private final int barColor;
        private final boolean showValues;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        BarChartView(Context ctx, List<StatsTracker.Entry> items, int barColor, boolean showValues) {
            super(ctx);
            this.items = items;
            this.barColor = barColor;
            this.showValues = showValues;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (items == null || items.isEmpty()) return;
            float w = getWidth();
            float h = getHeight();
            float d = getResources().getDisplayMetrics().density;
            float padTop = 16f * d;
            float padBot = 16f * d;
            float chartH = Math.max(10f, h - padTop - padBot);

            paint.setColor(Color.parseColor("#222631"));
            paint.setStrokeWidth(1f * d);
            c.drawLine(0, padTop + chartH, w, padTop + chartH, paint);

            long max = 1L;
            for (StatsTracker.Entry e : items) if (e.ms > max) max = e.ms;

            int n = items.size();
            float slot = w / n;
            float barW = Math.max(2f * d, slot * 0.58f);

            paint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < n; i++) {
                StatsTracker.Entry e = items.get(i);
                float cx = slot * i + slot * 0.5f;
                float ratio = (float) e.ms / (float) max;
                float bh = (e.ms > 0) ? Math.max(3f * d, chartH * ratio) : 1.5f * d;
                float top = padTop + chartH - bh;

                paint.setColor(e.ms == 0 ? Color.parseColor("#1E2430") : (e.highlight ? Color.parseColor("#EF4444") : barColor));
                rect.set(cx - barW * 0.5f, top, cx + barW * 0.5f, padTop + chartH);
                c.drawRoundRect(rect, 3f * d, 3f * d, paint);

                if (showValues && e.ms > 0) {
                    paint.setColor(Color.parseColor("#CBD5E1"));
                    paint.setTextSize(8.5f * d);
                    c.drawText(StatsTracker.fmtShort(e.ms), cx, top - 3f * d, paint);
                }
                if (n <= 8 || i % 5 == 0 || i == n - 1) {
                    paint.setColor(e.highlight ? Color.parseColor("#EF4444") : Color.parseColor("#94A3B8"));
                    paint.setTextSize(9f * d);
                    c.drawText(e.label, cx, h - 3f * d, paint);
                }
            }
        }
    }

    static class Hourly24View extends View {
        private final long[] hours;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Hourly24View(Context ctx, long[] hours) {
            super(ctx);
            this.hours = hours;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth();
            float h = getHeight();
            float d = getResources().getDisplayMetrics().density;
            float padTop = 8f * d;
            float padBot = 16f * d;
            float chartH = Math.max(10f, h - padTop - padBot);

            paint.setColor(Color.parseColor("#222631"));
            paint.setStrokeWidth(1f * d);
            c.drawLine(0, padTop + chartH, w, padTop + chartH, paint);

            long max = 1L;
            int peak = 0;
            for (int i = 0; i < 24; i++) {
                if (hours[i] > max) { max = hours[i]; peak = i; }
            }

            float slot = w / 24f;
            float barW = Math.max(2f * d, slot * 0.65f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(8.5f * d);

            for (int i = 0; i < 24; i++) {
                float cx = slot * i + slot * 0.5f;
                float bh = (hours[i] > 0) ? Math.max(3f * d, chartH * ((float) hours[i] / max)) : 1.5f * d;
                paint.setColor(hours[i] == 0 ? Color.parseColor("#1E2430")
                        : (i == peak ? Color.parseColor("#EF4444") : Color.parseColor("#38BDF8")));
                rect.set(cx - barW * 0.5f, padTop + chartH - bh, cx + barW * 0.5f, padTop + chartH);
                c.drawRoundRect(rect, 2f * d, 2f * d, paint);

                if (i == 0 || i == 6 || i == 12 || i == 18 || i == 23) {
                    paint.setColor(Color.parseColor("#94A3B8"));
                    c.drawText(String.format("%02d", i), cx, h - 3f * d, paint);
                }
            }
        }
    }
}
