package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

/**
 * Liquid Glass volume card. It listens to both the immediate system volume broadcast and the TCL
 * speaker setting (volume_music_speaker, 0..100), so the card appears as soon as the volume changes.
 * It runs inside the launcher process, so it needs the SYSTEM_ALERT_WINDOW app-op but no service.
 *
 * The card is attached once at startup and only fades between invisible and visible. Adding a window
 * on the first key press was the main source of the old delay; nothing is added or removed per press.
 *
 * The remote's mute key flips AudioManager's stream mute without changing volume_music_speaker, so a
 * small poll keeps the card in sync with it. While muted the card is pinned instead of timing out.
 *
 * The stock TCL volume OSD is a SYSTEM_ALERT window from com.tcl.framework.custom. build-and-install.sh
 * blocks that app-op for TCL so the two bars never show at once; see work/README.md to undo it.
 */
final class VolumeHud {
    private static final String TAG = "TvHome.VolumeHud";
    private static final String KEY = "volume_music_speaker";
    /** Public strings: AudioManager's constants for this broadcast are hidden in the SDK. */
    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final int MAX = 100;
    private static final long HIDE_MS = 1400;
    private static final long MUTE_POLL_MS = 150;
    private static final long IN_MS = 70;
    private static final long OUT_MS = 140;

