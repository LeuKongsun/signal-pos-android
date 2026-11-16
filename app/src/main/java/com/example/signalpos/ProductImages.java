package com.example.signalpos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.net.ssl.HttpsURLConnection;

final class ProductImages {
    private static final long DISK_FRESH = 12L * 60L * 60L * 1000L;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private File directory;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };

    ProductImages(Context context) { this.context = context; }

    private File directory() {
        if (directory == null) directory = new File(context.getCacheDir(), "product-images");
        return directory;
    }

    void load(String base, String source, Consumer<Bitmap> show) {
        String url = source.startsWith("/") && !source.startsWith("//") ? base + source : source;
        Bitmap cached = cache.get(url);
        if (cached != null) {
            show.accept(cached);
            return;
        }
        executor.execute(() -> {
            try {
                Bitmap stored = readDisk(url);
                File storedFile = file(url);
                boolean fresh = stored != null && storedFile != null && System.currentTimeMillis() - storedFile.lastModified() < DISK_FRESH;
                if (stored != null) {
                    cache.put(url, stored);
                    main.post(() -> show.accept(stored));
                    if (fresh) return;
                }
                Bitmap bitmap = fetch(url);
                if (bitmap == null) return;
                writeDisk(url, bitmap);
                cache.put(url, bitmap);
                main.post(() -> show.accept(bitmap));
            } catch (Exception ignored) {
                // The initials remain visible when an image is unavailable.
            }
        });
    }

    private Bitmap readDisk(String url) {
        try {
            File image = file(url);
            if (image == null || !image.isFile()) return null;
            return BitmapFactory.decodeFile(image.getAbsolutePath());
        } catch (Exception error) { return null; }
    }

    private void writeDisk(String url, Bitmap bitmap) {
        try {
            File image = file(url);
            if (image == null) return;
            try (FileOutputStream output = new FileOutputStream(image)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            }
        } catch (Exception ignored) { }
    }

    private File file(String url) throws Exception {
        if (!directory().exists() && !directory().mkdirs()) return null;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte value : digest) name.append(String.format("%02x", value));
        return new File(directory, name.toString());
    }

    private Bitmap fetch(String url) throws Exception {
        URI uri = new URI(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return null;
        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try {
            if (connection.getResponseCode() != 200) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    if (bytes.size() + size > 6 * 1024 * 1024) return null;
                    bytes.write(buffer, 0, size);
                }
            }
            byte[] data = bytes.toByteArray();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } finally {
            connection.disconnect();
        }
    }

    void close() {
        executor.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
