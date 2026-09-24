package com.jakes.tickerbar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws 1-3 lines of ticker text.
 *
 * Each line is laid out on a virtual strip built from the horizontal segments that
 * remain once display cutouts (and the rounded screen corners) are removed. Text
 * position is tracked in that virtual space and mapped back onto the segments when
 * drawing, so scrolling text runs up to the camera hole, jumps it, and continues.
 */
public class TickerView extends View {

    public static final int MODE_REVEAL = 0, MODE_LOOP = 1, MODE_OFF = 2;
    public static final int STYLE_NORMAL = 0, STYLE_BOLD = 1, STYLE_MUTED = 2;

    private static final long HOLD_MS = 1200;      // pause after a reveal finishes
    private static final float LOOP_GAP_DP = 48;   // space between loop repetitions
    private static final float LINE_SPACING = 1.1f;

    private final Paint bg = new Paint();
    private final Paint shadePaint = new Paint();
    private float shade;                        // 0..1 darkening, used while flipping in 3D
    private float originX, originY;             // window origin on screen, untransformed
    private boolean haveOrigin;
    private final List<Rect> cutouts = new ArrayList<Rect>();   // screen coordinates
    private final float density;

    // rounded corners, screen coordinates; radius 0 means unknown
    private int tlR, tlCx, tlCy, trR, trCx, trCy;

    private boolean autoPad = true, hop = true;
    private int pad = 120, gap = 14, mode = MODE_REVEAL, speed = 160, delay = 700;
    private float textPx = 36;

    private Line[] lines = new Line[0];
    private long startAt;   // uptime at which the scroll clock starts, before `delay`

    private static final class Line {
        String text = "";
        int style;
        TextPaint paint;
        float width, top, bottom, baseline, virtW;
        float[] segA = new float[0], segB = new float[0];   // physical x ranges, view coords
    }

    public TickerView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        bg.setColor(Color.BLACK);
    }

    // ------------------------------------------------------------------ config

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
        int a = Math.round(255f * Math.max(0, Math.min(100, opacityPct)) / 100f);
        bg.setColor(Color.argb(a, 0, 0, 0));
        for (Line l : lines) applyPaint(l);   // live-update anything currently shown
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

    public void setContent(String[] texts, int[] styles, long startDelayMs) {
        Line[] ls = new Line[texts.length];
        for (int i = 0; i < texts.length; i++) {
            Line l = new Line();
            String t = texts[i] == null ? "" : texts[i];
            l.text = t.replace('\n', ' ').replace('\r', ' ').trim();
            l.style = styles[i];
            applyPaint(l);
            ls[i] = l;
        }
        lines = ls;
        startAt = SystemClock.uptimeMillis() + Math.max(0, startDelayMs);
        relayout();
        invalidate();
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
            float dist = mode == MODE_REVEAL ? overflow : l.width + loopGap;
            end = Math.max(end, startAt + delay + (long) (dist * 1000f / speed));
        }
        return end == 0 ? 0 : Math.max(0, end + HOLD_MS - now);
    }

    // ------------------------------------------------------------------ layout

    private void applyPaint(Line l) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(l.style == STYLE_MUTED ? 0xFFB4B4BE : Color.WHITE);
        p.setTextSize(l.style == STYLE_MUTED ? textPx * 0.85f : textPx);
        p.setTypeface(l.style == STYLE_BOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        l.paint = p;
        l.width = p.measureText(l.text);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        relayout();
    }

    private void relayout() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || lines.length == 0) return;

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

        float[] lh = new float[lines.length];
        float total = 0;
        for (int i = 0; i < lines.length; i++) {
            Paint.FontMetrics fm = lines[i].paint.getFontMetrics();
            lh[i] = (fm.descent - fm.ascent) * LINE_SPACING;
            total += lh[i];
        }
        float y = Math.max(0f, (h - total) / 2f);
        for (int i = 0; i < lines.length; i++) {
            Line l = lines[i];
            Paint.FontMetrics fm = l.paint.getFontMetrics();
            l.top = y;
            l.bottom = y + lh[i];
            l.baseline = y + (lh[i] - (fm.descent - fm.ascent)) / 2f - fm.ascent;
            y += lh[i];
            segments(l, w, ox, oy);
        }
    }

    /** Works out which horizontal stretches of this line are usable. */
    private void segments(Line l, int w, float ox, float oy) {
        float left = pad, right = w - pad;
        if (autoPad && (tlR > 0 || trR > 0)) {
            // the corner arc bites deepest at the top of the line, where the glyphs start
            float probe = oy + l.top + (l.bottom - l.top) * 0.15f;
            float margin = 6 * density;
            float cl = arcX(tlR, tlCx, tlCy, probe, true);
            float cr = arcX(trR, trCx, trCy, probe, false);
            if (cl >= 0) left = cl - ox + margin;
            if (cr >= 0) right = cr - ox - margin;
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

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas c) {
        c.drawRect(0, 0, getWidth(), getHeight(), bg);
        long now = SystemClock.uptimeMillis();
        float loopGap = LOOP_GAP_DP * density;
        boolean animating = false;

        for (Line l : lines) {
            if (l.segA.length == 0) continue;

            float overflow = l.width - l.virtW;
            float u = 0;              // virtual x of the start of the text
            boolean copies = false;   // loop mode draws a trailing repeat

            if (mode != MODE_OFF && overflow > 0) {
                float t = (now - startAt - delay) / 1000f;
                if (t <= 0) {
                    animating = true;   // still in the pause before scrolling
                } else if (mode == MODE_REVEAL) {
                    float d = Math.min(overflow, t * speed);
                    u = -d;
                    if (d < overflow) animating = true;
                } else {
                    u = -((t * speed) % (l.width + loopGap));
                    copies = true;
                    animating = true;
                }
            }

            // map virtual space onto each physical segment in turn
            float vs = 0;
            for (int s = 0; s < l.segA.length; s++) {
                float a = l.segA[s], b = l.segB[s];
                float x = a + (u - vs);
                c.save();
                c.clipRect(a, l.top, b, l.bottom);
                c.drawText(l.text, x, l.baseline, l.paint);
                if (copies) c.drawText(l.text, x + l.width + loopGap, l.baseline, l.paint);
                c.restore();
                vs += b - a;
            }
        }
        if (shade > 0f) {
            shadePaint.setColor(Color.argb(Math.round(shade * 255f), 0, 0, 0));
            c.drawRect(0, 0, getWidth(), getHeight(), shadePaint);
        }
        if (animating) postInvalidateOnAnimation();
    }
}
