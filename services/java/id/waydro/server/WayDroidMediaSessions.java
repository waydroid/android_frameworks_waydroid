/*
 * Copyright (C) 2026 The WayDroid Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package id.waydro.server;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.os.BackgroundThread;

import id.waydro.waydroid.IMediaPlayers;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends Android's active media sessions to the host, which shows each one as a player in the
 * desktop's media controls, and runs the host's commands on them.
 *
 * @hide
 */
class WayDroidMediaSessions {
    private static final String TAG = "WayDroidMediaSessions";
    private static final int ART_MAX_SIZE = 512;
    private static final int ART_QUALITY = 90;
    // A change often comes as several callbacks; one update covers them.
    private static final long UPDATE_DELAY_MS = 50;

    interface Listener {
        void onRaise(String packageName);

        String getAppName(String packageName);
    }

    private final Context mContext;
    private final IMediaPlayers mHost;
    private final Listener mListener;
    // Everything but isShown() runs on this handler.
    private final Handler mHandler = BackgroundThread.getHandler();
    private final Map<MediaSession.Token, Session> mSessions = new HashMap<>();
    private final Set<MediaSession.Token> mShown = ConcurrentHashMap.newKeySet();
    private int mNextId = 1;

    private final class Session extends MediaController.Callback {
        final String id = String.valueOf(mNextId++);
        final MediaController controller;
        boolean updatePending;
        Bitmap artBitmap;
        byte[] art;

        Session(MediaController controller) {
            this.controller = controller;
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            scheduleUpdate(this);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            scheduleUpdate(this);
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    }

    WayDroidMediaSessions(Context context, IMediaPlayers host, Listener listener) {
        mContext = context;
        mHost = host;
        mListener = listener;
    }

