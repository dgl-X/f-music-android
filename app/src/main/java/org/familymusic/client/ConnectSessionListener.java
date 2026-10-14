package org.familymusic.client;

import androidx.media3.common.*;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Local callbacks must not overwrite the projected remote MediaSession state. */
final class ConnectSessionListener implements Player.Listener {
    private final Player.Listener listener;
    private final Player view;
    private final BooleanSupplier remote;
    ConnectSessionListener(Player.Listener listener,Player view,BooleanSupplier remote){this.listener=listener;this.view=view;this.remote=remote;}
    @Override public void onEvents(Player player,Player.Events events){if(!remote.getAsBoolean())listener.onEvents(view,events);}
    @Override public void onTimelineChanged(Timeline timeline,int reason){if(!remote.getAsBoolean())listener.onTimelineChanged(timeline,reason);}
    @Override public void onMediaItemTransition(MediaItem item,int reason){if(!remote.getAsBoolean())listener.onMediaItemTransition(item,reason);}
    @Override public void onTracksChanged(Tracks tracks){if(!remote.getAsBoolean())listener.onTracksChanged(tracks);}
    @Override public void onMediaMetadataChanged(MediaMetadata metadata){if(!remote.getAsBoolean())listener.onMediaMetadataChanged(metadata);}
    @Override public void onPlaylistMetadataChanged(MediaMetadata metadata){if(!remote.getAsBoolean())listener.onPlaylistMetadataChanged(metadata);}
    @Override public void onIsLoadingChanged(boolean value){if(!remote.getAsBoolean())listener.onIsLoadingChanged(value);}
    @Override public void onLoadingChanged(boolean value){if(!remote.getAsBoolean())listener.onLoadingChanged(value);}
    @Override public void onAvailableCommandsChanged(Player.Commands commands){if(!remote.getAsBoolean())listener.onAvailableCommandsChanged(commands);}
    @Override public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters){if(!remote.getAsBoolean())listener.onTrackSelectionParametersChanged(parameters);}
    @Override public void onPlayerStateChanged(boolean playing,int state){if(!remote.getAsBoolean())listener.onPlayerStateChanged(playing,state);}
    @Override public void onPlaybackStateChanged(int state){if(!remote.getAsBoolean())listener.onPlaybackStateChanged(state);}
    @Override public void onPlayWhenReadyChanged(boolean playing,int reason){if(!remote.getAsBoolean())listener.onPlayWhenReadyChanged(playing,reason);}
    @Override public void onPlaybackSuppressionReasonChanged(int reason){if(!remote.getAsBoolean())listener.onPlaybackSuppressionReasonChanged(reason);}
    @Override public void onIsPlayingChanged(boolean playing){if(!remote.getAsBoolean())listener.onIsPlayingChanged(playing);}
    @Override public void onRepeatModeChanged(int mode){if(!remote.getAsBoolean())listener.onRepeatModeChanged(mode);}
    @Override public void onShuffleModeEnabledChanged(boolean enabled){if(!remote.getAsBoolean())listener.onShuffleModeEnabledChanged(enabled);}
    @Override public void onPlayerError(PlaybackException error){if(!remote.getAsBoolean())listener.onPlayerError(error);}
    @Override public void onPlayerErrorChanged(PlaybackException error){if(!remote.getAsBoolean())listener.onPlayerErrorChanged(error);}
    @Override public void onPositionDiscontinuity(int reason){if(!remote.getAsBoolean())listener.onPositionDiscontinuity(reason);}
    @Override public void onPositionDiscontinuity(Player.PositionInfo old,Player.PositionInfo current,int reason){if(!remote.getAsBoolean())listener.onPositionDiscontinuity(old,current,reason);}
    @Override public void onPlaybackParametersChanged(PlaybackParameters parameters){if(!remote.getAsBoolean())listener.onPlaybackParametersChanged(parameters);}
    @Override public void onSeekBackIncrementChanged(long value){if(!remote.getAsBoolean())listener.onSeekBackIncrementChanged(value);}
    @Override public void onSeekForwardIncrementChanged(long value){if(!remote.getAsBoolean())listener.onSeekForwardIncrementChanged(value);}
    @Override public void onMaxSeekToPreviousPositionChanged(long value){if(!remote.getAsBoolean())listener.onMaxSeekToPreviousPositionChanged(value);}
    @Override public void onAudioSessionIdChanged(int value){if(!remote.getAsBoolean())listener.onAudioSessionIdChanged(value);}
    @Override public void onAudioAttributesChanged(AudioAttributes attributes){if(!remote.getAsBoolean())listener.onAudioAttributesChanged(attributes);}
    @Override public void onVolumeChanged(float value){if(!remote.getAsBoolean())listener.onVolumeChanged(value);}
    @Override public void onSkipSilenceEnabledChanged(boolean value){if(!remote.getAsBoolean())listener.onSkipSilenceEnabledChanged(value);}
    @Override public void onDeviceInfoChanged(DeviceInfo info){if(!remote.getAsBoolean())listener.onDeviceInfoChanged(info);}
    @Override public void onDeviceVolumeChanged(int volume,boolean muted){if(!remote.getAsBoolean())listener.onDeviceVolumeChanged(volume,muted);}
    @Override public void onVideoSizeChanged(VideoSize size){if(!remote.getAsBoolean())listener.onVideoSizeChanged(size);}
    @Override public void onSurfaceSizeChanged(int width,int height){if(!remote.getAsBoolean())listener.onSurfaceSizeChanged(width,height);}
    @Override public void onRenderedFirstFrame(){if(!remote.getAsBoolean())listener.onRenderedFirstFrame();}
    @Override public void onCues(List<Cue> cues){if(!remote.getAsBoolean())listener.onCues(cues);}
    @Override public void onCues(CueGroup cues){if(!remote.getAsBoolean())listener.onCues(cues);}
    @Override public void onMetadata(Metadata metadata){if(!remote.getAsBoolean())listener.onMetadata(metadata);}
}
