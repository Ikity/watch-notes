package dev.watchnotes;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Joplin Export File (JEX) import.
 *
 * <p>A {@code .jex} file is a tar archive of Joplin raw items: one {@code <id>.md}
 * file per note, folder, tag and note-tag association, plus a {@code resources/}
 * directory with attachment binaries. Each {@code .md} file holds the item title on
 * the first line, the body, then a trailing {@code key: value} metadata block
 * separated by a blank line, and a {@code type_} field (1 = note, 2 = folder,
 * 4 = resource, 5 = tag, 6 = note-tag).</p>
 *
 * <p>Folders become categories (nested folders are joined with "/"), tag
 * associations become the note's tags, and small image resources are embedded as
 * data URIs so they sync to the watch like shared images. Encrypted notes cannot
 * be decrypted and trashed notes are skipped. Archive paths are never extracted
 * to the filesystem.</p>
 */
public final class JexNotes {
    private static final int MAX_ENTRIES = 3000;
    private static final int MAX_TEXT_ENTRY = 1500000;
    private static final long MAX_TEXT_TOTAL = 16L * 1024 * 1024;
    private static final int MAX_RESOURCE_KEEP = 262144;
    private static final long MAX_IMAGES_TOTAL = 16L * 1024 * 1024;
    /** Tiny images embed byte-for-byte; larger ones are downscaled on Android. */
    static final int TINY_EMBED = 32768;
    static final long TINY_BUDGET = 98304;
    private static final long MAX_BINARY_SKIP = 256L * 1024 * 1024;
    private static final long MAX_STREAM_TOTAL = 256L * 1024 * 1024;

    private JexNotes() { }

    /** Notes keep {@code :/id} references; binaries are returned for Android downsampling. */
    public static final class Import {
        public final List<Note> notes;
        public final Map<String, byte[]> images;
        Import(List<Note> notes, Map<String, byte[]> images) { this.notes = notes; this.images = images; }
    }

    public static List<Note> importJex(InputStream input) throws Exception {
        Import detailed = importJexDetailed(input);
        for (Note n : detailed.notes) {
            long embedded = 0;
            for (Map.Entry<String, byte[]> image : detailed.images.entrySet()) {
                if (!n.body.contains(":/" + image.getKey())) continue;
                byte[] bytes = image.getValue();
                if (bytes.length > TINY_EMBED) continue;
                if (embedded + bytes.length > TINY_BUDGET) continue;
                String type = imageType(bytes);
                if (type == null) continue;
                String candidate = n.body.replace(":/" + image.getKey(),
                    "data:image/" + type + ";base64," + Base64.getEncoder().encodeToString(bytes));
                if (candidate.length() <= 200000) { n.body = candidate; embedded += bytes.length; }
            }
        }
        return detailed.notes;
    }

