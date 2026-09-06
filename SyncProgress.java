package com.themoon.y1.emby; // TODO: adjust to match your actual package name

/** Carries progress info from the background sync to the UI thread. */
class SyncProgress {
    final boolean listingPhase;
    final int current, total;
    final String trackName;

    SyncProgress(boolean listing, int current, int total, String trackName) {
        this.listingPhase = listing;
        this.current = current;
        this.total = total;
        this.trackName = trackName;
    }
}
