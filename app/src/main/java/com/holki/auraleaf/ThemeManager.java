package com.holki.auraleaf;

import android.content.Context;
import android.content.SharedPreferences;

public class ThemeManager {

    public static final int THEME_STUDIO = 0;
    public static final int THEME_SEPIA = 1;
    public static final int THEME_OLED = 2;
    public static final int THEME_FOREST = 3;

    public static class ReaderTheme {
        public final int id;
        public final String title;
        public final int pageBgColor;
        public final int textColor;
        public final int headerFooterColor;
        public final int backdropColor;
        public final int ambientGlowColor; // 0 if none
        public final boolean isDark;

        public ReaderTheme(int id, String title, int pageBgColor, int textColor,
                           int headerFooterColor, int backdropColor, int ambientGlowColor, boolean isDark) {
            this.id = id;
            this.title = title;
            this.pageBgColor = pageBgColor;
            this.textColor = textColor;
            this.headerFooterColor = headerFooterColor;
            this.backdropColor = backdropColor;
            this.ambientGlowColor = ambientGlowColor;
            this.isDark = isDark;
        }
    }

    private static final ReaderTheme[] THEMES = new ReaderTheme[]{
            new ReaderTheme(THEME_STUDIO, "Студия", 0xFFFBFBFA, 0xFF1E293B, 0xFF94A3B8, 0xFF0A0C10, 0, false),
            new ReaderTheme(THEME_SEPIA, "Пергамент", 0xFFF4ECD8, 0xFF3D312A, 0xFF967E6B, 0xFF18130E, 0x40D97706, false),
            new ReaderTheme(THEME_OLED, "Полночь OLED", 0xFF111418, 0xFFD1D5DB, 0xFF64748B, 0xFF000000, 0, true),
            new ReaderTheme(THEME_FOREST, "Хвоя", 0xFF0E1A15, 0xFFC8E6C9, 0xFF5C8A74, 0xFF08120E, 0x3510B981, true)
    };

    public static ReaderTheme getTheme(int id) {
        if (id < 0 || id >= THEMES.length) return THEMES[0];
        return THEMES[id];
    }

    public static ReaderTheme getCurrentTheme(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("auraleaf_prefs", Context.MODE_PRIVATE);
        int id = prefs.getInt("reader_theme", THEME_STUDIO);
        return getTheme(id);
    }

    public static void setCurrentTheme(Context context, int themeId) {
        SharedPreferences prefs = context.getSharedPreferences("auraleaf_prefs", Context.MODE_PRIVATE);
        prefs.edit().putInt("reader_theme", themeId).apply();
    }
}
