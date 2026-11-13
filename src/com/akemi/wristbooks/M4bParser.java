package com.akemi.wristbooks;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Environment;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class M4bParser {

    public static List<File> getAudiobookDirs(Context ctx) {
        List<File> dirs = new ArrayList<>();
        File pub = new File(Environment.getExternalStorageDirectory(), "Audiobooks");
        if (!pub.exists()) pub.mkdirs();
        dirs.add(pub);

        File priv = ctx.getExternalFilesDir("Audiobooks");
        if (priv != null) {
            if (!priv.exists()) priv.mkdirs();
            dirs.add(priv);
        }
        return dirs;
    }

    public static List<BookModel.Book> scanLibrary(Context ctx) {
        List<BookModel.Book> books = new ArrayList<>();
        for (File root : getAudiobookDirs(ctx)) {
            if (root == null || !root.exists() || !root.isDirectory()) continue;
            File[] entries = root.listFiles();
            if (entries == null) continue;
            Arrays.sort(entries, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

            for (File f : entries) {
                if (f.isDirectory()) {
                    BookModel.Book b = parseFolderBook(f);
                    if (b != null && !b.chapters.isEmpty()) books.add(b);
                } else if (isAudioFile(f)) {
                    BookModel.Book b = parseSingleFileBook(f);
                    if (b != null && !b.chapters.isEmpty()) books.add(b);
                }
            }
        }
        return books;
    }

    private static boolean isAudioFile(File f) {
        String n = f.getName().toLowerCase();
        return f.isFile() && (n.endsWith(".m4b") || n.endsWith(".mp3") || n.endsWith(".m4a"));
    }

    private static BookModel.Book parseFolderBook(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        List<File> audioFiles = new ArrayList<>();
        File folderCover = null;
        for (File f : files) {
            if (isAudioFile(f)) audioFiles.add(f);
            String low = f.getName().toLowerCase();
            if (low.equals("cover.jpg") || low.equals("cover.png") || low.equals("folder.jpg")) {
                folderCover = f;
            }
        }
        if (audioFiles.isEmpty()) return null;

        // Se la cartella contiene un singolo M4B, usa i suoi capitoli interni
        if (audioFiles.size() == 1 && audioFiles.get(0).getName().toLowerCase().endsWith(".m4b")) {
            BookModel.Book single = parseSingleFileBook(audioFiles.get(0));
            if (single != null && single.cover == null && folderCover != null) {
                single.cover = decodeScaledCover(folderCover.getAbsolutePath(), null);
            }
            return single;
        }

        BookModel.Book book = new BookModel.Book();
        book.id = dir.getAbsolutePath();
        book.folderOrFilePath = dir.getAbsolutePath();
        book.title = dir.getName();
        book.author = "Audiolibro";

        if (folderCover != null) {
            book.cover = decodeScaledCover(folderCover.getAbsolutePath(), null);
        }

        long total = 0;
        for (int i = 0; i < audioFiles.size(); i++) {
            File af = audioFiles.get(i);
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            long dur = 0;
            String chTitle = stripExt(af.getName());
            try {
                mmr.setDataSource(af.getAbsolutePath());
                String dStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (dStr != null) dur = Long.parseLong(dStr);
                String tMeta = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                if (tMeta != null && !tMeta.trim().isEmpty()) chTitle = tMeta.trim();
                if (i == 0) {
                    String alb = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                    if (alb != null && !alb.trim().isEmpty()) book.title = alb.trim();
                    String art = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    if (art == null) art = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR);
                    if (art != null && !art.trim().isEmpty()) book.author = art.trim();
                    if (book.cover == null) {
                        byte[] pic = mmr.getEmbeddedPicture();
                        if (pic != null) book.cover = decodeScaledCover(null, pic);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }
            book.chapters.add(new BookModel.Chapter(chTitle, af.getAbsolutePath(), 0, dur));
            total += dur;
        }
        book.totalDurationMs = total;
        return book;
    }

    private static BookModel.Book parseSingleFileBook(File f) {
        BookModel.Book book = new BookModel.Book();
        book.id = f.getAbsolutePath();
        book.folderOrFilePath = f.getAbsolutePath();
        book.title = stripExt(f.getName());
        book.author = "Audiolibro";

        long totalDur = 0;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.getAbsolutePath());
            String dStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dStr != null) totalDur = Long.parseLong(dStr);
            String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            if (t == null || t.trim().isEmpty()) t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (t != null && !t.trim().isEmpty()) book.title = t.trim();
            String art = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (art == null) art = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR);
            if (art != null && !art.trim().isEmpty()) book.author = art.trim();
            byte[] pic = mmr.getEmbeddedPicture();
            if (pic != null) book.cover = decodeScaledCover(null, pic);
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }

        book.totalDurationMs = totalDur;
        if (f.getName().toLowerCase().endsWith(".m4b") || f.getName().toLowerCase().endsWith(".m4a")) {
            List<BookModel.Chapter> parsed = extractM4bChapters(f, totalDur);
            if (!parsed.isEmpty()) {
                book.chapters = parsed;
                return book;
            }
        }
        book.chapters.add(new BookModel.Chapter(book.title, f.getAbsolutePath(), 0, totalDur));
        return book;
    }

    private static List<BookModel.Chapter> extractM4bChapters(File file, long totalDurMs) {
        List<BookModel.Chapter> res = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            long[] moov = findBox(raf, 0, len, "moov");
            if (moov == null) return res;

            // 1. Tentativo Nero chapters: moov -> udta -> chpl
            long[] udta = findBox(raf, moov[0], moov[1], "udta");
            if (udta != null) {
                long[] chpl = findBox(raf, udta[0], udta[1], "chpl");
                if (chpl != null) {
                    res = parseChplAtom(raf, chpl[0], file.getAbsolutePath(), totalDurMs);
                    if (!res.isEmpty()) return res;
                }
            }

            // 2. Tentativo QuickTime text track (Audible / Apple / FFmpeg M4B)
            long pos = moov[0];
            while (pos + 8 <= moov[1]) {
                raf.seek(pos);
                long bSize = raf.readInt() & 0xFFFFFFFFL;
                String bType = readFourCC(raf);
                long hdr = 8;
                if (bSize == 1) { bSize = raf.readLong(); hdr = 16; }
                if (bSize < hdr || pos + bSize > moov[1]) break;
                if ("trak".equals(bType)) {
                    List<BookModel.Chapter> qt = parseQtTextTrack(raf, pos + hdr, pos + bSize, file.getAbsolutePath(), totalDurMs);
                    if (!qt.isEmpty()) return qt;
                }
                pos += bSize;
            }
        } catch (Exception ignored) {}
        return res;
    }

    private static List<BookModel.Chapter> parseChplAtom(RandomAccessFile raf, long dataStart, String path, long totalDurMs) throws Exception {
        List<BookModel.Chapter> list = new ArrayList<>();
        raf.seek(dataStart);
        int ver = raf.readUnsignedByte();
        raf.skipBytes(3);
        int count;
        if (ver == 1) {
            raf.skipBytes(4);
            count = raf.readInt();
        } else {
            raf.skipBytes(1);
            count = raf.readUnsignedByte();
        }
        if (count <= 0 || count > 2000) return list;

        List<Long> starts = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long startMs = raf.readLong() / 10000L;
            int tLen = raf.readUnsignedByte();
            byte[] tb = new byte[tLen];
            raf.readFully(tb);
            String title = new String(tb, StandardCharsets.UTF_8).trim();
            if (title.isEmpty()) title = "Capitolo " + (i + 1);
            starts.add(startMs);
            titles.add(title);
        }
        for (int i = 0; i < starts.size(); i++) {
            long st = starts.get(i);
            long en = (i + 1 < starts.size()) ? starts.get(i + 1) : totalDurMs;
            list.add(new BookModel.Chapter(titles.get(i), path, st, Math.max(0, en - st)));
        }
        return list;
    }

    private static List<BookModel.Chapter> parseQtTextTrack(RandomAccessFile raf, long start, long end, String path, long totalDurMs) throws Exception {
        List<BookModel.Chapter> list = new ArrayList<>();
        long[] mdia = findBox(raf, start, end, "mdia");
        if (mdia == null) return list;
        long[] hdlr = findBox(raf, mdia[0], mdia[1], "hdlr");
        if (hdlr == null) return list;
        raf.seek(hdlr[0] + 8);
        if (!"text".equals(readFourCC(raf))) return list;

        long[] mdhd = findBox(raf, mdia[0], mdia[1], "mdhd");
        if (mdhd == null) return list;
        raf.seek(mdhd[0]);
        int mdhdVer = raf.readUnsignedByte();
        raf.skipBytes(mdhdVer == 1 ? 19 : 11);
        long timescale = raf.readInt() & 0xFFFFFFFFL;
        if (timescale <= 0) timescale = 1000;

        long[] minf = findBox(raf, mdia[0], mdia[1], "minf");
        if (minf == null) return list;
        long[] stbl = findBox(raf, minf[0], minf[1], "stbl");
        if (stbl == null) return list;

        long[] stts = findBox(raf, stbl[0], stbl[1], "stts");
        long[] stco = findBox(raf, stbl[0], stbl[1], "stco");
        if (stts == null || stco == null) return list;

        raf.seek(stts[0] + 4);
        int sttsEntries = raf.readInt();
        List<Long> sampleDurs = new ArrayList<>();
        for (int i = 0; i < sttsEntries; i++) {
            int sc = raf.readInt();
            long sd = raf.readInt() & 0xFFFFFFFFL;
            for (int k = 0; k < sc && sampleDurs.size() < 2000; k++) sampleDurs.add((sd * 1000L) / timescale);
        }

        raf.seek(stco[0] + 4);
        int chunkCount = raf.readInt();
        int count = Math.min(chunkCount, sampleDurs.size());
        long[] offsets = new long[count];
        for (int i = 0; i < count; i++) offsets[i] = raf.readInt() & 0xFFFFFFFFL;

        long cursorMs = 0;
        for (int i = 0; i < count; i++) {
            raf.seek(offsets[i]);
            int strLen = raf.readUnsignedShort();
            String title = "Capitolo " + (i + 1);
            if (strLen > 0 && strLen < 512) {
                byte[] sb = new byte[strLen];
                raf.readFully(sb);
                String parsed = new String(sb, StandardCharsets.UTF_8).trim();
                if (!parsed.isEmpty()) title = parsed;
            }
            long dur = sampleDurs.get(i);
            if (i == count - 1 && totalDurMs > cursorMs) dur = totalDurMs - cursorMs;
            list.add(new BookModel.Chapter(title, path, cursorMs, dur));
            cursorMs += dur;
        }
        return list;
    }

    private static long[] findBox(RandomAccessFile raf, long start, long end, String target) throws Exception {
        long pos = start;
        while (pos + 8 <= end) {
            raf.seek(pos);
            long size = raf.readInt() & 0xFFFFFFFFL;
            String type = readFourCC(raf);
            long hdr = 8;
            if (size == 1) { size = raf.readLong(); hdr = 16; }
            if (size < hdr || pos + size > end) break;
            if (target.equals(type)) return new long[]{pos + hdr, pos + size};
            pos += size;
        }
        return null;
    }

    private static String readFourCC(RandomAccessFile raf) throws Exception {
        byte[] b = new byte[4];
        raf.readFully(b);
        return new String(b, StandardCharsets.US_ASCII);
    }

    private static Bitmap decodeScaledCover(String path, byte[] bytes) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            if (path != null) BitmapFactory.decodeFile(path, o);
            else BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (o.outWidth / sample > 160) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return path != null ? BitmapFactory.decodeFile(path, o) : BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripExt(String name) {
        int d = name.lastIndexOf('.');
        return d > 0 ? name.substring(0, d) : name;
    }
}

    private static BookModel.Book parseSingleFileBook(File f) {
        BookModel.Book book = new BookModel.Book();
        book.id = f.getAbsolutePath();
        book.folderOrFilePath = f.getAbsolutePath();
        book.title = stripExt(f.getName());
        book.author = "Audiolibro";

        long totalDur = 0;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.getAbsolutePath());
            String dStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dStr != null) totalDur = Long.parseLong(dStr);
            String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            if (t == null || t.trim().isEmpty()) t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (t != null && !t.trim().isEmpty()) book.title = t.trim();
            String art = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (art == null) art = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR);
            if (art != null && !art.trim().isEmpty()) book.author = art.trim();
            byte[] pic = mmr.getEmbeddedPicture();
            if (pic != null) book.cover = decodeScaledCover(null, pic);
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }

        book.totalDurationMs = totalDur;
        if (f.getName().toLowerCase().endsWith(".m4b") || f.getName().toLowerCase().endsWith(".m4a")) {
            List<BookModel.Chapter> parsed = extractM4bChapters(f, totalDur);
            if (!parsed.isEmpty()) {
                book.chapters = parsed;
                return book;
            }
        }
        book.chapters.add(new BookModel.Chapter(book.title, f.getAbsolutePath(), 0, totalDur));
        return book;
    }

    private static List<BookModel.Chapter> extractM4bChapters(File file, long totalDurMs) {
        List<BookModel.Chapter> res = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            long[] moov = findBox(raf, 0, len, "moov");
            if (moov == null) return res;

            // 1. Tentativo Nero chapters: moov -> udta -> chpl
            long[] udta = findBox(raf, moov[0], moov[1], "udta");
            if (udta != null) {
                long[] chpl = findBox(raf, udta[0], udta[1], "chpl");
                if (chpl != null) {
                    res = parseChplAtom(raf, chpl[0], file.getAbsolutePath(), totalDurMs);
                    if (!res.isEmpty()) return res;
                }
            }

            // 2. Tentativo QuickTime text track (Audible / Apple / FFmpeg M4B)
            long pos = moov[0];
            while (pos + 8 <= moov[1]) {
                raf.seek(pos);
                long bSize = raf.readInt() & 0xFFFFFFFFL;
                String bType = readFourCC(raf);
                long hdr = 8;
                if (bSize == 1) { bSize = raf.readLong(); hdr = 16; }
                if (bSize < hdr || pos + bSize > moov[1]) break;
                if ("trak".equals(bType)) {
                    List<BookModel.Chapter> qt = parseQtTextTrack(raf, pos + hdr, pos + bSize, file.getAbsolutePath(), totalDurMs);
                    if (!qt.isEmpty()) return qt;
                }
                pos += bSize;
            }
        } catch (Exception ignored) {}
        return res;
    }

    private static List<BookModel.Chapter> parseChplAtom(RandomAccessFile raf, long dataStart, String path, long totalDurMs) throws Exception {
        List<BookModel.Chapter> list = new ArrayList<>();
        raf.seek(dataStart);
        int ver = raf.readUnsignedByte();
        raf.skipBytes(3);
        int count;
        if (ver == 1) {
            raf.skipBytes(4);
            count = raf.readInt();
        } else {
            raf.skipBytes(1);
            count = raf.readUnsignedByte();
        }
        if (count <= 0 || count > 2000) return list;

        List<Long> starts = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long startMs = raf.readLong() / 10000L;
            int tLen = raf.readUnsignedByte();
            byte[] tb = new byte[tLen];
            raf.readFully(tb);
            String title = new String(tb, StandardCharsets.UTF_8).trim();
            if (title.isEmpty()) title = "Capitolo " + (i + 1);
            starts.add(startMs);
            titles.add(title);
        }
        for (int i = 0; i < starts.size(); i++) {
            long st = starts.get(i);
            long en = (i + 1 < starts.size()) ? starts.get(i + 1) : totalDurMs;
            list.add(new BookModel.Chapter(titles.get(i), path, st, Math.max(0, en - st)));
        }
        return list;
    }

    private static List<BookModel.Chapter> parseQtTextTrack(RandomAccessFile raf, long start, long end, String path, long totalDurMs) throws Exception {
        List<BookModel.Chapter> list = new ArrayList<>();
        long[] mdia = findBox(raf, start, end, "mdia");
        if (mdia == null) return list;
        long[] hdlr = findBox(raf, mdia[0], mdia[1], "hdlr");
        if (hdlr == null) return list;
        raf.seek(hdlr[0] + 8);
        if (!"text".equals(readFourCC(raf))) return list;

        long[] mdhd = findBox(raf, mdia[0], mdia[1], "mdhd");
        if (mdhd == null) return list;
        raf.seek(mdhd[0]);
        int mdhdVer = raf.readUnsignedByte();
        raf.skipBytes(mdhdVer == 1 ? 19 : 11);
        long timescale = raf.readInt() & 0xFFFFFFFFL;
        if (timescale <= 0) timescale = 1000;

        long[] minf = findBox(raf, mdia[0], mdia[1], "minf");
        if (minf == null) return list;
        long[] stbl = findBox(raf, minf[0], minf[1], "stbl");
        if (stbl == null) return list;

        long[] stts = findBox(raf, stbl[0], stbl[1], "stts");
        long[] stco = findBox(raf, stbl[0], stbl[1], "stco");
        if (stts == null || stco == null) return list;

        raf.seek(stts[0] + 4);
        int sttsEntries = raf.readInt();
        List<Long> sampleDurs = new ArrayList<>();
        for (int i = 0; i < sttsEntries; i++) {
            int sc = raf.readInt();
            long sd = raf.readInt() & 0xFFFFFFFFL;
            for (int k = 0; k < sc && sampleDurs.size() < 2000; k++) sampleDurs.add((sd * 1000L) / timescale);
        }

        raf.seek(stco[0] + 4);
        int chunkCount = raf.readInt();
        int count = Math.min(chunkCount, sampleDurs.size());
        long[] offsets = new long[count];
        for (int i = 0; i < count; i++) offsets[i] = raf.readInt() & 0xFFFFFFFFL;

        long cursorMs = 0;
        for (int i = 0; i < count; i++) {
            raf.seek(offsets[i]);
            int strLen = raf.readUnsignedShort();
            String title = "Capitolo " + (i + 1);
            if (strLen > 0 && strLen < 512) {
                byte[] sb = new byte[strLen];
                raf.readFully(sb);
                String parsed = new String(sb, StandardCharsets.UTF_8).trim();
                if (!parsed.isEmpty()) title = parsed;
            }
            long dur = sampleDurs.get(i);
            if (i == count - 1 && totalDurMs > cursorMs) dur = totalDurMs - cursorMs;
            list.add(new BookModel.Chapter(title, path, cursorMs, dur));
            cursorMs += dur;
        }
        return list;
    }

    private static long[] findBox(RandomAccessFile raf, long start, long end, String target) throws Exception {
        long pos = start;
        while (pos + 8 <= end) {
            raf.seek(pos);
            long size = raf.readInt() & 0xFFFFFFFFL;
            String type = readFourCC(raf);
            long hdr = 8;
            if (size == 1) { size = raf.readLong(); hdr = 16; }
            if (size < hdr || pos + size > end) break;
            if (target.equals(type)) return new long[]{pos + hdr, pos + size};
            pos += size;
        }
        return null;
    }

    private static String readFourCC(RandomAccessFile raf) throws Exception {
        byte[] b = new byte[4];
        raf.readFully(b);
        return new String(b, StandardCharsets.US_ASCII);
    }

    private static Bitmap decodeScaledCover(String path, byte[] bytes) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            if (path != null) BitmapFactory.decodeFile(path, o);
            else BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (o.outWidth / sample > 160) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return path != null ? BitmapFactory.decodeFile(path, o) : BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripExt(String name) {
        int d = name.lastIndexOf('.');
        return d > 0 ? name.substring(0, d) : name;
    }
}
