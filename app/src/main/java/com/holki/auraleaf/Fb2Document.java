package com.holki.auraleaf;

import android.content.Context;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Fb2Document extends TextBookDocument {

    public Fb2Document(Context context, File file) throws Exception {
        super(context);
        this.bookTitle = file.getName().replaceFirst("[.][^.]+$", "");
        loadFb2(file);
    }

    private void loadFb2(File file) throws Exception {
        if (file.getName().toLowerCase().endsWith(".zip")) {
            try (ZipFile zf = new ZipFile(file)) {
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.getName().toLowerCase().endsWith(".fb2")) {
                        try (InputStream is = zf.getInputStream(ze)) {
                            parseXml(is);
                            return;
                        }
                    }
                }
            }
        }

        try (InputStream is = new FileInputStream(file)) {
            parseXml(is);
        }
    }

    private void parseXml(InputStream is) throws Exception {
        chapters.clear();
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(is, "UTF-8");

        Chapter currentChapter = new Chapter(bookTitle);
        chapters.add(currentChapter);

        boolean insideBody = false;
        boolean insideTitle = false;
        boolean insideDescription = false;
        boolean insideBookTitle = false;
        StringBuilder currentText = new StringBuilder();

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            String tagName = parser.getName();

            if (eventType == XmlPullParser.START_TAG) {
                if ("description".equalsIgnoreCase(tagName)) {
                    insideDescription = true;
                } else if ("book-title".equalsIgnoreCase(tagName) && insideDescription) {
                    insideBookTitle = true;
                    currentText.setLength(0);
                } else if ("body".equalsIgnoreCase(tagName)) {
                    insideBody = true;
                } else if ("section".equalsIgnoreCase(tagName) && insideBody) {
                    currentChapter = new Chapter(bookTitle);
                    chapters.add(currentChapter);
                } else if ("title".equalsIgnoreCase(tagName) && insideBody) {
                    insideTitle = true;
                    currentText.setLength(0);
                } else if ("p".equalsIgnoreCase(tagName)) {
                    currentText.setLength(0);
                }
            } else if (eventType == XmlPullParser.TEXT) {
                currentText.append(parser.getText());
            } else if (eventType == XmlPullParser.END_TAG) {
                if ("book-title".equalsIgnoreCase(tagName) && insideDescription) {
                    String titleStr = currentText.toString().trim();
                    if (!titleStr.isEmpty()) {
                        this.bookTitle = titleStr;
                    }
                    insideBookTitle = false;
                } else if ("description".equalsIgnoreCase(tagName)) {
                    insideDescription = false;
                } else if ("title".equalsIgnoreCase(tagName) && insideBody) {
                    String t = currentText.toString().trim();
                    if (!t.isEmpty()) {
                        currentChapter.title = t;
                    }
                    insideTitle = false;
                } else if ("p".equalsIgnoreCase(tagName) && insideBody) {
                    String pText = currentText.toString().trim();
                    if (!pText.isEmpty()) {
                        if (insideTitle) {
                            currentChapter.title = pText;
                        } else {
                            currentChapter.paragraphs.add(pText);
                        }
                    }
                } else if ("body".equalsIgnoreCase(tagName)) {
                    insideBody = false;
                }
            }
            eventType = parser.next();
        }

        paginate();
    }
}
