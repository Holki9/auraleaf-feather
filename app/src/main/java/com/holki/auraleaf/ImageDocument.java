package com.holki.auraleaf;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;

public class ImageDocument implements ComicDocument {
    private File imageFile;
    private int width = 0;
    private int height = 0;

    public ImageDocument(File file) {
        this.imageFile = file;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        this.width = opts.outWidth;
        this.height = opts.outHeight;
    }

    @Override
    public int getPageCount() {
        return 1;
    }

    @Override
    public synchronized Bitmap renderPage(int index, int maxW, int maxH) {
        if (!imageFile.exists() || width <= 0 || height <= 0) return null;
        try {
            // Decode at full native resolution with ARGB_8888 for 100% gallery fidelity
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inSampleSize = 1;
            return BitmapFactory.decodeFile(imageFile.getAbsolutePath(), opts);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public synchronized float getPageAspectRatio(int index) {
        if (width <= 0 || height <= 0) return 1.414f;
        return (float) height / (float) width;
    }

    @Override
    public synchronized void close() {
        imageFile = null;
    }
}
