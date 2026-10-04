package com.example.geminilegacy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;

/** Minimal streaming Gemini REST client (streamGenerateContent over SSE). Callbacks run on the main thread. */
public final class GeminiClient {

    public static class Reply {
        public String text = "";
        public String thoughts = "";
        public byte[] image;
        public String mimeType;
    }

    public interface Callback {
        /** Called as chunks arrive, with everything received so far. */
        void onProgress(String thoughts, String text);
        void onDone(Reply reply);
        void onError(String message);
        /** The user pressed Stop; passes whatever had arrived by then. */
        void onCancelled(String thoughts, String text);
    }

    /** Lets the caller stop a request that is in flight. */
    public static final class Handle {
        volatile boolean cancelled;
        volatile Call call;
        volatile String thoughts = "";
        volatile String text = "";

        public void cancel() {
            cancelled = true;
            Call c = call;
            if (c != null) c.cancel();
        }
    }

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    /** Only the most recent messages carry their images, to keep requests and memory small. */
    private static final int IMAGE_HISTORY_WINDOW = 4;
    /** Temporary failures (overload, rate limit, dropped connection) are retried this many times. */
    private static final int MAX_RETRIES = 2;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = Tls12SocketFactory.enableTls12(
            new OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS))
            .build();

    private GeminiClient() {}

    public static Handle generate(Context context, final List<ChatMessage> history, int tier, boolean extended,
                                  final Callback cb) {
        final String apiKey = Settings.getApiKey(context);
        final String model = Settings.getTierModel(context, tier);
        final String systemPrompt = Settings.getSystemPrompt(context);
        // Gemini 3 models take a thinking level. Pro cannot turn thinking off, so "low" is the
        // lightest setting that every tier accepts.
        final String thinkingLevel = extended ? "high" : "low";
        final Handle handle = new Handle();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final Reply reply = call(apiKey, model, history, thinkingLevel, systemPrompt, cb, handle);
                    MAIN.post(new Runnable() { @Override public void run() { cb.onDone(reply); } });
                } catch (final Exception e) {
                    if (handle.cancelled) {
                        MAIN.post(new Runnable() {
                            @Override public void run() { cb.onCancelled(handle.thoughts, handle.text); }
                        });
                    } else {
                        final String message = describe(e);
                        MAIN.post(new Runnable() {
                            @Override public void run() { cb.onError(message); }
                        });
                    }
                }
            }
        }).start();
        return handle;
    }

    private static Reply call(String apiKey, String model, List<ChatMessage> history, String thinkingLevel,
                              String systemPrompt, final Callback cb, Handle handle) throws Exception {
        JSONArray contents = new JSONArray();
        int size = history.size();
        for (int i = 0; i < size; i++) {
            ChatMessage m = history.get(i);
            if (m.error || m.pending) continue;
            JSONArray parts = new JSONArray();
            if (m.text.length() > 0) {
                parts.put(new JSONObject().put("text", m.text));
            }
            if (m.image != null && i >= size - IMAGE_HISTORY_WINDOW) {
                JSONObject inline = new JSONObject()
                        .put("mimeType", m.mimeType == null ? "image/jpeg" : m.mimeType)
                        .put("data", Base64.encodeToString(m.image, Base64.NO_WRAP));
                parts.put(new JSONObject().put("inlineData", inline));
            } else if (m.image != null && m.text.length() == 0) {
                parts.put(new JSONObject().put("text", "[image]"));
            }
            if (parts.length() == 0) continue;
            contents.put(new JSONObject().put("role", m.fromUser ? "user" : "model").put("parts", parts));
        }

        JSONObject body = new JSONObject().put("contents", contents);
        body.put("generationConfig", new JSONObject()
                .put("thinkingConfig", new JSONObject()
                        .put("thinkingLevel", thinkingLevel)
                        .put("includeThoughts", true)));
        if (systemPrompt.length() > 0) {
            body.put("systemInstruction", new JSONObject()
                    .put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt))));
        }

        Request req = new Request.Builder()
                .url(BASE + model + ":streamGenerateContent?alt=sse")
                .header("x-goog-api-key", apiKey)   // header, not URL, so the key never lands in logs
                .post(RequestBody.create(JSON, body.toString()))
                .build();

        // Connect, retrying temporary failures before any answer has started to arrive.
        Response response;
        for (int attempt = 0; ; attempt++) {
            Call httpCall = CLIENT.newCall(req);
            handle.call = httpCall;
            if (handle.cancelled) throw new IOException("Cancelled");
            try {
                response = httpCall.execute();
            } catch (IOException e) {
                if (handle.cancelled || attempt >= MAX_RETRIES) throw e;
                pause(handle, attempt);
                continue;
            }
            if (response.isSuccessful()) break;

            int code = response.code();
            String raw = response.body() != null ? response.body().string() : "";
            response.close();
            if (!retryable(code) || attempt >= MAX_RETRIES) throw new Exception(httpMessage(code, raw));
            pause(handle, attempt);
        }

        try {
            final StringBuilder text = new StringBuilder();
            final StringBuilder thoughts = new StringBuilder();
            Reply reply = new Reply();
            String blockReason = "";
            String finishReason = "";

            BufferedSource src = response.body().source();
            String line;
            while ((line = src.readUtf8Line()) != null) {
                if (!line.startsWith("data:")) continue;
                String json = line.substring(5).trim();
                if (json.length() == 0 || json.equals("[DONE]")) continue;

                JSONObject chunk = new JSONObject(json);
                JSONObject err = chunk.optJSONObject("error");
                if (err != null) throw new Exception(err.optString("message", "Gemini reported an error"));

                JSONObject fb = chunk.optJSONObject("promptFeedback");
                if (fb != null) blockReason = fb.optString("blockReason", blockReason);

                JSONArray candidates = chunk.optJSONArray("candidates");
                if (candidates == null || candidates.length() == 0) continue;
                JSONObject cand = candidates.getJSONObject(0);
                finishReason = cand.optString("finishReason", finishReason);

                JSONObject content = cand.optJSONObject("content");
                JSONArray parts = content == null ? null : content.optJSONArray("parts");
                if (parts == null) continue;
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.getJSONObject(i);
                    if (p.has("text")) {
                        if (p.optBoolean("thought", false)) thoughts.append(p.getString("text"));
                        else text.append(p.getString("text"));
                    }
                    JSONObject inline = p.optJSONObject("inlineData");
                    if (inline != null && reply.image == null) {
                        reply.image = Base64.decode(inline.getString("data"), Base64.DEFAULT);
                        reply.mimeType = inline.optString("mimeType", "image/png");
                    }
                }
                final String t = thoughts.toString();
                final String a = text.toString();
                handle.thoughts = t;
                handle.text = a;
                MAIN.post(new Runnable() { @Override public void run() { cb.onProgress(t, a); } });
            }

            reply.text = text.toString();
            reply.thoughts = thoughts.toString();
            if (reply.text.length() == 0 && reply.image == null) {
                if (blockReason.length() > 0) {
                    throw new Exception("Gemini blocked this request (" + blockReason + "). Try rephrasing it.");
                }
                throw new Exception("Gemini returned an empty answer"
                        + (finishReason.length() > 0 ? " (" + finishReason + ")" : "") + ". Try again.");
            }
            return reply;
        } finally {
            response.close();
        }
    }

    // ---- errors and retries ----

    private static boolean retryable(int code) {
        return code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    /** Waits 2s, then 4s between retries, staying responsive to the Stop button. */
    private static void pause(Handle handle, int attempt) throws IOException {
        long until = System.currentTimeMillis() + 2000L * (attempt + 1);
        while (System.currentTimeMillis() < until) {
            if (handle.cancelled) throw new IOException("Cancelled");
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new IOException("Cancelled");
            }
        }
    }

    /** Plain-language version of an HTTP error. */
    private static String httpMessage(int code, String raw) {
        String detail = "";
        try {
            JSONObject err = new JSONObject(raw).optJSONObject("error");
            if (err != null) detail = err.optString("message", "");
        } catch (Exception ignored) { }
        String tag = " (HTTP " + code + ")";
        if (code == 429) {
            return "Too many requests, or your free quota is used up. Wait a bit and try again." + tag;
        }
        if (code == 400 && detail.toLowerCase().contains("api key")) {
            return "Your API key isn't valid. Check it in Settings." + tag;
        }
        if (code == 401 || code == 403) {
            return "Your API key was refused, or it isn't allowed to use this model. Check it in Settings." + tag;
        }
        if (code == 404) {
            return "That model wasn't found. Check the model ids in Settings." + tag;
        }
        if (code >= 500) {
            return "Gemini is overloaded or having problems right now. Try again in a moment." + tag;
        }
        return (detail.length() > 0 ? detail : "The request failed") + tag;
    }

    /** Plain-language version of a network failure; other errors keep their own message. */
    private static String describe(Exception e) {
        if (e instanceof UnknownHostException || e instanceof ConnectException) {
            return "Can't reach Google. Check your internet connection.";
        }
        if (e instanceof SocketTimeoutException) {
            return "The connection timed out. Try again.";
        }
        if (e instanceof SSLException) {
            return "The secure connection failed. Check your connection and the phone's date and time.";
        }
        if (e instanceof IOException) {
            return "The connection was interrupted. Try again.";
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    // ---- downloads (update checks, image links) ----

    /** Downloads an image a reply linked to (no API key is sent). Throws if it is larger than maxBytes. */
    public static byte[] downloadBytes(String url, int maxBytes) throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "GeminiLegacy/0.5 (Android)")
                .get()
                .build();
        Response response = CLIENT.newCall(req).execute();
        try {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HTTP " + response.code());
            }
            java.io.InputStream in = response.body().byteStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) throw new Exception("Image too large");
            }
            return out.toByteArray();
        } finally {
            response.close();
        }
    }

    public interface ProgressListener {
        /** Called on the background thread, 0-100. */
        void onProgress(int percent);
    }

    /** Streams a download to a file and returns its SHA-256 as lowercase hex. */
    public static String downloadToFile(String url, java.io.File dest, ProgressListener listener) throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "GeminiLegacy/0.5 (Android)")
                .get()
                .build();
        Response response = CLIENT.newCall(req).execute();
        try {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HTTP " + response.code());
            }
            long total = response.body().contentLength();
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            java.io.InputStream in = response.body().byteStream();
            java.io.FileOutputStream out = new java.io.FileOutputStream(dest);
            try {
                byte[] buf = new byte[16384];
                long done = 0;
                int lastPercent = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    if (total > 0 && listener != null) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPercent) {
                            lastPercent = pct;
                            listener.onProgress(pct);
                        }
                    }
                }
            } finally {
                out.close();
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } finally {
            response.close();
        }
    }
}
