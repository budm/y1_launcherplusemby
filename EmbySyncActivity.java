package com.themoon.y1.emby; // TODO: adjust to match your actual package name

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Lists the Emby music library, diffs it against SyncManifest, and downloads
 * new/changed tracks into Artist/Album folders that the launcher's media
 * scanner should already index.
 *
 * INTEGRATION NOTE: after a successful sync, trigger whatever rescan method
 * the launcher's "Custom Embedded Media Scanner" exposes (per the README)
 * rather than relying on the ACTION_MEDIA_MOUNTED broadcast used here as a
 * placeholder — this repo doesn't use the system scanner, so that broadcast
 * may do nothing.
 */
public class EmbySyncActivity extends Activity {
    private ProgressBar spinner, downloadBar;
    private TextView percentText, trackText, titleText;
    private SyncTask syncTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emby_sync); // TODO: adjust R reference to your module

        spinner = findViewById(R.id.spinner);
        downloadBar = findViewById(R.id.downloadBar);
        percentText = findViewById(R.id.percentText);
        trackText = findViewById(R.id.trackText);
        titleText = findViewById(R.id.titleText);

        syncTask = new SyncTask();
        syncTask.execute();
    }

    @Override
    public void onBackPressed() {
        if (syncTask != null && syncTask.getStatus() == AsyncTask.Status.RUNNING) {
            syncTask.cancel(true);
            titleText.setText("Cancelling…");
        } else {
            super.onBackPressed();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("emby_config", MODE_PRIVATE);
    }

    private class SyncTask extends AsyncTask<Void, SyncProgress, String> {
        private File currentOutFile; // tracks the file being written, for cleanup on cancel/error

        @Override
        protected void onPreExecute() {
            spinner.setVisibility(View.VISIBLE);
            downloadBar.setVisibility(View.GONE);
            percentText.setVisibility(View.GONE);
            trackText.setText("Fetching library list…");
        }

        @Override
        protected String doInBackground(Void... v) {
            String host = prefs().getString("host", null);
            String userId = prefs().getString("user_id", null);
            String token = prefs().getString("access_token", null);
            if (host == null || token == null) return "Not connected. Set up server first.";

            File musicDir = new File(Environment.getExternalStorageDirectory(), "Music");
            if (!musicDir.exists()) musicDir.mkdirs();

            try {
                SyncManifest manifest = new SyncManifest(musicDir);

                URL url = new URL("http://" + host + "/Users/" + userId + "/Items"
                        + "?Recursive=true&IncludeItemTypes=Audio"
                        + "&Fields=Path,DateCreated,Album,AlbumArtist,IndexNumber"
                        + "&X-Emby-Token=" + token);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                if (conn.getResponseCode() != 200) return "Failed to list library.";
                JSONArray items = new JSONObject(readStream(conn.getInputStream())).getJSONArray("Items");

                int total = items.length();
                int downloaded = 0, skipped = 0;
                publishProgress(new SyncProgress(false, 0, total, "Starting…"));

                for (int i = 0; i < total; i++) {
                    if (isCancelled()) {
                        cleanupPartial();
                        return "Cancelled. " + downloaded + " downloaded.";
                    }

                    JSONObject item = items.getJSONObject(i);
                    String itemId = item.getString("Id");
                    String name = item.optString("Name", itemId);
                    String dateCreated = item.optString("DateCreated", "");

                    if (manifest.hasItem(itemId, dateCreated)) {
                        skipped++;
                        publishProgress(new SyncProgress(false, i + 1, total, "Skipped: " + name));
                        continue;
                    }

                    publishProgress(new SyncProgress(false, i + 1, total, name));

                    String artist = sanitizeFilename(item.optString("AlbumArtist", "Unknown Artist"));
                    String album = sanitizeFilename(item.optString("Album", "Unknown Album"));
                    int trackNum = item.optInt("IndexNumber", 0);
                    String ext = guessExtension(item.optString("Container", "mp3"));
                    String trackFilename = (trackNum > 0
                            ? String.format("%02d - %s", trackNum, sanitizeFilename(name))
                            : sanitizeFilename(name)) + "." + ext;

                    File albumDir = new File(new File(musicDir, artist), album);
                    if (!albumDir.exists()) albumDir.mkdirs();
                    File outFile = new File(albumDir, trackFilename);
                    String relativePath = artist + "/" + album + "/" + trackFilename;

                    currentOutFile = outFile; // mark in-flight before writing a single byte

                    try {
                        URL dlUrl = new URL("http://" + host + "/Items/" + itemId + "/Download?api_key=" + token);
                        HttpURLConnection dlConn = (HttpURLConnection) dlUrl.openConnection();
                        InputStream in = dlConn.getInputStream();
                        FileOutputStream out = new FileOutputStream(outFile);
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            if (isCancelled()) {
                                out.close();
                                in.close();
                                cleanupPartial();
                                return "Cancelled. " + downloaded + " downloaded.";
                            }
                            out.write(buf, 0, n);
                        }
                        out.close();
                        in.close();
                    } catch (IOException e) {
                        // network drop mid-write: don't let a partial file masquerade as a real track
                        cleanupPartial();
                        publishProgress(new SyncProgress(false, i + 1, total, "Failed, retrying next: " + name));
                        continue; // skip this track, keep going rather than aborting the whole sync
                    }

                    currentOutFile = null; // write succeeded, no longer "in flight"
                    manifest.recordItem(itemId, relativePath, dateCreated);
                    downloaded++;
                }

                manifest.save();
                return "Done. Downloaded " + downloaded + ", skipped " + skipped + ".";
            } catch (Exception e) {
                cleanupPartial();
                return "Sync failed: " + e.getMessage();
            }
        }

        private void cleanupPartial() {
            if (currentOutFile != null && currentOutFile.exists()) {
                currentOutFile.delete();
            }
            currentOutFile = null;
        }

        @Override
        protected void onProgressUpdate(SyncProgress... values) {
            SyncProgress p = values[0];
            if (spinner.getVisibility() == View.VISIBLE) {
                spinner.setVisibility(View.GONE);
                downloadBar.setVisibility(View.VISIBLE);
                percentText.setVisibility(View.VISIBLE);
            }
            int pct = p.total > 0 ? (p.current * 100 / p.total) : 0;
            downloadBar.setProgress(pct);
            percentText.setText(p.current + " / " + p.total + " (" + pct + "%)");
            trackText.setText(p.trackName);
        }

        @Override
        protected void onPostExecute(String result) {
            titleText.setText("Sync Complete");
            trackText.setText(result);
        }
    }

    private String guessExtension(String container) {
        return container != null && container.length() > 0 ? container.toLowerCase() : "mp3";
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
