package dev.watchnotes;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embed shared/imported images as note-friendly data URIs.
 *
 * Large images are downscaled to at most 1024 pixels and recompressed as JPEG so
 * note bodies stay small enough for the editor, sync and preview. Tiny images
 * embed byte-for-byte, preserving formats such as GIF animation.
 */
final class ImageDownscale {
    static final int MAX_DIMENSION = 1024;
    static final int TINY_BYTES = 32768;
    static final int MAX_OUTPUT = 120000;
    /** Total embedded images per note stay well under the 200,000-character body limit. */
    static final int BODY_BUDGET = 160000;
    private static final Pattern REFERENCE = Pattern.compile(":/[0-9a-fA-F]{32}");

    private ImageDownscale() { }

    /** Byte-for-byte embed for tiny images, or null when unsuitable. */
    static String original(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > TINY_BYTES) return null;
        String type = JexNotes.imageType(bytes);
        if (type == null) return null;
        String uri = "data:image/" + type + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
        return uri.length() <= BODY_BUDGET ? uri : null;
    }

    /** Downscaled JPEG embed for larger images, or null when unsuitable. */
    static String downscaled(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 8 * 1024 * 1024) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            if ((long) bounds.outWidth * bounds.outHeight > 16L * 1024 * 1024) return null;
            int sample = 1;
            while ((bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION)
                    && sample < 256) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (bitmap == null) return null;
            Bitmap scaled = bitmap;
            try {
                int longer = Math.max(bitmap.getWidth(), bitmap.getHeight());
                if (longer > MAX_DIMENSION) {
                    int targetWidth = Math.max(1, bitmap.getWidth() * MAX_DIMENSION / longer);
                    int targetHeight = Math.max(1, bitmap.getHeight() * MAX_DIMENSION / longer);
                    scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
                    if (scaled == null) return null;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)) return null;
                byte[] jpeg = out.toByteArray();
                if (jpeg.length == 0 || jpeg.length > MAX_OUTPUT) return null;
                return "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP);
            } finally {
                if (scaled != bitmap) scaled.recycle();
                bitmap.recycle();
            }
        } catch (Throwable ignored) { return null; }
    }

    /** Replace resource references with embedded images while the body stays within budget. */
    static String embedAll(String body, java.util.Map<String, byte[]> images) {
        if (body == null || images == null || images.isEmpty()) return body;
        java.util.Set<String> seen = new java.util.HashSet<>();
        Matcher refs = REFERENCE.matcher(body);
        while (refs.find()) seen.add(refs.group().substring(2).toLowerCase(java.util.Locale.ROOT));
        for (String id : seen) {
            byte[] bytes = images.get(id);
            if (bytes == null) continue;
            String uri = original(bytes);
            if (uri == null) uri = downscaled(bytes);
            if (uri == null) continue;
            String candidate = body.replace(":/" + id, uri);
            if (candidate.length() <= BODY_BUDGET) body = candidate;
        }
        return body;
    }
}
