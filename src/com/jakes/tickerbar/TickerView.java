package com.jakes.tickerbar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the ticker: 1-3 lines, in either the classic full-width black bar or a heads-up
 * style card in the system theme's colours.
 *
 * Each line is laid out on a virtual strip built from the horizontal segments left once
 * display cutouts are removed. Text position is tracked in that virtual space and mapped
 * back onto the segments when drawing, so scrolling text runs up to the camera hole,
 * jumps it, and continues.
 */
public class TickerView extends View {

    public static final int MODE_REVEAL = 0, MODE_LOOP = 1, MODE_OFF = 2;
    public static final int STYLE_NORMAL = 0, STYLE_BOLD = 1, STYLE_MUTED = 2;
    public static final int LOOK_CLASSIC = 0, LOOK_CARD = 1;

    private static final long HOLD_MS = 1200;      // pause after a reveal finishes
    private static final float LOOP_GAP_DP = 48;   // blank space in a loop cycle
    private static final float LINE_SPACING = 1.1f;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint();
    private final List<Rect> cutouts = new ArrayList<Rect>();   // screen coordinates
    private final float density;

    // rounded screen corners, screen coordinates; radius 0 means unknown
    private int tlR, tlCx, tlCy, trR, trCx, trCy;

    private boolean autoPad = true, hop = true;
    private int pad = 120, gap = 14, mode = MODE_REVEAL, speed = 160, delay = 700;
    private float textPx = 36;
    private int opacity = 100;

    // look
    private int look = LOOK_CLASSIC;
    private int surface = Color.BLACK, onSurface = Color.WHITE, onSurfaceVariant = 0xFFB4B4BE, accent = Color.WHITE;
    private Drawable icon;
    private final RectF card = new RectF();
    private float cardR;

    private float shade;                        // 0..1 darkening, used while flipping in 3D
    private float originX, originY;             // window origin on screen, untransformed
    private boolean haveOrigin;

    private Line[] lines = new Line[0];
    private long startAt;   // uptime at which the scroll clock starts, before `delay`

    private static final class Line {
        String bold = "", text = "";
        int style;
        TextPaint pBold, pText;
        float wBold, wSep, width, top, bottom, baseline, virtW;
        float[] segA = new float[0], segB = new float[0];   // physical x ranges, view coords
    }

