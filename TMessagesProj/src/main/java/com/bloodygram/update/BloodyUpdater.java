package com.bloodygram.update;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Update check against the latest GitHub release of the Bloodygram repo (tags are v<BLOODY_VERSION>). */
public class BloodyUpdater {

    public static final String REPO = "spookwy/Bloodygram";
    private static final long AUTO_INTERVAL = 12 * 60 * 60 * 1000L;

    private static boolean checking;

    public static String versionLabel() {
        return "Bloodygram " + BuildConfig.BLOODY_VERSION + " (Telegram " + BuildVars.BUILD_VERSION_STRING + ")";
    }

    /** Quiet check on app start: release builds only, at most every 12 hours, only speaks up if there is an update. */
    public static void autoCheck(Activity activity) {
        if (BuildVars.DEBUG_VERSION || activity == null) {
            return;
        }
        long last = BloodyConfig.prefs().getLong("updateCheckedAt", 0);
        if (System.currentTimeMillis() - last < AUTO_INTERVAL) {
            return;
        }
        BloodyConfig.prefs().edit().putLong("updateCheckedAt", System.currentTimeMillis()).apply();
        check(activity, null);
    }

    /** Manual check from settings: also reports "you're up to date" and errors. */
    public static void checkNow(BaseFragment fragment) {
        if (fragment.getParentActivity() != null) {
            check(fragment.getParentActivity(), fragment);
        }
    }

    private static void check(Activity activity, BaseFragment fragment) {
        if (checking) {
            return;
        }
        checking = true;
        Utilities.globalQueue.postRunnable(() -> {
            String tag = null, notes = null, url = null;
            boolean failed = false;
            try {
                JSONObject release = new JSONObject(get("https://api.github.com/repos/" + REPO + "/releases/latest"));
                tag = release.optString("tag_name", "");
                notes = release.optString("body", "");
                url = release.optString("html_url", "https://github.com/" + REPO + "/releases");
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (asset.optString("name").endsWith(".apk")) {
                            url = asset.optString("browser_download_url", url);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
                failed = true;
            }
            String finalTag = tag, finalNotes = notes, finalUrl = url;
            boolean finalFailed = failed;
            AndroidUtilities.runOnUIThread(() -> {
                checking = false;
                if (activity.isFinishing()) {
                    return;
                }
                if (finalFailed || TextUtils.isEmpty(finalTag)) {
                    if (fragment != null) {
                        BulletinFactory.of(fragment).createErrorBulletin(BloodyStrings.get(R.string.BloodyUpdateFailed)).show();
                    }
                    return;
                }
                String latest = finalTag.startsWith("v") ? finalTag.substring(1) : finalTag;
                if (compare(latest, BuildConfig.BLOODY_VERSION) <= 0) {
                    if (fragment != null) {
                        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, BloodyStrings.get(R.string.BloodyUpdateLatest)).show();
                    }
                    return;
                }
                String message = TextUtils.isEmpty(finalNotes) ? "" : finalNotes.length() > 1500 ? finalNotes.substring(0, 1500) + "…" : finalNotes;
                new AlertDialog.Builder(activity)
                        .setTitle(BloodyStrings.format(R.string.BloodyUpdateAvailable, latest))
                        .setMessage(message)
                        .setPositiveButton(BloodyStrings.get(R.string.BloodyUpdateDownload), (d, w) -> {
                            try {
                                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)));
                            } catch (Exception e) {
                                FileLog.e(e);
                            }
                        })
                        .setNegativeButton(BloodyStrings.get(R.string.BloodyUpdateLater), null)
                        .show();
            });
        });
    }

    /** Numeric compare of dotted versions: 1.10.0 > 1.9.2. */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Utilities.parseInt(x[i]) : 0;
            int q = i < y.length ? Utilities.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Bloodygram");
            if (connection.getResponseCode() / 100 != 2) {
                throw new Exception("HTTP " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }
}
