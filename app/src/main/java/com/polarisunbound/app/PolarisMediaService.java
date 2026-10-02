package com.polarisunbound.app;

import android.os.Bundle;
import android.support.v4.media.*;
import android.support.v4.media.session.*;
import androidx.media.MediaBrowserServiceCompat;
import java.util.*;

public class PolarisMediaService extends MediaBrowserServiceCompat {
    private MediaSessionCompat session;
    @Override public void onCreate(){
        super.onCreate();
        session=new MediaSessionCompat(this,"PolarisUnbound");
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS|MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setPlaybackState(new PlaybackStateCompat.Builder().setActions(
            PlaybackStateCompat.ACTION_PLAY|PlaybackStateCompat.ACTION_PAUSE|PlaybackStateCompat.ACTION_STOP|PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
        ).setState(PlaybackStateCompat.STATE_NONE,0,1).build());
        setSessionToken(session.getSessionToken()); session.setActive(true);
    }
    @Override public BrowserRoot onGetRoot(String pkg,int uid,Bundle hints){ return new BrowserRoot("root",null); }
    private MediaBrowserCompat.MediaItem folder(String id,String title){
        return new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build(),MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }
    private MediaBrowserCompat.MediaItem item(String id,String title,String sub){
        return new MediaBrowserCompat.MediaItem(new MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build(),MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }
    @Override public void onLoadChildren(String parent,Result<List<MediaBrowserCompat.MediaItem>> result){
        List<MediaBrowserCompat.MediaItem> x=new ArrayList<>();
        if(parent.equals("root")){
            x.add(folder("domestic","국내라디오")); x.add(folder("foreign","해외라디오")); x.add(folder("mp3","MP3"));
        } else if(parent.equals("domestic")){
            String[] n={"KBS CoolFM","MBC FM4U","CBS MusicFM","MBC 표준FM","AFN EagleFM","SBS PowerFM"};
            for(int i=0;i<n.length;i++) x.add(item("kr"+(i+1),n[i],"현재 프로그램"));
        } else if(parent.equals("foreign")) x.add(item("kiis","102.7 KIIS-FM","Los Angeles"));
        result.sendResult(x);
    }
}
