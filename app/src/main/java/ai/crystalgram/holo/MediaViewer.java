package ai.crystalgram.holo;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.drinkless.tdlib.TdApi;

import java.util.HashSet;
import java.util.Set;

final class MediaViewer {
    private final ChatActivity activity;
    private final Handler ui;
    private final Set<Long> consumed = new HashSet<>();
    private Dialog dialog;
    private long messageId;
    private boolean openedOnce;
    private Bitmap photo;
    private MediaPlayer player;
    private Surface videoSurface;
    private final Runnable stop = this::close;

    MediaViewer(ChatActivity activity, Handler ui) {
        this.activity = activity;
        this.ui = ui;
    }

    static boolean isProtected(TdApi.Message message) {
        TdApi.MessageContent content = message.content;
        if (isExpired(content)) return false;
        if (content instanceof TdApi.MessagePhoto) {
            return message.selfDestructType != null || ((TdApi.MessagePhoto) content).isSecret;
        }
        if (content instanceof TdApi.MessageVideo) {
            return message.selfDestructType != null || ((TdApi.MessageVideo) content).isSecret;
        }
        if (content instanceof TdApi.MessageVideoNote) {
            return message.selfDestructType != null || ((TdApi.MessageVideoNote) content).isSecret;
        }
        return content instanceof TdApi.MessageVoiceNote && message.selfDestructType != null;
    }

    static boolean isExpired(TdApi.MessageContent content) {
        return content instanceof TdApi.MessageExpiredPhoto || content instanceof TdApi.MessageExpiredVideo
                || content instanceof TdApi.MessageExpiredVideoNote || content instanceof TdApi.MessageExpiredVoiceNote;
    }

    static boolean canOpen(TdApi.Message message) {
        return (!isProtected(message) || !message.isOutgoing) && canPlay(message.content);
    }

    private static boolean canPlay(TdApi.MessageContent content) {
        return content instanceof TdApi.MessagePhoto || content instanceof TdApi.MessageVideo
                || content instanceof TdApi.MessageVideoNote || content instanceof TdApi.MessageVoiceNote
                || content instanceof TdApi.MessageAudio;
    }

