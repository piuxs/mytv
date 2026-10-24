package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * The Liquid Glass volume card: a small translucent pill with a soft shadow, a thin bright rim and a
 * diagonal sheen. It shows the speaker glyph, one thin white bar and the exact percentage. Muting
 * animates the sound waves away and draws a slash through the glyph.
 */
final class VolumeHudView extends View {
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slashCut = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cardRect = new RectF();
    private final RectF trackRect = new RectF();
    private final RectF fillRect = new RectF();
    private final RectF waveRect = new RectF();
    private final Path speaker = new Path();
    private final Path slashPath = new Path();
    private final Path slashSegment = new Path();
    private final PathMeasure slashMeasure = new PathMeasure();
    private final float density;
    private float shown;
    private int value;
    private float muteProgress;
    private float mutePulse = 1f;
    private boolean muted;
    private String previousLabel;
    private ValueAnimator animator;
    private ValueAnimator muteAnimator;
    private ValueAnimator pulseAnimator;

    VolumeHudView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        card.setColor(0xFF101116);
        card.setShadowLayer(dp(7), 0, dp(2), 0x99000000);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp(1));
        border.setColor(0x40FFFFFF);
        highlight.setStyle(Paint.Style.STROKE);
        highlight.setStrokeWidth(dp(1));
        highlight.setColor(0x55FFFFFF);
        highlight.setStrokeCap(Paint.Cap.ROUND);
        ink.setColor(0xFFFFFFFF);
        ink.setStyle(Paint.Style.FILL);
        wave.setColor(0xFFFFFFFF);
        wave.setStyle(Paint.Style.STROKE);
        wave.setStrokeWidth(dp(2));
        wave.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(0x33FFFFFF);
        fill.setColor(0xFFFFFFFF);
        slashCut.setColor(0xF01A1B20);
        slashCut.setStyle(Paint.Style.STROKE);
        slashCut.setStrokeWidth(dp(5));
        slashCut.setStrokeCap(Paint.Cap.ROUND);
        slash.setColor(0xFFFFFFFF);
        slash.setStyle(Paint.Style.STROKE);
        slash.setStrokeWidth(dp(2));
        slash.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.FILL);
        labelPaint.setColor(0xFFFFFFFF);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        labelPaint.setTextSize(sp(15));
        try {
            labelPaint.setTypeface(UiFont.get(context, R.string.font_medium));
        } catch (Exception ignored) {
            labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        // The soft shadow needs a software layer; the card is only 240x64dp, so this is cheap.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setVolume(int value, int max) {
        int oldValue = this.value;
        this.value = value;
        float target = max <= 0 ? 0f : Math.max(0f, Math.min(1f, value / (float) max));
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(shown, target);
        animator.setDuration(220);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();

        boolean nowMuted = value <= 0;
        if (nowMuted == muted) return;
        muted = nowMuted;
        previousLabel = labelFor(oldValue);

        // The slash and waves take long enough to read at TV distance. A 40ms beat lets the card
        // finish its fade-in first when mute is the key that brings it up.
        if (muteAnimator != null) muteAnimator.cancel();
        muteAnimator = ValueAnimator.ofFloat(muteProgress, muted ? 1f : 0f);
        muteAnimator.setDuration(650);
        muteAnimator.setStartDelay(40);
        muteAnimator.setInterpolator(new DecelerateInterpolator());
        muteAnimator.addUpdateListener(a -> {
            muteProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        muteAnimator.start();

        // A short squash-and-glow on the speaker gives mute a physical beat, not just a line.
        if (pulseAnimator != null) pulseAnimator.cancel();
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(560);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.addUpdateListener(a -> {
            mutePulse = (float) a.getAnimatedValue();
            invalidate();
        });
        pulseAnimator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cardRect.set(dp(8), dp(8), w - dp(8), h - dp(8));
        card.setShader(new LinearGradient(0, cardRect.top, 0, cardRect.bottom,
                new int[]{0xE0212228, 0xD0121318}, null, Shader.TileMode.CLAMP));
        gloss.setShader(new LinearGradient(cardRect.left, cardRect.top, cardRect.right, cardRect.bottom,
                new int[]{0x1FFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        glow.setShader(new RadialGradient(dp(34), h / 2f, dp(24),
                new int[]{0x55FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float cy = h / 2f;
        if (cardRect.isEmpty()) cardRect.set(dp(8), dp(8), w - dp(8), h - dp(8));
        float cardScale = 1f - 0.035f * (float) Math.sin(Math.PI * mutePulse);
        c.save();
        c.scale(cardScale, cardScale, w / 2f, h / 2f);
        float radius = dp(32);
        c.drawRoundRect(cardRect, radius, radius, card);
        c.drawRoundRect(cardRect, radius, radius, gloss);
        c.drawRoundRect(cardRect, radius, radius, border);
        c.drawLine(cardRect.left + dp(22), cardRect.top + dp(1.5f),
                cardRect.right - dp(22), cardRect.top + dp(1.5f), highlight);

        // Speaker body, cone and two short waves; the mute glyph is drawn over them. The whole
        // speaker group squashes and a soft glow blooms out when the mute state flips.
        float sx = dp(26);
        float gx = sx + dp(8);
        speaker.reset();
        speaker.moveTo(sx, cy - dp(5));
        speaker.lineTo(sx + dp(8), cy - dp(5));
        speaker.lineTo(sx + dp(16), cy - dp(13));
        speaker.lineTo(sx + dp(16), cy + dp(13));
        speaker.lineTo(sx + dp(8), cy + dp(5));
        speaker.lineTo(sx, cy + dp(5));
        speaker.close();

        float pulse = 1f - 0.14f * (float) Math.sin(Math.PI * mutePulse);
        c.save();
        c.scale(pulse, pulse, gx, cy);
        if (mutePulse < 0.999f) {
            glow.setAlpha((int) (255 * (1f - mutePulse)));
            c.drawCircle(gx, cy, dp(24 + 8 * mutePulse), glow);
        }
        c.drawPath(speaker, ink);

        if (muteProgress < 0.999f) {
            wave.setAlpha((int) (255 * (1f - muteProgress)));
            waveRect.set(sx + dp(12), cy - dp(8), sx + dp(22), cy + dp(8));
            c.drawArc(waveRect, -50, 100, false, wave);
            waveRect.set(sx + dp(12), cy - dp(13), sx + dp(28), cy + dp(13));
            c.drawArc(waveRect, -50, 100, false, wave);
        }

        if (muteProgress > 0.001f) {
            slashPath.reset();
            slashPath.moveTo(sx - dp(4), cy - dp(13));
            slashPath.lineTo(sx + dp(24), cy + dp(13));
            slashMeasure.setPath(slashPath, false);
            slashSegment.reset();
            slashMeasure.getSegment(0, slashMeasure.getLength() * muteProgress, slashSegment, true);
            c.drawPath(slashSegment, slashCut);
            c.drawPath(slashSegment, slash);
        }
        c.restore();

        // One thin white bar, Apple style, with the exact number on the right.
        float left = dp(58);
        float right = dp(170);
        float th = dp(6);
        trackRect.set(left, cy - th / 2f, right, cy + th / 2f);
        c.drawRoundRect(trackRect, th / 2f, th / 2f, track);
        float filled = left + (right - left) * shown;
        if (filled > left + dp(2)) {
            fillRect.set(left, cy - th / 2f, filled, cy + th / 2f);
            c.drawRoundRect(fillRect, th / 2f, th / 2f, fill);
        }

        // Crossfade the old percentage into "Muted" (and back), with a tiny upward drift.
        String label = labelFor(value);
        float labelAlpha = muted ? muteProgress : 1f - muteProgress;
        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        float baseline = cy - (metrics.ascent + metrics.descent) / 2f;
        if (previousLabel != null) {
            drawLabel(c, previousLabel, 1f - labelAlpha, -dp(3) * labelAlpha, baseline);
        }
        drawLabel(c, label, labelAlpha, dp(3) * (1f - labelAlpha), baseline);
        c.restore();
    }

    private static String labelFor(int value) {
        return value <= 0 ? "Muted" : value + "%";
    }

    private void drawLabel(Canvas c, String text, float alpha, float offset, float baseline) {
        if (alpha <= 0.01f) return;
        labelPaint.setColor(0xFFFFFFFF);
        int base = "Muted".equals(text) ? 0xCC : 0xFF;
        labelPaint.setAlpha(Math.max(0, Math.min(255, (int) (base * alpha))));
        c.drawText(text, cardRect.right - dp(18), baseline + offset, labelPaint);
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }
}
