package com.example.geminilegacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.support.v4.content.FileProvider;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-app updater backed by GitHub Releases. It reads the latest release of the repo set in Settings
 * ("owner/name"), compares its tag (e.g. v0.3.1) with the installed version, downloads the release's
 * .apk asset to the app's updates folder and opens the system installer. Android only installs the
 * update if it is signed with the same key as the installed app. Public repos only.
 */
public final class UpdateFlow {
    private static final long CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");

    private UpdateFlow() {}

    /** Quiet startup check, at most every few hours. */
    public static void checkOnStartup(Activity act) {
        if (Settings.getUpdateRepo(act).length() == 0) return;
        long now = System.currentTimeMillis();
        if (now - Settings.getLastUpdateCheck(act) < CHECK_INTERVAL_MS) return;
        Settings.setLastUpdateCheck(act, now);
        check(act, true);
    }

    public static void check(final Activity act, final boolean silent) {
        final String repo = Settings.getUpdateRepo(act);
        if (repo.length() == 0) {
            if (!silent) toast(act, "Enter your GitHub repo (owner/name) in Settings first");
            return;
        }
        if (!REPO.matcher(repo).matches()) {
            if (!silent) toast(act, "Repo must look like owner/name");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] raw = GeminiClient.downloadBytes(
                            "https://api.github.com/repos/" + repo + "/releases/latest", 1024 * 1024);
                    JSONObject release = new JSONObject(new String(raw, "UTF-8"));
                    final String tag = release.getString("tag_name");
                    final String notes = release.optString("body", "").trim();

                    String apkUrl = null;
                    String sha = "";
                    JSONArray assets = release.optJSONArray("assets");
                    for (int i = 0; assets != null && i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").toLowerCase().endsWith(".apk")) {
                            apkUrl = a.getString("browser_download_url");
                            String digest = a.optString("digest", "");   // "sha256:<hex>" when GitHub provides it
                            if (digest.startsWith("sha256:")) sha = digest.substring(7).toLowerCase();
                            break;
                        }
                    }
                    final String finalUrl = apkUrl;
                    final String finalSha = sha;

                    act.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (act.isFinishing()) return;
                            if (isNewer(tag, BuildConfig.VERSION_NAME)) {
                                if (finalUrl == null) {
                                    if (!silent) toast(act, "Release " + tag + " has no .apk file attached");
                                } else {
                                    prompt(act, tag, notes, finalUrl, finalSha);
                                }
                            } else if (!silent) {
                                toast(act, "You're up to date (" + BuildConfig.VERSION_NAME + ")");
                            }
                        }
                    });
                } catch (Exception e) {
                    if (!silent) toast(act, "Update check failed: " + e.getMessage());
                }
            }
        }).start();
    }

    /** Compares dotted numbers, ignoring a leading "v" and suffixes like "-alpha" (0.3.1 > 0.3.0). */
    static boolean isNewer(String tag, String current) {
        List<Integer> a = numbers(tag);
        List<Integer> b = numbers(current);
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static List<Integer> numbers(String v) {
        List<Integer> out = new ArrayList<Integer>();
        // Only the leading dotted-number part, e.g. "0.3.1" from "v0.3.1-alpha".
        Matcher m = Pattern.compile("\\d+(?:\\.\\d+)*").matcher(v);
        if (m.find()) {
            for (String part : m.group().split("\\.")) out.add(Integer.parseInt(part));
        }
        return out;
    }

    private static void prompt(final Activity act, String tag, String notes, final String apkUrl, final String sha) {
        String msg = "Version " + tag + " is available (you have " + BuildConfig.VERSION_NAME + ")."
                + (notes.length() > 0 ? "\n\n" + (notes.length() > 600 ? notes.substring(0, 600) + "..." : notes) : "");
        new AlertDialog.Builder(act)
                .setTitle("Update available")
                .setMessage(msg)
                .setNegativeButton("Later", null)
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { download(act, apkUrl, sha); }
                })
                .show();
    }

    private static void download(final Activity act, final String apkUrl, final String expectedSha) {
        final File dir = act.getExternalFilesDir("updates");
        if (dir == null) {
            toast(act, "No storage available for the update");
            return;
        }
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        final File apk = new File(dir, "GeminiLegacy-update.apk");

        final ProgressDialog progress = new ProgressDialog(act);
        progress.setTitle("Downloading update");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(false);
        progress.show();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String sha = GeminiClient.downloadToFile(apkUrl, apk, new GeminiClient.ProgressListener() {
                        @Override public void onProgress(final int percent) {
                            act.runOnUiThread(new Runnable() {
                                @Override public void run() { progress.setProgress(percent); }
                            });
                        }
                    });
                    if (expectedSha.length() > 0 && !sha.equals(expectedSha)) {
                        apk.delete();
                        throw new Exception("Checksum mismatch, update discarded");
                    }
                    act.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            progress.dismiss();
                            install(act, apk);
                        }
                    });
                } catch (final Exception e) {
                    apk.delete();
                    act.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            progress.dismiss();
                            toast(act, "Update failed: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private static void install(Activity act, File apk) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) {
                uri = FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", apk);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(apk);
            }
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            toast(act, "Downloaded to " + apk.getAbsolutePath() + " but could not open the installer: " + e.getMessage());
        }
    }

    private static void toast(final Activity act, final String msg) {
        act.runOnUiThread(new Runnable() {
            @Override public void run() { Toast.makeText(act, msg, Toast.LENGTH_LONG).show(); }
        });
    }
}