    private final Context context;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ContentObserver observer;
    private final Runnable hide = this::hideNow;
    private final Runnable mutePoll = this::pollMute;
    private final AudioManager audio;
    private final int maxStream;
    private WindowManager.LayoutParams lp;
    private int bottomInsetPx = -1;
    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_VOLUME_CHANGED.equals(intent.getAction())) return;
            int stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1);
            if (stream != AudioManager.STREAM_MUSIC) return;
            int value = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1);
            if (value < 0) return;
            int percent = maxStream > 0 ? Math.round(value * 100f / maxStream) : value;
            show(Math.max(0, Math.min(MAX, percent)));
        }
    };
    private VolumeHudView view;
    private boolean hiding;
    private boolean started;
    private boolean receiverRegistered;
    private boolean muted;
    private boolean lastMuted;
    private int lastValue = -1;

    VolumeHud(Context context) {
        this.context = context.getApplicationContext();
        this.wm = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        maxStream = audio == null ? MAX : Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        this.observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                read();
            }
        };
    }

    void start() {
        if (started || wm == null) return;
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW is not granted; the volume card stays off");
            return;
        }
        ContentResolver cr = context.getContentResolver();
        cr.registerContentObserver(Settings.System.getUriFor(KEY), false, observer);
        try {
            context.registerReceiver(volumeReceiver, new IntentFilter(ACTION_VOLUME_CHANGED));
            receiverRegistered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not listen for volume broadcasts; using the setting only", e);
        }

        // Attach the card once. It stays INVISIBLE and untouchable between presses, so the first press
        // only has to change alpha and visibility; there is no WindowManager add on the hot path.
        view = new VolumeHudView(context);
        lp = new WindowManager.LayoutParams(
                dp(256), dp(80),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        // A close default for the top-bar height; MainActivity replaces it with the measured top
        // edge of the top pills once the first layout is done.
        lp.y = dp(60);
        view.setAlpha(0f);
        view.setScaleX(0.96f);
        view.setScaleY(0.96f);
        view.setVisibility(View.INVISIBLE);
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not add the volume card", e);
            view = null;
            if (receiverRegistered) {
                try {
                    context.unregisterReceiver(volumeReceiver);
                } catch (IllegalArgumentException ignored) {
                    // Already unregistered.
                }
                receiverRegistered = false;
            }
            cr.unregisterContentObserver(observer);
            return;
        }
        started = true;
        applyBottomInset();

        // A TV muted at boot (or when the launcher restarts) should show the muted card right away,
        // and keep it there until the sound comes back. MainActivity calls refreshMute() after it
        // has measured the top bar, so the first frame is already in the right place.
        lastMuted = isMuted();
        handler.post(mutePoll);
    }

    /** Places the card's bottom edge as far from the screen bottom as the top pills' top edge is
     *  from the screen top. {@code px} is that measured top edge in screen pixels. */
    void setBottomInsetPx(int px) {
        bottomInsetPx = px;
        applyBottomInset();
    }

    void refreshMute() {
        if (!started) return;
        lastMuted = isMuted();
        if (lastMuted || readSetting() == 0) show(0);
    }

    private void applyBottomInset() {
        if (lp == null || view == null || bottomInsetPx < 0) return;
        // The card itself is inset 8dp inside its window for the shadow and glow.
        int y = Math.max(0, bottomInsetPx - dp(8));
        if (lp.y == y) return;
        lp.y = y;
        try {
            wm.updateViewLayout(view, lp);
        } catch (IllegalArgumentException ignored) {
            // Detached between the check and the update.
        }
    }

    void stop() {
        if (!started) return;
        started = false;
        context.getContentResolver().unregisterContentObserver(observer);
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(volumeReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
            receiverRegistered = false;
        }
        handler.removeCallbacks(hide);
        handler.removeCallbacks(mutePoll);
        if (view != null) {
            view.animate().cancel();
            try {
                wm.removeView(view);
            } catch (IllegalArgumentException ignored) {
                // Already detached.
            }
            view = null;
        }
        lp = null;
        hiding = false;
        muted = false;
        lastMuted = false;
        lastValue = -1;
    }

    private void read() {
        if (isMuted()) {
            show(0);
            return;
        }
        int value = readSetting();
        if (value < 0) return;
        show(value);
    }

    private void pollMute() {
        if (!started) return;
        boolean nowMuted = isMuted();
        if (nowMuted != lastMuted) {
            lastMuted = nowMuted;
            if (nowMuted) show(0);
            else read();
        } else if (nowMuted) {
            // Keep the card pinned while the sound is off, even if a hide was queued earlier.
            show(0);
        }
        handler.postDelayed(mutePoll, MUTE_POLL_MS);
    }

    private boolean isMuted() {
        return audio != null && audio.isStreamMute(AudioManager.STREAM_MUSIC);
    }

    private int readSetting() {
        int value = Settings.System.getInt(context.getContentResolver(), KEY, -1);
        if (value >= 0) return Math.min(MAX, value);
        if (audio == null) return -1;
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
    }

    private void show(int value) {
        if (wm == null || view == null) return;
        if (value < 0) return;
        if (value > MAX) value = MAX;

        boolean nowMuted = value == 0 || isMuted();
        int display = nowMuted ? 0 : value;
        muted = nowMuted;

        boolean visible = view.getVisibility() == View.VISIBLE;
        boolean same = visible && !hiding && display == lastValue;
        if (!same) {
            view.setVolume(display, MAX);
            lastValue = display;
        }
        handler.removeCallbacks(hide);

        if (!visible) {
            hiding = false;
            view.animate().cancel();
            view.setVisibility(View.VISIBLE);
            view.setAlpha(0f);
            view.setScaleX(0.96f);
            view.setScaleY(0.96f);
            view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(IN_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else if (hiding) {
            // Pressed again while fading out: bring the same card back instead of stacking another one.
            hiding = false;
            view.animate().cancel();
            view.setAlpha(1f);
            view.setScaleX(1f);
            view.setScaleY(1f);
        }
        // While the TV is muted the card is a status indicator, not a transient OSD: leave it up.
        if (!nowMuted) handler.postDelayed(hide, HIDE_MS);
    }

    private void hideNow() {
        if (view == null || hiding || muted || isMuted()) return;
        hiding = true;
        final VolumeHudView gone = view;
        gone.animate().alpha(0f).scaleX(0.98f).scaleY(0.98f)
                .setDuration(OUT_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (view != gone) return;
                    gone.setVisibility(View.INVISIBLE);
                    hiding = false;
                }).start();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
