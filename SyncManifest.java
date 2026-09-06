package com.themoon.y1.emby; // TODO: adjust to match your actual package name

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Tracks which Emby items have already been downloaded, keyed by item ID,
 * so repeat syncs only pull new/changed tracks. Stored as a hidden JSON
 * file inside the music folder so it survives app reinstalls.
 */
public class SyncManifest {
    private JSONObject data;
    private File manifestFile;

    public SyncManifest(File musicDir) throws IOException, JSONException {
        manifestFile = new File(musicDir, ".emby_manifest.json");
        if (manifestFile.exists()) {
            data = new JSONObject(readFile(manifestFile));
        } else {
            data = new JSONObject();
        }
    }

    public boolean hasItem(String itemId, String dateCreated) throws JSONException {
        return data.has(itemId) && data.getJSONObject(itemId)
                .getString("dateCreated").equals(dateCreated);
    }

    public void recordItem(String itemId, String relativePath, String dateCreated) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("path", relativePath);
        entry.put("dateCreated", dateCreated);
        data.put(itemId, entry);
    }

    public void save() throws IOException {
        FileWriter fw = new FileWriter(manifestFile);
        fw.write(data.toString());
        fw.close();
    }

    private String readFile(File f) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        byte[] buf = new byte[(int) f.length()];
        fis.read(buf);
        fis.close();
        return new String(buf, "UTF-8");
    }
}
