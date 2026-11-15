package com.akemi.wristbooks;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

public class PlayerView {
    private static boolean scrubbing = false;

    public static void show(final MainActivity app) {
        final WatchUi ui = app.ui;
        final PlaybackService svc = app.svc;
        app.screenMode = 2;
        app.uiHandler.removeCallbacksAndMessages(null);
        ui.clear();

        final BookModel.Book book = (svc != null && svc.currentBook != null) ? svc.currentBook : app.selectedBook;
        if (book == null) { app.showLibrary(); return; }
        final AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);

        LinearLayout top = new LinearLayout(app);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageView mini = new ImageView(app);
        mini.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        mini.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (book.cover != null) mini.setImageBitmap(book.cover);
        else mini.setBackground(ui.roundBox("#1F2430", "#EF4444", 8));
        top.addView(mini);

        LinearLayout titles = new LinearLayout(app);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(8);
        tlp.rightMargin = ui.dp(6);
        titles.setLayoutParams(tlp);
        TextView tvB = ui.text(titles, book.title, 12f, "#F8FAFC", true);
        tvB.setMaxLines(1);
        tvB.setEllipsize(TextUtils.TruncateAt.END);
        TextView tvA = ui.text(titles, book.author, 10f, "#94A3B8", false);
        tvA.setMaxLines(1);
        tvA.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(titles);

        Button bList = ui.chip("☰", "#181B22", "#272C37", "#2E3442", "#FFFFFF", 13f);
        bList.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        bList.setOnClickListener(v -> app.showChapters(book, false));
        top.addView(bList);
        ui.box.addView(top);

        final ProgressBar bookProg = ui.makeRedBookProgress();
        ((LinearLayout.LayoutParams) bookProg.getLayoutParams()).topMargin = ui.dp(8);
        ui.box.addView(bookProg);

        LinearLayout bTimes = new LinearLayout(app);
        bTimes.setOrientation(LinearLayout.HORIZONTAL);
        final TextView tvBookEl = new TextView(app);
        tvBookEl.setTextColor(Color.parseColor("#94A3B8"));
        tvBookEl.setTextSize(9.5f);
        tvBookEl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView tvCount = new TextView(app);
        tvCount.setTextColor(Color.parseColor("#CBD5E1"));
        tvCount.setTextSize(10f);
        tvCount.setTypeface(null, Typeface.BOLD);
        tvCount.setGravity(Gravity.CENTER);
        tvCount.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView tvBookRem = new TextView(app);
        tvBookRem.setTextColor(Color.parseColor("#94A3B8"));
        tvBookRem.setTextSize(9.5f);
        tvBookRem.setGravity(Gravity.END);
        tvBookRem.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bTimes.addView(tvBookEl);
        bTimes.addView(tvCount);
        bTimes.addView(tvBookRem);
        ui.box.addView(bTimes);

        final TextView tvCh = new TextView(app);
        tvCh.setTextColor(Color.WHITE);
        tvCh.setTextSize(13.5f);
        tvCh.setTypeface(null, Typeface.BOLD);
        tvCh.setGravity(Gravity.CENTER);
        tvCh.setMaxLines(2);
        tvCh.setEllipsize(TextUtils.TruncateAt.END);
        tvCh.setPadding(0, ui.dp(8), 0, ui.dp(2));
        ui.box.addView(tvCh);

        final SeekBar seek = ui.makeRedSeekBar();
        ui.box.addView(seek);

        LinearLayout cTimes = new LinearLayout(app);
        cTimes.setOrientation(LinearLayout.HORIZONTAL);
        final TextView tvChEl = new TextView(app);
        tvChEl.setTextColor(Color.parseColor("#94A3B8"));
        tvChEl.setTextSize(10f);
        tvChEl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView tvChRem = new TextView(app);
        tvChRem.setTextColor(Color.parseColor("#94A3B8"));
        tvChRem.setTextSize(10f);
        tvChRem.setGravity(Gravity.END);
        tvChRem.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cTimes.addView(tvChEl);
        cTimes.addView(tvChRem);
        ui.box.addView(cTimes);

