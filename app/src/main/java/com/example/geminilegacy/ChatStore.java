package com.example.geminilegacy;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Saves chats in the app's private files dir: one JSON file per chat plus one file per image. */
public final class ChatStore {

    public static class Summary {
        public final String id;
        public final String title;
        public final long updated;
        /** Lowercased message text, for searching inside chats. */
        public final String search;

        Summary(String id, String title, long updated, String search) {
            this.id = id;
            this.title = title;
            this.updated = updated;
            this.search = search;
        }
    }

    private static final Object LOCK = new Object();
    private static final int MAX_SEARCH_CHARS = 30000;

    private ChatStore() {}

    private static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "chats");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static List<Summary> list(Context c) {
        List<Summary> out = new ArrayList<Summary>();
        synchronized (LOCK) {
            File[] files = dir(c).listFiles();
            if (files == null) return out;
            for (File f : files) {
                String name = f.getName();
                if (!name.endsWith(".json")) continue;
                try {
                    JSONObject o = new JSONObject(new String(readAll(f), "UTF-8"));
                    StringBuilder text = new StringBuilder();
                    JSONArray msgs = o.optJSONArray("messages");
                    for (int i = 0; msgs != null && i < msgs.length() && text.length() < MAX_SEARCH_CHARS; i++) {
                        JSONObject m = msgs.optJSONObject(i);
                        if (m != null && !m.optBoolean("e")) text.append(m.optString("t")).append('\n');
                    }
                    out.add(new Summary(name.substring(0, name.length() - 5),
                            o.optString("title", "Chat"), o.optLong("updated", f.lastModified()),
                            text.toString().toLowerCase(Locale.getDefault())));
                } catch (Exception ignored) { }
            }
        }
        Collections.sort(out, new Comparator<Summary>() {
            @Override public int compare(Summary a, Summary b) {
                return a.updated < b.updated ? 1 : (a.updated > b.updated ? -1 : 0);
            }
        });
        return out;
    }

    public static void save(Context c, String id, String title, List<ChatMessage> messages) {
        synchronized (LOCK) {
            try {
                File d = dir(c);
                File jsonFile = new File(d, id + ".json");

                // A name the user typed in "Rename" is kept; otherwise the title follows the first message.
                boolean custom = false;
                String finalTitle = title;
                if (jsonFile.exists()) {
                    try {
                        JSONObject old = new JSONObject(new String(readAll(jsonFile), "UTF-8"));
                        if (old.optBoolean("custom")) {
                            custom = true;
                            finalTitle = old.optString("title", title);
                        }
                    } catch (Exception ignored) { }
                }

                Set<String> keep = new HashSet<String>();
                JSONArray arr = new JSONArray();
                for (int i = 0; i < messages.size(); i++) {
                    ChatMessage m = messages.get(i);
                    if (m.pending) continue;
                    JSONObject o = new JSONObject();
                    o.put("th", m.thoughts);
                    o.put("u", m.fromUser);
                    o.put("t", m.text);
                    o.put("e", m.error);
                    if (m.image != null) {
                        // The size is part of the name, so an edited message never picks up a stale image.
                        String imgName = id + "_" + i + "_" + m.image.length + ".img";
                        File imgFile = new File(d, imgName);
                        if (!imgFile.exists()) writeAll(imgFile, m.image);
                        keep.add(imgName);
                        o.put("img", imgName);
                        o.put("m", m.mimeType);
                    }
                    arr.put(o);
                }
                JSONObject root = new JSONObject();
                root.put("title", finalTitle);
                root.put("custom", custom);
                root.put("updated", System.currentTimeMillis());
                root.put("messages", arr);
                writeAll(jsonFile, root.toString().getBytes("UTF-8"));

                // Drop images that no longer belong to any message (deleted, edited or regenerated).
                File[] files = d.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String n = f.getName();
                        if (n.startsWith(id + "_") && n.endsWith(".img") && !keep.contains(n)) f.delete();
                    }
                }
            } catch (Exception ignored) { }
        }
    }

    public static void rename(Context c, String id, String title) {
        synchronized (LOCK) {
            try {
                File jsonFile = new File(dir(c), id + ".json");
                JSONObject root = new JSONObject(new String(readAll(jsonFile), "UTF-8"));
                root.put("title", title);
                root.put("custom", true);
                writeAll(jsonFile, root.toString().getBytes("UTF-8"));
            } catch (Exception ignored) { }
        }
    }

    public static List<ChatMessage> load(Context c, String id) {
        List<ChatMessage> out = new ArrayList<ChatMessage>();
        synchronized (LOCK) {
            try {
                File d = dir(c);
                JSONObject root = new JSONObject(new String(readAll(new File(d, id + ".json")), "UTF-8"));
                JSONArray arr = root.getJSONArray("messages");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    byte[] img = null;
                    Bitmap thumb = null;
                    String imgName = o.optString("img", "");
                    if (imgName.length() > 0) {
                        File f = new File(d, imgName);
                        if (f.exists()) {
                            img = readAll(f);
                            thumb = ImageUtil.decodeForDisplay(img, 800);
                        }
                    }
                    ChatMessage cm = new ChatMessage(o.optBoolean("u"), o.optString("t"), img,
                            o.optString("m", "image/jpeg"), thumb, o.optBoolean("e"));
                    cm.thoughts = o.optString("th", "");
                    out.add(cm);
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    public static void delete(Context c, String id) {
        synchronized (LOCK) {
            File[] files = dir(c).listFiles();
            if (files == null) return;
            for (File f : files) {
                String n = f.getName();
                if (n.equals(id + ".json") || n.startsWith(id + "_")) f.delete();
            }
        }
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }
}
