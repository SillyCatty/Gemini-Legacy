package com.example.geminilegacy;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/** One chat bubble. Mutable because a Gemini reply is filled in while it streams. */
public class ChatMessage {

    /** An image that Gemini linked to in its text, downloaded for display. */
    public static class LinkImage {
        public final byte[] bytes;
        public final Bitmap bitmap;

        public LinkImage(byte[] bytes, Bitmap bitmap) {
            this.bytes = bytes;
            this.bitmap = bitmap;
        }
    }

    public final boolean fromUser;
    public String text;
    public byte[] image;       // raw JPEG/PNG bytes, or null
    public String mimeType;    // mime type of image
    public Bitmap thumb;       // downscaled bitmap for display, or null
    public boolean error;

    public String thoughts = "";          // Gemini's thought summary, if it sent one
    public boolean pending;               // true while the reply is still streaming
    public boolean thoughtsExpanded;      // "Show thinking" toggle state
    public List<Object> rendered;         // cached Markdown blocks (CharSequence or Markdown.Table)
    public final List<LinkImage> linkImages = new ArrayList<LinkImage>();
    public boolean linksRequested;        // image URLs in text have been fetched (or are being fetched)

    public ChatMessage(boolean fromUser, String text, byte[] image, String mimeType, Bitmap thumb, boolean error) {
        this.fromUser = fromUser;
        this.text = text == null ? "" : text;
        this.image = image;
        this.mimeType = mimeType;
        this.thumb = thumb;
        this.error = error;
    }
}
