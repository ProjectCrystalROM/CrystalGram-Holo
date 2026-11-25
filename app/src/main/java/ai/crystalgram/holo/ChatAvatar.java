package ai.crystalgram.holo;

import android.app.Activity;
import android.app.ActionBar;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import org.drinkless.tdlib.TdApi;

import java.util.Locale;

/** Circular chat photo, with a letter placeholder while the thumbnail loads. */
final class ChatAvatar extends Drawable {
    private static final int[] COLORS = {0xFF4E6684, 0xFF5B715C, 0xFF735D80, 0xFF845C52};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final int size;
    private final int color;
    private final String initial;
    private final Bitmap image;

    ChatAvatar(TdApi.Chat chat, int size) {
        this.size = size;
        color = COLORS[(int) ((chat.id ^ (chat.id >>> 32)) & 3)];
        String title = Td.chatTitle(chat).trim();
        initial = title.isEmpty() ? "?" : title.substring(0, Character.charCount(title.codePointAt(0)))
                .toUpperCase(Locale.getDefault());

        TdApi.File file = chat.photo == null ? null : chat.photo.small;
        if (file == null || !Td.authorized() || Td.scope(chat) == ChatScope.EXCLUDED) {
            image = null;
        } else {
            MediaLoader loader = MediaLoader.get();
            image = loader.bitmap(file.id);
            if (image == null) loader.need(file);
        }
    }

    static boolean matches(TdApi.Chat chat, int fileId) {
        return chat != null && chat.photo != null && chat.photo.small != null
                && chat.photo.small.id == fileId;
    }

    static void showInActionBar(Activity activity, TdApi.Chat chat) {
        ActionBar bar = activity.getActionBar();
        if (bar == null || chat == null || !Td.authorized() || Td.scope(chat) == ChatScope.EXCLUDED) return;
        int size = Math.round(32 * activity.getResources().getDisplayMetrics().density);
        bar.setDisplayUseLogoEnabled(false);
        bar.setIcon(new ChatAvatar(chat, size));
    }

    @Override protected void onBoundsChange(Rect bounds) {
        if (image == null) return;
        float scale = Math.max((float) bounds.width() / image.getWidth(),
                (float) bounds.height() / image.getHeight());
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(bounds.exactCenterX() - image.getWidth() * scale * 0.5f,
                bounds.exactCenterY() - image.getHeight() * scale * 0.5f);
        BitmapShader shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        paint.setColor(image == null ? color : 0xFFFFFFFF);
        canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(),
                Math.min(bounds.width(), bounds.height()) * 0.5f, paint);
        if (image == null) {
            paint.setColor(0xFFFFFFFF);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(bounds.height() * 0.46f);
            canvas.drawText(initial, bounds.exactCenterX(),
                    bounds.exactCenterY() - (paint.ascent() + paint.descent()) * 0.5f, paint);
        }
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
