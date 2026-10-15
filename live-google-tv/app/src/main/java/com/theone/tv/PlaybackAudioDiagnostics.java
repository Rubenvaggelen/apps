package com.theone.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;

/** On-device diagnosis; no stream URLs, tokens, or license details are logged. */
final class PlaybackAudioDiagnostics {
    private PlaybackAudioDiagnostics() {}

    static String describe(ExoPlayer player, AudioManager audioManager) {
        StringBuilder text = new StringBuilder();
        if (player == null) return "Geen actieve videospeler.";
        int volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        text.append("Android mediavolume: ").append(volume).append(" / ").append(max).append("\n");
        text.append("Spelervolume: ").append(Math.round(player.getVolume() * 100)).append("%\n");
        text.append("Afspelen: ").append(player.getPlaybackState() == Player.STATE_READY ? "gereed" :
            player.getPlaybackState() == Player.STATE_BUFFERING ? "laden" : "niet gereed").append("\n\n");
        int audioTracks = 0, supported = 0, selected = 0;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                audioTracks++;
                if (group.isTrackSupported(i)) supported++;
                if (group.isTrackSelected(i)) selected++;
                Format format = group.getTrackFormat(i);
                text.append("Audio ").append(audioTracks).append(": ")
                    .append(format.sampleMimeType == null ? "onbekend" : format.sampleMimeType)
                    .append(" | ")
                    .append(format.language == null ? "taal onbekend" : format.language)
                    .append(" | ")
                    .append(format.channelCount > 0 ? format.channelCount + " kanalen" : "kanalen onbekend")
                    .append(" | ")
                    .append(group.isTrackSupported(i) ? "ondersteund" : "niet ondersteund")
                    .append(group.isTrackSelected(i) ? " | geselecteerd" : "")
                    .append("\n");
            }
        }
        if (audioTracks == 0) text.append("Deze bron meldt GEEN audiotracks.\n");
        else if (supported == 0) text.append("Geen ondersteunde audiotracks: decoder ontbreekt.\n");
        else if (selected == 0) text.append("Audiotracks aanwezig maar er is GEEN spoor geselecteerd.\n");
        else text.append("Ondersteund en geselecteerd spoor aanwezig.\n");
        return text.toString();
    }

    static void show(Activity activity, ExoPlayer player) {
        AudioManager manager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return;
        new AlertDialog.Builder(activity)
            .setTitle("Audio onderzoeken")
            .setMessage(describe(player, manager) +
                "\nTestgeluid gebruikt de Android-uitvoer, onafhankelijk van de film of zender.")
            .setPositiveButton("Testgeluid", (dialog, which) -> {
                try {
                    ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 85);
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 750);
                    new Handler(Looper.getMainLooper()).postDelayed(tone::release, 1100);
                } catch (RuntimeException failure) {
                    android.widget.Toast.makeText(activity,
                        "Android kon geen testtoon starten.", android.widget.Toast.LENGTH_LONG).show();
                }
            })
            .setNeutralButton("Sluiten", null)
            .show();
    }
}
