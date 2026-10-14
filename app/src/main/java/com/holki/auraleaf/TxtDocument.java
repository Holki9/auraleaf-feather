package com.holki.auraleaf;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class TxtDocument extends TextBookDocument {

    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "^(?:глава|часть|том|книга|пролог|эпилог|act|chapter|part|volume|book|epilogue|prologue)\\s+[0-9ivxlc]+.*$",
            Pattern.CASE_INSENSITIVE
    );

    public TxtDocument(Context context, File file) throws Exception {
        super(context);
        this.bookTitle = file.getName().replaceFirst("[.][^.]+$", "");
        loadText(file);
    }

    private void loadText(File file) throws Exception {
        chapters.clear();
        Chapter currentChapter = new Chapter(bookTitle);
        chapters.add(currentChapter);

        StringBuilder paraBuilder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    if (paraBuilder.length() > 0) {
                        currentChapter.paragraphs.add(paraBuilder.toString());
                        paraBuilder = new StringBuilder();
                    }
                    continue;
                }

                if (CHAPTER_PATTERN.matcher(trimmed).matches()) {
                    if (paraBuilder.length() > 0) {
                        currentChapter.paragraphs.add(paraBuilder.toString());
                        paraBuilder = new StringBuilder();
                    }
                    currentChapter = new Chapter(trimmed);
                    chapters.add(currentChapter);
                    continue;
                }

                if (paraBuilder.length() > 0) {
                    paraBuilder.append(" ");
                }
                paraBuilder.append(trimmed);
            }
        }

        if (paraBuilder.length() > 0) {
            currentChapter.paragraphs.add(paraBuilder.toString());
        }

        paginate();
    }
}
