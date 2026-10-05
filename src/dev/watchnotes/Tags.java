package dev.watchnotes;

import java.util.*;

/** Pure-Java tag parsing, search filtering and Markdown-import duplicate detection. */
public final class Tags {
    private Tags() { }

    public static List<String> parse(String tags) {
        List<String> result = new ArrayList<>();
        if (tags == null) return result;
        Set<String> seen = new HashSet<>();
        for (String part : tags.split(",")) {
            String tag = part.trim();
            if (tag.isEmpty()) continue;
            String key = tag.toLowerCase(Locale.ROOT);
            if (seen.contains(key)) continue;
            seen.add(key);
            result.add(tag);
        }
        return result;
    }

    public static String format(Collection<String> tags) {
        return String.join(", ", parse(String.join(",", tags)));
    }

    public static Set<String> collect(List<Note> notes) {
        Set<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Note n : notes) {
            if (n.deleted) continue;
            result.addAll(parse(n.tags));
        }
        return result;
    }

    public static List<String> filter(Collection<String> tags, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String tag : tags) {
            if (q.isEmpty() || tag.toLowerCase(Locale.ROOT).contains(q)) result.add(tag);
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    static String normalized(String tags) {
        List<String> parsed = parse(tags);
        List<String> keys = new ArrayList<>();
        for (String tag : parsed) keys.add(tag.toLowerCase(Locale.ROOT));
        Collections.sort(keys);
        return String.join(",", keys);
    }

    public static String fingerprint(Note n) {
        String title = n.title == null ? "" : n.title.trim();
        String body = n.body == null ? "" : n.body;
        String category = n.category == null ? "" : n.category.trim();
        return title + "\n" + body + "\n" + category + "\n" + normalized(n.tags)
            + "\n" + (n.todo ? "1" : "0") + (n.done ? "1" : "0");
    }

    /** Return only incoming notes whose content is absent from existing active notes. */
    public static List<Note> filterNew(List<Note> existing, List<Note> incoming) {
        Set<String> known = new HashSet<>();
        for (Note n : existing) {
            if (n.deleted) continue;
            known.add(fingerprint(n));
        }
        List<Note> result = new ArrayList<>();
        for (Note n : incoming) {
            String key = fingerprint(n);
            if (known.contains(key)) continue;
            known.add(key);
            result.add(n);
        }
        return result;
    }

    public static String union(String current, Collection<String> selected) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        for (String tag : parse(current)) merged.put(tag.toLowerCase(Locale.ROOT), tag);
        for (String tag : parse(String.join(",", selected))) {
            String key = tag.toLowerCase(Locale.ROOT);
            if (!merged.containsKey(key)) merged.put(key, tag);
        }
        return String.join(", ", merged.values());
    }
}
