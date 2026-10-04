package com.example.geminilegacy;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.support.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Keeps images small enough for a 2013-era phone and for the request size. */
public final class ImageUtil {
    private ImageUtil() {}

    /** Reads an image from a content Uri, scales its longest side to maxDim, returns JPEG bytes. */
    public static byte[] loadScaledJpeg(ContentResolver cr, Uri uri, int maxDim) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        try {
            BitmapFactory.decodeStream(in, null, o);
        } finally {
            if (in != null) in.close();
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, maxDim * 2);

        Bitmap bmp;
        in = cr.openInputStream(uri);
        try {
            bmp = BitmapFactory.decodeStream(in, null, o);
        } finally {
            if (in != null) in.close();
        }
        if (bmp == null) throw new IOException("Could not read that image");

        int longest = Math.max(bmp.getWidth(), bmp.getHeight());
        if (longest > maxDim) {
            float s = maxDim / (float) longest;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * s),
                    Math.round(bmp.getHeight() * s), true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }

        // Phone cameras store portrait shots sideways plus an EXIF "rotate me" flag; apply it.
        int degrees = exifRotation(cr, uri);
        if (degrees != 0) {
            Matrix m = new Matrix();
            m.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            bmp = rotated;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
        bmp.recycle();
        return out.toByteArray();
    }

    private static int exifRotation(ContentResolver cr, Uri uri) {
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) return 0;
            int orientation = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Throwable t) {
            return 0;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** Decodes image bytes to a bitmap no larger than about maxDim on its longest side. */
    public static Bitmap decodeForDisplay(byte[] data, int maxDim) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, maxDim);
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private static int sampleSize(int w, int h, int target) {
        int sample = 1;
        while (Math.max(w, h) / (sample * 2) >= target) sample *= 2;
        return sample;
    }
}
