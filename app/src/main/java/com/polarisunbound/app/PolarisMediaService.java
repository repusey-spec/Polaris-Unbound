package com.polarisunbound.app;

import android.os.Bundle;
import android.support.v4.media.*;
import android.support.v4.media.session.*;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.*;

public class PolarisMediaService extends MediaBrowserServiceCompat {
    private MediaSessionCompat session;
    private ExoPlayer player;
    private static final String KIIS_URL="https://stream.revma.ihrhls.com/zc185";

    @Override public void onCreate(){
        super.onCreate();
        player=new ExoPlayer.Builder(this).build();
        session=new MediaSessionCompat(this,"PolarisUnbound");
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS|MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSessionCompat.Callback(){
            @Override public void onPlayFromMediaId(String id,Bundle extras){
                if("kiis".equals(id)) playUrl(KIIS_URL,"102.7 KIIS-FM","Los Angeles");
            }
            @Override public void onPlay(){ player.play(); publishState(); }
            @Override public void onPause(){ player.pause(); publishState(); }
            @Override public void onStop(){ player.stop(); publishState(); }
        });
        player.addListener(new Player.Listener(){
            @Override public void onIsPlayingChanged(boolean playing){ publishState(); }
            @Override public void onPlaybackStateChanged(int state){ publishState(); }
        });
        setSessionToken(session.getSessionToken());
        session.setActive(true);
        publishState();
    }

    private void playUrl(String url,String title,String subtitle){
        session.setMetadata(new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,subtitle).build());
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
        publishState();
    }

    private void publishState(){
        int state=PlaybackStateCompat.STATE_STOPPED;
        if(player!=null && player.isPlaying()) state=PlaybackStateCompat.STATE_PLAYING;
        else if(player!=null && player.getPlaybackState()==Player.STATE_BUFFERING) state=PlaybackStateCompat.STATE_BUFFERING;
        else if(player!=null && player.getPlaybackState()==Player.STATE_READY) state=PlaybackStateCompat.STATE_PAUSED;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY|PlaybackStateCompat.ACTION_PAUSE|PlaybackStateCompat.ACTION_STOP|PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
            .setState(state,0,1f).build());
    }

    @Override public BrowserRoot onGetRoot(String pkg,int uid,Bundle hints){ return new BrowserRoot("root",null); }

    private android.support.v4.media.MediaBrowserCompat.MediaItem folder(String id,String title){
        return new android.support.v4.media.MediaBrowserCompat.MediaItem(
            new MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build(),
            android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }
    private android.support.v4.media.MediaBrowserCompat.MediaItem item(String id,String title,String sub){
        return new android.support.v4.media.MediaBrowserCompat.MediaItem(
            new MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build(),
            android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    @Override public void onLoadChildren(String parent,Result<List<android.support.v4.media.MediaBrowserCompat.MediaItem>> result){
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> x=new ArrayList<>();
        if(parent.equals("root")){
            x.add(folder("domestic","국내라디오"));
            x.add(folder("foreign","해외라디오"));
            x.add(folder("mp3","MP3"));
        } else if(parent.equals("domestic")){
            String[] n={"KBS CoolFM","MBC FM4U","CBS MusicFM","MBC 표준FM","AFN EagleFM","SBS PowerFM"};
            for(int i=0;i<n.length;i++) x.add(item("kr"+(i+1),n[i],"현재 프로그램"));
        } else if(parent.equals("foreign")){
            x.add(item("kiis","102.7 KIIS-FM","Los Angeles"));
        }
        result.sendResult(x);
    }

    @Override public void onDestroy(){
        if(player!=null) player.release();
        if(session!=null) session.release();
        super.onDestroy();
    }
}
