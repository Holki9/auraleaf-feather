package com.holki.auraleaf;

public class BookItem extends LibraryItem {
    public long id;
    public String title;
    public String uriString;
    public int totalPages;
    public int lastPage;
    public String coverPath;
    public long updatedAt;
    public long groupId; // 0 = root, >0 = inside group

    public BookItem(long id, String title, String uriString, int totalPages, int lastPage, String coverPath, long updatedAt) {
        this(id, title, uriString, totalPages, lastPage, coverPath, updatedAt, 0);
    }

    public BookItem(long id, String title, String uriString, int totalPages, int lastPage, String coverPath, long updatedAt, long groupId) {
        this.id = id;
        this.title = title;
        this.uriString = uriString;
        this.totalPages = totalPages;
        this.lastPage = lastPage;
        this.coverPath = coverPath;
        this.updatedAt = updatedAt;
        this.groupId = groupId;
    }

    @Override
    public int getType() {
        return TYPE_BOOK;
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public String getTitle() {
        return title;
    }
}
