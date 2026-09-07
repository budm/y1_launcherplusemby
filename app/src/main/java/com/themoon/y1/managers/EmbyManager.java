package com.themoon.y1.managers;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Toast;

import com.themoon.y1.MainActivity;
import com.themoon.y1.StoragePaths;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Emby server connection + library sync. Mirrors LastFmManager's shape
 * (singleton, prefs-backed, OkHttp callbacks) so it fits this codebase's
 * existing conventions rather than introducing a new pattern.
 *
 * Server is assumed HTTP-only (no TLS handling here) per the user's setup.
 */
public class EmbyManager {
    private static EmbyManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final OkHttpClient httpClient;

    private String host, userId, accessToken;
    private volatile boolean syncing = false;
    // 🚀 [Stuck-sync safety valve] If a sync ever truly hangs and never
    // reaches finishSync() (never resets `syncing`), every future attempt —
    // manual or scheduled — would silently refuse to start forever, with
    // only a "Sync already running" toast as a clue, until the whole app
    // process gets killed (e.g. a reboot). Tracked so beginSync() can detect
    // this and force a fresh attempt instead of staying stuck.
    private volatile long syncStartTime = 0;
    private static final long STUCK_SYNC_THRESHOLD_MS = 45 * 60 * 1000L; // generous — longer than the wake lock's renewable ceiling
    private android.os.PowerManager.WakeLock wakeLock;

