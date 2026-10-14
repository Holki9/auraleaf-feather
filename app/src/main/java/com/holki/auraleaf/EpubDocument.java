package com.holki.auraleaf;

import android.content.Context;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class EpubDocument extends TextBookDocument {

    public EpubDocument(Context context, File file) throws Exception {
        super(context);
        this.bookTitle = file.getName().replaceFirst("[.][^.]+$", "");
        loadEpub(file);
    }

    private void loadEpub(File file) throws Exception {
        chapters.clear();
        try (ZipFile zip = new ZipFile(file)) {
            // 1. Locate OPF file path from META-INF/container.xml
            String opfPath = findOpfPath(zip);
            if (opfPath == null) {
                opfPath = "content.opf";
            }

            ZipEntry opfEntry = zip.getEntry(opfPath);
            if (opfEntry == null) {
                // Try case-insensitive search
                java.util.Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.getName().toLowerCase().endsWith(".opf")) {
                        opfEntry = ze;
                        opfPath = ze.getName();
                        break;
                    }
                }
            }

            if (opfEntry == null) {
                throw new Exception("Не найден файл манифеста .opf в EPUB");
            }

            String opfDir = "";
            int slashIdx = opfPath.lastIndexOf('/');
            if (slashIdx != -1) {
                opfDir = opfPath.substring(0, slashIdx + 1);
            }

            // 2. Parse OPF to get manifest item mapping and spine order
            Map<String, String> idToHref = new HashMap<>();
            List<String> spineIds = new ArrayList<>();

            try (InputStream is = zip.getInputStream(opfEntry)) {
                XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                factory.setNamespaceAware(false);
                XmlPullParser parser = factory.newPullParser();
                parser.setInput(is, "UTF-8");

                boolean inSpine = false;
                boolean inMetadata = false;
                boolean inDcTitle = false;
                StringBuilder textBuf = new StringBuilder();

                int eventType = parser.getEventType();
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    String tagName = parser.getName();
                    if (eventType == XmlPullParser.START_TAG) {
                        if ("metadata".equalsIgnoreCase(tagName)) {
                            inMetadata = true;
                        } else if ("title".equalsIgnoreCase(tagName) && inMetadata) {
                            inDcTitle = true;
                            textBuf.setLength(0);
                        } else if ("item".equalsIgnoreCase(tagName)) {
                            String id = parser.getAttributeValue(null, "id");
                            String href = parser.getAttributeValue(null, "href");
                            if (id != null && href != null) {
                                idToHref.put(id, href);
                            }
                        } else if ("spine".equalsIgnoreCase(tagName)) {
                            inSpine = true;
                        } else if ("itemref".equalsIgnoreCase(tagName) && inSpine) {
                            String idref = parser.getAttributeValue(null, "idref");
                            if (idref != null) {
                                spineIds.add(idref);
                            }
                        }
                    } else if (eventType == XmlPullParser.TEXT) {
                        if (inDcTitle) {
                            textBuf.append(parser.getText());
                        }
                    } else if (eventType == XmlPullParser.END_TAG) {
                        if ("title".equalsIgnoreCase(tagName) && inDcTitle) {
                            String t = textBuf.toString().trim();
                            if (!t.isEmpty()) {
                                this.bookTitle = t;
                            }
                            inDcTitle = false;
                        } else if ("metadata".equalsIgnoreCase(tagName)) {
                            inMetadata = false;
                        } else if ("spine".equalsIgnoreCase(tagName)) {
                            inSpine = false;
                        }
                    }
                    eventType = parser.next();
                }
            }

            // 3. Extract text from each chapter in spine order
            for (String spineId : spineIds) {
                String href = idToHref.get(spineId);
                if (href == null) continue;

                // Remove anchor #...
                int hashIdx = href.indexOf('#');
                if (hashIdx != -1) href = href.substring(0, hashIdx);

                String fullEntryPath = opfDir + href;
                ZipEntry chapterEntry = zip.getEntry(fullEntryPath);
                if (chapterEntry == null) {
                    chapterEntry = zip.getEntry(href);
                }
                if (chapterEntry == null) continue;

                try (InputStream is = zip.getInputStream(chapterEntry)) {
                    String html = readStream(is);
                    extractChapterContent(html);
                }
            }
        }

        paginate();
    }

    private String findOpfPath(ZipFile zip) {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container == null) return null;
        try (InputStream is = zip.getInputStream(container)) {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(is, "UTF-8");
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && "rootfile".equalsIgnoreCase(parser.getName())) {
                    return parser.getAttributeValue(null, "full-path");
                }
                eventType = parser.next();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String readStream(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private static final Pattern TITLE_TAG_PATTERN = Pattern.compile("<(?:h[1-3]|title)[^>]*>(.*?)</(?:h[1-3]|title)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern P_TAG_PATTERN = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STRIP_TAGS_PATTERN = Pattern.compile("<[^>]+>");

    private void extractChapterContent(String html) {
        if (html == null || html.isEmpty()) return;

        // Try extracting chapter title
        String detectedTitle = bookTitle;
        Matcher titleMatcher = TITLE_TAG_PATTERN.matcher(html);
        if (titleMatcher.find()) {
            String rawTitle = titleMatcher.group(1);
            detectedTitle = cleanHtml(rawTitle);
        }

        Chapter chapter = new Chapter(detectedTitle);

        Matcher pMatcher = P_TAG_PATTERN.matcher(html);
        boolean foundParagraphs = false;
        while (pMatcher.find()) {
            String rawP = pMatcher.group(1);
            String clean = cleanHtml(rawP);
            if (!clean.isEmpty()) {
                chapter.paragraphs.add(clean);
                foundParagraphs = true;
            }
        }

        // Fallback if no <p> tags found (e.g. <div> or raw text with <br>)
        if (!foundParagraphs) {
            String cleanBody = cleanHtml(html);
            String[] lines = cleanBody.split("\n+");
            for (String line : lines) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    chapter.paragraphs.add(t);
                }
            }
        }

        if (!chapter.paragraphs.isEmpty()) {
            chapters.add(chapter);
        }
    }

    private String cleanHtml(String str) {
        if (str == null) return "";
        String s = STRIP_TAGS_PATTERN.matcher(str).replaceAll("");
        s = s.replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#160;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }
}
