package com.themoon.y1;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fired by AlarmManager once/day (see EmbyManager.scheduleAutoSync). Mirrors
 * BootReceiver's existing pattern: bring MainActivity forward (it's already
 * the HOME app so this just resumes it via singleTask/onNewIntent in the
 * normal case) with a flag telling it to kick off a sync.
 */
public class EmbyAutoSyncReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra("emby_auto_sync", true);
        context.startActivity(i);
    }
}
