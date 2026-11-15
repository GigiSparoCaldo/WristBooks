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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_IMPORT = 55;
    public WatchUi ui;
    public PlaybackService svc;
    public final Handler uiHandler = new Handler(Looper.getMainLooper());
    public int screenMode = 0;
    public BookModel.Book selectedBook;

    private boolean bound = false;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private List<BookModel.Book> library = new ArrayList<>();

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = ((PlaybackService.LocalBinder) binder).getService();
            bound = true;
            if (svc.currentBook != null && svc.isPlaying()) {
                selectedBook = svc.currentBook;
                showPlayer();
            } else if (BookCache.hasCache(MainActivity.this)) {
                library = BookCache.loadLibrary(MainActivity.this);
                showLibrary();
            } else {
                refreshLibrary();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) { bound = false; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new WatchUi(this);
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO}, 10);
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 10);
        }
        Intent it = new Intent(this, PlaybackService.class);
        bindService(it, conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (!BookCache.hasCache(this)) refreshLibrary();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMPORT && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
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
                    File dir = getExternalFilesDir("Audiobooks");
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    try (InputStream is = getContentResolver().openInputStream(uri);
                         FileOutputStream fos = new FileOutputStream(new File(dir, name))) {
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

    public void refreshLibrary() {
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

    public void showLibrary() {
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
            startActivityForResult(pick, REQ_IMPORT);
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
            iv.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
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

    public void showChapters(BookModel.Book book, boolean notesTab) { ChaptersView.show(this, book, notesTab); }
    public void showPlayer() { PlayerView.show(this); }
}