    void start() {
        MediaSessionManager manager = mContext.getSystemService(MediaSessionManager.class);
        // A null listener component needs MEDIA_CONTENT_CONTROL, which the system server has.
        manager.addOnActiveSessionsChangedListener(new MediaSessionManager.OnActiveSessionsChangedListener() {
            @Override
            public void onActiveSessionsChanged(List<MediaController> controllers) {
                refresh();
            }
        }, (ComponentName) null, mHandler);

        // Off the boot thread, which must not wait on the host
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mHost.registerListener(new IMediaPlayers.IMediaPlayersCallback.Stub() {
                        @Override
                        public void onCommand(final String id, final String command, final long positionMs) {
                            mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    runCommand(id, command, positionMs);
                                }
                            });
                        }
                    });
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to register with the host", e);
                }
                refresh();
            }
        });
    }

    /** Whether the host shows the session with this token. Any thread. */
    boolean isShown(MediaSession.Token token) {
        return token != null && mShown.contains(token);
    }

    private void refresh() {
        MediaSessionManager manager = mContext.getSystemService(MediaSessionManager.class);
        List<MediaController> controllers = manager.getActiveSessions(null);
        Map<MediaSession.Token, Session> gone = new HashMap<>(mSessions);
        for (MediaController c : controllers) {
            MediaSession.Token token = c.getSessionToken();
            if (gone.remove(token) != null) {
                continue;
            }
            Session s = new Session(c);
            mSessions.put(token, s);
            mShown.add(token);
            c.registerCallback(s, mHandler);
            scheduleUpdate(s);
        }
        for (Map.Entry<MediaSession.Token, Session> e : gone.entrySet()) {
            Session s = e.getValue();
            s.controller.unregisterCallback(s);
            mSessions.remove(e.getKey());
            mShown.remove(e.getKey());
            try {
                mHost.remove(s.id);
            } catch (RemoteException ex) {
                Log.e(TAG, ex.getLocalizedMessage(), ex);
            }
        }
    }

    private void scheduleUpdate(final Session s) {
        if (s.updatePending) {
            return;
        }
        s.updatePending = true;
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                s.updatePending = false;
                if (mSessions.get(s.controller.getSessionToken()) != s) {
                    return;
                }
                try {
                    mHost.update(describe(s));
                } catch (RemoteException | RuntimeException e) {
                    Log.w(TAG, "Sending " + s.controller.getPackageName() + " failed", e);
                }
            }
        }, UPDATE_DELAY_MS);
    }

    private IMediaPlayers.Player describe(Session s) {
        MediaController c = s.controller;
        IMediaPlayers.Player p = new IMediaPlayers.Player();
        p.id = s.id;
        p.package_name = c.getPackageName();
        p.app_name = mListener.getAppName(p.package_name);
        p.title = "";
        p.artist = "";
        p.album = "";

        MediaMetadata m = c.getMetadata();
        if (m != null) {
            p.title = text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            p.artist = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            p.album = text(m, MediaMetadata.METADATA_KEY_ALBUM);
            p.duration_ms = Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
            p.art = art(s, m);
        }

        PlaybackState state = c.getPlaybackState();
        p.status = IMediaPlayers.Status.STOPPED;
        if (state != null) {
            switch (state.getState()) {
                case PlaybackState.STATE_PLAYING:
                case PlaybackState.STATE_BUFFERING:
                case PlaybackState.STATE_CONNECTING:
                case PlaybackState.STATE_FAST_FORWARDING:
                case PlaybackState.STATE_REWINDING:
                case PlaybackState.STATE_SKIPPING_TO_NEXT:
                case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
                case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
                    p.status = IMediaPlayers.Status.PLAYING;
                    break;
                case PlaybackState.STATE_PAUSED:
                    p.status = IMediaPlayers.Status.PAUSED;
                    break;
                default:
                    break;
            }
            long position = state.getPosition();
            if (state.getState() == PlaybackState.STATE_PLAYING) {
                p.speed = state.getPlaybackSpeed();
                position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * p.speed);
            }
            p.position_ms = Math.max(0, position);
            p.actions = state.getActions();
        }
        return p;
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String key : keys) {
            CharSequence v = m.getText(key);
            if (v != null && v.length() > 0) {
                return v.toString();
            }
        }
        return "";
    }

    /** The track's picture as a JPEG, encoded again only when the picture changes. */
    private static byte[] art(Session s, MediaMetadata m) {
        Bitmap bitmap = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bitmap == null) {
            bitmap = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }
        if (bitmap == null) {
            bitmap = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        // Each getMetadata() unparcels a new bitmap, so compare the pixels
        if (bitmap == null ? s.artBitmap == null : s.artBitmap != null && bitmap.sameAs(s.artBitmap)) {
            return s.art;
        }
        s.artBitmap = bitmap;
        s.art = null;
        if (bitmap == null) {
            return null;
        }
        float scale = Math.min(1f, (float) ART_MAX_SIZE / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        Bitmap scaled = scale < 1f
                ? Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale),
                        Math.round(bitmap.getHeight() * scale), true)
                : bitmap;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (scaled.compress(Bitmap.CompressFormat.JPEG, ART_QUALITY, out)) {
            s.art = out.toByteArray();
        }
        if (scaled != bitmap) {
            scaled.recycle();
        }
        return s.art;
    }

    private void runCommand(String id, String command, long positionMs) {
        Session s = null;
        for (Session candidate : mSessions.values()) {
            if (candidate.id.equals(id)) {
                s = candidate;
                break;
            }
        }
        if (s == null) {
            return;
        }
        MediaController.TransportControls t = s.controller.getTransportControls();
        switch (command) {
            case "play": t.play(); break;
            case "pause": t.pause(); break;
            case "stop": t.stop(); break;
            case "next": t.skipToNext(); break;
            case "previous": t.skipToPrevious(); break;
            case "seek": t.seekTo(Math.max(0, positionMs)); break;
            case "raise": mListener.onRaise(s.controller.getPackageName()); break;
            default:
                Log.w(TAG, "Unknown command " + command);
                break;
        }
    }
}
