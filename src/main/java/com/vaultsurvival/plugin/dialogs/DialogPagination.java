package com.vaultsurvival.plugin.dialogs;
import java.util.List;
public record DialogPagination<T>(List<T> entries, int page, int pageSize, int total) {
    public DialogPagination { entries = List.copyOf(entries); page = Math.max(1, page); pageSize = Math.max(1, pageSize); }
    public int pages() { return Math.max(1, (total + pageSize - 1) / pageSize); }
    public boolean hasPrevious() { return page > 1; }
    public boolean hasNext() { return page < pages(); }
}
