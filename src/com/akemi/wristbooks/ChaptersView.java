package com.akemi.wristbooks;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

public class ChaptersView {
    public static void show(final MainActivity app, final BookModel.Book book, final boolean notesTab) {
        final WatchUi ui = app.ui;
        final PlaybackService svc = app.svc;
        app.screenMode = 1;
        app.uiHandler.removeCallbacksAndMessages(null);
        ui.clear();

        SharedPreferences prog = app.getSharedPreferences("wb_progress", Context.MODE_PRIVATE);
        final int activeCh = (svc != null && svc.currentBook != null && svc.currentBook.id.equals(book.id))
                ? svc.currentChapterIdx : prog.getInt(book.id + "_ch", 0);
        final long activePos = (svc != null && svc.currentBook != null && svc.currentBook.id.equals(book.id))
                ? svc.getChapterPosMs() : prog.getLong(book.id + "_pos", 0);

        LinearLayout top = new LinearLayout(app);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button bBack = ui.chip("←", "#181B22", "#272C37", "#2E3442", "#FFFFFF", 13f);
        bBack.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        bBack.setOnClickListener(v -> app.showLibrary());
        top.addView(bBack);

        TextView tvB = new TextView(app);
        tvB.setText(book.title);
        tvB.setTextColor(Color.WHITE);
        tvB.setTextSize(13f);
        tvB.setTypeface(null, Typeface.BOLD);
        tvB.setMaxLines(1);
        tvB.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(8);
        tvB.setLayoutParams(tlp);
        top.addView(tvB);
        ui.box.addView(top);

        Button bRes = ui.chip("▶ Continua (Cap. " + (activeCh + 1) + ")", "#EF4444", "#DC2626", null, "#FFFFFF", 12f);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(38));
        rlp.setMargins(0, ui.dp(8), 0, ui.dp(6));
        bRes.setLayoutParams(rlp);
        bRes.setOnClickListener(v -> { if (svc != null) svc.loadBookChapter(book, activeCh, activePos, true); app.showPlayer(); });
        ui.box.addView(bRes);

        LinearLayout tabs = new LinearLayout(app);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.bottomMargin = ui.dp(6);
        tabs.setLayoutParams(tabLp);

        Button tCap = ui.chip("CAPITOLI (" + book.chapters.size() + ")",
                !notesTab ? "#261214" : "#111318", "#2E1619", !notesTab ? "#EF4444" : "#222631",
                !notesTab ? "#EF4444" : "#94A3B8", 10.5f);
        Button tNote = ui.chip("NOTE",
                notesTab ? "#261214" : "#111318", "#2E1619", notesTab ? "#EF4444" : "#222631",
                notesTab ? "#EF4444" : "#94A3B8", 10.5f);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, ui.dp(32), 1f);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, ui.dp(32), 1f);
        p2.leftMargin = ui.dp(6);
        tCap.setLayoutParams(p1);
        tNote.setLayoutParams(p2);
        tCap.setOnClickListener(v -> show(app, book, false));
        tNote.setOnClickListener(v -> show(app, book, true));
        tabs.addView(tCap);
        tabs.addView(tNote);
        ui.box.addView(tabs);

        if (notesTab) { renderNotes(app, book); return; }

        for (int i = 0; i < book.chapters.size(); i++) {
            final int idx = i;
            BookModel.Chapter ch = book.chapters.get(i);
            boolean cur = (i == activeCh);
            LinearLayout row = new LinearLayout(app);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(ui.roundBox(cur ? "#1D1316" : "#0D0F14", cur ? "#EF4444" : "#1C2029", 12));
            row.setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, ui.dp(3), 0, ui.dp(3));
            row.setLayoutParams(lp);

            TextView tvN = new TextView(app);
            tvN.setText(ch.title);
            tvN.setTextColor(Color.parseColor(cur ? "#38BDF8" : "#F8FAFC"));
            tvN.setTextSize(12.5f);
            if (cur) tvN.setTypeface(null, Typeface.BOLD);
            tvN.setMaxLines(2);
            tvN.setEllipsize(TextUtils.TruncateAt.END);
            tvN.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tvN);

            TextView tvD = new TextView(app);
            tvD.setText(BookModel.fmtChapterTime(ch.durationMs));
            tvD.setTextColor(Color.parseColor(cur ? "#38BDF8" : "#94A3B8"));
            tvD.setTextSize(11f);
            tvD.setPadding(ui.dp(8), 0, 0, 0);
            row.addView(tvD);

            row.setOnClickListener(v -> {
                if (svc != null) svc.loadBookChapter(book, idx, (idx == activeCh) ? activePos : 0, true);
                app.showPlayer();
            });
            ui.box.addView(row);
        }
    }

    private static void renderNotes(final MainActivity app, final BookModel.Book book) {
        try {
            JSONArray arr = new JSONArray(app.getSharedPreferences("wb_notes", Context.MODE_PRIVATE).getString(book.id, "[]"));
            if (arr.length() == 0) { app.ui.text(app.ui.box, "Nessun segnalibro salvato.", 12f, "#94A3B8", false); return; }
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                final int ch = o.optInt("ch", 0);
                final long pos = o.optLong("pos", 0);
                LinearLayout c = app.ui.card();
                app.ui.text(c, o.optString("label", "Segnalibro"), 12.5f, "#38BDF8", true);
                app.ui.text(c, "Cap. " + (ch + 1) + " • " + BookModel.fmtChapterTime(pos), 11f, "#94A3B8", false);
                c.setOnClickListener(v -> { if (app.svc != null) app.svc.loadBookChapter(book, ch, pos, true); app.showPlayer(); });
                app.ui.box.addView(c);
            }
        } catch (Exception ignored) {}
    }

    public static void addBookmark(MainActivity app) {
        if (app.svc == null || app.svc.currentBook == null) return;
        try {
            SharedPreferences sp = app.getSharedPreferences("wb_notes", Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(app.svc.currentBook.id, "[]"));
            BookModel.Chapter ch = app.svc.currentBook.chapters.get(app.svc.currentChapterIdx);
            arr.put(new JSONObject().put("ch", app.svc.currentChapterIdx).put("pos", app.svc.getChapterPosMs()).put("label", ch.title));
            sp.edit().putString(app.svc.currentBook.id, arr.toString()).apply();
            Toast.makeText(app, "Segnalibro salvato!", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }
}
