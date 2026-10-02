package com.polarisunbound.app;

import android.os.Bundle;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.support.v4.media.*;
import android.support.v4.media.session.*;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class PolarisMediaService extends MediaBrowserServiceCompat {
    private MediaSessionCompat session;
    private ExoPlayer player;
    private final ExecutorService resolver=Executors.newSingleThreadExecutor();
    private static final Map<String,String> STREAMS=new HashMap<>();
    static {
        STREAMS.put("kr1","https://2fm-ad.gscdn.kbs.co.kr/2fm_ad_192_1.m3u8?");
        STREAMS.put("kr2","https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=mfm&callback=jarvis.miniInfo.loadOnAirComplete");
        STREAMS.put("kr3","http://m-aac.cbs.co.kr/cbs939/_definst_/cbs939.stream/playlist.m3u8");
        STREAMS.put("kr4","https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=sfm&callback=jarvis.miniInfo.loadOnAirComplete");
        STREAMS.put("kr5","https://playerservices.streamtheworld.com/api/livestream-redirect/AFNP_DGU_SC");
        STREAMS.put("kr6","http://gorealra.sbs.co.kr/g4/protocol/GetStream.jsp?pmDevice=ios&pmNetwork=wifi&pmChannel=RA02&pmAppver=4.5.0&from=iphone");
        STREAMS.put("kiis","https://stream.revma.ihrhls.com/zc185");
    }
    private static final Map<String,String> TITLES=new HashMap<>();
    static {
        TITLES.put("kr1","89.1 KBS CoolFM");
        TITLES.put("kr2","91.9 MBC FM4U");
        TITLES.put("kr3","93.9 CBS MusicFM");
        TITLES.put("kr4","95.9 MBC 표준FM");
        TITLES.put("kr5","102.7 AFN EagleFM");
        TITLES.put("kr6","107.7 SBS PowerFM");
        TITLES.put("kiis","102.7 KIIS-FM");
    }

    @Override public void onCreate(){
        super.onCreate();
        player=new ExoPlayer.Builder(this).build();
        session=new MediaSessionCompat(this,"PolarisUnbound");
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS|MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSessionCompat.Callback(){
            @Override public void onPlayFromMediaId(String id,Bundle extras){
                try{
                    if(id!=null && id.startsWith("mp3:")) { playLocalAudio(id); return; }
                    String url=STREAMS.get(id);
                    if(url==null) return;
                    if("kr2".equals(id)||"kr4".equals(id)||"kr6".equals(id)) resolveAndPlay(id,url);
                    else playUrl(url,TITLES.get(id),"kiis".equals(id) ? "Los Angeles" : "Live");
                }catch(Throwable e){ publishError("playFromMediaId: "+e); }
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



    private void playLocalAudio(String id){
        try{
            long mediaId=Long.parseLong(id.substring(4));
            Uri uri=ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,mediaId);
            String title="Local audio";
            String artist="";
            String[] projection={MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST};
            try(Cursor c=getContentResolver().query(uri,projection,null,null,null)){
                if(c!=null && c.moveToFirst()){
                    title=c.getString(0);
                    artist=c.getString(1);
                }
            }
            playUrl(uri.toString(),title,artist);
        }catch(Exception e){
            session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                .setState(PlaybackStateCompat.STATE_ERROR,0,1f)
                .setErrorMessage(e.getMessage()).build());
        }
    }

    private List<android.support.v4.media.MediaBrowserCompat.MediaItem> loadLocalAudio(){
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> out=new ArrayList<>();
        String[] projection={MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST};
        String selection=MediaStore.Audio.Media.IS_MUSIC+" != 0";
        try(Cursor c=getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,projection,selection,null,MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC")){
            if(c!=null){
                int idCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                while(c.moveToNext()){
                    long id=c.getLong(idCol);
                    String title=c.getString(titleCol);
                    String artist=c.getString(artistCol);
                    out.add(item("mp3:"+id,title,artist==null ? "" : artist));
                }
            }
        }catch(SecurityException ignored){}
        return out;
    }

    private void resolveAndPlay(final String id,final String lookupUrl){
        resolver.execute(() -> {
            try{
                HttpURLConnection con=(HttpURLConnection)new URL(lookupUrl).openConnection();
                con.setConnectTimeout(8000); con.setReadTimeout(8000);
                con.setInstanceFollowRedirects(true);
                con.setRequestProperty("User-Agent","Mozilla/5.0");
                StringBuilder b=new StringBuilder();
                try(BufferedReader r=new BufferedReader(new InputStreamReader(con.getInputStream()))){
                    String line; while((line=r.readLine())!=null) b.append(line);
                }
                String resolved=extractStreamUrl(b.toString());
                if(resolved==null) throw new IOException("No stream URL in resolver response");
                final String u=resolved;
                runOnPlayerThread(() -> playUrl(u,TITLES.get(id),"Live"));
            }catch(Exception e){
                runOnPlayerThread(() -> {
                    session.setMetadata(new MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE,TITLES.get(id))
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,"Stream resolver error").build());
                    session.setPlaybackState(new PlaybackStateCompat.Builder()
                        .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                        .setState(PlaybackStateCompat.STATE_ERROR,0,1f)
                        .setErrorMessage(e.getMessage()).build());
                });
            }
        });
    }

    private String extractStreamUrl(String raw){
        if(raw==null) return null;
        String s=raw.replace("\\/","/").replace("\\u0026","&").replace("&amp;","&");
        Matcher m=Pattern.compile("https?://[^\\\"'\\s,}\\)]+",Pattern.CASE_INSENSITIVE).matcher(s);
        String fallback=null;
        while(m.find()){
            String u=m.group();
            if(u.contains(".m3u8")||u.contains(".aac")||u.contains(".mp3")||u.contains("stream")) return u;
            if(fallback==null) fallback=u;
        }
        return fallback;
    }

    private void runOnPlayerThread(Runnable r){
        new android.os.Handler(getMainLooper()).post(r);
    }

    private void playUrl(String url,String title,String subtitle){
        runOnPlayerThread(() -> playUrlOnMain(url,title,subtitle));
    }

    private void publishError(String message){
        session.setPlaybackState(new PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
            .setState(PlaybackStateCompat.STATE_ERROR,0,1f)
            .setErrorMessage(message).build());
    }

    private void playUrlOnMain(String url,String title,String subtitle){
        try{
        session.setMetadata(new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,subtitle).build());
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
        publishState();
        }catch(Throwable e){ publishError("player: "+e); }
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
        } else if(parent.equals("mp3")){
            x.addAll(loadLocalAudio());
        }
        result.sendResult(x);
    }

    @Override public void onDestroy(){
        if(player!=null) player.release();
        resolver.shutdownNow();
        if(session!=null) session.release();
        super.onDestroy();
    }
}
