package ru.ivan.azerothpulse;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.webkit.WebResourceResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import javax.net.ssl.HttpsURLConnection;

final class CharacterMedia {
    static synchronized WebResourceResponse read(Context context, String address, String revision) {
        if (!CharacterSources.allowedImage(address)) return null;
        try {
            File directory = new File(context.getCacheDir(), "armory-images");
            if (!directory.isDirectory() && !directory.mkdirs()) return null;
            boolean characterRender = address.endsWith("-main-raw.png") || address.endsWith("-main-sd-raw.png");
            String cacheKey = characterRender ? "cropped:" + address : address;
            String hash = ApkUpdater.hex(MessageDigest.getInstance("SHA-256").digest(cacheKey.getBytes(StandardCharsets.UTF_8)));
            File file = new File(directory, hash);
            long freshAfter = 0;
            if (revision != null && address.contains("/character/")) {
                try { freshAfter = Math.min(Instant.parse(revision).toEpochMilli(), System.currentTimeMillis()); }
                catch (RuntimeException invalidRevision) { /* Ignore malformed revisions. */ }
            }
            if (!file.isFile() || file.lastModified() < freshAfter) {
                try { download(address, file, characterRender); }
                catch (java.io.IOException unavailable) { /* The previously cached image is still usable. */ }
                File[] entries = directory.listFiles();
                if (entries != null && entries.length > 30) {
                    java.util.Arrays.sort(entries, java.util.Comparator.comparingLong(File::lastModified));
                    for (int i = 0; i < entries.length - 30; i++) if (!entries[i].equals(file)) entries[i].delete();
                }
            }
            return new WebResourceResponse(address.endsWith(".png") ? "image/png" : "image/jpeg", null, new FileInputStream(file));
        } catch (Exception unavailable) { return null; }
    }

    private static void download(String address, File file, boolean characterRender) throws java.io.IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(address).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setUseCaches(false);
        File temporary = new File(file.getPath() + ".part");
        try {
            if (connection.getResponseCode() != 200) return;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] bytes = new byte[16384]; int n, count = 0;
                while ((n = input.read(bytes)) != -1) {
                    count += n;
                    if (count > 4 * 1024 * 1024) throw new java.io.IOException("Image too large");
                    output.write(bytes, 0, n);
                }
            }
            if (!characterRender || !cropRender(temporary, file)) temporary.renameTo(file);
        } finally {
            connection.disconnect();
            temporary.delete();
        }
    }

    private static boolean cropRender(File source, File destination) {
        File cropped = new File(source.getPath() + ".crop");
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getPath(), options);
            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 2048) {
                options.inSampleSize *= 2;
            }
            Bitmap image = BitmapFactory.decodeFile(source.getPath(), options);
            if (image == null || !image.hasAlpha()) return false;
            int width = image.getWidth(), height = image.getHeight();
            int left = width, top = height, right = -1, bottom = -1;
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                image.getPixels(row, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    if ((row[x] >>> 24) == 0) continue;
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = y;
                }
            }
            if (right < left || (left == 0 && top == 0 && right == width - 1 && bottom == height - 1)) return false;
            Bitmap model = Bitmap.createBitmap(image, left, top, right - left + 1, bottom - top + 1);
            try (FileOutputStream output = new FileOutputStream(cropped)) {
                if (!model.compress(Bitmap.CompressFormat.PNG, 100, output)) return false;
            }
            return cropped.renameTo(destination);
        } catch (Exception unavailable) {
            return false;
        } finally {
            cropped.delete();
        }
    }
}
