package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Personal-fork backdrop: streams one CozyPixels still wallpaper into memory every five minutes and
 * crossfades it behind Home. Nothing is written to the TV: the only stored item is the small path list
 * in assets, and the current bitmap lives in RAM until the next one replaces it.
 *
 * The image is fetched through wsrv.nl at the screen size first (a few hundred KB instead of a 2-20 MB
 * repository PNG). If that service is unavailable, it falls back to raw.githubusercontent.com and
 * downsamples while decoding. Live Wallpapers (MP4/GIF) are deliberately not part of this still shuffle.
 */
final class OnlineWallpaperPlayer {
    private static final String TAG = "TvHome.OnlineWallpaper";
    private static final String ASSET = "cozypixels_wallpapers.txt";
    private static final String RAW_BASE = "https://raw.githubusercontent.com/SleepyCatHey/CozyPixels/main/";
    private static final String PROXY_BASE = "https://wsrv.nl/?url=";
    private static final long INTERVAL_MS = 5 * 60_000L;
    private static final long RETRY_MS = 45_000L;
    private static final long FADE_MS = 900;
    private static final int MAX_RAW_BYTES = 30 * 1024 * 1024;

    // Home can be recreated while YouTube is in front. Keep the last picture and its clock in the
    // process so returning Home paints it immediately, then continues the same five-minute timer.
    private static Bitmap lastBitmap;
    private static long lastShownAt;
    private static String lastPath;

    private final Context context;
    private final FrameLayout root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final List<String> paths;
    private final int width;
    private final int height;
    private final ImageView back;
    private final ImageView front;
    private final View scrim;
    private final Runnable fetch = this::fetchNow;
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private int index;