    public static Import importJexDetailed(InputStream input) throws Exception {
        Map<String, Folder> folders = new HashMap<>();
        Map<String, String> tagTitles = new HashMap<>();
        Map<String, List<String>> noteTags = new HashMap<>();
        Map<String, String> resourceMime = new HashMap<>();
        Map<String, byte[]> images = new HashMap<>();
        List<RawNote> raws = new ArrayList<>();
        long textTotal = 0, streamed = 0, imagesTotal = 0;
        int entries = 0;
        TarReader tar = new TarReader(input);
        for (;;) {
            TarReader.Entry entry = tar.next();
            if (entry == null) break;
            if (++entries > MAX_ENTRIES) throw new IOException("Archive exceeds 3,000 entries");
            String name = entry.name;
            if (name.startsWith("/") || name.replace('\\', '/').matches("(^|.*/)\\.\\.(/.*|$)"))
                throw new IOException("Unsafe archive path");
            if (entry.dir) continue;
            boolean underResources = name.startsWith("resources/") && name.length() > "resources/".length();
            if (name.toLowerCase(Locale.ROOT).endsWith(".md") && !underResources) {
                if (entry.size > MAX_TEXT_ENTRY) throw new IOException("File exceeds import limit");
                textTotal += entry.size;
                if (textTotal > MAX_TEXT_TOTAL) throw new IOException("Import exceeds 16 MiB; split the archive");
                RawItem item = parseRaw(new String(tar.readData(), StandardCharsets.UTF_8), name);
                String id = item.props.get("id");
                if (id == null || !id.matches("(?i)[0-9a-f]{32}")) throw new IOException("Invalid item in " + name);
                int type = parseInt(item.props.get("type_"), -1);
                switch (type) {
                    case 1: raws.add(toNote(item, name)); break;
                    case 2: folders.put(id, new Folder(item.title, item.props.get("parent_id"))); break;
                    case 4: resourceMime.put(id.toLowerCase(Locale.ROOT), item.props.get("mime")); break;
                    case 5: tagTitles.put(id, item.title); break;
                    case 6: {
                        String noteId = item.props.get("note_id"), tagId = item.props.get("tag_id");
                        if (noteId != null && tagId != null) {
                            List<String> list = noteTags.get(noteId);
                            if (list == null) { list = new ArrayList<>(); noteTags.put(noteId, list); }
                            list.add(tagId);
                        }
                        break;
                    }
                    default: break;
                }
            } else if (underResources) {
                String base = name.substring("resources/".length());
                int slash = base.lastIndexOf('/');
                base = slash < 0 ? base : base.substring(slash + 1);
                String id = base.contains(".") ? base.substring(0, base.lastIndexOf('.')) : base;
                String extension = base.contains(".") ? base.substring(base.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                boolean imageExt = extension.equals("png") || extension.equals("jpg") || extension.equals("jpeg")
                    || extension.equals("gif") || extension.equals("webp");
                if (id.matches("(?i)[0-9a-f]{32}") && imageExt && entry.size <= MAX_RESOURCE_KEEP
                        && !images.containsKey(id.toLowerCase(Locale.ROOT)) && imagesTotal + entry.size <= MAX_IMAGES_TOTAL) {
                    images.put(id.toLowerCase(Locale.ROOT), tar.readData());
                    imagesTotal += entry.size;
                    streamed += entry.size;
                } else {
                    streamed += tar.skipData(MAX_BINARY_SKIP);
                }
            } else {
                streamed += tar.skipData(MAX_BINARY_SKIP);
            }
            if (streamed > MAX_STREAM_TOTAL) throw new IOException("Import exceeds 256 MiB");
        }
        if (raws.isEmpty()) throw new IOException("Archive contains no notes");
        List<Note> notes = new ArrayList<>();
        for (RawNote raw : raws) {
            if (raw.deleted || raw.encrypted) continue;
            Note n = new Note();
            n.title = truncate(raw.title.isEmpty() ? "Untitled" : raw.title, 1000);
            n.body = raw.body;
            n.category = truncate(folderPath(folders, raw.parentId), 1000);
            List<String> tags = new ArrayList<>();
            List<String> associated = noteTags.get(raw.id);
            if (associated != null) for (String tagId : associated) {
                String title = tagTitles.get(tagId);
                if (title != null && !title.trim().isEmpty()) tags.add(title.trim());
            }
            n.tags = fitTags(tags);
            n.todo = raw.todo; n.done = raw.done;
            if (raw.created > 0) n.created = raw.created;
            if (raw.updated > 0) n.updated = raw.updated;
            n.validate();
            notes.add(n);
        }
        if (notes.isEmpty()) throw new IOException("Archive contains no importable notes");
        return new Import(notes, images);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String fitTags(List<String> tags) {
        List<String> parsed = Tags.parse(String.join(",", tags));
        String joined = String.join(", ", parsed);
        while (joined.length() > 4000 && !parsed.isEmpty()) {
            parsed.remove(parsed.size() - 1);
            joined = String.join(", ", parsed);
        }
        return joined;
    }

    private static String folderPath(Map<String, Folder> folders, String folderId) {
        List<String> parts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String current = folderId;
        while (current != null && !current.isEmpty() && seen.add(current)) {
            Folder folder = folders.get(current);
            if (folder == null) break;
            if (!folder.title.trim().isEmpty()) parts.add(0, folder.title.trim());
            current = folder.parent;
        }
        return String.join("/", parts);
    }

    /** Image format by magic bytes, or null when the bytes are not a supported image. */
    static String imageType(byte[] bytes) {
        if (bytes == null) return null;
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "png";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) return "jpeg";
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "gif";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "webp";
        return null;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static long parseTime(String value) {
        if (value == null) return 0;
        value = value.trim();
        if (value.isEmpty() || value.equals("0")) return 0;
        if (value.matches("-?\\d+")) {
            try { long time = Long.parseLong(value); return time < 0 ? 0 : time; }
            catch (NumberFormatException e) { return 0; }
        }
        try { return Instant.parse(value).toEpochMilli(); }
        catch (Exception e) { return 0; }
    }

    private static final class Folder {
        final String title; final String parent;
        Folder(String title, String parent) { this.title = title == null ? "" : title; this.parent = parent == null ? "" : parent.trim(); }
    }

    private static final class RawNote {
        String id = "", title = "", body = "", parentId = "";
        boolean todo, done, deleted, encrypted;
        long created, updated;
    }

    private static RawNote toNote(RawItem item, String filename) throws IOException {
        RawNote raw = new RawNote();
        raw.id = item.props.get("id");
        raw.title = item.title;
        raw.body = item.body;
        if (raw.body.length() > 200000) throw new IOException("Note exceeds import limit: " + filename);
        raw.parentId = item.props.get("parent_id") == null ? "" : item.props.get("parent_id").trim();
        raw.todo = "1".equals(item.props.get("is_todo"));
        String completed = item.props.get("todo_completed");
        raw.done = completed != null && !completed.trim().isEmpty() && !completed.trim().equals("0");
        raw.deleted = parseTime(item.props.get("deleted_time")) > 0;
        raw.encrypted = "1".equals(item.props.get("encryption_applied"));
        raw.created = parseTime(item.props.get("user_created_time"));
        if (raw.created <= 0) raw.created = parseTime(item.props.get("created_time"));
        raw.updated = parseTime(item.props.get("user_updated_time"));
        if (raw.updated <= 0) raw.updated = parseTime(item.props.get("updated_time"));
        return raw;
    }

    static final class RawItem {
        String title = "";
        String body = "";
        final Map<String, String> props = new HashMap<>();
    }

    static RawItem parseRaw(String text, String filename) throws IOException {
        if (text.startsWith("\ufeff")) text = text.substring(1);
        text = text.replace("\r\n", "\n");
        List<String> all = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        while (!all.isEmpty() && all.get(all.size() - 1).trim().isEmpty()) all.remove(all.size() - 1);
        if (all.isEmpty()) throw new IOException("Invalid Joplin item: " + filename);
        String[] lines = all.toArray(new String[0]);
        int end = -1;
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].trim().isEmpty()) { end = i; break; }
        }
        if (end < 0) {
            // Items without title or body (such as tag links) are metadata only.
            end = 0;
            List<String> shifted = new ArrayList<>();
            shifted.add("");
            Collections.addAll(shifted, lines);
            lines = shifted.toArray(new String[0]);
        }
        RawItem item = new RawItem();
        for (int i = end + 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            int colon = lines[i].indexOf(':');
            if (colon < 0) throw new IOException("Invalid Joplin metadata in " + filename);
            String key = lines[i].substring(0, colon).trim();
            String value = lines[i].substring(colon + 1).trim().replace("\\n", "\n").replace("\\r", "\r");
            item.props.put(key, value);
        }
        if (!item.props.containsKey("type_")) throw new IOException("Invalid Joplin item (missing type): " + filename);
        item.title = lines[0];
        StringBuilder body = new StringBuilder();
        for (int i = 2; i < end; i++) {
            if (i > 2) body.append('\n');
            body.append(lines[i]);
        }
        item.body = body.toString();
        return item;
    }

    /** Minimal ustar tar reader: regular files, directories, GNU long names and pax headers. */
    static final class TarReader {
        static final class Entry {
            final String name; final long size; final boolean dir;
            Entry(String name, long size, boolean dir) { this.name = name; this.size = size; this.dir = dir; }
        }

        private final InputStream in;
        private long remaining;
        private long padding;
        private String longName;

        TarReader(InputStream in) { this.in = in; }

        Entry next() throws IOException {
            skipFully(remaining + padding);
            remaining = 0; padding = 0;
            for (;;) {
                byte[] header = new byte[512];
                int read = readPartial(header);
                if (read == 0) return null;
                if (read < 512) throw new IOException("Truncated archive");
                if (isZero(header)) return null;
                String magic = new String(header, 257, 5, StandardCharsets.US_ASCII);
                if (!magic.startsWith("ustar")) throw new IOException("Not a Joplin export (invalid tar header)");
                long stored = 0, field = 0;
                for (int i = 0; i < 512; i++) stored += header[i] & 0xFF;
                for (int i = 148; i < 156; i++) field += header[i] & 0xFF;
                long parsed = parseOctal(header, 148, 8, "checksum");
                if (stored - field + 8 * 32 != parsed) throw new IOException("Corrupt archive (bad tar checksum)");
                String name = readName(header);
                if (longName != null) { name = longName; longName = null; }
                long size = parseOctal(header, 124, 12, "size");
                if (size < 0) throw new IOException("Corrupt archive (bad entry size)");
                char type = (char) header[156];
                if (type == 'L') {
                    if (size > 4096 || size <= 0) throw new IOException("Corrupt archive (bad long name)");
                    byte[] data = new byte[(int) size];
                    readFully(data);
                    skipFully((512 - size % 512) % 512);
                    int end = 0;
                    while (end < data.length && data[end] != 0) end++;
                    longName = new String(data, 0, end, StandardCharsets.UTF_8);
                    continue;
                }
                if (type == 'x' || type == 'g') {
                    skipFully(size + (512 - size % 512) % 512);
                    continue;
                }
                if (type != '0' && type != 0 && type != '5') throw new IOException("Unsupported tar entry");
                remaining = size;
                padding = (512 - size % 512) % 512;
                return new Entry(name, size, type == '5');
            }
        }

        byte[] readData() throws IOException {
            if (remaining > Integer.MAX_VALUE) throw new IOException("File exceeds import limit");
            byte[] data = new byte[(int) remaining];
            readFully(data);
            remaining = 0;
            return data;
        }

        long skipData(long max) throws IOException {
            if (remaining > max) throw new IOException("File exceeds import limit");
            long skipped = remaining;
            skipFully(remaining);
            remaining = 0;
            return skipped;
        }

        private int readPartial(byte[] buffer) throws IOException {
            int total = 0;
            while (total < buffer.length) {
                int count = in.read(buffer, total, buffer.length - total);
                if (count == -1) break;
                total += count;
            }
            return total;
        }

        private void readFully(byte[] buffer) throws IOException {
            int total = 0;
            while (total < buffer.length) {
                int count = in.read(buffer, total, buffer.length - total);
                if (count == -1) throw new IOException("Truncated archive");
                total += count;
            }
        }

        private void skipFully(long count) throws IOException {
            byte[] buffer = new byte[8192];
            while (count > 0) {
                int step = (int) Math.min(buffer.length, count);
                int read = in.read(buffer, 0, step);
                if (read == -1) throw new IOException("Truncated archive");
                count -= read;
            }
        }

        private static boolean isZero(byte[] header) {
            for (byte b : header) if (b != 0) return false;
            return true;
        }

        private static String readName(byte[] header) {
            String prefix = readString(header, 345, 155);
            String name = readString(header, 0, 100);
            return prefix.isEmpty() ? name : prefix + "/" + name;
        }

        private static String readString(byte[] header, int offset, int length) {
            int end = offset;
            while (end < offset + length && header[end] != 0) end++;
            return new String(header, offset, end - offset, StandardCharsets.US_ASCII);
        }

        private static long parseOctal(byte[] header, int offset, int length, String field) throws IOException {
            String text = new String(header, offset, length, StandardCharsets.US_ASCII).trim().replace("\0", "").trim();
            if (text.isEmpty()) return 0;
            try { return Long.parseLong(text, 8); }
            catch (NumberFormatException e) { throw new IOException("Corrupt archive (bad tar " + field + ")"); }
        }
    }
}
