package com.polarisunbound.app;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;

/**
 * Experiment H01: bridge Polaris AA transport to the *installed* Hush Media3
 * library service, never to official YouTube Music. No activity launch.
 *
 * This is an integration probe, NOT a port of Hush's playback engine.
 * An internalized engine requires a separate, later implementation.
 *
 * Browser connection owns the controller. A pending search is dispatched
 * only after a successful connection, never silently sent to another app.
 */
final class PolarisHushBridge {
    static final String SOURCE = "app.hush.music";
    static final String SERVICE = "app.hush.music.playback.MusicService";
    static final String MEDIA_ID = PolarisYtmBridge.MEDIA_ID;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PolarisYtmBridge.Listener listener;
    private MediaBrowserCompat browser;
    private MediaControllerCompat controller;
    private String pendingSearch;
    private String pendingMediaId;
    private boolean running;
    private int generation;
    private MediaMetadataCompat lastMetadata;

    PolarisHushBridge(Context context, PolarisYtmBridge.Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        main.post(() -> {
            if (running && browser != null) return;
            running = true;
            final int token = ++generation;
            listener.onTrace("HUSH browser connection requested");
            try {
                browser = new MediaBrowserCompat(context,
                    new ComponentName(SOURCE, SERVICE),
                    new MediaBrowserCompat.ConnectionCallback() {
                        @Override public void onConnected() {
                            if (!running || token != generation) return;
                            try {
                                controller = new MediaControllerCompat(context, browser.getSessionToken());
                                controller.registerCallback(callback, main);
                                listener.onTrace("HUSH library session connected");
                                publish();
                                dispatchPending();
                            } catch (Exception e) {
                                listener.onTrace("HUSH controller connect failed: " +
                                    e.getClass().getSimpleName());
                                publishUnavailable("Hush 세션 연결 오류");
                            }
                        }
                        @Override public void onConnectionSuspended() {
                            if (token != generation) return;
                            releaseController();
                            listener.onTrace("HUSH library session suspended");
                            publishUnavailable("Hush 연결 중단");
                        }
                        @Override public void onConnectionFailed() {
                            if (token != generation) return;
                            releaseController();
                            listener.onTrace("HUSH browser connection failed; verify app installed");
                            publishUnavailable("Hush 설치 및 실행 상태를 확인하세요");
                        }
                    }, null);
                browser.connect();
            } catch (Exception e) {
                listener.onTrace("HUSH browser error " + e.getClass().getSimpleName());
                publishUnavailable("Hush 연결 실패");
            }
        });
    }

    void stop() {
        main.post(() -> {
            ++generation;
            running = false;
            pendingSearch = null;
            pendingMediaId = null;
            releaseController();
            if (browser != null) {
                try { browser.disconnect(); } catch (Exception ignored) {}
                browser = null;
            }
        });
    }

    private final MediaControllerCompat.Callback callback = new MediaControllerCompat.Callback() {
        @Override public void onMetadataChanged(MediaMetadataCompat metadata) { publish(); }
        @Override public void onPlaybackStateChanged(PlaybackStateCompat state) { publish(); }
        @Override public void onSessionDestroyed() {
            listener.onTrace("HUSH session destroyed");
            releaseController();
            publishUnavailable("Hush 재생 세션 종료");
        }
    };

    private void releaseController() {
        if (controller != null) {
            try { controller.unregisterCallback(callback); } catch (Exception ignored) {}
            controller = null;
        }
    }

    private void publishUnavailable(String reason) {
        if (!running) return;
        lastMetadata = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, MEDIA_ID)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, reason).build();
        listener.onMirror(lastMetadata, new PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PAUSED, 0, 0f)
            .setActions(0).build());
    }

    private void publish() {
        if (!running || controller == null) return;
        MediaMetadataCompat source = controller.getMetadata();
        PlaybackStateCompat state = controller.getPlaybackState();
        MediaMetadataCompat.Builder md = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, MEDIA_ID)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,
                source == null ? "Hush" : safe(source.getString(MediaMetadataCompat.METADATA_KEY_TITLE)))
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,
                source == null ? "" : safe(source.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)))
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,
                source == null ? "" : safe(source.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)))
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,
                source == null ? 0 : source.getLong(MediaMetadataCompat.METADATA_KEY_DURATION));
        if (source != null) {
            Bitmap art = source.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
            if (art == null) art = source.getBitmap(MediaMetadataCompat.METADATA_KEY_ART);
            if (art != null) {
                try {
                    if (art.getWidth() > 256 || art.getHeight() > 256) {
                        float scale = Math.min(256f / art.getWidth(), 256f / art.getHeight());
                        art = Bitmap.createScaledBitmap(art,
                            Math.max(1, Math.round(art.getWidth() * scale)),
                            Math.max(1, Math.round(art.getHeight() * scale)), true);
                    }
                    md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
                } catch (Exception ignored) {}
            }
        }
        lastMetadata = md.build();
        PlaybackStateCompat mapped = state == null
            ? new PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 0f).setActions(0).build()
            : new PlaybackStateCompat.Builder()
                .setState(state.getState(), Math.max(0, state.getPosition()),
                    state.getPlaybackSpeed(), state.getLastPositionUpdateTime())
                .setActions(state.getActions()).build();
        listener.onMirror(lastMetadata, mapped);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    void searchAndPlay(String query) {
        if (query == null || query.trim().isEmpty()) return;
        main.post(() -> {
            pendingMediaId = null;
            pendingSearch = query.trim();
            start();
            dispatchPending();
        });
    }

    void playPlaylist(String playlistId) {
        if (playlistId == null || !playlistId.matches("[A-Za-z0-9_-]{6,120}")) {
            listener.onTrace("HUSH invalid playlist id");
            return;
        }
        main.post(() -> {
            pendingSearch = null;
            pendingMediaId = "online_playlist/" + playlistId;
            start();
            dispatchPending();
        });
    }

    private void dispatchPending() {
        if (!running || controller == null) return;
        try {
            if (pendingSearch != null) {
                String query = pendingSearch;
                pendingSearch = null;
                controller.getTransportControls().playFromSearch(query, Bundle.EMPTY);
                listener.onTrace("HUSH playFromSearch dispatched; track selection unverified");
            } else if (pendingMediaId != null) {
                String id = pendingMediaId;
                pendingMediaId = null;
                controller.getTransportControls().playFromMediaId(id, Bundle.EMPTY);
                listener.onTrace("HUSH playFromMediaId dispatched; playback unverified");
            }
        } catch (Exception e) {
            listener.onTrace("HUSH media command error: " + e.getClass().getSimpleName());
        }
    }

    private interface Command { void run(MediaControllerCompat.TransportControls t); }
    private void forward(String label, Command command) {
        main.post(() -> {
            if (controller == null) {
                listener.onTrace("HUSH " + label + " unavailable: no session");
                start();
                return;
            }
            try {
                command.run(controller.getTransportControls());
                listener.onTrace("HUSH forwarded " + label);
            } catch (Exception e) {
                listener.onTrace("HUSH " + label + " failed: " + e.getClass().getSimpleName());
            }
        });
    }
    void play() { forward("PLAY", t -> t.play()); }
    void pause() { forward("PAUSE", t -> t.pause()); }
    void next() { forward("NEXT", t -> t.skipToNext()); }
    void previous() { forward("PREVIOUS", t -> t.skipToPrevious()); }
    void seek(long pos) { forward("SEEK", t -> t.seekTo(Math.max(0, pos))); }
    void custom(String action) {
        listener.onTrace("HUSH custom action not yet mapped: " + action);
    }
}