    OnlineWallpaperPlayer(Context context, FrameLayout root) {
        this.context = context.getApplicationContext();
        this.root = root;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        paths = readAsset(this.context);
        if (paths.isEmpty()) {
            Log.w(TAG, "No wallpaper paths in " + ASSET + "; online wallpapers stay off");
            back = null;
            front = null;
            scrim = null;
            return;
        }
        Collections.shuffle(paths, random);

        back = new ImageView(this.context);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        back.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        back.setFocusable(false);
        back.setClickable(false);
        back.setAlpha(0f);

        front = new ImageView(this.context);
        front.setScaleType(ImageView.ScaleType.CENTER_CROP);
        front.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        front.setFocusable(false);
        front.setClickable(false);
        front.setAlpha(0f);

        // Child 0 is the sky. The two pictures sit over it and under the rest of Home; the scrim keeps
        // the clock, pills and dock readable without hiding the artwork.
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(back, 1, full);
        root.addView(front, 2, full);
        scrim = new View(this.context);
        scrim.setBackground(scrimDrawable());
        scrim.setFocusable(false);
        scrim.setClickable(false);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(scrim, 3, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (lastBitmap != null && !lastBitmap.isRecycled()) {
            current = lastBitmap;
            front.setImageBitmap(lastBitmap);
            front.setAlpha(1f);
            back.setImageBitmap(lastBitmap);
            back.setAlpha(1f);
        }
    }

    void start() {
        if (closed || paths == null || paths.isEmpty() || front == null) return;
        active = true;
        handler.removeCallbacks(fetch);
        if (current == null) {
            fetchNow();
            return;
        }
        long remaining = INTERVAL_MS - (SystemClock.elapsedRealtime() - lastShownAt);
        if (remaining <= 0) fetchNow();
        else handler.postDelayed(fetch, remaining);
    }

    void stop() {
        active = false;
        handler.removeCallbacks(fetch);
    }

    void close() {
        active = false;
        closed = true;
        handler.removeCallbacks(fetch);
        loader.shutdownNow();
        if (front != null) front.setImageBitmap(null);
        if (back != null) back.setImageBitmap(null);
        // current is the static lastBitmap; keep it for the next Home activity instead of recycling it.
        current = null;
    }

    private void fetchNow() {
        if (!active || closed || loading || paths == null || paths.isEmpty()) return;
        loading = true;
        final String path = nextPath();
        loader.execute(() -> {
            final Bitmap bitmap = download(path);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    Log.w(TAG, "Could not load " + path + "; retrying in " + (RETRY_MS / 1000) + "s");
                    handler.postDelayed(fetch, RETRY_MS);
                    return;
                }
                show(bitmap);
                lastShownAt = SystemClock.elapsedRealtime();
                handler.removeCallbacks(fetch);
                handler.postDelayed(fetch, INTERVAL_MS);
            });
        });
    }

    /** A shuffled queue, with a fresh shuffle after every full pass and no immediate repeat at the wrap. */
    private String nextPath() {
        if (index >= paths.size()) {
            Collections.shuffle(paths, random);
            index = 0;
        }
        String path = paths.get(index++);
        if (paths.size() > 1 && path.equals(lastPath)) {
            if (index >= paths.size()) {
                Collections.shuffle(paths, random);
                index = 0;
            }
            path = paths.get(index++);
        }
        lastPath = path;
        return path;
    }

    private Bitmap download(String path) {
        String raw = RAW_BASE + Uri.encode(path, "/");
        String proxy = PROXY_BASE + Uri.encode(raw) + "&w=" + width + "&h=" + height
                + "&fit=cover&output=jpg&q=85";
        Bitmap bitmap = fetchImage(proxy, true);
        if (bitmap == null) bitmap = fetchImage(raw, false);
        return bitmap;
    }

    private Bitmap fetchImage(String url, boolean resized) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(45_000);
            connection.setUseCaches(false);
            connection.setDefaultUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "CozyPixelsTV/1.0");
            connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp,image/*");
            connection.setRequestProperty("Cache-Control", "no-store");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + code + " for " + url);
                return null;
            }
            String type = connection.getContentType();
            if (type != null && !type.startsWith("image/")) {
                Log.w(TAG, "Not an image (" + type + ") for " + url);
                return null;
            }
            if (resized) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                return BitmapFactory.decodeStream(connection.getInputStream(), null, options);
            }
            return decodeRaw(connection);
        } catch (Exception e) {
            Log.w(TAG, "Download failed for " + url + ": " + e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** The fallback path: raw GitHub can be 20 MB and 4K+, so bound it and let BitmapFactory downsample. */
    private Bitmap decodeRaw(HttpURLConnection connection) throws IOException {
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_RAW_BYTES) {
                    Log.w(TAG, "Raw image is over " + MAX_RAW_BYTES + " bytes; skipping");
                    return null;
                }
                bytes.write(buffer, 0, n);
            }
            byte[] data = bytes.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sourceW = bounds.outWidth;
            int sourceH = bounds.outHeight;
            if (sourceW <= 0 || sourceH <= 0) return null;
            int sample = WallpaperSize.sampleSize(sourceW, sourceH, width, height);
            if (!WallpaperSize.accepted(sourceW / sample, sourceH / sample)) {
                Log.w(TAG, "Raw image is too large: " + sourceW + "x" + sourceH);
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        }
    }

    /**
     * The front picture is the one on screen. A new one fades in over the back, which holds the old one,
     * so there is no flash of the root colour between changes. Both views end up sharing the same bitmap.
     */
    private void show(Bitmap bitmap) {
        final Bitmap old = current;
        current = bitmap;
        lastBitmap = bitmap;
        front.animate().cancel();
        front.setImageBitmap(bitmap);
        front.setAlpha(0f);
        front.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            if (closed) return;
            back.setImageBitmap(bitmap);
            back.setAlpha(1f);
            if (old != null && old != bitmap) handler.postDelayed(old::recycle, 1200);
        }).start();
    }

    private static List<String> readAsset(Context context) {
        List<String> list = new ArrayList<>();
        try (InputStream in = context.getAssets().open(ASSET);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) list.add(line);
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read " + ASSET, e);
        }
        return list;
    }

    private static GradientDrawable scrimDrawable() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        drawable.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        drawable.setColors(new int[]{0x4D000000, 0x14000000, 0x66000000});
        return drawable;
    }
}