    private EmbyManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("emby_prefs", Context.MODE_PRIVATE);
        // 🚀 [Bugfix] Default OkHttp timeouts (10s each) were never set
        // explicitly — meaning a stalled connection on a flaky Wi-Fi
        // connection could hang per-request for however long the platform
        // defaults allow before failing, repeatedly, across a sync of
        // thousands of requests. Explicit, deliberate values instead: fail
        // fast on a truly dead connection attempt, but tolerate normal slow
        // transfers (large lossless files) without spuriously timing out
        // mid-download.
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.host = prefs.getString("host", null);
        this.userId = prefs.getString("user_id", null);
        this.accessToken = prefs.getString("access_token", null);
    }

    public static EmbyManager getInstance(Context context) {
        if (instance == null) {
            instance = new EmbyManager(context);
        }
        return instance;
    }

    public boolean isEnabled() {
        return accessToken != null && host != null;
    }

    public String getHost() {
        return host;
    }

    public void logout() {
        host = null;
        userId = null;
        accessToken = null;
        prefs.edit().clear().apply();
    }

    private String deviceId() {
        return "y1-" + android.os.Build.SERIAL;
    }

    public void login(final String host, final String user, final String pass, final LoginCallback callback) {
        try {
            JSONObject body = new JSONObject();
            body.put("Username", user);
            body.put("Pw", pass);

            Request request = new Request.Builder()
                    .url("http://" + host + "/Users/AuthenticateByName")
                    .addHeader("X-Emby-Authorization",
                            "MediaBrowser Client=\"Y1Sync\", Device=\"InnioasisY1\", DeviceId=\"" + deviceId()
                                    + "\", Version=\"1.0.0\"")
                    .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body.toString()))
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (callback != null) callback.onError(e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (response.isSuccessful() && response.body() != null) {
                            JSONObject json = new JSONObject(response.body().string());
                            String newUserId = json.getJSONObject("User").getString("Id");
                            String token = json.getString("AccessToken");

                            EmbyManager.this.host = host;
                            EmbyManager.this.userId = newUserId;
                            EmbyManager.this.accessToken = token;
                            prefs.edit()
                                    .putString("host", host)
                                    .putString("user_id", newUserId)
                                    .putString("access_token", token)
                                    .apply();

                            if (callback != null) callback.onSuccess();
                        } else {
                            if (callback != null) callback.onError("HTTP Error: " + response.code());
                        }
                    } catch (Exception e) {
                        if (callback != null) callback.onError(e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Manual "Sync Now" entry point — no Wi-Fi/battery gating, since the user
     * explicitly asked for it right now.
     */
    public void startSync(final MainActivity main) {
        beginSync(main);
    }

    /**
     * Scheduled daily entry point. Gated: skips quietly (no toast — the
     * device may be asleep/unattended) if there's no Wi-Fi connection or
     * the battery is critically low and not charging. A manual tap always
     * bypasses these checks via startSync() above.
     */
    public void startAutoSync(final MainActivity main) {
        if (!isWifiConnected()) {
            return;
        }
        if (!isBatteryOkForAutoSync()) {
            return;
        }
        beginSync(main);
    }

    /**
     * Kicks off a background sync using the small non-blocking corner bubble
     * (not the full-screen scan overlay) — the device stays fully usable
     * while a sync runs. Safe to call repeatedly; ignores re-entry while a
     * sync is already running.
     */
    private void beginSync(final MainActivity main) {
        if (!isEnabled()) {
            main.runOnUiThread(new Runnable() {
                public void run() {
                    Toast.makeText(main, main.t("Emby not connected. Set it up from Web Server first."),
                            Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
        if (syncing) {
            long elapsed = System.currentTimeMillis() - syncStartTime;
            if (elapsed < STUCK_SYNC_THRESHOLD_MS) {
                main.runOnUiThread(new Runnable() {
                    public void run() {
                        Toast.makeText(main, main.t("Sync already running."), Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }
            // 🚀 Stuck-sync recovery: the previous attempt never reached
            // finishSync() within a generous window, so `syncing` was
            // permanently blocking every future attempt. Force it clear and
            // proceed with a fresh sync rather than staying stuck forever.
            syncing = false;
        }
        syncing = true;
        syncStartTime = System.currentTimeMillis();

        // 🚀 [Bugfix] A multi-thousand-file sync can run well past whatever
        // screen timeout is set (especially now that short timeouts like
        // 15/30 Sec exist). Without a wake lock, the CPU can suspend
        // mid-sync — and since this app IS the device's home screen, that
        // can present as the whole device freezing rather than just this
        // app pausing. Released unconditionally in finishSync().
        try {
            android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Y1EmbySync:sync");
                // 🚀 Reference-counted by default, which would require exactly
                // as many release() calls as acquire() calls — but we now
                // renew (re-acquire) periodically during a long sync, and
                // finishSync() only ever calls release() once. Disabling
                // reference counting makes each acquire() just reset/extend
                // the timeout instead, so a single release() always fully
                // releases it regardless of how many times we renewed.
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(30 * 60 * 1000L); // safety ceiling — renewed periodically below for long syncs
            }
        } catch (Exception e) {
        }

        main.runOnUiThread(new Runnable() {
            public void run() {
                main.syncBubbleContainer.setVisibility(View.VISIBLE);
                main.syncBubbleProgress.setIndeterminate(true);
                main.syncBubbleText.setText(main.t("Emby Sync") + "...");
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                runSync(main);
            }
        }).start();
    }

    private void runSync(final MainActivity main) {
        int downloaded = 0, skipped = 0, failed = 0, pruned = 0;
        String resultMsg;
        File musicDir = StoragePaths.getMusicDir();
        long lastProgressPostTime = 0;

        try {
            SyncManifest manifest = new SyncManifest(musicDir);

            String listUrl = "http://" + host + "/Users/" + userId + "/Items"
                    + "?Recursive=true&IncludeItemTypes=Audio"
                    + "&Fields=Path,DateCreated,Album,AlbumArtist,IndexNumber"
                    + "&X-Emby-Token=" + accessToken;
            Request listRequest = new Request.Builder().url(listUrl).get().build();
            Response listResponse = httpClient.newCall(listRequest).execute();
            if (!listResponse.isSuccessful() || listResponse.body() == null) {
                finishSync(main, main.t("Failed to list library."), false);
                return;
            }
            JSONArray items = new JSONObject(listResponse.body().string()).getJSONArray("Items");
            int total = items.length();

            for (int i = 0; i < total; i++) {
                JSONObject item = items.getJSONObject(i);
                String itemId = item.getString("Id");
                String name = item.optString("Name", itemId);
                String dateCreated = item.optString("DateCreated", "");

                if (manifest.hasItem(itemId, dateCreated)) {
                    skipped++;
                    // 🚀 [Bugfix] Skips need no I/O and can churn through
                    // hundreds of items in a burst — throttle these UI posts
                    // (unlike real downloads, which are naturally paced by
                    // network I/O) so a long run of skips can't flood the
                    // main thread's message queue. Since this app IS the
                    // device's home screen, a backed-up main thread here
                    // looks like the whole device freezing, not just a
                    // laggy app.
                    long now = System.currentTimeMillis();
                    if (now - lastProgressPostTime > 150) {
                        lastProgressPostTime = now;
                        publishProgress(main, i + 1, total, main.t("Skipped") + ": " + name);
                    }
                    continue;
                }

                lastProgressPostTime = System.currentTimeMillis();
                publishProgress(main, i + 1, total, name);

                String artist = sanitize(item.optString("AlbumArtist", "Unknown Artist"));
                String album = sanitize(item.optString("Album", "Unknown Album"));
                int trackNum = item.optInt("IndexNumber", 0);
                String ext = extFor(item.optString("Container", "mp3"));
                String trackFilename = (trackNum > 0
                        ? String.format(Locale.US, "%02d - %s", trackNum, sanitize(name))
                        : sanitize(name)) + "." + ext;

                File albumDir = new File(new File(musicDir, artist), album);
                if (!albumDir.exists()) albumDir.mkdirs();
                File outFile = new File(albumDir, trackFilename);
                String relativePath = artist + "/" + album + "/" + trackFilename;

                String dlUrl = "http://" + host + "/Items/" + itemId + "/Download?api_key=" + accessToken;
                Request dlRequest = new Request.Builder().url(dlUrl).get().build();

                try {
                    Response dlResponse = httpClient.newCall(dlRequest).execute();
                    if (!dlResponse.isSuccessful() || dlResponse.body() == null) {
                        // 🚀 [Bugfix] Must close the response even on failure —
                        // an unclosed body leaks the underlying connection.
                        // Over ~1000 sequential requests this can exhaust
                        // OkHttp's connection pool, after which further
                        // requests can hang indefinitely waiting for one.
                        dlResponse.close();
                        failed++;
                        continue;
                    }
                    InputStream in = dlResponse.body().byteStream();
                    FileOutputStream out = new FileOutputStream(outFile);
                    byte[] buf = new byte[8192];
                    int n;
                    long bytesWritten = 0;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        bytesWritten += n;
                    }
                    out.close();
                    in.close();

                    // 🚀 [Bugfix] A connection that closes early but "cleanly"
                    // (no exception — read() just returns -1 sooner than
                    // expected) previously got silently accepted as a
                    // complete download. A truncated MP4/M4A is missing its
                    // structural data (often stored at the end of the file),
                    // which is exactly what "none of the extractors could
                    // read the stream" looks like on playback — while the
                    // truncation itself throws no error here to catch.
                    long expectedLength = dlResponse.body().contentLength();
                    if (expectedLength > 0 && bytesWritten != expectedLength) {
                        if (outFile.exists()) outFile.delete();
                        failed++;
                        continue;
                    }

                    manifest.recordItem(itemId, relativePath, dateCreated);
                    downloaded++;

                    // 🚀 [Bugfix] Previously batched to every 25 downloads —
                    // meaning a crash/freeze could lose up to 24 files' worth
                    // of manifest bookkeeping. Those files would still be
                    // correctly on disk (just redundantly re-downloaded next
                    // time), not lost or corrupted — but saving after every
                    // single successful download instead means a sync can
                    // resume from exactly where it stopped, not "up to 24
                    // items behind." A full manifest rewrite is small/fast
                    // enough on local storage that doing it per-item rather
                    // than batched isn't a meaningful cost.
                    try {
                        manifest.save();
                    } catch (Exception ignored) {
                    }
                } catch (IOException e) {
                    // network drop mid-write: don't leave a partial file masquerading as real
                    if (outFile.exists()) outFile.delete();
                    failed++;
                }

                // 🚀 [Bugfix] The wake lock had a flat 30-minute ceiling —
                // fine for a typical sync, but a genuinely large library
                // or slow connection could outlast it, letting the CPU
                // suspend mid-sync even with the wake lock "fix" in
                // place. Renewing periodically (as long as the loop is
                // still making progress) means only a truly stuck sync
                // ever hits the ceiling — which is the right fallback
                // for that case, rather than holding the lock forever.
                // (Coarser interval than the manifest save above — this
                // is just CPU-suspend prevention, not data safety, so it
                // doesn't need per-item granularity.)
                if ((downloaded + failed) % 25 == 0) {
                    try {
                        if (wakeLock != null && wakeLock.isHeld()) {
                            wakeLock.acquire(30 * 60 * 1000L);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            pruned = pruneRemovedItems(manifest, items, musicDir);

            manifest.save();
            resultMsg = main.t("Sync complete") + ": " + downloaded + " " + main.t("downloaded") + ", "
                    + skipped + " " + main.t("skipped")
                    + (failed > 0 ? ", " + failed + " " + main.t("failed") : "")
                    + (pruned > 0 ? ", " + pruned + " " + main.t("removed") : "");
        } catch (Exception e) {
            resultMsg = main.t("Sync failed") + ": " + e.getMessage();
        }

        finishSync(main, resultMsg, downloaded > 0 || pruned > 0);
    }

    /**
     * Removes local files (and manifest entries) for items that are still in
     * the manifest but no longer present in the server's current item list —
     * i.e. tracks deleted/removed server-side since the last sync.
     */
    private int pruneRemovedItems(SyncManifest manifest, JSONArray items, File musicDir) throws org.json.JSONException {
        java.util.Set<String> serverIds = new java.util.HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            serverIds.add(items.getJSONObject(i).getString("Id"));
        }
        int pruned = 0;
        for (String existingId : new java.util.ArrayList<>(manifest.getAllItemIds())) {
            if (!serverIds.contains(existingId)) {
                String relPath = manifest.getPath(existingId);
                if (relPath != null) {
                    File f = new File(musicDir, relPath);
                    if (f.exists()) f.delete();
                }
                manifest.removeItem(existingId);
                pruned++;
            }
        }
        return pruned;
    }

    private void publishProgress(final MainActivity main, final int current, final int total, final String track) {
        main.runOnUiThread(new Runnable() {
            public void run() {
                main.syncBubbleProgress.setIndeterminate(false);
                main.syncBubbleProgress.setMax(total);
                main.syncBubbleProgress.setProgress(current);
                main.syncBubbleText.setText(current + "/" + total + " " + track);
            }
        });
    }

    private void finishSync(final MainActivity main, final String message, final boolean somethingChanged) {
        syncing = false;
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
        }
        main.runOnUiThread(new Runnable() {
            public void run() {
                main.syncBubbleContainer.setVisibility(View.GONE);
                Toast.makeText(main, message, Toast.LENGTH_LONG).show();
                // 🚀 [Bugfix] Previously rescanned unconditionally, even when
                // a sync found nothing new — meaning every sync was followed
                // by a full blocking library rescan for no reason. Only
                // worth the (correctly still-blocking) rescan when something
                // actually changed.
                if (somethingChanged) {
                    main.startMediaLibraryScan();
                }
            }
        });
    }

    // =========================================================
    // 🚀 [Emby] Daily auto-sync via AlarmManager. API 17 predates Doze/
    // background-execution limits, so a plain repeating alarm is reliable
    // here without JobScheduler/WorkManager (neither available at this API
    // level anyway).
    // =========================================================

    public boolean isAutoSyncEnabled() {
        return prefs.getBoolean("auto_sync_enabled", false);
    }

    /** Hour of day (0-23, device local time) the daily sync fires. Default 3 AM. */
    public int getAutoSyncHour() {
        return prefs.getInt("auto_sync_hour", 3);
    }

    public void setAutoSyncHour(int hour) {
        prefs.edit().putInt("auto_sync_hour", hour).apply();
        if (isAutoSyncEnabled()) {
            scheduleAutoSync(); // re-arm with the new time
        }
    }

    public void setAutoSyncEnabled(boolean enabled) {
        prefs.edit().putBoolean("auto_sync_enabled", enabled).apply();
        if (enabled) {
            scheduleAutoSync();
        } else {
            cancelAutoSync();
        }
    }

    /** Called from BootReceiver — alarms are cleared on reboot. */
    public void rescheduleAutoSyncIfEnabled() {
        if (isAutoSyncEnabled()) {
            scheduleAutoSync();
        }
    }

    private android.app.PendingIntent autoSyncPendingIntent() {
        Intent intent = new Intent(context, com.themoon.y1.EmbyAutoSyncReceiver.class);
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        // API 23+ requires an explicit mutability flag; irrelevant on the Y1's
        // API 17 but keeps this correct if ever run on a newer emulator.
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
        }
        return android.app.PendingIntent.getBroadcast(context, 0, intent, flags);
    }

    private void scheduleAutoSync() {
        android.app.AlarmManager am = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, getAutoSyncHour());
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1); // today's slot already passed, start tomorrow
        }

        am.setRepeating(android.app.AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(),
                android.app.AlarmManager.INTERVAL_DAY, autoSyncPendingIntent());
    }

    private void cancelAutoSync() {
        android.app.AlarmManager am = (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(autoSyncPendingIntent());
    }

    /** Used only to gate scheduled auto-sync — manual "Sync Now" ignores this. */
    private boolean isWifiConnected() {
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm == null || !wm.isWifiEnabled()) return false;
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            return info != null && info.getNetworkId() != -1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Used only to gate scheduled auto-sync — manual "Sync Now" ignores this.
     * Skips only when critically low (<15%) and not charging; a portable
     * device shouldn't have a background job draining it further.
     */
    private boolean isBatteryOkForAutoSync() {
        try {
            Intent batteryStatus = context.registerReceiver(null,
                    new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryStatus == null) return true; // can't determine — don't block on uncertainty
            int level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            int status = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
            if (charging || level < 0 || scale <= 0) return true;
            int pct = (level * 100) / scale;
            return pct >= 15;
        } catch (Exception e) {
            return true;
        }
    }

    private String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String extFor(String container) {
        return (container != null && container.length() > 0) ? container.toLowerCase(Locale.US) : "mp3";
    }

    public interface LoginCallback {
        void onSuccess();
        void onError(String errorMsg);
    }
}