        LinearLayout mainCtrl = new LinearLayout(app);
        mainCtrl.setOrientation(LinearLayout.HORIZONTAL);
        mainCtrl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mclp.topMargin = ui.dp(8);
        mainCtrl.setLayoutParams(mclp);

        Button bRew = ui.chip("-15s", "#14171F", "#242936", "#2E3442", "#F8FAFC", 12f);
        bRew.setLayoutParams(new LinearLayout.LayoutParams(0, ui.dp(40), 1f));
        bRew.setOnClickListener(v -> { if (svc != null) svc.skipSeconds(-15); });

        final Button bPlay = new Button(app);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ui.dp(50), ui.dp(50));
        plp.leftMargin = ui.dp(8);
        plp.rightMargin = ui.dp(8);
        bPlay.setLayoutParams(plp);
        bPlay.setMinWidth(0);
        bPlay.setMinimumWidth(0);
        bPlay.setBackground(ui.pillBg("#1E222D", "#2E3444", "#3B4254"));
        bPlay.setTextColor(Color.parseColor("#EF4444"));
        bPlay.setTextSize(18f);
        bPlay.setOnClickListener(v -> { if (svc != null) svc.togglePlayPause(); });

        Button bFwd = ui.chip("+15s", "#14171F", "#242936", "#2E3442", "#F8FAFC", 12f);
        bFwd.setLayoutParams(new LinearLayout.LayoutParams(0, ui.dp(40), 1f));
        bFwd.setOnClickListener(v -> { if (svc != null) svc.skipSeconds(15); });
        mainCtrl.addView(bRew);
        mainCtrl.addView(bPlay);
        mainCtrl.addView(bFwd);
        ui.box.addView(mainCtrl);

        LinearLayout subCtrl = new LinearLayout(app);
        subCtrl.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sclp.topMargin = ui.dp(8);
        subCtrl.setLayoutParams(sclp);

        final Button bSpeed = ui.chip("1.0×", "#14171F", "#242936", "#2E3442", "#F8FAFC", 11.5f);
        bSpeed.setLayoutParams(new LinearLayout.LayoutParams(0, ui.dp(36), 1f));
        bSpeed.setOnClickListener(v -> { if (svc != null) { svc.cycleSpeed(); bSpeed.setText(String.format(Locale.US, "%.2g×", svc.speed)); } });

        final Button bSleep = ui.chip("Zzz", "#14171F", "#242936", "#2E3442", "#94A3B8", 11.5f);
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(0, ui.dp(36), 1f);
        zlp.leftMargin = ui.dp(8);
        bSleep.setLayoutParams(zlp);
        bSleep.setOnClickListener(v -> { if (svc != null) { svc.cycleSleepTimer(); bSleep.setText(svc.getSleepLabel()); } });
        subCtrl.addView(bSpeed);
        subCtrl.addView(bSleep);
        ui.box.addView(subCtrl);

        LinearLayout volRow = new LinearLayout(app);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams vrlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vrlp.topMargin = ui.dp(8);
        volRow.setLayoutParams(vrlp);

        final TextView tvVol = new TextView(app);
        tvVol.setText("🔊 " + volPct(am) + "%");
        tvVol.setTextColor(Color.parseColor("#E2E8F0"));
        tvVol.setTextSize(11.5f);
        tvVol.setTypeface(null, Typeface.BOLD);
        tvVol.setGravity(Gravity.CENTER);
        tvVol.setBackground(ui.roundBox("#111318", "#222631", 999));
        LinearLayout.LayoutParams vtlp = new LinearLayout.LayoutParams(0, ui.dp(36), 1f);
        vtlp.leftMargin = ui.dp(6);
        vtlp.rightMargin = ui.dp(6);
        tvVol.setLayoutParams(vtlp);

        Button bVd = ui.chip("−", "#14171F", "#242936", "#2E3442", "#FFFFFF", 14f);
        bVd.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(42), ui.dp(36)));
        bVd.setOnClickListener(v -> { if (am != null) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); tvVol.setText("🔊 " + volPct(am) + "%"); } });

        Button bVu = ui.chip("+", "#14171F", "#242936", "#2E3442", "#FFFFFF", 14f);
        bVu.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(42), ui.dp(36)));
        bVu.setOnClickListener(v -> { if (am != null) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); tvVol.setText("🔊 " + volPct(am) + "%"); } });
        volRow.addView(bVd);
        volRow.addView(tvVol);
        volRow.addView(bVu);
        ui.box.addView(volRow);

        LinearLayout exRow = new LinearLayout(app);
        exRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams exlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        exlp.topMargin = ui.dp(8);
        exRow.setLayoutParams(exlp);

        Button bNote = ui.chip("🔖 Nota", "#14171F", "#242936", "#2E3442", "#FBBF24", 11f);
        bNote.setLayoutParams(new LinearLayout.LayoutParams(0, ui.dp(36), 1f));
        bNote.setOnClickListener(v -> ChaptersView.addBookmark(app));

        Button bBt = ui.chip("🎧 Bluetooth", "#111D2E", "#1A2E47", "#2563EB", "#60A5FA", 11f);
        LinearLayout.LayoutParams btlp = new LinearLayout.LayoutParams(0, ui.dp(36), 1f);
        btlp.leftMargin = ui.dp(8);
        bBt.setLayoutParams(btlp);
        bBt.setOnClickListener(v -> {
            try { app.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { try { app.startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {} }
        });
        exRow.addView(bNote);
        exRow.addView(bBt);
        ui.box.addView(exRow);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (user && svc != null && svc.currentBook != null) {
                    BookModel.Chapter ch = svc.currentBook.chapters.get(svc.currentChapterIdx);
                    long prev = (ch.durationMs * p) / 1000L;
                    tvChEl.setText(BookModel.fmtChapterTime(prev));
                    tvChRem.setText("-" + BookModel.fmtChapterTime(Math.max(0, ch.durationMs - prev)));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { scrubbing = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                scrubbing = false;
                if (svc != null && svc.currentBook != null) {
                    BookModel.Chapter ch = svc.currentBook.chapters.get(svc.currentChapterIdx);
                    svc.seekChapterTo((ch.durationMs * sb.getProgress()) / 1000L);
                }
            }
        });

        app.uiHandler.post(new Runnable() {
            @Override public void run() {
                if (app.screenMode != 2) return;
                if (svc != null && svc.currentBook != null) {
                    BookModel.Book b = svc.currentBook;
                    int ci = svc.currentChapterIdx;
                    BookModel.Chapter ch = b.chapters.get(ci);
                    long cp = svc.getChapterPosMs();
                    tvCh.setText(ch.title);
                    tvCount.setText((ci + 1) + "/" + b.chapters.size());
                    long be = b.getElapsedBookMs(ci, cp);
                    tvBookEl.setText(BookModel.fmtBookHours(be));
                    tvBookRem.setText("-" + BookModel.fmtBookHours(Math.max(0, b.totalDurationMs - be)));
                    if (b.totalDurationMs > 0) bookProg.setProgress((int) ((be * 1000L) / b.totalDurationMs));
                    if (!scrubbing) {
                        seek.setProgress((int) Math.min(1000, (cp * 1000L) / Math.max(1, ch.durationMs)));
                        tvChEl.setText(BookModel.fmtChapterTime(cp));
                        tvChRem.setText("-" + BookModel.fmtChapterTime(Math.max(0, ch.durationMs - cp)));
                    }
                    bPlay.setText(svc.isPlaying() ? "❚❚" : "▶");
                    bSpeed.setText(String.format(Locale.US, "%.2g×", svc.speed));
                    String sl = svc.getSleepLabel();
                    bSleep.setText(sl);
                    bSleep.setTextColor(Color.parseColor("Zzz".equals(sl) ? "#94A3B8" : "#EF4444"));
                    tvVol.setText("🔊 " + volPct(am) + "%");
                }
                app.uiHandler.postDelayed(this, 500);
            }
        });
    }

    private static int volPct(AudioManager am) {
        if (am == null) return 0;
        return Math.round((am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f) / Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
    }
}
