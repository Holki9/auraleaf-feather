package com.holki.auraleaf;

import java.util.ArrayList;
import java.util.List;

public class GroupItem extends LibraryItem {
    public long id;
    public String title;
    public int bookCount;
    public List<String> coverPaths = new ArrayList<>();
    public long createdAt;

    public GroupItem(long id, String title, int bookCount, List<String> coverPaths, long createdAt) {
        this.id = id;
        this.title = title;
        this.bookCount = bookCount;
        if (coverPaths != null) {
            this.coverPaths = coverPaths;
        }
        this.createdAt = createdAt;
    }

    @Override
    public int getType() {
        return TYPE_GROUP;
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
