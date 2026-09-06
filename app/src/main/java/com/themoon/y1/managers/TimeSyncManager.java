package com.themoon.y1.managers;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Fetches accurate time + timezone from the network and applies both.
 *
 * This hardware has no cell radio (no NITZ) and no GPS, so there's no
 * on-device source of truth for time/timezone the way a phone would have.
 * Time comes from real NTP (time.google.com) — the actual protocol, not a
 * wrapper API that can shut down — and timezone from ip-api.com's free
 * IP-geolocation lookup. (An earlier version used worldtimeapi.org for
 * both; that service has since shut down.)
 *
 * Applied via the same self-verifying, multi-format root shell approach
 * already proven in MainActivity.buildDateTimeUI()'s manual "Apply" button —
 * SystemClock.setCurrentTimeMillis()/AlarmManager.setTimeZone() are not
 * reliably usable on this ROM (that's presumably why the manual flow already
 * goes straight to su), so this mirrors that exact proven path rather than
 * introducing an alternate one.
 */
public class TimeSyncManager {
    private static TimeSyncManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    private volatile boolean syncing = false;

    private TimeSyncManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("time_sync_prefs", Context.MODE_PRIVATE);
    }

    public static TimeSyncManager getInstance(Context context) {
        if (instance == null) {
            instance = new TimeSyncManager(context);
        }
        return instance;
    }

    public boolean isAutoTimeEnabled() {
        return prefs.getBoolean("auto_time_enabled", false);
    }

    public void setAutoTimeEnabled(boolean enabled) {
        prefs.edit().putBoolean("auto_time_enabled", enabled).apply();
    }

    public String getLastSyncStatus() {
        return prefs.getString("last_sync_status", "Never synced");
    }

    /** Opportunistic trigger (Wi-Fi connect, app startup) — no-ops if disabled. */
    public void syncIfEnabled() {
        if (isAutoTimeEnabled()) {
            syncNow(null);
        }
    }

    /** Manual "Sync Now" entry point too — callback runs on a background thread. */
    public void syncNow(final SyncCallback callback) {
        if (syncing) {
            if (callback != null) callback.onDone("Sync already running");
            return;
        }
        syncing = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = doSync();
                syncing = false;
                prefs.edit().putString("last_sync_status", result).apply();
                if (callback != null) callback.onDone(result);
            }
        }).start();
    }

    private String doSync() {
        try {
            // 🚀 [worldtimeapi.org shut down — confirmed via their own site,
            // "the service has shut down... costs became too high to justify
            // for a free service." Replaced with two independent, more
            // durable sources: real NTP for time (the actual protocol, not a
            // wrapper API that can vanish) and ip-api.com — a long-running,
            // widely-used free geolocation service — for timezone only.]
            long unixMillis = fetchNtpTime("time.google.com");
            long unixSeconds = unixMillis / 1000L;
            String tzId = fetchTimezoneFromIp();

            boolean applied = applyTimeAndZone(unixSeconds, tzId);
            return applied ? ("Synced: " + tzId) : "Sync failed: root access required";
        } catch (Exception e) {
            return "Sync failed: " + e.getMessage();
        }
    }

    /** Minimal SNTP client — sends a client request, reads the transmit timestamp back. */
    private long fetchNtpTime(String ntpServer) throws Exception {
        java.net.DatagramSocket socket = new java.net.DatagramSocket();
        try {
            socket.setSoTimeout(8000);
            java.net.InetAddress address = java.net.InetAddress.getByName(ntpServer);
            byte[] buf = new byte[48];
            buf[0] = 0x1B; // client request, NTP version 3, mode 3
            java.net.DatagramPacket request = new java.net.DatagramPacket(buf, buf.length, address, 123);
            socket.send(request);

            java.net.DatagramPacket response = new java.net.DatagramPacket(buf, buf.length);
            socket.receive(response);

            // Transmit Timestamp: seconds at bytes 40-43, fraction at 44-47 (NTP epoch = 1900).
            long seconds = 0;
            for (int i = 40; i < 44; i++) seconds = (seconds << 8) | (buf[i] & 0xFF);
            long fraction = 0;
            for (int i = 44; i < 48; i++) fraction = (fraction << 8) | (buf[i] & 0xFF);

            long millisSinceNtpEpoch = (seconds * 1000L) + ((fraction * 1000L) / 0x100000000L);
            long ntpToUnixOffsetMillis = 2208988800L * 1000L;
            return millisSinceNtpEpoch - ntpToUnixOffsetMillis;
        } finally {
            socket.close();
        }
    }

    private String fetchTimezoneFromIp() throws Exception {
        URL url = new URL("http://ip-api.com/json/?fields=timezone");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        if (conn.getResponseCode() != 200) throw new java.io.IOException("HTTP " + conn.getResponseCode());

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();

        JSONObject json = new JSONObject(sb.toString());
        return json.getString("timezone");
    }

    private boolean applyTimeAndZone(long unixSeconds, String tzId) {
        try {
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(tzId));
            cal.setTimeInMillis(unixSeconds * 1000L);
            int y = cal.get(Calendar.YEAR);
            int mo = cal.get(Calendar.MONTH) + 1;
            int d = cal.get(Calendar.DAY_OF_MONTH);
            int h = cal.get(Calendar.HOUR_OF_DAY);
            int mi = cal.get(Calendar.MINUTE);

            String targetYMD = String.format(Locale.US, "%04d%02d%02d", y, mo, d);
            String dateToolbox = String.format(Locale.US, "%04d%02d%02d.%02d%02d%02d", y, mo, d, h, mi, 0);
            String datePosix = String.format(Locale.US, "%02d%02d%02d%02d%04d.00", mo, d, h, mi, y);
            String dateString = String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:%02d", y, mo, d, h, mi, 0);

            String cmd = "settings put global auto_time 0; settings put system auto_time 0; "
                    + "setprop persist.sys.timezone " + tzId + "; "
                    + "date -s " + dateToolbox + "; "
                    + "if [ \"$(date +%Y%m%d)\" != \"" + targetYMD + "\" ]; then "
                    + "  date " + datePosix + "; "
                    + "  if [ \"$(date +%Y%m%d)\" != \"" + targetYMD + "\" ]; then "
                    + "    date -s \"" + dateString + "\"; "
                    + "  fi; "
                    + "fi; "
                    + "hwclock -w; sync";

            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            proc.waitFor();

            context.sendBroadcast(new Intent(Intent.ACTION_TIME_CHANGED));
            Intent tzIntent = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
            tzIntent.putExtra("time-zone", tzId);
            context.sendBroadcast(tzIntent);

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public interface SyncCallback {
        void onDone(String resultMessage);
    }
}
