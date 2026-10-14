package com.holki.auraleaf;

public abstract class LibraryItem {
    public static final int TYPE_BOOK = 1;
    public static final int TYPE_GROUP = 2;

    public abstract int getType();
    public abstract long getId();
    public abstract String getTitle();
}