    void open(TdApi.Message message) {
        if (dialog != null || !activity.canUse(message) || consumed.contains(message.id)
                || !canOpen(message)) return;
        TdApi.File file = fileOf(message.content);
        if (file == null) return;
        messageId = message.id;
        Dialog screen = new Dialog(activity, android.R.style.Theme_Holo_NoActionBar_Fullscreen);
        dialog = screen;
        if (isProtected(message) || !message.canBeSaved) {
            screen.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        FrameLayout frame = new FrameLayout(activity);
        TextView loading = new TextView(activity);
        loading.setText("Loading media… Tap to close");
        loading.setPadding(16, 16, 16, 16);
        loading.setOnClickListener(v -> close());
        frame.addView(loading);
        screen.setContentView(frame);
        screen.setOnDismissListener(d -> {
            if (dialog == screen) release();
        });
        screen.show();
        Td.send(new TdApi.DownloadFile(file.id, 32, 0, 0, true), result -> ui.post(() -> {
            if (dialog != screen || !activity.canUse(message) || !canOpen(message)) return;
            if (!(result instanceof TdApi.File)) {
                fail(result instanceof TdApi.Error ? ((TdApi.Error) result).message : "Media unavailable.");
                return;
            }
            TdApi.LocalFile local = ((TdApi.File) result).local;
            if (local == null || !local.isDownloadingCompleted || local.path.length() == 0) {
                fail("Media unavailable.");
                return;
            }
            if (message.content instanceof TdApi.MessagePhoto) showPhoto(screen, frame, message, local.path);
            else if (message.content instanceof TdApi.MessageVoiceNote
                    || message.content instanceof TdApi.MessageAudio) playAudio(message, local.path, loading);
            else playVideo(frame, message, local.path);
        }));
    }

    private void showPhoto(Dialog screen, FrameLayout frame, TdApi.Message message, String path) {
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, options);
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1280) {
                    options.inSampleSize *= 2;
                }
                options.inJustDecodeBounds = false;
                bitmap = BitmapFactory.decodeFile(path, options);
            } catch (RuntimeException | OutOfMemoryError ignored) {
                // The decoder can reject a damaged or unsupported image.
            }
            Bitmap decoded = bitmap;
            ui.post(() -> {
                if (dialog != screen || !activity.canUse(message) || !canOpen(message)) {
                    if (decoded != null) decoded.recycle();
                    return;
                }
                if (decoded == null) {
                    fail("This device could not display the photo.");
                    return;
                }
                photo = decoded;
                ImageView image = new ImageView(activity);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setImageBitmap(photo);
                image.setOnClickListener(v -> close());
                frame.removeAllViews();
                frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
                acknowledge(message);
            });
        }, "disappearing-photo").start();
    }

    private void playVideo(FrameLayout frame, TdApi.Message message, String path) {
        // TextureView stays in the secure dialog window on API 16.
        TextureView texture = new TextureView(activity);
        frame.removeAllViews();
        frame.addView(texture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        Dialog screen = dialog;
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (dialog != screen) return;
                videoSurface = new Surface(surface);
                prepare(message, path, videoSurface, () -> {}, (media, videoWidth, videoHeight) -> {
                    if (videoWidth <= 0 || videoHeight <= 0) return;
                    float scale = Math.min((float) width / videoWidth, (float) height / videoHeight);
                    texture.setLayoutParams(new FrameLayout.LayoutParams(
                            Math.max(1, Math.round(videoWidth * scale)),
                            Math.max(1, Math.round(videoHeight * scale)), Gravity.CENTER));
                });
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (dialog == screen) close();
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
    }

    private void playAudio(TdApi.Message message, String path, TextView label) {
        prepare(message, path, null, () -> label.setText("Playing audio · Tap to close"), null);
    }

    private void prepare(TdApi.Message message, String path, Surface surface, Runnable start,
                         MediaPlayer.OnVideoSizeChangedListener sizeChanged) {
        MediaPlayer media = new MediaPlayer();
        player = media;
        try {
            media.setDataSource(path);
            if (surface != null) {
                media.setSurface(surface);
                media.setOnVideoSizeChangedListener(sizeChanged);
            }
            media.setOnCompletionListener(p -> { if (player == p) close(); });
            media.setOnErrorListener((p, what, extra) -> {
                if (player == p) fail("This device could not play the media.");
                return true;
            });
            media.setOnPreparedListener(p -> {
                if (player != p || !activity.canUse(message) || !canOpen(message)) return;
                start.run();
                try {
                    p.start();
                    acknowledge(message);
                } catch (IllegalStateException e) {
                    fail("This device could not play the media.");
                }
            });
            media.prepareAsync();
        } catch (java.io.IOException | RuntimeException e) {
            fail("This device could not play the media.");
        }
    }

    private void acknowledge(TdApi.Message message) {
        TdApi.MessageSelfDestructType type = message.selfDestructType;
        openedOnce = type instanceof TdApi.MessageSelfDestructTypeImmediately;
        if (openedOnce) consumed.add(message.id);
        Dialog screen = dialog;
        Td.send(new TdApi.OpenMessageContent(message.chatId, message.id), result -> ui.post(() -> {
            if (dialog == screen && result instanceof TdApi.Error) fail(((TdApi.Error) result).message);
        }));
        if (type instanceof TdApi.MessageSelfDestructTypeTimer) {
            int seconds = ((TdApi.MessageSelfDestructTypeTimer) type).selfDestructTime;
            double left = message.selfDestructIn > 0 ? Math.min(seconds, message.selfDestructIn) : seconds;
            ui.postDelayed(stop, Math.max(1, (long) (left * 1000)));
        }
    }

    void expired(long id) {
        if (id == messageId && !openedOnce) close();
    }

    void deleted(long[] ids) {
        for (long id : ids) {
            if (id == messageId) {
                close();
                return;
            }
        }
    }

    void close() {
        Dialog screen = dialog;
        if (screen == null) return;
        release();
        screen.dismiss();
    }

    private void release() {
        dialog = null;
        messageId = 0;
        openedOnce = false;
        ui.removeCallbacks(stop);
        if (player != null) {
            player.release();
            player = null;
        }
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (photo != null) {
            // The dismissed view may still be present in the renderer until the next frame.
            photo = null;
        }
    }

    private void fail(String text) {
        close();
        ConversationActions.toast(activity, text);
    }

    private static TdApi.File fileOf(TdApi.MessageContent content) {
        if (content instanceof TdApi.MessagePhoto) {
            TdApi.Photo photo = ((TdApi.MessagePhoto) content).photo;
            TdApi.PhotoSize[] sizes = photo == null ? null : photo.sizes;
            return sizes == null || sizes.length == 0 ? null : sizes[sizes.length - 1].photo;
        }
        if (content instanceof TdApi.MessageVideo) return ((TdApi.MessageVideo) content).video.video;
        if (content instanceof TdApi.MessageVideoNote) return ((TdApi.MessageVideoNote) content).videoNote.video;
        if (content instanceof TdApi.MessageVoiceNote) return ((TdApi.MessageVoiceNote) content).voiceNote.voice;
        if (content instanceof TdApi.MessageAudio) return ((TdApi.MessageAudio) content).audio.audio;
        return null;
    }
}
