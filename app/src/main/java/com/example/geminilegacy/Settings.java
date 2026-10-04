package com.example.geminilegacy;

import android.content.Context;
import android.content.SharedPreferences;

/** API key, model ids and the selected model tier, kept in app-private SharedPreferences. */
public final class Settings {
    public static final int TIER_PRO = 0;
    public static final int TIER_FLASH = 1;
    public static final int TIER_LITE = 2;

    private static final String[] TIER_LABELS = {"Pro", "Flash", "Flash Lite"};
    private static final String[] TIER_DEFAULT_MODELS = {
            "gemini-3.1-pro-preview", "gemini-3.5-flash", "gemini-3.5-flash-lite"};
    private static final String[] TIER_KEYS = {"model_pro", "model_flash", "model_lite"};

    /** Releases are checked here unless another repo is entered in Settings. */
    public static final String DEFAULT_UPDATE_REPO = "SillyCatty/Gemini-Legacy";

    private static final String PREFS = "settings";
    private static final String KEY_API = "api_key";
    private static final String KEY_TIER = "tier";
    private static final String KEY_EXTENDED = "extended";

    private Settings() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String tierLabel(int tier) { return TIER_LABELS[tier]; }

    public static String getApiKey(Context c) { return prefs(c).getString(KEY_API, ""); }

    public static String getTierModel(Context c, int tier) {
        return prefs(c).getString(TIER_KEYS[tier], TIER_DEFAULT_MODELS[tier]);
    }

    public static int getTier(Context c) { return prefs(c).getInt(KEY_TIER, TIER_FLASH); }

    public static void setTier(Context c, int tier) { prefs(c).edit().putInt(KEY_TIER, tier).commit(); }

    public static boolean isExtended(Context c) { return prefs(c).getBoolean(KEY_EXTENDED, false); }

    public static void setExtended(Context c, boolean on) { prefs(c).edit().putBoolean(KEY_EXTENDED, on).commit(); }

    /** GitHub repo to check for new releases, as "owner/name". */
    public static String getUpdateRepo(Context c) {
        String repo = prefs(c).getString("update_repo", "").trim();
        return repo.length() == 0 ? DEFAULT_UPDATE_REPO : repo;
    }

    public static long getLastUpdateCheck(Context c) { return prefs(c).getLong("last_update_check", 0); }

    public static void setLastUpdateCheck(Context c, long t) { prefs(c).edit().putLong("last_update_check", t).commit(); }

    /** Optional instructions sent with every request ("keep answers short", a persona, ...). */
    public static String getSystemPrompt(Context c) { return prefs(c).getString("system_prompt", "").trim(); }

    public static void save(Context c, String apiKey, String pro, String flash, String lite, String updateRepo,
                            String systemPrompt) {
        String[] given = {pro, flash, lite};
        SharedPreferences.Editor e = prefs(c).edit();
        e.putString("system_prompt", systemPrompt.trim());
        e.putString("update_repo", updateRepo.trim());
        e.putString(KEY_API, apiKey.trim());
        for (int i = 0; i < 3; i++) {
            String v = given[i].trim();
            e.putString(TIER_KEYS[i], v.length() == 0 ? TIER_DEFAULT_MODELS[i] : v);
        }
        e.commit();
    }
}
