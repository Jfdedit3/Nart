package com.nartya.app.player;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import java.util.Arrays;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.util.Rational;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Luminosité, volume multimédia et touches volume (capturées dans MainActivity.dispatchKeyEvent,
 * relayées au JS par l'événement `volume`). PiP natif : la WebView Android n'a pas le PiP HTML.
 */
@CapacitorPlugin(name = "NartyaPlayer")
public class PlayerPlugin extends Plugin {

    private static PlayerPlugin instance;
    private MediaSession mediaSession;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus;
    private boolean playing;
    private boolean receiverRegistered;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (playing) dispatchControl("pause");
        }
    };
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change < 0) {
            hasFocus = false;
            dispatchControl("pause");
        }
    };

    public static void dispatchControl(String action) {
        PlayerPlugin plugin = instance;
        if (plugin == null || action == null) return;
        plugin.notifyListeners("mediaControl", new JSObject().put("action", action));
    }

    @PluginMethod
    public void setPlayback(PluginCall call) {
        boolean active = Boolean.TRUE.equals(call.getBoolean("active", true));
        boolean nextPlaying = Boolean.TRUE.equals(call.getBoolean("playing", false));
        long position = Math.max(0, call.getDouble("position", 0.0).longValue());
        long duration = Math.max(0, call.getDouble("duration", 0.0).longValue());
        String title = call.getString("title", "Nartya");
        getActivity().runOnUiThread(() -> {
            if (mediaSession == null) { call.resolve(); return; }
            if (nextPlaying && !hasFocus) {
                int result;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (focusRequest == null) focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                        .setOnAudioFocusChangeListener(focusListener).build();
                    result = audio.requestAudioFocus(focusRequest);
                } else {
                    result = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                }
                hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                if (!hasFocus) dispatchControl("pause");
            } else if (!nextPlaying && hasFocus) { abandonFocus(); }
            playing = nextPlaying && hasFocus;
            mediaSession.setActive(active);
            mediaSession.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration).build());
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, position, playing ? 1f : 0f)
                .build());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { getActivity().setPictureInPictureParams(pipParams()); } catch (IllegalStateException ignored) { }
            }
            call.resolve();
        });
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        else audio.abandonAudioFocus(focusListener);
        hasFocus = false;
    }
    private static boolean captureVolume = false;
    /** Vidéo en lecture : quitter l'app passe en PiP. */
    private static volatile boolean autoPip = false;
    private static volatile Rational pipRatio = new Rational(16, 9);

    @Override
    public void load() {
        instance = this;
        audio = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        mediaSession = new MediaSession(getContext(), "Nartya");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { dispatchControl("play"); }
            @Override public void onPause() { dispatchControl("pause"); }
            @Override public void onStop() { dispatchControl("pause"); }
            @Override public void onFastForward() { dispatchControl("forward"); }
            @Override public void onRewind() { dispatchControl("rewind"); }
            @Override public void onSeekTo(long position) {
                notifyListeners("mediaControl", new JSObject().put("action", "seek").put("position", position));
            }
        }, new Handler(Looper.getMainLooper()));
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
        else getContext().registerReceiver(noisy, filter);
        receiverRegistered = true;
    }

    @Override
    protected void handleOnDestroy() {
        autoPip = false;
        captureVolume = false;
        abandonFocus();
        if (mediaSession != null) { mediaSession.release(); mediaSession = null; }
        if (receiverRegistered) { getContext().unregisterReceiver(noisy); receiverRegistered = false; }
        if (instance == this) instance = null;
        super.handleOnDestroy();
    }

    /** 0..1, ou -1 pour rendre la main au système. */
    @PluginMethod
    public void setBrightness(PluginCall call) {
        final float v = call.getFloat("value", -1f);
        getActivity().runOnUiThread(() -> {
            WindowManager.LayoutParams lp = getActivity().getWindow().getAttributes();
            lp.screenBrightness = (v < 0)
                ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                : Math.max(0.01f, Math.min(1f, v));
            getActivity().getWindow().setAttributes(lp);
        });
        call.resolve();
    }

    /** HUD de l'app à la place de l'UI système. */
    @PluginMethod
    public void setVolumeCapture(PluginCall call) {
        captureVolume = Boolean.TRUE.equals(call.getBoolean("enabled", false));
        call.resolve();
    }

    /** Partagé avec les touches physiques. */
    @PluginMethod
    public void getVolume(PluginCall call) {
        AudioManager audio = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        JSObject data = new JSObject();
        data.put("value", current);
        data.put("max", max);
        call.resolve(data);
    }

    /** Ratio 0..1 ; synchronise le HUD WebView. */
    @PluginMethod
    public void setVolume(PluginCall call) {
        float ratio = call.getFloat("value", 0f);
        AudioManager audio = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = Math.round(Math.max(0f, Math.min(1f, ratio)) * max);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        emitVolume(target, max);
        call.resolve();
    }

    @PluginMethod
    public void setAutoPip(PluginCall call) {
        autoPip = Boolean.TRUE.equals(call.getBoolean("enabled", false));
        int width = call.getInt("width", 16);
        int height = call.getInt("height", 9);
        if (width > 0 && height > 0) pipRatio = clampRatio(width, height);
        Activity activity = getActivity();
        // Android 12+ entre seul en PiP ; avant, MainActivity.onUserLeaveHint s'en charge.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.runOnUiThread(() -> {
                try {
                    activity.setPictureInPictureParams(pipParams());
                } catch (IllegalStateException ignored) {
                    // PiP désactivé pour l'app dans les réglages.
                }
            });
        }
        call.resolve();
    }

    @PluginMethod
    public void enterPip(PluginCall call) {
        Activity activity = getActivity();
        activity.runOnUiThread(() -> {
            JSObject data = new JSObject();
            data.put("entered", enterPip(activity));
            call.resolve(data);
        });
    }

    public static boolean enterPip(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            return activity.enterPictureInPictureMode(pipParams());
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static boolean isAutoPip() {
        return autoPip;
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public static PictureInPictureParams pipParams() {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
            .setAspectRatio(pipRatio);
        if (instance != null) {
            builder.setActions(Arrays.asList(instance.pipAction("rewind", "Reculer de 10 secondes", android.R.drawable.ic_media_rew),
                instance.pipAction(instance.playing ? "pause" : "play", instance.playing ? "Pause" : "Lecture",
                    instance.playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                instance.pipAction("forward", "Avancer de 10 secondes", android.R.drawable.ic_media_ff)));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoPip).setSeamlessResizeEnabled(true);
        }
        return builder.build();
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private RemoteAction pipAction(String action, String label, int icon) {
        PendingIntent pending = PendingIntent.getBroadcast(getContext(), action.hashCode(),
            new Intent(getContext(), MediaActionReceiver.class).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new RemoteAction(Icon.createWithResource(getContext(), icon), label, label, pending);
    }

    /** Android refuse un ratio hors de [1/2.39, 2.39]. */
    static Rational clampRatio(int width, int height) {
        double ratio = (double) width / height;
        if (ratio > 2.39) return new Rational(239, 100);
        if (ratio < 1 / 2.39) return new Rational(100, 239);
        return new Rational(width, height);
    }

    /** `dismissed` : fenêtre fermée, pas agrandie. */
    public static void emitPip(boolean active, boolean dismissed) {
        if (instance == null) return;
        JSObject data = new JSObject();
        data.put("active", active);
        data.put("dismissed", dismissed);
        instance.notifyListeners("pip", data);
    }

    public static boolean isCapturingVolume() {
        return captureVolume;
    }

    /** Appelé par MainActivity à chaque appui pendant la capture. */
    public static void emitVolume(int current, int max) {
        if (instance == null) return;
        JSObject data = new JSObject();
        data.put("value", current);
        data.put("max", max);
        instance.notifyListeners("volume", data);
    }
}
