package com.holki.auraleaf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Base64;
import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CoverExtractor {

    public static class ComicInfo {
        public final int totalPages;
        public final String coverPath;

        public ComicInfo(int totalPages, String coverPath) {
            this.totalPages = totalPages;
            this.coverPath = coverPath;
        }
    }

    public static ComicInfo extract(Context context, Uri uri, String fileName) throws Exception {
        String lower = (fileName != null ? fileName : "").toLowerCase();
        File coversDir = new File(context.getFilesDir(), "covers");
        coversDir.mkdirs();
        File coverFile = new File(coversDir, "cover_" + System.currentTimeMillis() + ".jpg");

        if (lower.endsWith(".cbz") || lower.endsWith(".zip") && !lower.endsWith(".fb2.zip")) {
            File tempFile = getFileFromUri(context, uri, "temp_cover.cbz");
            try (ZipFile zip = new ZipFile(tempFile)) {
                List<String> names = new ArrayList<>();
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.isDirectory()) continue;
                    String zn = ze.getName();
                    String zl = zn.toLowerCase();
                    if (zl.contains("__macosx") || zl.startsWith(".") || zl.contains("/.")) continue;
                    if (zl.endsWith(".jpg") || zl.endsWith(".jpeg") || zl.endsWith(".png") || zl.endsWith(".webp") || zl.endsWith(".bmp")) {
                        names.add(zn);
                    }
                }
                Collections.sort(names, new NaturalOrderComparator());
                if (!names.isEmpty()) {
                    ZipEntry first = zip.getEntry(names.get(0));
                    try (InputStream is = zip.getInputStream(first);
                         FileOutputStream fos = new FileOutputStream(coverFile)) {
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        if (bmp != null) {
                            saveScaledBitmap(bmp, coverFile);
                            bmp.recycle();
                        }
                    }
                }
                return new ComicInfo(names.size(), coverFile.getAbsolutePath());
            }
        } else if (lower.endsWith(".cbr") || lower.endsWith(".rar")) {
            File tempFile = getFileFromUri(context, uri, "temp_cover.cbr");
            try (Archive arch = new Archive(tempFile)) {
                List<FileHeader> headers = new ArrayList<>();
                for (FileHeader fh : arch.getFileHeaders()) {
                    if (fh.isDirectory()) continue;
                    String fn = fh.getFileName().toLowerCase();
                    if (fn.contains("__macosx") || fn.startsWith(".") || fn.contains("/.")) continue;
                    if (fn.endsWith(".jpg") || fn.endsWith(".jpeg") || fn.endsWith(".png") || fn.endsWith(".webp") || fn.endsWith(".bmp")) {
                        headers.add(fh);
                    }
                }
                NaturalOrderComparator comp = new NaturalOrderComparator();
                Collections.sort(headers, (h1, h2) -> comp.compare(h1.getFileName(), h2.getFileName()));
                if (!headers.isEmpty()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    arch.extractFile(headers.get(0), baos);
                    byte[] bytes = baos.toByteArray();
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) {
                        saveScaledBitmap(bmp, coverFile);
                        bmp.recycle();
                    }
                }
                return new ComicInfo(headers.size(), coverFile.getAbsolutePath());
            }
        } else if (lower.endsWith(".epub")) {
            File tempFile = getFileFromUri(context, uri, "temp_cover.epub");
            return extractEpub(tempFile, coverFile, fileName);
        } else if (lower.endsWith(".fb2") || lower.endsWith(".fb2.zip")) {
            File tempFile = getFileFromUri(context, uri, "temp_cover.fb2");
            return extractFb2(tempFile, coverFile, fileName);
        } else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
            File tempFile = getFileFromUri(context, uri, "temp_cover.png");
            Bitmap bmp = BitmapFactory.decodeFile(tempFile.getAbsolutePath());
            if (bmp != null) {
                saveScaledBitmap(bmp, coverFile);
                bmp.recycle();
            }
            return new ComicInfo(1, coverFile.getAbsolutePath());
        } else if (lower.endsWith(".txt")) {
            File tempFile = getFileFromUri(context, uri, "temp_cover.txt");
            TxtDocument doc = new TxtDocument(context, tempFile);
            generateTypographicCover(coverFile, doc.bookTitle, "TXT");
            return new ComicInfo(doc.getPageCount(), coverFile.getAbsolutePath());
        } else {
            // Default: PDF
            ParcelFileDescriptor pfd = null;
            try {
                if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                    pfd = ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
                } else {
                    pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                }
            } catch (Exception e) {
                File temp = new File(context.getCacheDir(), "import_temp.pdf");
                try (InputStream in = context.getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[8192];
                    int r;
                    while ((r = in.read(buffer)) != -1) out.write(buffer, 0, r);
                }
                pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
            }

            if (pfd != null) {
                PdfRenderer renderer = new PdfRenderer(pfd);
                int total = renderer.getPageCount();
                if (total > 0) {
                    PdfRenderer.Page page0 = renderer.openPage(0);
                    int tw = 320;
                    int th = Math.max(10, (int) (tw * ((float) page0.getHeight() / page0.getWidth())));
                    Bitmap coverBmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(coverBmp);
                    canvas.drawColor(Color.WHITE);
                    page0.render(coverBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page0.close();

                    try (FileOutputStream fos = new FileOutputStream(coverFile)) {
                        coverBmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                    }
                    coverBmp.recycle();
                }
                renderer.close();
                pfd.close();
                return new ComicInfo(total, coverFile.getAbsolutePath());
            }
            return new ComicInfo(0, coverFile.getAbsolutePath());
        }
    }

    private static ComicInfo extractEpub(File epubFile, File coverFile, String fileName) {
        String fallbackTitle = fileName != null ? fileName.replaceFirst("[.][^.]+$", "") : "EPUB Книга";
        try (ZipFile zip = new ZipFile(epubFile)) {
            // Look for OPF manifest
            String opfPath = null;
            ZipEntry container = zip.getEntry("META-INF/container.xml");
            if (container != null) {
                try (InputStream is = zip.getInputStream(container)) {
                    XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                    factory.setNamespaceAware(false);
                    XmlPullParser parser = factory.newPullParser();
                    parser.setInput(is, "UTF-8");
                    int event = parser.getEventType();
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG && "rootfile".equalsIgnoreCase(parser.getName())) {
                            opfPath = parser.getAttributeValue(null, "full-path");
                            break;
                        }
                        event = parser.next();
                    }
                }
            }

            String coverHref = null;
            String opfDir = "";
            if (opfPath != null) {
                int slash = opfPath.lastIndexOf('/');
                if (slash != -1) opfDir = opfPath.substring(0, slash + 1);

                ZipEntry opfEntry = zip.getEntry(opfPath);
                if (opfEntry != null) {
                    try (InputStream is = zip.getInputStream(opfEntry)) {
                        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                        factory.setNamespaceAware(false);
                        XmlPullParser parser = factory.newPullParser();
                        parser.setInput(is, "UTF-8");
                        int event = parser.getEventType();
                        while (event != XmlPullParser.END_DOCUMENT) {
                            if (event == XmlPullParser.START_TAG) {
                                String name = parser.getName();
                                if ("item".equalsIgnoreCase(name)) {
                                    String id = parser.getAttributeValue(null, "id");
                                    String href = parser.getAttributeValue(null, "href");
                                    String props = parser.getAttributeValue(null, "properties");
                                    if ("cover-image".equalsIgnoreCase(props) || (id != null && id.toLowerCase().contains("cover") && href != null && isImageFile(href))) {
                                        coverHref = href;
                                        break;
                                    }
                                }
                            }
                            event = parser.next();
                        }
                    }
                }
            }

            // If not found in OPF, search zip for any cover image file
            if (coverHref == null) {
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    String l = ze.getName().toLowerCase();
                    if (l.contains("cover") && isImageFile(l)) {
                        coverHref = ze.getName();
                        opfDir = "";
                        break;
                    }
                }
            }

            if (coverHref != null) {
                ZipEntry coverEntry = zip.getEntry(opfDir + coverHref);
                if (coverEntry == null) coverEntry = zip.getEntry(coverHref);
                if (coverEntry != null) {
                    try (InputStream is = zip.getInputStream(coverEntry)) {
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        if (bmp != null) {
                            saveScaledBitmap(bmp, coverFile);
                            bmp.recycle();
                            return new ComicInfo(1, coverFile.getAbsolutePath());
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Fallback: generate typographic cover
        generateTypographicCover(coverFile, fallbackTitle, "EPUB");
        return new ComicInfo(1, coverFile.getAbsolutePath());
    }

    private static ComicInfo extractFb2(File fb2File, File coverFile, String fileName) {
        String fallbackTitle = fileName != null ? fileName.replaceFirst("[.][^.]+$", "") : "FB2 Книга";
        try {
            InputStream is = null;
            ZipFile zf = null;
            if (fb2File.getName().toLowerCase().endsWith(".zip")) {
                zf = new ZipFile(fb2File);
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.getName().toLowerCase().endsWith(".fb2")) {
                        is = zf.getInputStream(ze);
                        break;
                    }
                }
            } else {
                is = new FileInputStream(fb2File);
            }

            if (is != null) {
                XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                factory.setNamespaceAware(false);
                XmlPullParser parser = factory.newPullParser();
                parser.setInput(is, "UTF-8");

                boolean insideBinary = false;
                StringBuilder base64Data = new StringBuilder();

                int event = parser.getEventType();
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        String name = parser.getName();
                        if ("binary".equalsIgnoreCase(name)) {
                            String id = parser.getAttributeValue(null, "id");
                            if (id != null && (id.toLowerCase().contains("cover") || base64Data.length() == 0)) {
                                insideBinary = true;
                                base64Data.setLength(0);
                            }
                        }
                    } else if (event == XmlPullParser.TEXT && insideBinary) {
                        base64Data.append(parser.getText());
                    } else if (event == XmlPullParser.END_TAG) {
                        if ("binary".equalsIgnoreCase(parser.getName()) && insideBinary) {
                            insideBinary = false;
                            String b64 = base64Data.toString().replaceAll("\\s+", "");
                            if (!b64.isEmpty()) {
                                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                                if (bmp != null) {
                                    saveScaledBitmap(bmp, coverFile);
                                    bmp.recycle();
                                    is.close();
                                    if (zf != null) zf.close();
                                    return new ComicInfo(1, coverFile.getAbsolutePath());
                                }
                            }
                        }
                    }
                    event = parser.next();
                }
                is.close();
            }
            if (zf != null) zf.close();
        } catch (Exception ignored) {}

        generateTypographicCover(coverFile, fallbackTitle, "FB2");
        return new ComicInfo(1, coverFile.getAbsolutePath());
    }

    private static boolean isImageFile(String path) {
        String p = path.toLowerCase();
        return p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") || p.endsWith(".webp");
    }

    private static void saveScaledBitmap(Bitmap bmp, File targetFile) throws Exception {
        int tw = 320;
        int th = Math.max(10, Math.round(tw * ((float) bmp.getHeight() / bmp.getWidth())));
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, tw, th, true);
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos);
        }
        if (scaled != bmp) scaled.recycle();
    }

    public static void generateTypographicCover(File targetFile, String title, String formatBadge) {
        int w = 320;
        int h = 480;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // Rich dark gradient background
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setShader(new LinearGradient(0, 0, w, h, 0xFF1E293B, 0xFF0F172A, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, bgPaint);

        // Subtle inset luxury border
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(0x4038BDF8); // Subtle blue/cyan glow
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2.5f);
        canvas.drawRoundRect(new RectF(12, 12, w - 12, h - 12), 10, 10, borderPaint);

        // Format badge pill (Top Center)
        Paint pillBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        pillBg.setColor(0x3360A5FA);
        RectF pillRect = new RectF(w / 2f - 40, 24, w / 2f + 40, 52);
        canvas.drawRoundRect(pillRect, 14, 14, pillBg);

        Paint pillTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pillTextPaint.setColor(0xFF93C5FD);
        pillTextPaint.setTextSize(14f);
        pillTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        pillTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(formatBadge != null ? formatBadge : "BOOK", w / 2f, 43, pillTextPaint);

        // Book title (Centered, wrapped)
        TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(0xFFF8FAFC);
        titlePaint.setTextSize(26f);
        titlePaint.setTypeface(Typeface.create("serif", Typeface.BOLD));

        String cleanTitle = title != null ? title : "Книга";
        int textW = w - 48;
        StaticLayout layout = StaticLayout.Builder.obtain(
                cleanTitle, 0, cleanTitle.length(), titlePaint, textW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.2f)
                .build();

        int textH = layout.getHeight();
        int topY = Math.max(70, (h - textH) / 2);
        canvas.save();
        canvas.translate(24, topY);
        layout.draw(canvas);
        canvas.restore();

        // AuraLeaf Edition footer
        Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        footerPaint.setColor(0xFF64748B);
        footerPaint.setTextSize(12f);
        footerPaint.setTextAlign(Paint.Align.CENTER);
        footerPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        canvas.drawText("AuraLeaf Edition", w / 2f, h - 26, footerPaint);

        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
        } catch (Exception ignored) {}
        bmp.recycle();
    }

    public static File getFileFromUri(Context context, Uri uri, String fallbackName) throws Exception {
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            if (f.exists()) return f;
        }
        File temp = new File(context.getCacheDir(), fallbackName);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }
        return temp;
    }
}
