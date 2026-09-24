package com.jakes.tickerbar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;

/**
 * The wallet card graphic. Google Wallet doesn't expose card art to other apps (only the
 * system's own wallet screen can read it), so the user picks the closest finish instead.
 * Shared by the nav-bar card, its pull-out animation and the preview in settings.
 */
final class CardArt {
    static final int[] IDS = {0, 1, 2, 3, 4, 5, 6, 7};
    static final String[] NAMES = {"White", "Black metal", "Platinum", "Gold", "Rose gold",
            "Midnight blue", "Emerald", "System colours"};

    private final Context ctx;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF chip = new RectF();

    /**
     * Where the label goes, in px from the card's top edge. The nav-bar card only peeks a
     * sliver, so the service sizes the label to that sliver; 0 = the card's own proportions.
     */
    float labelSize, labelBaseline;

    CardArt(Context c) {
        ctx = c;
        ink.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ink.setLetterSpacing(0.08f);
    }

    /**
     * Finish for a style: top-left colour, bottom-right colour, border, label ink,
     * sheen strength (0 = flat) and whether it carries a chip.
     */
    private int[] finish(int style) {
        switch (style) {
            case 1: return new int[]{0xFF383A40, 0xFF0A0B0D, 0x3DFFFFFF, 0xFFD6D7DA, 0x1E, 1};
            case 2: return new int[]{0xFFF2F3F5, 0xFFA9AEB6, 0x2E000000, 0xFF2C2F34, 0x5A, 1};
            case 3: return new int[]{0xFFF4E0A6, 0xFFAD8642, 0x2E000000, 0xFF3B2C10, 0x55, 1};
            case 4: return new int[]{0xFFF7D6CB, 0xFFBD8577, 0x2E000000, 0xFF4A2921, 0x55, 1};
            case 5: return new int[]{0xFF2C4D8C, 0xFF091530, 0x3DFFFFFF, 0xFFE2E8F5, 0x1E, 1};
            case 6: return new int[]{0xFF25805F, 0xFF072A1F, 0x3DFFFFFF, 0xFFDFF2EA, 0x1E, 1};
            case 7: {
                int a = 0xFF8C9EFF, b = 0xFF3A4CC0, on = 0xFFFFFFFF;
                if (Build.VERSION.SDK_INT >= 31) {
                    a = ctx.getColor(android.R.color.system_accent1_300);
                    b = ctx.getColor(android.R.color.system_accent1_700);
                    on = ctx.getColor(android.R.color.system_accent1_10);
                }
                return new int[]{a, b, 0x33FFFFFF, on, 0x28, 1};
            }
            default: return new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0x22000000, 0xFF3C4043, 0, 0};
        }
    }

    /** @param alpha 0..1, already including the user's card opacity */
    void draw(Canvas c, RectF r, float alpha, int style, String label) {
        int[] f = finish(style);
        float w = r.width(), h = r.height();
        float rad = Math.min(w * 0.045f, h * 0.075f);
        int a = Math.round(245 * Math.max(0f, Math.min(1f, alpha)));

        // body
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        p.setShader(f[0] == f[1] ? null
                : new LinearGradient(r.left, r.top, r.right, r.bottom, f[0], f[1], Shader.TileMode.CLAMP));
        if (f[0] == f[1]) p.setColor(f[0]);
        p.setAlpha(a);
        c.drawRoundRect(r, rad, rad, p);

        // a soft diagonal sheen, like light across metal
        if (f[4] > 0) {
            int s = f[4] << 24 | 0xFFFFFF;
            p.setShader(new LinearGradient(r.left, r.top, r.left + w * 0.9f, r.top + h * 1.4f,
                    new int[]{0x00FFFFFF, 0x00FFFFFF, s, 0x00FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.30f, 0.42f, 0.56f, 1f}, Shader.TileMode.CLAMP));
            p.setColor(0xFFFFFFFF);
            p.setAlpha(a);
            c.drawRoundRect(r, rad, rad, p);
        }
        p.setShader(null);

        // chip
        if (f[5] == 1) {
            float cw = w * 0.13f, ch = h * 0.17f;
            chip.set(r.left + w * 0.085f, r.top + h * 0.37f, r.left + w * 0.085f + cw, r.top + h * 0.37f + ch);
            p.setShader(new LinearGradient(chip.left, chip.top, chip.right, chip.bottom,
                    0xFFEBD493, 0xFFB08F4C, Shader.TileMode.CLAMP));
            p.setColor(0xFFFFFFFF);
            p.setAlpha(a);
            c.drawRoundRect(chip, ch * 0.2f, ch * 0.2f, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, h * 0.006f));
            p.setColor(0xFF7A6232);
            p.setAlpha(Math.round(a * 0.55f));
            float my = chip.centerY(), x1 = chip.left + cw * 0.34f, x2 = chip.left + cw * 0.66f;
            c.drawLine(chip.left, my, x1, my, p);
            c.drawLine(x2, my, chip.right, my, p);
            c.drawLine(x1, chip.top, x1, chip.bottom, p);
            c.drawLine(x2, chip.top, x2, chip.bottom, p);
            c.drawRect(x1, chip.top + ch * 0.3f, x2, chip.bottom - ch * 0.3f, p);
            p.setStyle(Paint.Style.FILL);
        }

        // edge, so light cards read on light wallpapers and dark ones on dark
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, h * 0.004f));
        p.setColor(f[2] | 0xFF000000);
        p.setAlpha(Math.round((f[2] >>> 24) * alpha));
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL);

        // the user's own label, top-left where the peek shows it
        if (label != null && !label.trim().isEmpty()) {
            ink.setTextSize(labelSize > 0 ? labelSize : h * 0.085f);
            ink.setColor(f[3]);
            ink.setAlpha(Math.round(a * 0.9f));
            c.drawText(label.trim(), r.left + w * 0.085f,
                    r.top + (labelBaseline > 0 ? labelBaseline : h * 0.165f), ink);
        }
    }
}
