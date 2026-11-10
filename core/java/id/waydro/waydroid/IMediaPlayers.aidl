package id.waydro.waydroid;

interface IMediaPlayers {
    void registerListener(in IMediaPlayersCallback listener);

    oneway void update(in Player player);

    oneway void remove(in String id);

    interface IMediaPlayersCallback {
        // command: play, pause, stop, next, previous, seek (to position_ms), raise
        oneway void onCommand(String id, String command, long position_ms);
    }

    @Backing(type="byte")
    enum Status {
        STOPPED = 0,
        PLAYING = 1,
        PAUSED = 2,
    }

    parcelable Player {
        String id;
        String package_name;
        String app_name;
        String title;
        String artist;
        String album;
        long duration_ms;
        Status status = Status.STOPPED;
        // the position when the player was sent, which advances at speed
        long position_ms;
        float speed;
        // PlaybackState actions
        long actions;
        // JPEG, at most 512 pixels on a side
        @nullable byte[] art;
    }
}
