package com.holki.auraleaf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfManager {

    public interface PageCallback {
        void onPageRendered(int pageIndex, Bitmap bitmap);
    }

    private ComicDocument document;
    private int pageCount = 0;
    private LruCache<String, Bitmap> memoryCache;
    private ExecutorService renderExecutor;
    private Handler mainHandler;
    private float pageAspectRatio = 1.414f;

    public PdfManager(Context context, Uri uri) throws Exception {
        mainHandler = new Handler(Looper.getMainLooper());
        renderExecutor = Executors.newFixedThreadPool(2);

        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8; // 1/8th of available memory

        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        String uriStr = uri.toString().toLowerCase();
        String pathStr = uri.getPath() != null ? uri.getPath().toLowerCase() : "";
        String fileName = "";
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) fileName = cursor.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        if (fileName == null || fileName.isEmpty()) {
            fileName = uri.getLastPathSegment();
        }
        String lowerName = (fileName != null ? fileName : "").toLowerCase();

        if (lowerName.endsWith(".epub") || uriStr.endsWith(".epub") || pathStr.endsWith(".epub")) {
            File epubFile = CoverExtractor.getFileFromUri(context, uri, "active.epub");
            document = new EpubDocument(context, epubFile);
        } else if (lowerName.endsWith(".fb2") || lowerName.endsWith(".fb2.zip") || uriStr.endsWith(".fb2") || pathStr.endsWith(".fb2")) {
            File fb2File = CoverExtractor.getFileFromUri(context, uri, "active.fb2");
            document = new Fb2Document(context, fb2File);
        } else if (lowerName.endsWith(".txt") || uriStr.endsWith(".txt") || pathStr.endsWith(".txt")) {
            File txtFile = CoverExtractor.getFileFromUri(context, uri, "active.txt");
            document = new TxtDocument(context, txtFile);
        } else if (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp")
                || uriStr.endsWith(".png") || uriStr.endsWith(".jpg") || uriStr.endsWith(".jpeg") || uriStr.endsWith(".webp")) {
            File imgFile = CoverExtractor.getFileFromUri(context, uri, "active_image.png");
            document = new ImageDocument(imgFile);
        } else if (lowerName.endsWith(".cbz") || lowerName.endsWith(".zip") || uriStr.endsWith(".cbz") || uriStr.endsWith(".zip") || pathStr.endsWith(".cbz") || pathStr.endsWith(".zip")) {
            File cbzFile = CoverExtractor.getFileFromUri(context, uri, "active.cbz");
            document = new CbzDocument(cbzFile);
        } else if (lowerName.endsWith(".cbr") || lowerName.endsWith(".rar") || uriStr.endsWith(".cbr") || uriStr.endsWith(".rar") || pathStr.endsWith(".cbr") || pathStr.endsWith(".rar")) {
            File cbrFile = CoverExtractor.getFileFromUri(context, uri, "active.cbr");
            document = new CbrDocument(cbrFile);
        } else {
            // PDF document
            ParcelFileDescriptor pfd;
            try {
                if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                    pfd = ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
                } else {
                    pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                }
            } catch (Exception e) {
                File temp = new File(context.getCacheDir(), "temp_render.pdf");
                try (InputStream in = context.getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            document = new PdfDocument(pfd);
        }

        pageCount = document.getPageCount();
        if (pageCount > 0) {
            pageAspectRatio = document.getPageAspectRatio(0);
        }
    }

    public int getPageCount() {
        return pageCount;
    }

    public float getPageAspectRatio() {
        return pageAspectRatio;
    }

    public boolean isTextBook() {
        return document instanceof TextBookDocument;
    }

    private int blurLevel = 25; // 0..100, default 25 (light blur)

    public void setBlurLevel(int level) {
        if (this.blurLevel != level) {
            this.blurLevel = Math.max(0, Math.min(100, level));
            clearCache();
        }
    }

    public int getBlurLevel() {
        return blurLevel;
    }

    public void clearCache() {
        if (memoryCache != null) {
            memoryCache.evictAll();
        }
    }

    public synchronized Bitmap getRawThumbnail(int index, int maxW, int maxH) {
        if (index < 0 || index >= pageCount || document == null) return null;
        try {
            return document.renderPage(index, maxW, maxH);
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized Bitmap getPageSync(int index, int targetW, int targetH) {
        if (index < 0 || index >= pageCount || document == null) return null;

        String cacheKey = index + "@" + targetW + "x" + targetH;
        Bitmap cached = memoryCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        try {
            // Render sharp comic page preserving aspect ratio
            Bitmap pageBmp = document.renderPage(index, targetW, targetH);
            if (pageBmp == null) return null;

            int drawW = pageBmp.getWidth();
            int drawH = pageBmp.getHeight();

            // Calculate precise destination rect on screen preserving exact aspect ratio
            float aspect = (float) drawW / drawH;
            float targetAspect = (float) targetW / targetH;
            int finalW, finalH;
            if (aspect > targetAspect) {
                finalW = targetW;
                finalH = Math.max(10, Math.round(targetW / aspect));
            } else {
                finalH = targetH;
                finalW = Math.max(10, Math.round(targetH * aspect));
            }

            int left = (targetW - finalW) / 2;
            int top = (targetH - finalH) / 2;
            Rect dstPageRect = new Rect(left, top, left + finalW, top + finalH);

            // Create full-screen bitmap for authentic edge-to-edge reading
            Bitmap fullBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            Canvas fullCanvas = new Canvas(fullBmp);

            if (finalW < targetW || finalH < targetH) {
                if (blurLevel <= 0) {
                    // Pure dark background
                    fullCanvas.drawColor(0xFF07090E);
                } else {
                    // Center-crop page area matching target screen aspect ratio with zoom.
                    float bgZoom = 1.28f;
                    int baseCropW = drawW;
                    int baseCropH = drawH;
                    if (aspect > targetAspect) {
                        baseCropW = Math.round(drawH * targetAspect);
                    } else {
                        baseCropH = Math.round(drawW / targetAspect);
                    }
                    int cropW = Math.max(10, Math.round(baseCropW / bgZoom));
                    int cropH = Math.max(10, Math.round(baseCropH / bgZoom));
                    int cropX = Math.max(0, (drawW - cropW) / 2);
                    int cropY = Math.max(0, (drawH - cropH) / 2);
                    cropW = Math.min(cropW, drawW - cropX);
                    cropH = Math.min(cropH, drawH - cropY);

                    int thumbW = 360;
                    int radius = 2;
                    int passes = 1;
                    float bright = 0.84f;
                    float satBoost = 1.05f;

                    if (blurLevel <= 15) {
                        thumbW = 420; radius = 1; bright = 0.86f; satBoost = 1.02f;
                    } else if (blurLevel <= 35) {
                        thumbW = 360; radius = 2; bright = 0.84f; satBoost = 1.05f;
                    } else if (blurLevel <= 65) {
                        thumbW = 300; radius = 4; bright = 0.82f; satBoost = 1.10f;
                    } else if (blurLevel <= 85) {
                        thumbW = 220; radius = 7; bright = 0.80f; satBoost = 1.15f;
                    } else {
                        thumbW = 140; radius = 9; passes = 2; bright = 0.78f; satBoost = 1.25f;
                    }

                    int thumbH = Math.max(10, Math.round((float) thumbW * targetH / targetW));
                    Bitmap croppedSrc = Bitmap.createBitmap(pageBmp, cropX, cropY, cropW, cropH);
                    Bitmap thumb = Bitmap.createScaledBitmap(croppedSrc, thumbW, thumbH, true);
                    if (croppedSrc != thumb && !croppedSrc.isRecycled()) croppedSrc.recycle();

                    Bitmap blurredThumb = blurAndDimThumbnail(thumb, radius, passes, bright, satBoost);
                    if (thumb != blurredThumb && !thumb.isRecycled()) thumb.recycle();

                    if (blurredThumb != null) {
                        Rect srcRect = new Rect(0, 0, blurredThumb.getWidth(), blurredThumb.getHeight());
                        Rect dstRect = new Rect(0, 0, targetW, targetH);
                        Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
                        fullCanvas.drawBitmap(blurredThumb, srcRect, dstRect, bgPaint);
                        blurredThumb.recycle();
                    }
                }
            }

            // Draw comic page with maximum bilinear / bicubic filtering — zero erosion or blurring of the artwork
            Paint pagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            fullCanvas.drawBitmap(pageBmp, null, dstPageRect, pagePaint);
            pageBmp.recycle();

            memoryCache.put(cacheKey, fullBmp);
            return fullBmp;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static Bitmap blurAndDimThumbnail(Bitmap src, int radius, int passes, float bright, float satBoost) {
        if (src == null || src.isRecycled()) return null;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int[] pix = new int[w * h];
            src.getPixels(pix, 0, w, 0, 0, w, h);

            // Compute dominant chromatic artwork colors to suppress white paper gutters
            long sumR = 0, sumG = 0, sumB = 0;
            int chromaticCount = 0;
            for (int p : pix) {
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                if (max - min > 15 && max < 240 && min > 20) {
                    sumR += r;
                    sumG += g;
                    sumB += b;
                    chromaticCount++;
                }
            }
            int avgR = chromaticCount > 0 ? (int)(sumR / chromaticCount) : 40;
            int avgG = chromaticCount > 0 ? (int)(sumG / chromaticCount) : 40;
            int avgB = chromaticCount > 0 ? (int)(sumB / chromaticCount) : 40;

            float[] hsv = new float[3];
            for (int i = 0; i < pix.length; i++) {
                int p = pix[i];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;

                if (r > 205 && g > 205 && b > 205) {
                    // Suppress white paper gutter/margins and tint with artwork atmosphere
                    int nr = Math.min(255, (int)(r * 0.12f + avgR * 0.88f * bright));
                    int ng = Math.min(255, (int)(g * 0.12f + avgG * 0.88f * bright));
                    int nb = Math.min(255, (int)(b * 0.12f + avgB * 0.88f * bright));
                    pix[i] = 0xFF000000 | (nr << 16) | (ng << 8) | nb;
                } else {
                    Color.colorToHSV(p, hsv);
                    hsv[1] = Math.min(1.0f, hsv[1] * satBoost);
                    hsv[2] = hsv[2] * bright;
                    pix[i] = Color.HSVToColor(255, hsv);
                }
            }

            for (int p = 0; p < passes; p++) {
                fastBoxBlur(pix, w, h, radius);
            }

            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(pix, 0, w, 0, 0, w, h);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static void fastBoxBlur(int[] pix, int w, int h, int radius) {
        if (w <= 0 || h <= 0 || radius <= 0) return;
        int[] temp = new int[pix.length];
        int div = 2 * radius + 1;

        // Horizontal pass
        for (int y = 0; y < h; y++) {
            int yOffset = y * w;
            int rSum = 0, gSum = 0, bSum = 0;

            for (int i = -radius; i <= radius; i++) {
                int p = pix[yOffset + Math.min(Math.max(i, 0), w - 1)];
                rSum += (p >> 16) & 0xFF;
                gSum += (p >> 8) & 0xFF;
                bSum += p & 0xFF;
            }

            for (int x = 0; x < w; x++) {
                temp[yOffset + x] = 0xFF000000 | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);
                int leftP = pix[yOffset + Math.max(0, x - radius)];
                int rightP = pix[yOffset + Math.min(w - 1, x + radius + 1)];
                rSum += ((rightP >> 16) & 0xFF) - ((leftP >> 16) & 0xFF);
                gSum += ((rightP >> 8) & 0xFF) - ((leftP >> 8) & 0xFF);
                bSum += (rightP & 0xFF) - (leftP & 0xFF);
            }
        }

        // Vertical pass
        for (int x = 0; x < w; x++) {
            int rSum = 0, gSum = 0, bSum = 0;

            for (int i = -radius; i <= radius; i++) {
                int p = temp[Math.min(Math.max(i, 0), h - 1) * w + x];
                rSum += (p >> 16) & 0xFF;
                gSum += (p >> 8) & 0xFF;
                bSum += p & 0xFF;
            }

            for (int y = 0; y < h; y++) {
                pix[y * w + x] = 0xFF000000 | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);
                int topP = temp[Math.max(0, y - radius) * w + x];
                int bottomP = temp[Math.min(h - 1, y + radius + 1) * w + x];
                rSum += ((bottomP >> 16) & 0xFF) - ((topP >> 16) & 0xFF);
                gSum += ((bottomP >> 8) & 0xFF) - ((topP >> 8) & 0xFF);
                bSum += (bottomP & 0xFF) - (topP & 0xFF);
            }
        }
    }

    public void getPageAsync(int index, int targetW, int targetH, PageCallback callback) {
        String cacheKey = index + "@" + targetW + "x" + targetH;
        Bitmap cached = memoryCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            callback.onPageRendered(index, cached);
            return;
        }

        renderExecutor.execute(() -> {
            Bitmap bmp = getPageSync(index, targetW, targetH);
            if (bmp != null) {
                mainHandler.post(() -> callback.onPageRendered(index, bmp));
            }
        });
    }
    public void close() {
        if (renderExecutor != null) {
            renderExecutor.shutdownNow();
        }
        if (memoryCache != null) {
            memoryCache.evictAll();
        }
        if (document != null) {
            document.close();
            document = null;
        }
    }
}
