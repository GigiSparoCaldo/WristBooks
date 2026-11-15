package com.akemi.wristbooks;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class BookCache {
    private static final String PREF = "wb_cache";
    private static final String KEY = "books_json";

    public static boolean hasCache(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).contains(KEY);
    }

    public static void saveLibrary(Context ctx, List<BookModel.Book> books) {
        try {
            JSONArray arr = new JSONArray();
            File dir = ctx.getCacheDir();
            for (BookModel.Book b : books) {
                JSONObject o = new JSONObject()
                        .put("id", b.id).put("title", b.title).put("author", b.author)
                        .put("path", b.folderOrFilePath).put("totalDur", b.totalDurationMs);
                String cov = "";
                if (b.cover != null) {
                    File cf = new File(dir, "cov_" + Math.abs(b.id.hashCode()) + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(cf)) {
                        b.cover.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                        cov = cf.getAbsolutePath();
                    } catch (Exception ignored) {}
                }
                o.put("coverFile", cov);
                JSONArray chs = new JSONArray();
                for (BookModel.Chapter ch : b.chapters) {
                    chs.put(new JSONObject().put("t", ch.title).put("p", ch.filePath)
                            .put("s", ch.startMs).put("d", ch.durationMs));
                }
                o.put("chapters", chs);
                arr.put(o);
            }
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static List<BookModel.Book> loadLibrary(Context ctx) {
        List<BookModel.Book> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                BookModel.Book b = new BookModel.Book();
                b.id = o.optString("id", "");
                b.title = o.optString("title", "Audiolibro");
                b.author = o.optString("author", "Audiolibro");
                b.folderOrFilePath = o.optString("path", "");
                b.totalDurationMs = o.optLong("totalDur", 0);
                String cf = o.optString("coverFile", "");
                if (!cf.isEmpty() && new File(cf).exists()) b.cover = BitmapFactory.decodeFile(cf);
                JSONArray chs = o.optJSONArray("chapters");
                if (chs != null) {
                    for (int k = 0; k < chs.length(); k++) {
                        JSONObject co = chs.optJSONObject(k);
                        if (co != null) b.chapters.add(new BookModel.Chapter(
                                co.optString("t", "Capitolo " + (k + 1)), co.optString("p", ""),
                                co.optLong("s", 0), co.optLong("d", 0)));
                    }
                }
                if (!b.chapters.isEmpty()) list.add(b);
            }
        } catch (Exception ignored) {}
        return list;
    }
}
