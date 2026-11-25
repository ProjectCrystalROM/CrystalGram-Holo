package ai.crystalgram.holo;

import android.graphics.Bitmap;

import org.drinkless.tdlib.TdApi;

/**
 * What a bubble needs to draw a piece of media, worked out from a TdApi.Message.
 *
 * Kept out of the view so the view never touches TdApi types: the adapter asks
 * for one of these and renders it, which keeps the layout code independent of
 * whichever media kinds TDLib grows next.
 */
final class MediaVm {

    static final int NONE = 0;
    static final int PENDING = 1;      // not downloaded yet
    static final int DOWNLOADING = 2;  // TDLib is fetching it
    static final int READY = 3;        // local, show it

    final int kind;
    final int fileId;
    final int state;
    final String label;

    private final MediaLoader loader;

    private MediaVm(int kind, int fileId, int state, String label, MediaLoader loader) {
        this.kind = kind;
        this.fileId = fileId;
        this.state = state;
        this.label = label;
        this.loader = loader;
    }

    Bitmap bitmap() {
        if (fileId == 0) return null;
        return loader.bitmap(fileId);
    }

    /**
     * Builds a description of the media in a message, and asks TDLib for the
     * smallest adequate file. Returns null for text messages and anything with
     * no media to show.
     */
    static MediaVm of(TdApi.Message m, MediaLoader loader) {
        TdApi.MessageContent c = m.content;

        if (c instanceof TdApi.MessagePhoto) {
            TdApi.Photo p = ((TdApi.MessagePhoto) c).photo;
            TdApi.File f = pickPhotoSize(p);
            if (f == null) return null;
            loader.need(f);
            return make(NONE, f, "Photo", loader);
        }

        if (c instanceof TdApi.MessageSticker) {
            TdApi.Sticker st = ((TdApi.MessageSticker) c).sticker;
            if (st == null || st.sticker == null) return null;
            // Stickers are small; take the thumbnail if there is one, else the
            // sticker file itself.
            TdApi.File f = st.thumbnail != null && st.thumbnail.file != null
                    ? st.thumbnail.file : st.sticker;
            loader.need(f);
            String emoji = st.emoji == null ? "" : st.emoji;
            return make(NONE, f, emoji.length() == 0 ? "Sticker" : emoji, loader);
        }

        if (c instanceof TdApi.MessageVideo) {
            TdApi.Video v = ((TdApi.MessageVideo) c).video;
            if (v == null) return null;
            // Show the cover frame, not the video: decoding video on API 16 is
            // not something to do in a list.
            TdApi.File f = null;
            if (v.thumbnail != null) f = v.thumbnail.file;
            String dur = v.duration > 0 ? " " + clockOf(v.duration) : "";
            if (f == null) {
                return new MediaVm(NONE, 0, READY, "Video" + dur, loader);
            }
            loader.need(f);
            return make(NONE, f, "Video" + dur, loader);
        }

        if (c instanceof TdApi.MessageDocument) {
            TdApi.Document d = ((TdApi.MessageDocument) c).document;
            if (d == null) return null;
            String name = d.fileName == null || d.fileName.length() == 0
                    ? "File" : d.fileName;
            TdApi.File f = d.document;
            if (f == null) return new MediaVm(NONE, 0, READY, name, loader);
            // Only fetch metadata-sized bytes for the icon; the file itself is
            // downloaded when the user taps it.
            return new MediaVm(NONE, f.id, READY, name + sizeSuffix(f), loader);
        }

        if (c instanceof TdApi.MessageVoiceNote) {
            TdApi.MessageVoiceNote vn = (TdApi.MessageVoiceNote) c;
            TdApi.VoiceNote v = vn.voiceNote;
            String dur = v != null && v.duration > 0 ? " " + clockOf(v.duration) : "";
            return new MediaVm(NONE, 0, READY, "Voice message" + dur, loader);
        }

        return null;
    }

    private static MediaVm make(int kind, TdApi.File f, String label, MediaLoader loader) {
        return new MediaVm(kind, f.id, sizeState(f), label, loader);
    }

    /**
     * Whether the bytes are here yet, so the view can show a spinner, a bar, or
     * the picture itself.
     */
    private static int sizeState(TdApi.File f) {
        if (f.local == null) return PENDING;
        if (f.local.isDownloadingCompleted && f.local.path != null
                && f.local.path.length() > 0) {
            return READY;
        }
        if (f.local.isDownloadingActive) return DOWNLOADING;
        return PENDING;
    }

    /**
     * The smallest photo size that will still look right in a bubble, or the
     * last one if every size is small.
     */
    private static TdApi.File pickPhotoSize(TdApi.Photo p) {
        if (p == null || p.sizes == null || p.sizes.length == 0) return null;
        // Sizes are ordered smallest to largest. ~320 px is a 110 dp bubble at
        // xhdpi and is enough for a preview.
        TdApi.File best = null;
        for (TdApi.PhotoSize s : p.sizes) {
            if (s.photo == null) continue;
            best = s.photo;
            if (s.width >= 320) return s.photo;
        }
        return best;
    }

    private static String clockOf(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }

    private static String sizeSuffix(TdApi.File f) {
        long size = f.size > 0 ? f.size : f.expectedSize;
        if (size <= 0) return "";
        if (size < 1024) return "  " + size + " B";
        if (size < 1024 * 1024) return "  " + (size / 1024) + " KB";
        return String.format("  %.1f MB", size / (1024.0 * 1024.0));
    }
}
