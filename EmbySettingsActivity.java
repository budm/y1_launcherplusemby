package com.themoon.y1.emby; // TODO: adjust to match your actual package name

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Server host / username / password entry + Emby AuthenticateByName call.
 *
 * INTEGRATION NOTE: the EditText fields below are placeholders. If your
 * launcher already has a wheel-optimized virtual keyboard widget (used for
 * Wi-Fi password entry per the JJ Launcher README), swap EditText for that
 * widget class so wheel navigation/text entry matches the rest of the app.
 */
public class EmbySettingsActivity extends Activity {
    private EditText hostField, userField, passField;
    private TextView statusText;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emby_settings); // TODO: adjust R reference to your module

        prefs = getSharedPreferences("emby_config", MODE_PRIVATE);
        hostField = findViewById(R.id.hostField);
        userField = findViewById(R.id.userField);
        passField = findViewById(R.id.passField);
        statusText = findViewById(R.id.statusText);

        hostField.setText(prefs.getString("host", ""));
        userField.setText(prefs.getString("username", ""));

        findViewById(R.id.connectButton).setOnClickListener(v -> attemptAuth());
    }

    private void attemptAuth() {
        statusText.setText("Connecting…");
        new AuthTask().execute(
                hostField.getText().toString(),
                userField.getText().toString(),
                passField.getText().toString()
        );
    }

    private class AuthTask extends AsyncTask<String, Void, String[]> {
        @Override
        protected String[] doInBackground(String... args) {
            String host = args[0], user = args[1], pass = args[2];
            try {
                URL url = new URL("http://" + host + "/Users/AuthenticateByName");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Emby-Authorization",
                        "MediaBrowser Client=\"Y1Sync\", Device=\"InnioasisY1\", "
                                + "DeviceId=\"y1-" + android.os.Build.SERIAL + "\", Version=\"1.0.0\"");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("Username", user);
                body.put("Pw", pass);
                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.close();

                if (conn.getResponseCode() != 200) return null;

                InputStream in = conn.getInputStream();
                String response = readStream(in);
                JSONObject json = new JSONObject(response);
                String userId = json.getJSONObject("User").getString("Id");
                String token = json.getString("AccessToken");
                return new String[]{userId, token};
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(String[] result) {
            if (result == null) {
                statusText.setText("Connection failed. Check host/credentials.");
                return;
            }
            prefs.edit()
                    .putString("host", hostField.getText().toString())
                    .putString("username", userField.getText().toString())
                    .putString("user_id", result[0])
                    .putString("access_token", result[1])
                    .apply();
            statusText.setText("Connected.");
            // TODO: reveal/enable the "Sync Now" menu action here, however
            // your action-list/theme system exposes conditional menu items.
        }
    }

    private String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
