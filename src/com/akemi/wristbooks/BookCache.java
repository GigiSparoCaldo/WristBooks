package com.akemi.wristbooks;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class BookCache {
    private static final String PREF_NAME = "wb_cache";
    private static final String KEY_JSON = "books_json";

    public static boolean hasCache(Context ctx) {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).contains(KEY_JSON);
    }

    public static void saveLibrary(Context ctx, List<BookModel.Book> books) {
        try {
            JSONArray arr = new JSONArray();
            File cacheDir = ctx.getCacheDir();
            for (BookModel.Book b : books) {
                JSONObject o = new JSONObject();
                o.put("id", b.id);
                o.put("title", b.title);
                o.put("author", b.author);
                o.put("path", b.folderOrFilePath);
                o.put("totalDur", b.totalDurationMs);

                String coverPath = "";
                if (b.cover != null) {
                    File cf = new File(cacheDir, "cov_" + Math.abs(b.id.hashCode()) + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(cf)) {
                        b.cover.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                        coverPath = cf.getAbsolutePath();
                    } catch (Exception ignored) {}
                }
                o.put("coverFile", coverPath);

                JSONArray chArr = new JSONArray();
                for (BookModel.Chapter ch : b.chapters) {
                    JSONObject co = new JSONObject();
                    co.put("t", ch.title);
                    co.put("p", ch.filePath);
                    co.put("s", ch.startMs);
                    co.put("d", ch.durationMs);
                    chArr.put(co);
                }
                o.put("chapters", chArr);
                arr.put(o);
            }
            ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_JSON, arr.toString())
                    .apply();
        } catch (Exception ignored) {}
    }

    public static List<BookModel.Book> loadLibrary(Context ctx) {
        List<BookModel.Book> list = new ArrayList<>();
        try {
            String raw = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_JSON, "[]");
            JSONArray arr = new JSONArray(raw);
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
                if (!cf.isEmpty() && new File(cf).exists()) {
                    b.cover = BitmapFactory.decodeFile(cf);
                }

                JSONArray chArr = o.optJSONArray("chapters");
                if (chArr != null) {
                    for (int k = 0; k < chArr.length(); k++) {
                        JSONObject co = chArr.optJSONObject(k);
                        if (co == null) continue;
                        b.chapters.add(new BookModel.Chapter(
                                co.optString("t", "Capitolo " + (k + 1)),
                                co.optString("p", ""),
                                co.optLong("s", 0),
                                co.optLong("d", 0)));
                    }
                }
                if (!b.chapters.isEmpty()) list.add(b);
            }
        } catch (Exception ignored) {}
        return list;
    }
}

    public static List<BookModel.Book> loadLibrary(Context ctx) {
        List<BookModel.Book> list = new ArrayList<>();
        try {
            String raw = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_JSON, "[]");
            JSONArray arr = new JSONArray(raw);
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
                if (!cf.isEmpty() && new File(cf).exists()) {
                    b.cover = BitmapFactory.decodeFile(cf);
                }

                JSONArray chArr = o.optJSONArray("chapters");
                if (chArr != null) {
                    for (int k = 0; k < chArr.length(); k++) {
                        JSONObject co = chArr.optJSONObject(k);
                        if (co == null) continue;
                        b.chapters.add(new BookModel.Chapter(
                                co.optString("t", "Capitolo " + (k + 1)),
                                co.optString("p", ""),
                                co.optLong("s", 0),
                                co.optLong("d", 0)));
                    }
                }
                if (!b.chapters.isEmpty()) list.add(b);
            }
        } catch (Exception ignored) {}
        return list;
    }
}
