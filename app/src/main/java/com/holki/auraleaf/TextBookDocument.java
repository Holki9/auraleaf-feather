package com.holki.auraleaf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.util.ArrayList;
import java.util.List;

public abstract class TextBookDocument implements ComicDocument {

    public static class Chapter {
        public String title;
        public List<String> paragraphs = new ArrayList<>();

        public Chapter(String title) {
            this.title = title != null ? title.trim() : "";
        }
    }

    public static class PageData {
        public String chapterTitle;
        public String text;
        public int pageIndex;
    }

    protected Context context;
    protected String bookTitle = "";
    protected String bookAuthor = "";
    protected List<Chapter> chapters = new ArrayList<>();
    protected List<PageData> pages = new ArrayList<>();

    protected static final int BASE_WIDTH = 1080;
    protected static final int BASE_HEIGHT = 1620;
    protected static final int MARGIN_HORIZ = 76;
    protected static final int MARGIN_TOP = 110;
    protected static final int MARGIN_BOTTOM = 90;

    public TextBookDocument(Context context) {
        this.context = context;
    }

    @Override
    public int getPageCount() {
        return Math.max(1, pages.size());
    }

    @Override
    public float getPageAspectRatio(int pageIndex) {
        return (float) BASE_HEIGHT / (float) BASE_WIDTH; // 1.5
    }

    /**
     * Paginates collected chapters into pages using Android StaticLayout measurement
     */
    protected void paginate() {
        pages.clear();
        int contentWidth = BASE_WIDTH - 2 * MARGIN_HORIZ;
        int maxContentHeight = BASE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM;

        TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setTextSize(38f);
        bodyPaint.setTypeface(Typeface.create("serif", Typeface.NORMAL));

        for (Chapter ch : chapters) {
            String chTitle = ch.title;
            StringBuilder pageBuilder = new StringBuilder();
            int currentHeight = 0;

            for (String para : ch.paragraphs) {
                if (para == null || para.trim().isEmpty()) continue;
                String formattedPara = "    " + para.trim() + "\n\n";

                StaticLayout testLayout = StaticLayout.Builder.obtain(
                        formattedPara, 0, formattedPara.length(), bodyPaint, contentWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0f, 1.35f)
                        .setIncludePad(false)
                        .build();

                int paraHeight = testLayout.getHeight();

                if (currentHeight + paraHeight <= maxContentHeight) {
                    pageBuilder.append(formattedPara);
                    currentHeight += paraHeight;
                } else {
                    // Paragraph doesn't fully fit; check if we can split across pages
                    if (currentHeight > 0) {
                        // Finish current page
                        PageData pd = new PageData();
                        pd.chapterTitle = chTitle;
                        pd.text = pageBuilder.toString();
                        pd.pageIndex = pages.size();
                        pages.add(pd);

                        pageBuilder = new StringBuilder();
                        currentHeight = 0;
                    }

                    // If single paragraph is longer than whole page, split it line by line
                    int startChar = 0;
                    while (startChar < formattedPara.length()) {
                        StaticLayout chunkLayout = StaticLayout.Builder.obtain(
                                formattedPara, startChar, formattedPara.length(), bodyPaint, contentWidth)
                                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                                .setLineSpacing(0f, 1.35f)
                                .setIncludePad(false)
                                .build();

                        int lineCount = chunkLayout.getLineCount();
                        int fitLines = 0;
                        for (int i = 0; i < lineCount; i++) {
                            if (chunkLayout.getLineBottom(i) <= maxContentHeight) {
                                fitLines++;
                            } else {
                                break;
                            }
                        }

                        if (fitLines == 0) fitLines = 1; // At least one line

                        int endChar = chunkLayout.getLineEnd(fitLines - 1);
                        String chunk = formattedPara.substring(startChar, endChar);

                        PageData pd = new PageData();
                        pd.chapterTitle = chTitle;
                        pd.text = chunk;
                        pd.pageIndex = pages.size();
                        pages.add(pd);

                        startChar = endChar;
                    }
                }
            }

            if (pageBuilder.length() > 0) {
                PageData pd = new PageData();
                pd.chapterTitle = chTitle;
                pd.text = pageBuilder.toString();
                pd.pageIndex = pages.size();
                pages.add(pd);
            }
        }

        if (pages.isEmpty()) {
            PageData emptyPage = new PageData();
            emptyPage.chapterTitle = bookTitle;
            emptyPage.text = "Книга не содержит текста или пуста.";
            emptyPage.pageIndex = 0;
            pages.add(emptyPage);
        }
    }

    @Override
    public Bitmap renderPage(int pageIndex, int maxWidth, int maxHeight) {
        if (pageIndex < 0 || pageIndex >= pages.size()) return null;

        int renderW = maxWidth > 0 ? maxWidth : BASE_WIDTH;
        int renderH = maxHeight > 0 ? maxHeight : BASE_HEIGHT;
        float scale = (float) renderW / (float) BASE_WIDTH;

        Bitmap bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        ThemeManager.ReaderTheme theme = ThemeManager.getCurrentTheme(context);

        // 1. Draw page background
        canvas.drawColor(theme.pageBgColor);

        PageData pageData = pages.get(pageIndex);
        int marginH = Math.round(MARGIN_HORIZ * scale);
        int marginT = Math.round(MARGIN_TOP * scale);
        int marginB = Math.round(MARGIN_BOTTOM * scale);
        int contentW = renderW - 2 * marginH;

        // 2. Running Chapter Header (Top)
        Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headerPaint.setColor(theme.headerFooterColor);
        headerPaint.setTextSize(22f * scale);
        headerPaint.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        String topText = (pageData.chapterTitle != null && !pageData.chapterTitle.isEmpty())
                ? pageData.chapterTitle : bookTitle;
        if (topText != null && topText.length() > 42) {
            topText = topText.substring(0, 40) + "...";
        }
        if (topText != null && !topText.isEmpty()) {
            canvas.drawText(topText, marginH, 58f * scale, headerPaint);
            // Thin elegant header rule
            Paint rulePaint = new Paint();
            rulePaint.setColor(theme.headerFooterColor);
            rulePaint.setAlpha(45);
            rulePaint.setStrokeWidth(1.2f * scale);
            canvas.drawLine(marginH, 72f * scale, renderW - marginH, 72f * scale, rulePaint);
        }

        // 3. Body Text
        TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setColor(theme.textColor);
        bodyPaint.setTextSize(38f * scale);
        bodyPaint.setTypeface(Typeface.create("serif", Typeface.NORMAL));

        if (pageData.text != null && !pageData.text.isEmpty()) {
            StaticLayout layout = StaticLayout.Builder.obtain(
                    pageData.text, 0, pageData.text.length(), bodyPaint, contentW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.35f)
                    .setIncludePad(false)
                    .build();

            canvas.save();
            canvas.translate(marginH, marginT);
            layout.draw(canvas);
            canvas.restore();
        }

        // 4. Footer: Page Number (Bottom Centered)
        Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        footerPaint.setColor(theme.headerFooterColor);
        footerPaint.setTextSize(22f * scale);
        footerPaint.setTextAlign(Paint.Align.CENTER);
        footerPaint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        String footerText = (pageIndex + 1) + " / " + pages.size();
        canvas.drawText(footerText, renderW / 2f, renderH - (32f * scale), footerPaint);

        return bitmap;
    }

    @Override
    public void close() {
        pages.clear();
        chapters.clear();
    }
}
