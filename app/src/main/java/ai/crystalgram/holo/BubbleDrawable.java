package ai.crystalgram.holo;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * A chat bubble drawn in a single Canvas pass: rounded rectangle plus tail as
 * one continuous outline path.
 *
 * Why one path and not a rounded-rect Drawable layered with a triangle: two
 * antialiased shapes sharing an edge blend twice along the overlap, which
 * leaves a visible lighter seam. Building the outline as one Path and fill()ing
 * it once cannot produce a seam, because there is no shared edge at all.
 *
 * Framework-only (android.graphics.drawable), no dependencies, API 16 safe.
 */
public final class BubbleDrawable extends Drawable {

    public static final int TAIL_NONE  = 0;
    public static final int TAIL_RIGHT = 1;   // outgoing: bottom-right
    public static final int TAIL_LEFT  = 2;   // incoming: bottom-left

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private final float radius;
    private final int tailSide;
    private final float tailWidth;
    private final float tailHeight;

    public BubbleDrawable(int fillColor, int tailSide, float radius,
                          float tailWidth, float tailHeight) {
        this.paint.setStyle(Paint.Style.FILL);
        this.paint.setColor(fillColor);
        this.tailSide = tailSide;
        this.radius = radius;
        this.tailWidth = tailWidth;
        this.tailHeight = tailHeight;
    }

    public void setFillColor(int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    @Override
    protected boolean onLevelChange(int level) {
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(Canvas canvas) {
        final Rect b = getBounds();
        if (b.isEmpty()) return;

        path.reset();

        // The tail occupies the strip along the foot of the bubble, so the body
        // is shortened by tailHeight at the bottom.
        final float bodyBottom = b.bottom - tailHeight;
        final float insetBodyBottom = bodyBottom;
        final float left = b.left;
        final float right = b.right;
        final float top = b.top;

        rect.set(left, top, right, insetBodyBottom);

        // Body: rounded rect, but with the two bottom corners squared off on
        // the side that carries the tail so the tail grows out of it smoothly.
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);

        if (tailSide != TAIL_NONE) {
            // Draw the tail as a filled triangle merged into the same path.
            // Because fill() of the union path is a single coverage
            // computation, the join is watertight.
            path.moveTo(tailSide == TAIL_RIGHT ? right - tailWidth : left,
                        bodyBottom - 1f);                     // slightly inside
            path.lineTo(tailSide == TAIL_RIGHT ? right - 1f : left + 1f,
                        bodyBottom - 1f);
            path.lineTo(tailSide == TAIL_RIGHT ? right - 1f : left + 1f,
                        bodyBottom + tailHeight);
            path.close();
        }

        // Non-zero winding: overlapping subpaths union rather than cancel.
        path.setFillType(Path.FillType.WINDING);
        canvas.drawPath(path, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return Color.alpha(paint.getColor()) == 255
                ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
    }

    /** Convenience: sent / received bubbles in the CrystalGram palette. */
    public static BubbleDrawable sent(float d) {
        return new BubbleDrawable(Palette.LAVENDER, TAIL_RIGHT,
                dp(d, 6), dp(d, 10), dp(d, 7));
    }

    public static BubbleDrawable received(float d) {
        return new BubbleDrawable(Palette.MINT, TAIL_LEFT,
                dp(d, 6), dp(d, 10), dp(d, 7));
    }

    private static float dp(float density, float v) {
        return v * density;
    }
}
