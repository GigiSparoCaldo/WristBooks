package com.akemi.wristbooks;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQ_IMPORT_BOOK = 55;
    private WatchUi ui;
    private PlaybackService svc;
    private boolean bound = false;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private List<BookModel.Book> library = new ArrayList<>();

    private Runnable playerRefreshTask;
    private boolean userScrubbing = false;
    private int screenMode = 0;
    private BookModel.Book selectedBook;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = ((PlaybackService.LocalBinder) binder).getService();
            bound = true;
            if (svc.currentBook != null && svc.isPlaying()) {
                selectedBook = svc.currentBook;
                showPlayer();
            } else {
                refreshLibrary();
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { bound = false; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new WatchUi(this);
        checkStoragePerm();
        Intent it = new Intent(this, PlaybackService.class);
        startService(it);
        bindService(it, conn, Context.BIND_AUTO_CREATE);
    }

    private void checkStoragePerm() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO}, 10);
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 10);
        }
    }

    @Override
    public void onRequestPermissionsResult(int reqCode, String[] perms, int[] res) {
        super.onRequestPermissionsResult(reqCode, perms, res);
        refreshLibrary();
    }

    @Override
    protected void onActivityResult(int reqCode, int resCode, Intent data) {
        super.onActivityResult(reqCode, resCode, data);
        if (reqCode == REQ_IMPORT_BOOK && resCode == RESULT_OK && data != null && data.getData() != null) {
            importAudioUri(data.getData());
        }
    }

    private void importAudioUri(final Uri uri) {
        Toast.makeText(this, "Importazione...", Toast.LENGTH_SHORT).show();
        pool.execute(() -> {
            try {
                String name = "audiolibro.m4b";
                try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) name = c.getString(idx);
                    }
                }
                File destDir = getExternalFilesDir("Audiobooks");
                if (destDir != null && !destDir.exists()) destDir.mkdirs();
                File outF = new File(destDir, name);
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(outF)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                }
                library = M4bParser.scanLibrary(this);
                runOnUiThread(() -> { Toast.makeText(this, "Importato!", Toast.LENGTH_SHORT).show(); showLibrary(); });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Errore: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ui != null && ui.onRotary(ev)) return true;
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public void onBackPressed() {
        uiHandler.removeCallbacksAndMessages(null);
        if (screenMode == 2 && selectedBook != null) showChapters(selectedBook, false);
        else if (screenMode == 1) showLibrary();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (bound) { unbindService(conn); bound = false; }
        pool.shutdownNow();
        super.onDestroy();
    }

    private void refreshLibrary() {
        screenMode = 0;
        uiHandler.removeCallbacksAndMessages(null);
        ui.clear();
        ui.text(ui.box, "WristBooks", 16f, "#EF4444", true).setGravity(Gravity.CENTER);
        ui.text(ui.box, "Scansione Audiobooks...", 12f, "#94A3B8", false).setGravity(Gravity.CENTER);
        pool.execute(() -> { library = M4bParser.scanLibrary(this); runOnUiThread(this::showLibrary); });
    }

    private void showLibrary() {
        screenMode = 0;
        uiHandler.removeCallbacksAndMessages(null);
        ui.clear();

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER);
        TextView title = new TextView(this);
        title.setText("Audiolibri");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15f);
        title.setTypeface(null, Typeface.BOLD);
        hdr.addView(title);

        Button bScan = ui.chip("↻", "#181B22", "#272C37", "#2E3442", "#EF4444", 12f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32));
        slp.leftMargin = ui.dp(8);
        bScan.setLayoutParams(slp);
        bScan.setOnClickListener(v -> refreshLibrary());
        hdr.addView(bScan);

        Button bAdd = ui.chip("+", "#181B22", "#272C37", "#2E3442", "#34D399", 13f);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32));
        alp.leftMargin = ui.dp(6);
        bAdd.setLayoutParams(alp);
        bAdd.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            startActivityForResult(pick, REQ_IMPORT_BOOK);
        });
        hdr.addView(bAdd);
        ui.box.addView(hdr);

        if (library.isEmpty()) {
            LinearLayout empty = ui.card();
            ui.text(empty, "Nessun audiolibro trovato", 13f, "#F8FAFC", true);
            ui.text(empty, "Copia .m4b/.mp3 in /sdcard/Audiobooks e tocca ↻.", 11.5f, "#94A3B8", false);
            ui.box.addView(empty);
            return;
        }

        SharedPreferences prog = getSharedPreferences("wb_progress", MODE_PRIVATE);
        for (final BookModel.Book b : library) {
            int savedCh = prog.getInt(b.id + "_ch", 0);
            long savedPos = prog.getLong(b.id + "_pos", 0);
            int pct = b.getPercent(savedCh, savedPos);
            long remMs = Math.max(0, b.totalDurationMs - b.getElapsedBookMs(savedCh, savedPos));

            LinearLayout card = ui.card();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            ImageView iv = new ImageView(this);
            int cSize = ui.dp(48);
            iv.setLayoutParams(new LinearLayout.LayoutParams(cSize, cSize));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (b.cover != null) iv.setImageBitmap(b.cover);
            else iv.setBackground(ui.roundBox("#1F2430", "#EF4444", 10));
            row.addView(iv);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ilp.leftMargin = ui.dp(10);
            info.setLayoutParams(ilp);

            TextView tvT = ui.text(info, b.title, 13f, "#F8FAFC", true);
            tvT.setMaxLines(2);
            tvT.setEllipsize(TextUtils.TruncateAt.END);
            ui.text(info, pct + "%  •  -" + BookModel.fmtBookHours(remMs), 11f, "#EF4444", true);
            ui.text(info, b.chapters.size() + " capitoli", 10f, "#94A3B8", false);

            row.addView(info);
            card.addView(row);
            card.setOnClickListener(v -> { selectedBook = b; showChapters(b, false); });
            ui.box.addView(card);
        }
    }

    private void showChapters(final BookModel.Book book, final boolean showNotesTab) {
        screenMode = 1;
        uiHandler.removeCallbacksAndMessages(null);
        ui.clear();

        SharedPreferences prog = getSharedPreferences("wb_progress", MODE_PRIVATE);
        final int activeCh = (svc != null && svc.currentBook != null && svc.currentBook.id.equals(book.id))
                ? svc.currentChapterIdx : prog.getInt(book.id + "_ch", 0);
        final long activePos = (svc != null && svc.currentBook != null && svc.currentBook.id.equals(book.id))
                ? svc.getChapterPosMs() : prog.getLong(book.id + "_pos", 0);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button bBack = ui.chip("←", "#181B22", "#272C37", "#2E3442", "#FFFFFF", 13f);
        bBack.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        bBack.setOnClickListener(v -> showLibrary());
        top.addView(bBack);

        TextView tvB = new TextView(this);
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

        Button bResume = ui.chip("▶ Continua (Cap. " + (activeCh + 1) + ")", "#EF4444", "#DC2626", null, "#FFFFFF", 12f);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(38));
        rlp.setMargins(0, ui.dp(8), 0, ui.dp(6));
        bResume.setLayoutParams(rlp);
        bResume.setOnClickListener(v -> { if (svc != null) svc.loadBookChapter(book, activeCh, activePos, true); showPlayer(); });
        ui.box.addView(bResume);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.bottomMargin = ui.dp(6);
        tabs.setLayoutParams(tabLp);

        Button tCap = ui.chip("CAPITOLI (" + book.chapters.size() + ")",
                !showNotesTab ? "#261214" : "#111318", "#2E1619", !showNotesTab ? "#EF4444" : "#222631",
                !showNotesTab ? "#EF4444" : "#94A3B8", 10.5f);
        Button tNote = ui.chip("NOTE",
                showNotesTab ? "#261214" : "#111318", "#2E1619", showNotesTab ? "#EF4444" : "#222631",
                showNotesTab ? "#EF4444" : "#94A3B8", 10.5f);

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, ui.dp(32), 1f);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, ui.dp(32), 1f);
        p2.leftMargin = ui.dp(6);
        tCap.setLayoutParams(p1);
        tNote.setLayoutParams(p2);
        tCap.setOnClickListener(v -> showChapters(book, false));
        tNote.setOnClickListener(v -> showChapters(book, true));
        tabs.addView(tCap);
        tabs.addView(tNote);
        ui.box.addView(tabs);

        if (showNotesTab) { renderBookmarksList(book); return; }

        for (int i = 0; i < book.chapters.size(); i++) {
            final int idx = i;
            BookModel.Chapter ch = book.chapters.get(i);
            boolean isCur = (i == activeCh);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(ui.roundBox(isCur ? "#1D1316" : "#0D0F14", isCur ? "#EF4444" : "#1C2029", 12));
            row.setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, ui.dp(3), 0, ui.dp(3));
            row.setLayoutParams(lp);

            TextView tvName = new TextView(this);
            tvName.setText(ch.title);
            tvName.setTextColor(Color.parseColor(isCur ? "#38BDF8" : "#F8FAFC"));
            tvName.setTextSize(12.5f);
            if (isCur) tvName.setTypeface(null, Typeface.BOLD);
            tvName.setMaxLines(2);
            tvName.setEllipsize(TextUtils.TruncateAt.END);
            tvName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tvName);

            TextView tvDur = new TextView(this);
            tvDur.setText(BookModel.fmtChapterTime(ch.durationMs));
            tvDur.setTextColor(Color.parseColor(isCur ? "#38BDF8" : "#94A3B8"));
            tvDur.setTextSize(11f);
            tvDur.setPadding(ui.dp(8), 0, 0, 0);
            row.addView(tvDur);

            row.setOnClickListener(v -> {
                long startAt = (idx == activeCh) ? activePos : 0;
                if (svc != null) svc.loadBookChapter(book, idx, startAt, true);
                showPlayer();
            });
            ui.box.addView(row);
        }
    }

    private void renderBookmarksList(final BookModel.Book book) {
        try {
            JSONArray arr = new JSONArray(getSharedPreferences("wb_notes", MODE_PRIVATE).getString(book.id, "[]"));
            if (arr.length() == 0) {
                ui.text(ui.box, "Nessun segnalibro salvato.", 12f, "#94A3B8", false);
                return;
            }
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                final int ch = o.optInt("ch", 0);
                final long pos = o.optLong("pos", 0);
                LinearLayout c = ui.card();
                ui.text(c, o.optString("label", "Segnalibro"), 12.5f, "#38BDF8", true);
                ui.text(c, "Cap. " + (ch + 1) + " • " + BookModel.fmtChapterTime(pos), 11f, "#94A3B8", false);
                c.setOnClickListener(v -> { if (svc != null) svc.loadBookChapter(book, ch, pos, true); showPlayer(); });
                ui.box.addView(c);
            }
        } catch (Exception ignored) {}
    }

    private void addBookmarkNow() {
        if (svc == null || svc.currentBook == null) return;
        try {
            SharedPreferences sp = getSharedPreferences("wb_notes", MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(svc.currentBook.id, "[]"));
            BookModel.Chapter ch = svc.currentBook.chapters.get(svc.currentChapterIdx);
            JSONObject o = new JSONObject().put("ch", svc.currentChapterIdx).put("pos", svc.getChapterPosMs()).put("label", ch.title);
            arr.put(o);
            sp.edit().putString(svc.currentBook.id, arr.toString()).apply();
            Toast.makeText(this, "Segnalibro salvato!", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    private int getVolPercent(AudioManager am) {
        if (am == null) return 0;
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return Math.round((cur * 100f) / max);
    }

    private void openBluetoothMenu() {
        try {
            Intent it = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    private void showPlayer() {
        screenMode = 2;
        uiHandler.removeCallbacksAndMessages(null);
        ui.clear();

        final BookModel.Book book = (svc != null && svc.currentBook != null) ? svc.currentBook : selectedBook;
        if (book == null) { showLibrary(); return; }
        final AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // 1. Header pulito: Mini-copertina | Titolo & Autore | Pulsante Lista Capitoli
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageView miniCover = new ImageView(this);
        int mcSz = ui.dp(34);
        miniCover.setLayoutParams(new LinearLayout.LayoutParams(mcSz, mcSz));
        miniCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (book.cover != null) miniCover.setImageBitmap(book.cover);
        else miniCover.setBackground(ui.roundBox("#1F2430", "#EF4444", 8));
        top.addView(miniCover);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(8);
        tlp.rightMargin = ui.dp(6);
        titles.setLayoutParams(tlp);

        TextView tvBookTitle = ui.text(titles, book.title, 12f, "#F8FAFC", true);
        tvBookTitle.setMaxLines(1);
        tvBookTitle.setEllipsize(TextUtils.TruncateAt.END);
        TextView tvAuthor = ui.text(titles, book.author, 10f, "#94A3B8", false);
        tvAuthor.setMaxLines(1);
        tvAuthor.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(titles);

        Button bList = ui.chip("☰", "#181B22", "#272C37", "#2E3442", "#FFFFFF", 13f);
        bList.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        bList.setOnClickListener(v -> showChapters(book, false));
        top.addView(bList);
        ui.box.addView(top);

        // 2. Barra sottile avanzamento totale del libro
        final ProgressBar bookProg = ui.makeRedBookProgress();
        ((LinearLayout.LayoutParams) bookProg.getLayoutParams()).topMargin = ui.dp(8);
        ui.box.addView(bookProg);

        LinearLayout bookTimes = new LinearLayout(this);
        bookTimes.setOrientation(LinearLayout.HORIZONTAL);
        final TextView tvBookEl = new TextView(this);
        tvBookEl.setTextColor(Color.parseColor("#94A3B8"));
        tvBookEl.setTextSize(9.5f);
        tvBookEl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView tvChapCount = new TextView(this);
        tvChapCount.setTextColor(Color.parseColor("#CBD5E1"));
        tvChapCount.setTextSize(10f);
        tvChapCount.setTypeface(null, Typeface.BOLD);
        tvChapCount.setGravity(Gravity.CENTER);
        tvChapCount.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView tvBookRem = new TextView(this);
        tvBookRem.setTextColor(Color.parseColor("#94A3B8"));
        tvBookRem.setTextSize(9.5f);
        tvBookRem.setGravity(Gravity.END);
        tvBookRem.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bookTimes.addView(tvBookEl);
        bookTimes.addView(tvChapCount);
        bookTimes.addView(tvBookRem);
        ui.box.addView(bookTimes);

        // 3. Titolo capitolo corrente + SeekBar
        final TextView tvChapTitle = new TextView(this);
        tvChapTitle.setTextColor(Color.WHITE);
        tvChapTitle.setTextSize(13.5f);
        tvChapTitle.setTypeface(null, Typeface.BOLD);
        tvChapTitle.setGravity(Gravity.CENTER);
        tvChapTitle.setMaxLines(2);
        tvChapTitle.setEllipsize(TextUtils.TruncateAt.END);
        tvChapTitle.setPadding(0, ui.dp(8), 0, ui.dp(2));
        ui.box.addView(tvChapTitle);

        final SeekBar seekBar = ui.makeRedSeekBar();
        ui.box.addView(seekBar);

        LinearLayout chapTimes = new LinearLayout(this);
        chapTimes.setOrientation(LinearLayout.HORIZONTAL);
        final TextView tvChapEl = new TextView(this);
        tvChapEl.setTextColor(Color.parseColor("#94A3B8"));
        tvChapEl.setTextSize(10f);
        tvChapEl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView tvChapRem = new TextView(this);
        tvChapRem.setTextColor(Color.parseColor("#94A3B8"));
        tvChapRem.setTextSize(10f);
        tvChapRem.setGravity(Gravity.END);
        tvChapRem.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        chapTimes.addView(tvChapEl);
        chapTimes.addView(tvChapRem);
        ui.box.addView(chapTimes);

        // 4. Controlli trasporto simmetrici: [-15s] | [ ▶ ] | [+15s]
        LinearLayout mainCtrl = new LinearLayout(this);
        mainCtrl.setOrientation(LinearLayout.HORIZONTAL);
        mainCtrl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mclp.topMargin = ui.dp(8);
        mainCtrl.setLayoutParams(mclp);

        Button bRew = ui.chip("-15s", "#14171F", "#242936", "#2E3442", "#F8FAFC", 12f);
        bRew.setLayoutParams(new LinearLayout.LayoutParams(0, ui.dp(40), 1f));
        bRew.setOnClickListener(v -> { if (svc != null) svc.skipSeconds(-15); });

        final Button bPlay = new Button(this);
        int playSz = ui.dp(50);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(playSz, playSz);
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

        // 5. Riga Velocità & Sleep Timer (scorrendo con la rotella)
        LinearLayout subCtrl = new LinearLayout(this);
        subCtrl.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sclp.topMargin = ui.dp(8);
        subCtrl.setLayoutParams(sclp);

        final Button bSpeed = ui.chip("1.0×", "#14171F", "#242936", "#2E3442", "#F8FAFC", 11.5f);
        bSpeed.setLayoutParams(new LinearLayout.LayoutParams(0, ui.dp(36), 1f));
        bSpeed.setOnClickListener(v -> {
            if (svc != null) { svc.cycleSpeed(); bSpeed.setText(String.format(Locale.US, "%.2g×", svc.speed)); }
        });

        final Button bSleep = ui.chip("Zzz", "#14171F", "#242936", "#2E3442", "#94A3B8", 11.5f);
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(0, ui.dp(36), 1f);
        zlp.leftMargin = ui.dp(8);
        bSleep.setLayoutParams(zlp);
        bSleep.setOnClickListener(v -> {
            if (svc != null) {
                svc.cycleSleepTimer();
                bSleep.setText(svc.getSleepLabel());
                bSleep.setTextColor(Color.parseColor("Zzz".equals(svc.getSleepLabel()) ? "#94A3B8" : "#EF4444"));
            }
        });
        subCtrl.addView(bSpeed);
        subCtrl.addView(bSleep);
        ui.box.addView(subCtrl);

        // 6. Selettore Volume Audio (scorrendo con la rotella)
        LinearLayout volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams vrlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vrlp.topMargin = ui.dp(8);
        volRow.setLayoutParams(vrlp);

        final TextView tvVol = new TextView(this);
        tvVol.setText("🔊 " + getVolPercent(am) + "%");
        tvVol.setTextColor(Color.parseColor("#E2E8F0"));
        tvVol.setTextSize(11.5f);
        tvVol.setTypeface(null, Typeface.BOLD);
        tvVol.setGravity(Gravity.CENTER);
        tvVol.setBackground(ui.roundBox("#111318", "#222631", 999));
        LinearLayout.LayoutParams vtlp = new LinearLayout.LayoutParams(0, ui.dp(36), 1f);
        vtlp.leftMargin = ui.dp(6);
        vtlp.rightMargin = ui.dp(6);
        tvVol.setLayoutParams(vtlp);

        Button bVolDown = ui.chip("−", "#14171F", "#242936", "#2E3442", "#FFFFFF", 14f);
        bVolDown.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(42), ui.dp(36)));
        bVolDown.setOnClickListener(v -> {
            if (am != null) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); tvVol.setText("🔊 " + getVolPercent(am) + "%"); }
        });

        Button bVolUp = ui.chip("+", "#14171F", "#242936", "#2E3442", "#FFFFFF", 14f);
        bVolUp.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(42), ui.dp(36)));
        bVolUp.setOnClickListener(v -> {
            if (am != null) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); tvVol.setText("🔊 " + getVolPercent(am) + "%"); }
        });

        volRow.addView(bVolDown);
        volRow.addView(tvVol);
        volRow.addView(bVolUp);
        ui.box.addView(volRow);

        // 7. Riga Segnalibro & Menu Bluetooth Auricolari
        LinearLayout extraRow = new LinearLayout(this);
        extraRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams exlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        exlp.topMargin = ui.dp(8);
        extraRow.setLayoutParams(exlp);

        Button bNote = ui.chip("🔖 Nota", "#14171F", "#242936", "#2E3442", "#FBBF24", 11f);
        bNote.setLayoutParams(new LinearLayout.LayoutParams(0, ui.dp(36), 1f));
        bNote.setOnClickListener(v -> addBookmarkNow());

        Button bBt = ui.chip("🎧 Bluetooth", "#111D2E", "#1A2E47", "#2563EB", "#60A5FA", 11f);
        LinearLayout.LayoutParams btlp = new LinearLayout.LayoutParams(0, ui.dp(36), 1f);
        btlp.leftMargin = ui.dp(8);
        bBt.setLayoutParams(btlp);
        bBt.setOnClickListener(v -> openBluetoothMenu());

        extraRow.addView(bNote);
        extraRow.addView(bBt);
        ui.box.addView(extraRow);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && svc != null && svc.currentBook != null) {
                    BookModel.Chapter ch = svc.currentBook.chapters.get(svc.currentChapterIdx);
                    long preview = (ch.durationMs * progress) / 1000L;
                    tvChapEl.setText(BookModel.fmtChapterTime(preview));
                    tvChapRem.setText("-" + BookModel.fmtChapterTime(Math.max(0, ch.durationMs - preview)));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userScrubbing = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userScrubbing = false;
                if (svc != null && svc.currentBook != null) {
                    BookModel.Chapter ch = svc.currentBook.chapters.get(svc.currentChapterIdx);
                    svc.seekChapterTo((ch.durationMs * sb.getProgress()) / 1000L);
                }
            }
        });

        playerRefreshTask = new Runnable() {
            @Override
            public void run() {
                if (screenMode != 2) return;
                if (svc != null && svc.currentBook != null) {
                    BookModel.Book b = svc.currentBook;
                    int cIdx = svc.currentChapterIdx;
                    BookModel.Chapter ch = b.chapters.get(cIdx);
                    long chPos = svc.getChapterPosMs();
                    long chDur = Math.max(1, ch.durationMs);

                    tvChapTitle.setText(ch.title);
                    tvChapCount.setText((cIdx + 1) + "/" + b.chapters.size());

                    long bookEl = b.getElapsedBookMs(cIdx, chPos);
                    long bookRem = Math.max(0, b.totalDurationMs - bookEl);
                    tvBookEl.setText(BookModel.fmtBookHours(bookEl));
                    tvBookRem.setText("-" + BookModel.fmtBookHours(bookRem));
                    if (b.totalDurationMs > 0) bookProg.setProgress((int) ((bookEl * 1000L) / b.totalDurationMs));

                    if (!userScrubbing) {
                        seekBar.setProgress((int) Math.min(1000, (chPos * 1000L) / chDur));
                        tvChapEl.setText(BookModel.fmtChapterTime(chPos));
                        tvChapRem.setText("-" + BookModel.fmtChapterTime(Math.max(0, ch.durationMs - chPos)));
                    }

                    bPlay.setText(svc.isPlaying() ? "❚❚" : "▶");
                    bSpeed.setText(String.format(Locale.US, "%.2g×", svc.speed));
                    String sl = svc.getSleepLabel();
                    bSleep.setText(sl);
                    bSleep.setTextColor(Color.parseColor("Zzz".equals(sl) ? "#94A3B8" : "#EF4444"));
                    tvVol.setText("🔊 " + getVolPercent(am) + "%");
                }
                uiHandler.postDelayed(this, 500);
            }
        };
        uiHandler.post(playerRefreshTask);
    }
}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new WatchUi(this);
        checkStoragePerm();
        Intent it = new Intent(this, PlaybackService.class);
        startService(it);
        bindService(it, conn, Context.BIND_AUTO_CREATE);
    }

    private void checkStoragePerm() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO}, 10);
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 10);
        }
    }

    @Override
    public void onRequestPermissionsResult(int reqCode, String[] perms, int[] res) {
        super.onRequestPermissionsResult(reqCode, perms, res);
        if (!BookCache.hasCache(this)) refreshLibrary();
    }

    @Override
    protected void onActivityResult(int reqCode, int resCode, Intent data) {
        super.onActivityResult(reqCode, resCode, data);
        if (reqCode == REQ_IMPORT_BOOK && resCode == RESULT_OK && data != null && data.getData() != null) {
            importAudioUri(data.getData());
        }
    }

    private void importAudioUri(final Uri uri) {
        Toast.makeText(this, "Importazione...", Toast.LENGTH_SHORT).show();
        pool.execute(() -> {
            try {
                String name = "audiolibro.m4b";
                try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) name = c.getString(idx);
                    }
                }
                File destDir = getExternalFilesDir("Audiobooks");
                if (destDir != null && !destDir.exists()) destDir.mkdirs();
                File outF = new File(destDir, name);
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(outF)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                }
                library = M4bParser.scanLibrary(this);
                BookCache.saveLibrary(this, library);
                runOnUiThread(() -> { Toast.makeText(this, "Importato!", Toast.LENGTH_SHORT).show(); showLibrary(); });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Errore: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ui != null && ui.onRotary(ev)) return true;
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public void onBackPressed() {
        uiHandler.removeCallbacksAndMessages(null);
        if (screenMode == 2 && selectedBook != null) showChapters(selectedBook, false);
        else if (screenMode == 1) showLibrary();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (bound) { unbindService(conn); bound = false; }
        pool.shutdownNow();
        super.onDestroy();
    }

    private void refreshLibrary() {
        screenMode = 0;
        uiHandler.removeCallbacksAndMessages(null);
        ui.clear();
        ui.text(ui.box, "WristBooks", 16f, "#EF4444", true).setGravity(Gravity.CENTER);
        ui.text(ui.box, "Scansione Audiobooks...", 12f, "#94A3B8", false).setGravity(Gravity.CENTER);
        pool.execute(() -> {
            library = M4bParser.scanLibrary(this);
            BookCache.saveLibrary(this, library);
            runOnUiThread(() -> {
                Toast.makeText(this, "Trovati " + library.size() + " audiolibri", Toast.LENGTH_SHORT).show();
                showLibrary();
            });
        });
    }

    private void showLibrary() {
        screenMode = 0;
        uiHandler.removeCallbacksAndMessages(null);
        ui.clear();

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER);
        TextView title = new TextView(this);
        title.setText("Audiolibri");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15f);
        title.setTypeface(null, Typeface.BOLD);
        hdr.addView(title);

        Button bScan = ui.chip("↻", "#181B22", "#272C37", "#2E3442", "#EF4444", 12f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32));
        slp.leftMargin = ui.dp(8);
        bScan.setLayoutParams(slp);
        bScan.setOnClickListener(v -> refreshLibrary());
        hdr.addView(bScan);

        Button bAdd = ui.chip("+", "#181B22", "#272C37", "#2E3442", "#34D399", 13f);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32));
        alp.leftMargin = ui.dp(6);
        bAdd.setLayoutParams(alp);
        bAdd.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            startActivityForResult(pick, REQ_IMPORT_BOOK);
        });
        hdr.addView(bAdd);
        ui.box.addView(hdr);

        if (library.isEmpty()) {
            LinearLayout empty = ui.card();
            ui.text(empty, "Nessun audiolibro in lista", 13f, "#F8FAFC", true);
            ui.text(empty, "Tocca ↻ per scansionare /sdcard/Audiobooks oppure '+' per importare.", 11.5f, "#94A3B8", false);
            ui.box.addView(empty);
            return;
        }

        SharedPreferences prog = getSharedPreferences("wb_progress", MODE_PRIVATE);
        for (final BookModel.Book b : library) {
            int savedCh = prog.getInt(b.id + "_ch", 0);
            long savedPos = prog.getLong(b.id + "_pos", 0);
            int pct = b.getPercent(savedCh, savedPos);
            long remMs = Math.max(0, b.totalDurationMs - b.getElapsedBookMs(savedCh, savedPos));

            LinearLayout card = ui.card();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            ImageView iv = new ImageView(this);
            int cSize = ui.dp(48);
            iv.setLayoutParams(new LinearLayout.LayoutParams(cSize, cSize));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (b.cover != null) iv.setImageBitmap(b.cover);
            else iv.setBackground(ui.roundBox("#1F2430", "#EF4444", 10));
            row.addView(iv);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ilp.leftMargin = ui.dp(10);
            info.setLayoutParams(ilp);

            TextView tvT = ui.text(info, b.title, 13f, "#F8FAFC", true);
            tvT.setMaxLines(2);
            tvT.setEllipsize(TextUtils.TruncateAt.END);
            ui.text(info, pct + "%  •  -" + BookModel.fmtBookHours(remMs), 11f, "#EF4444", true);
            ui.text(info, b.chapters.size() + " capitoli", 10f, "#94A3B8", false);

            row.addView(info);
            card.addView(row);
            card.setOnClickListener(v -> { selectedBook = b; showChapters(b, false); });
            ui.box.addView(card);
        }
    }

    private void showChapters(final BookModel.Book book, final boolean showNotesTab) {
        screenMode = 1;
        uiHandler.removeCallbacksAndMessages(null);
        ui.clear();

        SharedPreferences prog = getSharedPreferences("wb_progress", MODE_PRIVATE);
        final int activeCh = (svc != null && svc.currentBook != null && svc.currentBook.id.equals(book.id))
                ? svc.currentChapterIdx : prog.getInt(book.id + "_ch", 0);
        final long activePos = (svc != null && svc.currentBook != null && svc.currentBook.id.equals(book.id))
                ? svc.getChapterPosMs() : prog.getLong(book.id + "_pos", 0);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button bBack = ui.chip("←", "#181B22", "#272C37", "#2E3442", "#FFFFFF", 13f);
        bBack.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        bBack.setOnClickListener(v -> showLibrary());
        top.addView(bBack);

        TextView tvB = new TextView(this);
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

        Button bResume = ui.chip("▶ Continua (Cap. " + (activeCh + 1) + ")", "#EF4444", "#DC2626", null, "#FFFFFF", 12f);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(38));
        rlp.setMargins(0, ui.dp(8), 0, ui.dp(6));
        bResume.setLayoutParams(rlp);
        bResume.setOnClickListener(v -> { if (svc != null) svc.loadBookChapter(book, activeCh, activePos, true); showPlayer(); });
        ui.box.addView(bResume);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.bottomMargin = ui.dp(6);
        tabs.setLayoutParams(tabLp);

        Button tCap = ui.chip("CAPITOLI (" + book.chapters.size() + ")",
                !showNotesTab ? "#261214" : "#111318", "#2E1619", !showNotesTab ? "#EF4444" : "#222631",
                !showNotesTab ? "#EF4444" : "#94A3B8", 10.5f);
        Button tNote = ui.chip("NOTE",
                showNotesTab ? "#261214" : "#111318", "#2E1619", showNotesTab ? "#EF4444" : "#222631",
                showNotesTab ? "#EF4444" : "#94A3B8", 10.5f);

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, ui.dp(32), 1f);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, ui.dp(32), 1f);
        p2.leftMargin = ui.dp(6);
        tCap.setLayoutParams(p1);
        tNote.setLayoutParams(p2);
        tCap.setOnClickListener(v -> showChapters(book, false));
        tNote.setOnClickListener(v -> showChapters(book, true));
        tabs.addView(tCap);
        tabs.addView(tNote);
        ui.box.addView(tabs);

        if (showNotesTab) { renderBookmarksList(book); return; }

        for (int i = 0; i < book.chapters.size(); i++) {
            final int idx = i;
            BookModel.Chapter ch = book.chapters.get(i);
            boolean isCur = (i == activeCh);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(ui.roundBox(isCur ? "#1D1316" : "#0D0F14", isCur ? "#EF4444" : "#1C2029", 12));
            row.setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, ui.dp(3), 0, ui.dp(3));
            row.setLayoutParams(lp);

            TextView tvName = new TextView(this);
            tvName.setText(ch.title);
            tvName.setTextColor(Color.parseColor(isCur ? "#38BDF8" : "#F8FAFC"));
            tvName.setTextSize(12.5f);
            if (isCur) tvName.setTypeface(null, Typeface.BOLD);
            tvName.setMaxLines(2);
            tvName.setEllipsize(TextUtils.TruncateAt.END);
            tvName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tvName);

            TextView tvDur = new TextView(this);
            tvDur.setText(BookModel.fmtChapterTime(ch.durationMs));
            tvDur.setTextColor(Color.parseColor(isCur ? "#38BDF8" : "#94A3B8"));
            tvDur.setTextSize(11f);
            tvDur.setPadding(ui.dp(8), 0, 0, 0);
            row.addView(tvDur);

            row.setOnClickListener(v -> {
                long startAt = (idx == activeCh) ? activePos : 0;
                if (svc != null) svc.loadBookChapter(book, idx, startAt, true);
                showPlayer();
            });
            ui.box.addView(row);
        }
    }

    private void renderBookmarksList(final BookModel.Book book) {
        try {
            JSONArray arr = new JSONArray(getSharedPreferences("wb_notes", MODE_PRIVATE).getString(book.id, "[]"));
            if (arr.length() == 0) {
                ui.text(ui.box, "Nessun segnalibro salvato.", 12f, "#94A3B8", false);
                return;
            }
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                final int ch = o.optInt("ch", 0);
                final long pos = o.optLong("pos", 0);
                LinearLayout c = ui.card();
                ui.text(c, o.optString("label", "Segnalibro"), 12.5f, "#38BDF8", true);
                ui.text(c, "Cap. " + (ch + 1) + " • " + BookModel.fmtChapterTime(pos), 11f, "#94A3B8", false);
                c.setOnClickListener(v -> { if (svc != null) svc.loadBookChapter(book, ch, pos, true); showPlayer(); });
                ui.box.addView(c);
            }
        } catch (Exception ignored) {}
    }

    private void addBookmarkNow() {
        if (svc == null || svc.currentBook == null) return;
        try {
            SharedPreferences sp = getSharedPreferences("wb_notes", MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(svc.currentBook.id, "[]"));
            BookModel.Chapter ch = svc.currentBook.chapters.get(svc.currentChapterIdx);
            JSONObject o = new JSONObject().put("ch", svc.currentChapterIdx).put("pos", svc.getChapterPosMs()).put("label", ch.title);
            arr.put(o);
            sp.edit().putString(svc.currentBook.id, arr.toString()).apply();
            Toast.makeText(this, "Segnalibro salvato!", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    private int getVolPercent(AudioManager am) {
        if (am == null) return 0;
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return Math.round((cur * 100f) / max);
    }

    private void openBluetoothMenu() {
        try {
            Intent it = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    private void showPlayer() {
        screenMode = 2;
        uiHandler.removeCallbacksAndMessages(null);
        ui.clear();

        final BookModel.Book book = (svc != null && svc.currentBook != null) ? svc.currentBook : selectedBook;
        if (book == null) { showLibrary(); return; }
        final AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageView miniCover = new ImageView(this);
        int mcSz = ui.dp(34);
        miniCover.setLayoutParams(new LinearLayout.LayoutParams(mcSz, mcSz));
        miniCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (book.cover != null) miniCover.setImageBitmap(book.cover);
        else miniCover.setBackground(ui.roundBox("#1F2430", "#EF4444", 8));
        top.addView(miniCover);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(8);
        tlp.rightMargin = ui.dp(6);
        titles.setLayoutParams(tlp);

        TextView tvBookTitle = ui.text(titles, book.title, 12f, "#F8FAFC", true);
        tvBookTitle.setMaxLines(1);
        tvBookTitle.setEllipsize(TextUtils.TruncateAt.END);
        TextView tvAuthor = ui.text(titles, book.author, 10f, "#94A3B8", false);
        tvAuthor.setMaxLines(1);
        tvAuthor.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(titles);

        Button bList = ui.chip("☰", "#181B22", "#272C37", "#2E3442", "#FFFFFF", 13f);
        bList.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        bList.setOnClickListener(v -> showChapters(book, false));
        top.addView(bList);
        ui.box.addView(top);

        final ProgressBar bookProg = ui.makeRedBookProgress();
        ((LinearLayout.LayoutParams) bookProg.getLayoutParams()).topMargin = ui.dp(8);
        ui.box.addView(bookProg);

        LinearLayout bookTimes = new LinearLayout(this);
        bookTimes.setOrientation(LinearLayout.HORIZONTAL);
        final TextView tvBookEl = new TextView(this);
        tvBookEl.setTextColor(Color.parseColor("#94A3B8"));
        tvBookEl.setTextSize(9.5f);
        tvBookEl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView tvChapCount = new TextView(this);
        tvChapCount.setTextColor(Color.parseColor("#CBD5E1"));
        tvChapCount.setTextSize(10f);
        tvChapCount.setTypeface(null, Typeface.BOLD);
        tvChapCount.setGravity(Gravity.CENTER);
        tvChapCount.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView tvBookRem = new TextView(this);
        tvBookRem.setTextColor(Color.parseColor("#94A3B8"));
        tvBookRem.setTextSize(9.5f);
        tvBookRem.setGravity(Gravity.END);
        tvBookRem.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bookTimes.addView(tvBookEl);
        bookTimes.addView(tvChapCount);
        bookTimes.addView(tvBookRem);
        ui.box.addView(bookTimes);

        final TextView tvChapTitle = new TextView(this);
        tvChapTitle.setTextColor(Color.WHITE);
        tvChapTitle.setTextSize(13.5f);
        tvChapTitle.setTypeface(null, Typeface.BOLD);
        tvChapTitle.setGravity(Gravity.CENTER);
        tvChapTitle.setMaxLines(2);
        tvChapTitle.setEllipsize(TextUtils.TruncateAt.END);
        tvChapTitle.setPadding(0, ui.dp(8), 0, ui.dp(2));
        ui.box.addView(tvChapTitle);

        final SeekBar seekBar = ui.makeRedSeekBar();
        ui.box.addView(seekBar);

        LinearLayout chapTimes = new LinearLayout(this);
        chapTimes.setOrientation(LinearLayout.HORIZONTAL);
        final TextView tvChapEl = new TextView(this);
        tvChapEl.setTextColor(Color.parseColor("#94A3B8"));
        tvChapEl.setTextSize(10f);
        tvChapEl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView tvChapRem = new TextView(this);
        tvChapRem.setTextColor(Color.parseColor("#94A3B8"));
        tvChapRem.setTextSize(10f);
        tvChapRem.setGravity(Gravity.END);
        tvChapRem.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        chapTimes.addView(tvChapEl);
        chapTimes.addView(tvChapRem);
        ui.box.addView(chapTimes);

        LinearLayout mainCtrl = new LinearLayout(this);
        mainCtrl.setOrientation(LinearLayout.HORIZONTAL);
        mainCtrl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mclp.topMargin = ui.dp(8);
        mainCtrl.setLayoutParams(mclp);

        Button bRew = ui.chip("-15s", "#14171F", "#242936", "#2E3442", "#F8FAFC", 12f);
        bRew.setLayoutParams(new LinearLayout.LayoutParams(0, ui.dp(40), 1f));
        bRew.setOnClickListener(v -> { if (svc != null) svc.skipSeconds(-15); });

        final Button bPlay = new Button(this);
        int playSz = ui.dp(50);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(playSz, playSz);
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

        LinearLayout subCtrl = new LinearLayout(this);
        subCtrl.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sclp.topMargin = ui.dp(8);
        subCtrl.setLayoutParams(sclp);

        final Button bSpeed = ui.chip("1.0×", "#14171F", "#242936", "#2E3442", "#F8FAFC", 11.5f);
        bSpeed.setLayoutParams(new LinearLayout.LayoutParams(0, ui.dp(36), 1f));
        bSpeed.setOnClickListener(v -> {
            if (svc != null) { svc.cycleSpeed(); bSpeed.setText(String.format(Locale.US, "%.2g×", svc.speed)); }
        });

        final Button bSleep = ui.chip("Zzz", "#14171F", "#242936", "#2E3442", "#94A3B8", 11.5f);
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(0, ui.dp(36), 1f);
        zlp.leftMargin = ui.dp(8);
        bSleep.setLayoutParams(zlp);
        bSleep.setOnClickListener(v -> {
            if (svc != null) {
                svc.cycleSleepTimer();
                bSleep.setText(svc.getSleepLabel());
                bSleep.setTextColor(Color.parseColor("Zzz".equals(svc.getSleepLabel()) ? "#94A3B8" : "#EF4444"));
            }
        });
        subCtrl.addView(bSpeed);
        subCtrl.addView(bSleep);
        ui.box.addView(subCtrl);

        LinearLayout volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams vrlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vrlp.topMargin = ui.dp(8);
        volRow.setLayoutParams(vrlp);

        final TextView tvVol = new TextView(this);
        tvVol.setText("🔊 " + getVolPercent(am) + "%");
        tvVol.setTextColor(Color.parseColor("#E2E8F0"));
        tvVol.setTextSize(11.5f);
        tvVol.setTypeface(null, Typeface.BOLD);
        tvVol.setGravity(Gravity.CENTER);
        tvVol.setBackground(ui.roundBox("#111318", "#222631", 999));
        LinearLayout.LayoutParams vtlp = new LinearLayout.LayoutParams(0, ui.dp(36), 1f);
        vtlp.leftMargin = ui.dp(6);
        vtlp.rightMargin = ui.dp(6);
        tvVol.setLayoutParams(vtlp);

        Button bVolDown = ui.chip("−", "#14171F", "#242936", "#2E3442", "#FFFFFF", 14f);
        bVolDown.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(42), ui.dp(36)));
        bVolDown.setOnClickListener(v -> {
            if (am != null) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); tvVol.setText("🔊 " + getVolPercent(am) + "%"); }
        });

        Button bVolUp = ui.chip("+", "#14171F", "#242936", "#2E3442", "#FFFFFF", 14f);
        bVolUp.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(42), ui.dp(36)));
        bVolUp.setOnClickListener(v -> {
            if (am != null) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); tvVol.setText("🔊 " + getVolPercent(am) + "%"); }
        });

        volRow.addView(bVolDown);
        volRow.addView(tvVol);
        volRow.addView(bVolUp);
        ui.box.addView(volRow);

        LinearLayout extraRow = new LinearLayout(this);
        extraRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams exlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        exlp.topMargin = ui.dp(8);
        extraRow.setLayoutParams(exlp);

        Button bNote = ui.chip("🔖 Nota", "#14171F", "#242936", "#2E3442", "#FBBF24", 11f);
        bNote.setLayoutParams(new LinearLayout.LayoutParams(0, ui.dp(36), 1f));
        bNote.setOnClickListener(v -> addBookmarkNow());

        Button bBt = ui.chip("🎧 Bluetooth", "#111D2E", "#1A2E47", "#2563EB", "#60A5FA", 11f);
        LinearLayout.LayoutParams btlp = new LinearLayout.LayoutParams(0, ui.dp(36), 1f);
        btlp.leftMargin = ui.dp(8);
        bBt.setLayoutParams(btlp);
        bBt.setOnClickListener(v -> openBluetoothMenu());

        extraRow.addView(bNote);
        extraRow.addView(bBt);
        ui.box.addView(extraRow);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && svc != null && svc.currentBook != null) {
                    BookModel.Chapter ch = svc.currentBook.chapters.get(svc.currentChapterIdx);
                    long preview = (ch.durationMs * progress) / 1000L;
                    tvChapEl.setText(BookModel.fmtChapterTime(preview));
                    tvChapRem.setText("-" + BookModel.fmtChapterTime(Math.max(0, ch.durationMs - preview)));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userScrubbing = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userScrubbing = false;
                if (svc != null && svc.currentBook != null) {
                    BookModel.Chapter ch = svc.currentBook.chapters.get(svc.currentChapterIdx);
                    svc.seekChapterTo((ch.durationMs * sb.getProgress()) / 1000L);
                }
            }
        });

        playerRefreshTask = new Runnable() {
            @Override
            public void run() {
                if (screenMode != 2) return;
                if (svc != null && svc.currentBook != null) {
                    BookModel.Book b = svc.currentBook;
                    int cIdx = svc.currentChapterIdx;
                    BookModel.Chapter ch = b.chapters.get(cIdx);
                    long chPos = svc.getChapterPosMs();
                    long chDur = Math.max(1, ch.durationMs);

                    tvChapTitle.setText(ch.title);
                    tvChapCount.setText((cIdx + 1) + "/" + b.chapters.size());

                    long bookEl = b.getElapsedBookMs(cIdx, chPos);
                    long bookRem = Math.max(0, b.totalDurationMs - bookEl);
                    tvBookEl.setText(BookModel.fmtBookHours(bookEl));
                    tvBookRem.setText("-" + BookModel.fmtBookHours(bookRem));
                    if (b.totalDurationMs > 0) bookProg.setProgress((int) ((bookEl * 1000L) / b.totalDurationMs));

                    if (!userScrubbing) {
                        seekBar.setProgress((int) Math.min(1000, (chPos * 1000L) / chDur));
                        tvChapEl.setText(BookModel.fmtChapterTime(chPos));
                        tvChapRem.setText("-" + BookModel.fmtChapterTime(Math.max(0, ch.durationMs - chPos)));
                    }

                    bPlay.setText(svc.isPlaying() ? "❚❚" : "▶");
                    bSpeed.setText(String.format(Locale.US, "%.2g×", svc.speed));
                    String sl = svc.getSleepLabel();
                    bSleep.setText(sl);
                    bSleep.setTextColor(Color.parseColor("Zzz".equals(sl) ? "#94A3B8" : "#EF4444"));
                    tvVol.setText("🔊 " + getVolPercent(am) + "%");
                }
                uiHandler.postDelayed(this, 500);
            }
        };
        uiHandler.post(playerRefreshTask);
    }
}
