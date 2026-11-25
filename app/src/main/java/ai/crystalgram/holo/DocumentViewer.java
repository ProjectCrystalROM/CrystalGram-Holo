package ai.crystalgram.holo;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

final class DocumentViewer {
    private DocumentViewer() {}

    static void open(ChatActivity activity, TdApi.Message message) {
        if (!activity.canUse(message) || !(message.content instanceof TdApi.MessageDocument)) return;
        if (!canShare(message)) {
            ConversationActions.toast(activity, "This document is protected from opening in another app.");
            return;
        }
        TdApi.Document document = ((TdApi.MessageDocument) message.content).document;
        if (document == null || document.document == null) return;
        String name = document.fileName == null || document.fileName.isEmpty() ? "Document" : document.fileName;
        new AlertDialog.Builder(activity).setTitle("Open document in another app?").setMessage(name)
                .setPositiveButton("Open", (dialog, which) -> {
                    if (activity.canUse(message) && canShare(message)) download(activity, message, document, name);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static boolean canShare(TdApi.Message message) {
        return message.canBeSaved && message.selfDestructType == null && message.autoDeleteIn <= 0;
    }

    private static void download(ChatActivity activity, TdApi.Message message, TdApi.Document document, String name) {
        TdApi.File file = document.document;
        if (file.local == null || !file.local.isDownloadingCompleted) {
            ConversationActions.toast(activity, "Downloading document…");
        }
        Td.send(new TdApi.DownloadFile(file.id, 32, 0, 0, true), result -> activity.runOnUiThread(() -> {
            if (!activity.canUse(message)) return;
            if (!(result instanceof TdApi.File)) {
                ConversationActions.toast(activity, result instanceof TdApi.Error
                        ? ((TdApi.Error) result).message : "Document unavailable.");
                return;
            }
            openVerified(activity, message, document, name, (TdApi.File) result);
        }));
    }

    private static void openVerified(ChatActivity activity, TdApi.Message message, TdApi.Document document,
                                     String name, TdApi.File file) {
        // The attachment and its save permission can change while the download is pending.
        Td.send(new TdApi.GetMessage(message.chatId, message.id), result -> activity.runOnUiThread(() -> {
            if (!activity.canUse(message)) return;
            TdApi.Message latest = result instanceof TdApi.Message ? (TdApi.Message) result : null;
            TdApi.Document current = latest != null && latest.content instanceof TdApi.MessageDocument
                    ? ((TdApi.MessageDocument) latest.content).document : null;
            if (current == null || current.document == null || current.document.id != file.id
                    || !activity.canUse(latest) || !canShare(latest)
                    || !openLocal(activity, document, name, file)) {
                ConversationActions.toast(activity, "Document is no longer available to open.");
            }
        }));
    }

    private static boolean openLocal(ChatActivity activity, TdApi.Document document,
                                     String name, TdApi.File file) {
        if (file.local == null || !file.local.isDownloadingCompleted || file.local.path == null
                || file.local.path.isEmpty()) return false;
        File local;
        try {
            local = new File(file.local.path).getCanonicalFile();
            if (!local.isFile() || !local.canRead()) return false;
            File data = activity.getDir("tdlib", 0).getCanonicalFile();
            File documents = new File(data, "documents").getCanonicalFile();
            boolean download = local.getPath().startsWith(documents.getPath() + File.separator);
            if (!download && !UploadPrep.isStaged(activity, local)
                    && !UploadPrep.isReadableSharedFile(local)) return false;
        } catch (IOException | SecurityException e) {
            return false;
        }
        String mime = mimeType(document.mimeType, name);
        Uri uri = DocumentProvider.offer(local, name, mime);
        ClipData grant = ClipData.newRawUri(name, uri);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (android.os.Build.VERSION.SDK_INT < 30 && view.resolveActivity(activity.getPackageManager()) == null) {
            ConversationActions.toast(activity, "No app on this device can open this document.");
            return true;
        }
        view.setClipData(grant);
        Intent chooser = Intent.createChooser(view, "Open document")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        chooser.setClipData(grant);
        try {
            activity.startActivity(chooser);
        } catch (ActivityNotFoundException | SecurityException e) {
            ConversationActions.toast(activity, "No app on this device can open this document.");
        }
        return true;
    }

    private static String mimeType(String hint, String name) {
        if (hint != null && hint.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")
                && !"application/octet-stream".equalsIgnoreCase(hint)) return hint.toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        String mime = dot < 0 ? null : MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.US));
        return mime == null ? "application/octet-stream" : mime;
    }
}