    public TickerView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
    }

    // ================================================================== config

    public void configure(boolean autoPad, int pad, boolean hop, int gap, int mode,
                          int speed, int delay, float textPx, int opacityPct) {
        this.autoPad = autoPad;
        this.pad = Math.max(0, pad);
        this.hop = hop;
        this.gap = Math.max(0, gap);
        this.mode = mode;
        this.speed = Math.max(10, speed);
        this.delay = Math.max(0, delay);
        this.textPx = textPx;
        this.opacity = Math.max(0, Math.min(100, opacityPct));
        relayout();
        invalidate();
    }

    /** Heads-up card colours, from the system theme. */
    public void setLook(int look, int surface, int onSurface, int onSurfaceVariant, int accent) {
        this.look = look;
        this.surface = surface;
        this.onSurface = onSurface;
        this.onSurfaceVariant = onSurfaceVariant;
        this.accent = accent;
        if (icon != null) icon.setTint(accent);
        relayout();
        invalidate();
    }

    public void setCutouts(List<Rect> rects) {
        cutouts.clear();
        if (rects != null) cutouts.addAll(rects);
        relayout();
        invalidate();
    }

    public void setCorners(int tlR, int tlCx, int tlCy, int trR, int trCx, int trCy) {
        this.tlR = tlR; this.tlCx = tlCx; this.tlCy = tlCy;
        this.trR = trR; this.trCx = trCx; this.trCy = trCy;
        relayout();
        invalidate();
    }

    /**
     * @param bold   per line, a bold lead-in drawn before the text ("" for none)
     * @param texts  per line, the text
     * @param styles per line, STYLE_*
     */
    public void setContent(String[] bold, String[] texts, int[] styles, Drawable icon, long startDelayMs) {
        Line[] ls = new Line[texts.length];
        for (int i = 0; i < texts.length; i++) {
            Line l = new Line();
            l.bold = clean(bold == null ? null : bold[i]);
            l.text = clean(texts[i]);
            l.style = styles[i];
            ls[i] = l;
        }
        lines = ls;
        this.icon = icon;
        if (icon != null) icon.setTint(accent);
        startAt = SystemClock.uptimeMillis() + Math.max(0, startDelayMs);
        relayout();
        invalidate();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** Darkens the whole panel; flips use it so the face shades as it turns away. */
    public void setShade(float s) {
        shade = Math.max(0f, Math.min(1f, s));
        invalidate();
    }

    /** Height one line of text needs at the given size. */
    public static float lineHeight(float px) {
        TextPaint p = new TextPaint();
        p.setTextSize(px);
        Paint.FontMetrics fm = p.getFontMetrics();
        return (fm.descent - fm.ascent) * LINE_SPACING;
    }

    /** Milliseconds until every line has finished scrolling, plus a hold; 0 if nothing scrolls. */
    public long remainingMs() {
        long now = SystemClock.uptimeMillis();
        long end = 0;
        float loopGap = LOOP_GAP_DP * density;
        for (Line l : lines) {
            float overflow = l.width - l.virtW;
            if (mode == MODE_OFF || overflow <= 0 || l.virtW <= 0) continue;
            float dist = mode == MODE_REVEAL ? overflow : l.width + loopGap + l.virtW;
            end = Math.max(end, startAt + delay + (long) (dist * 1000f / speed));
        }
        return end == 0 ? 0 : Math.max(0, end + HOLD_MS - now);
    }

    // ================================================================== layout

    private void applyPaints(Line l, float size) {
        int main = look == LOOK_CARD ? onSurface : Color.WHITE;
        int muted = look == LOOK_CARD ? onSurfaceVariant : 0xFFB4B4BE;

        l.pBold = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        l.pBold.setColor(main);
        l.pBold.setTextSize(size);
        l.pBold.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        l.pText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        l.pText.setColor(l.style == STYLE_MUTED ? muted : main);
        l.pText.setTextSize(l.style == STYLE_MUTED ? size * 0.85f : size);
        l.pText.setTypeface(l.style == STYLE_BOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

        l.wBold = l.bold.isEmpty() ? 0 : l.pBold.measureText(l.bold);
        l.wSep = l.bold.isEmpty() || l.text.isEmpty() ? 0 : l.pText.measureText("   ");
        l.width = l.wBold + l.wSep + l.pText.measureText(l.text);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        relayout();
    }

    private void relayout() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        // Window origin on screen. Only trust the measurement while the view is untransformed:
        // mid-flip, the rotation matrix moves where (0,0) lands.
        if (getRotationX() == 0f && getRotationY() == 0f && getScaleX() == 1f && getScaleY() == 1f) {
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            originX = loc[0] - getTranslationX();
            originY = loc[1] - getTranslationY();
            haveOrigin = true;
        }
        float ox = haveOrigin ? originX : 0f, oy = haveOrigin ? originY : 0f;

        if (look == LOOK_CARD) layoutCard(w, h, ox, oy);
        if (lines.length == 0) return;

        // text size, shrunk if the lines would overflow the card
        float size = textPx;
        float room = look == LOOK_CARD ? card.height() - 4 * density : h;
        float need = 0;
        for (Line l : lines) { applyPaints(l, size); need += lh(l); }
        if (need > room && need > 0) {
            size *= room / need;
            need = 0;
            for (Line l : lines) { applyPaints(l, size); need += lh(l); }
        }

        float top = look == LOOK_CARD ? card.top : 0f;
        float span = look == LOOK_CARD ? card.height() : h;
        float y = top + Math.max(0f, (span - need) / 2f);
        for (Line l : lines) {
            Paint.FontMetrics fm = l.pText.getFontMetrics();
            float lh = lh(l);
            l.top = y;
            l.bottom = y + lh;
            l.baseline = y + (lh - (fm.descent - fm.ascent)) / 2f - fm.ascent;
            y += lh;
            segments(l, w, ox, oy);
        }
    }

    private static float lh(Line l) {
        Paint.FontMetrics fm = l.pText.getFontMetrics();
        return (fm.descent - fm.ascent) * LINE_SPACING;
    }

    /** The heads-up card: a pill whose rounded ends sit inside the screen's rounded corners. */
    private void layoutCard(int w, int h, float ox, float oy) {
        float m = 3 * density;
        float top = m, bottom = h - m;
        float r = (bottom - top) / 2f;
        float pcy = oy + top + r;                     // pill centre line, screen coords
        float left = fitPill(tlR, tlCx, tlCy, pcy, r, true);
        float right = fitPill(trR, trCx, trCy, pcy, r, false);
        float edge = 4 * density;
        float l = Float.isNaN(left) ? 8 * density : left - ox + edge;
        float rt = Float.isNaN(right) ? w - 8 * density : right - ox - edge;
        card.set(l, top, Math.max(l + 2 * r, rt), bottom);
        cardR = r;
    }

    /**
     * Screen x at which the pill's round end, radius r centred on pcy, just fits inside a
     * rounded corner of radius R centred at (cx, cy). NaN when the corner is unknown.
     */
    private static float fitPill(int R, int cx, int cy, float pcy, float r, boolean leftSide) {
        if (R <= 0) return Float.NaN;
        if (pcy >= cy) return leftSide ? cx - R : cx + R;          // below the curve: straight edge
        double disc = (double) (R - r) * (R - r) - (double) (cy - pcy) * (cy - pcy);
        if (disc < 0) return cx;                                    // can't fit; stay clear
        float d = (float) Math.sqrt(disc);
        return leftSide ? cx - r - d : cx + r + d;
    }

    private float iconSize() { return Math.min(18 * density, card.height() * 0.5f); }

    /** Works out which horizontal stretches of this line are usable. */
    private void segments(Line l, int w, float ox, float oy) {
        float left, right;
        if (look == LOOK_CARD) {
            left = card.left + cardR * 0.6f + (icon != null ? iconSize() + 8 * density : 0);
            right = card.right - cardR * 0.6f;
        } else {
            left = pad;
            right = w - pad;
            if (autoPad && (tlR > 0 || trR > 0)) {
                // the corner arc bites deepest at the top of the line, where the glyphs start
                float probe = oy + l.top + (l.bottom - l.top) * 0.15f;
                float margin = 6 * density;
                float cl = arcX(tlR, tlCx, tlCy, probe, true);
                float cr = arcX(trR, trCx, trCy, probe, false);
                if (cl >= 0) left = cl - ox + margin;
                if (cr >= 0) right = cr - ox - margin;
            }
        }

        List<float[]> segs = new ArrayList<float[]>();
        if (right - left > 1) segs.add(new float[]{left, right});

        if (hop) {
            for (Rect r : cutouts) {
                float rt = r.top - oy, rb = r.bottom - oy;
                if (!(rt < l.bottom && rb > l.top)) continue;   // cutout not in this line's band
                float ca = r.left - ox - gap, cb = r.right - ox + gap;
                List<float[]> out = new ArrayList<float[]>();
                for (float[] s : segs) {
                    if (cb <= s[0] || ca >= s[1]) { out.add(s); continue; }
                    if (ca > s[0]) out.add(new float[]{s[0], ca});
                    if (cb < s[1]) out.add(new float[]{cb, s[1]});
                }
                segs = out;
            }
        }

        l.segA = new float[segs.size()];
        l.segB = new float[segs.size()];
        l.virtW = 0;
        for (int i = 0; i < segs.size(); i++) {
            l.segA[i] = segs.get(i)[0];
            l.segB[i] = segs.get(i)[1];
            l.virtW += l.segB[i] - l.segA[i];
        }
    }

    /** Screen x of a rounded-corner arc at screen height y; -1 if the corner is unknown. */
    private static float arcX(int r, int cx, int cy, float y, boolean leftSide) {
        if (r <= 0) return -1;
        float dy = cy - y;
        if (dy <= 0) return leftSide ? cx - r : cx + r;    // below the arc: straight edge
        if (dy >= r) return cx;                            // above the arc
        float dx = (float) Math.sqrt((double) r * r - (double) dy * dy);
        return leftSide ? cx - dx : cx + dx;
    }

    // ================================================================== drawing

    private static final float FADE_DP = 18f;
    /** Unit-width ramps, stretched onto an edge: they erase the text they cover (DST_OUT). */
    private final LinearGradient fadeIn = new LinearGradient(0, 0, 1, 0,
            new int[]{0xFF000000, 0xB0000000, 0x00000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
    private final LinearGradient fadeOut = new LinearGradient(0, 0, 1, 0,
            new int[]{0x00000000, 0xB0000000, 0xFF000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
    private final Paint fadePaint = new Paint();
    private final Matrix fadeM = new Matrix();
    { fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT)); }

    private void fadeEdge(Canvas c, LinearGradient g, float x, float w, Line l) {
        fadeM.setScale(w, 1f);
        fadeM.postTranslate(x, 0f);
        g.setLocalMatrix(fadeM);
        fadePaint.setShader(g);
        c.drawRect(x, l.top, x + w, l.bottom, fadePaint);
    }

    @Override protected void onDraw(Canvas c) {
        int a = Math.round(255f * opacity / 100f);
        if (look == LOOK_CARD) {
            bg.setColor(surface);
            bg.setAlpha(a);
            bg.setShadowLayer(6 * density, 0, 2 * density, 0x40000000);
            c.drawRoundRect(card, cardR, cardR, bg);
            bg.clearShadowLayer();
            if (icon != null) {
                int s = Math.round(iconSize());
                int ix = Math.round(card.left + cardR * 0.6f);
                int iy = Math.round(card.centerY() - s / 2f);
                icon.setBounds(ix, iy, ix + s, iy + s);
                icon.draw(c);
            }
        } else {
            bg.setColor(Color.BLACK);
            bg.setAlpha(a);
            c.drawRect(0, 0, getWidth(), getHeight(), bg);
        }

        long now = SystemClock.uptimeMillis();
        float loopGap = LOOP_GAP_DP * density;
        boolean animating = false;

        for (Line l : lines) {
            if (l.segA.length == 0 || l.pText == null) continue;

            float overflow = l.width - l.virtW;
            float u = 0;              // virtual x of the start of the text
            if (mode != MODE_OFF && overflow > 0) {
                float t = (now - startAt - delay) / 1000f;
                if (t <= 0) {
                    animating = true;   // still in the pause before scrolling
                } else if (mode == MODE_REVEAL) {
                    float d = Math.min(overflow, t * speed);
                    u = -d;
                    if (d < overflow) animating = true;
                } else {
                    // One copy at a time, like a news ticker: scroll off to the left, a short
                    // blank, then back in from the right edge and home to the start.
                    float cycle = l.width + loopGap + l.virtW;
                    float p = (t * speed) % cycle;
                    u = p <= l.width ? -p : l.virtW + loopGap - (p - l.width);
                    animating = true;
                }
            }

            // map virtual space onto each physical segment in turn
            float vs = 0;
            for (int s = 0; s < l.segA.length; s++) {
                float sa = l.segA[s], sb = l.segB[s];
                float x = sa + (u - vs);
                // soft edges wherever the text is cut: the camera gap and the scroll ends
                boolean fl = x < sa - 0.5f, fr = x + l.width > sb + 0.5f;
                c.save();
                c.clipRect(sa, l.top, sb, l.bottom);
                int layer = fl || fr ? c.saveLayer(sa, l.top, sb, l.bottom, null) : -1;
                if (l.wBold > 0) c.drawText(l.bold, x, l.baseline, l.pBold);
                if (!l.text.isEmpty()) c.drawText(l.text, x + l.wBold + l.wSep, l.baseline, l.pText);
                if (layer >= 0) {
                    float fw = Math.min(FADE_DP * density, (sb - sa) / 3f);
                    if (fl) fadeEdge(c, fadeIn, sa, fw, l);
                    if (fr) fadeEdge(c, fadeOut, sb - fw, fw, l);
                    c.restoreToCount(layer);
                }
                c.restore();
                vs += sb - sa;
            }
        }

        if (shade > 0f) {
            shadePaint.setColor(Color.argb(Math.round(shade * 255f), 0, 0, 0));
            c.drawRect(0, 0, getWidth(), getHeight(), shadePaint);
        }
        if (animating) postInvalidateOnAnimation();
    }
}
