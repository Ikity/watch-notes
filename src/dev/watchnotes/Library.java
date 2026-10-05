package dev.watchnotes;

/** Pure-Java library helpers: category filtering and page navigation. */
public final class Library {
    public static final int PAGE_SIZE = 20;
    public static final String UNCATEGORIZED = "Uncategorized";
    private Library() { }

    public static boolean categoryMatches(String category, String filter) {
        if (filter == null || filter.isEmpty()) return true;
        if (UNCATEGORIZED.equals(filter)) return category == null || category.isEmpty();
        return filter.equals(category);
    }

    public static int pageCount(int total) {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    public static String pageLabel(int page, int total) {
        int pages = pageCount(total);
        int current = Math.min(Math.max(page, 0), pages - 1) + 1;
        return "Page " + current + " of " + pages;
    }

    public static String pageItem(int index, int total) {
        int from = index * PAGE_SIZE + 1;
        int to = Math.min(total, (index + 1) * PAGE_SIZE);
        return "Page " + (index + 1) + " (" + from + "–" + to + " of " + total + ")";
    }
}
