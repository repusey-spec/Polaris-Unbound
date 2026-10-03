package com.polarisunbound.app;

import android.os.Bundle;
import android.content.Intent;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.support.v4.media.*;
import android.support.v4.media.session.*;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.*;
import java.io.*;
import java.net.*;
import androidx.media3.datasource.HttpDataSource;
import java.util.concurrent.*;
import java.util.regex.*;

public class PolarisMediaService extends MediaBrowserServiceCompat {
    private void trace(String step){
        Intent i=new Intent("com.polarisunbound.app.DIAG");
        i.setPackage(getPackageName());
        i.putExtra("step",step);
        sendBroadcast(i);
    }
    private MediaSessionCompat session;
    private ExoPlayer player;
    private final ExecutorService resolver=Executors.newSingleThreadExecutor();
    private final android.os.Handler retryHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private String currentRadioId=null;
    private int retryCount=0;
    private boolean userStopped=false;
    private static final String CHANNEL_ID="polaris_playback";
    private static final int NOTIFICATION_ID=71;
    private static final Map<String,String> STREAMS=new HashMap<>();
    static {
        STREAMS.put("kr1","https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/25");
        STREAMS.put("kr2","https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=mfm&callback=jarvis.miniInfo.loadOnAirComplete");
        STREAMS.put("kr3","http://m-aac.cbs.co.kr/cbs939/_definst_/cbs939.stream/playlist.m3u8");
        STREAMS.put("kr4","https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=sfm&callback=jarvis.miniInfo.loadOnAirComplete");
        STREAMS.put("kr5","https://playerservices.streamtheworld.com/api/livestream-redirect/AFNP_DGU_SC");
        STREAMS.put("kr6","https://apis.sbs.co.kr/play-api/1.0/livestream/powerpc/powerfm?protocol=hls&ssl=Y");
        STREAMS.put("kiis","https://stream.revma.ihrhls.com/zc185");
        STREAMS.put("gallery","https://radio.garden/api/ara/content/listen/kWNLnJEl/channel.mp3");
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
        TITLES.put("gallery","Jazz from Gallery 41");
    }

    @Override public void onCreate(){
        super.onCreate();
        ensurePlaybackChannel();
        player=new ExoPlayer.Builder(this).build();
        session=new MediaSessionCompat(this,"PolarisUnbound");
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS|MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSessionCompat.Callback(){
            @Override public void onPlayFromMediaId(String id,Bundle extras){
                trace("SERVICE onPlayFromMediaId: "+id);
                try{
                    if(id!=null && id.startsWith("mp3:")) { playLocalAudio(id); return; }
                    currentRadioId=id;
                    enterPlaybackForeground(TITLES.get(id));
                    retryCount=0;
                    userStopped=false;
                    retryHandler.removeCallbacksAndMessages(null);
                    startRadio(id);
                }catch(Throwable e){ publishError("playFromMediaId: "+e); }
            }
            @Override public void onPlay(){ player.play(); publishState(); }
            @Override public void onPause(){ player.pause(); publishState(); }
            @Override public void onStop(){
                userStopped=true;
                currentRadioId=null;
                retryCount=0;
                retryHandler.removeCallbacksAndMessages(null);
                leavePlaybackForeground();
                player.stop();
                publishState();
            }
        });
        player.addListener(new Player.Listener(){
            @Override public void onIsPlayingChanged(boolean playing){ publishState(); }
            @Override public void onPlaybackStateChanged(int state){ publishState(); }
            @Override public void onPlayerError(PlaybackException error){
                String msg="Media3 "+error.getErrorCodeName()+": "+error.getMessage();
                Throwable cause=error;
                while(cause!=null){
                    if(cause instanceof HttpDataSource.InvalidResponseCodeException){
                        HttpDataSource.InvalidResponseCodeException h=(HttpDataSource.InvalidResponseCodeException)cause;
                        msg+=" HTTP "+h.responseCode;
                        break;
                    }
                    cause=cause.getCause();
                }
                trace("PLAYER ERROR: "+msg);
                publishError(msg);
                scheduleRetry();
            }
        });
        setSessionToken(session.getSessionToken());
        session.setActive(true);
        publishState();
    }



    private void ensurePlaybackChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=getSystemService(NotificationManager.class);
            if(nm!=null) nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,"Polaris Unbound 재생",NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void enterPlaybackForeground(String title){
        Notification n=new NotificationCompat.Builder(this,CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Polaris Unbound")
            .setContentText(title==null ? "라디오 재생 중" : title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build();
        startForeground(NOTIFICATION_ID,n);
    }

    private void leavePlaybackForeground(){
        stopForeground(true);
    }

    private void startRadio(String id){
        String url=STREAMS.get(id);
        if(url==null || userStopped) return;
        trace("STREAM selected: "+id);
        if("gallery".equals(id)){
            // Diagnostic: let Media3 follow the Radio Garden redirect chain itself.
            trace("Gallery Media3 direct redirect test: "+url);
            playUrl(url,TITLES.get(id),"San Francisco Bay");
        } else if("kr1".equals(id)||"kr2".equals(id)||"kr4".equals(id)||"kr6".equals(id)){
            trace("resolver start: "+id);
            resolveAndPlay(id,url);
        } else {
            trace("direct play start: "+id);
            playUrl(url,TITLES.get(id),"kiis".equals(id) ? "Los Angeles" : "Live");
        }
    }

    private void scheduleRetry(){
        if(userStopped || currentRadioId==null) return;
        retryCount++;
        long delay=retryCount==1 ? 2000L : retryCount==2 ? 5000L : 10000L;
        final String id=currentRadioId;
        trace("retry scheduled: "+id+" in "+delay+"ms");
        retryHandler.removeCallbacksAndMessages(null);
        retryHandler.postDelayed(() -> {
            if(!userStopped && id.equals(currentRadioId)){
                trace("retry start: "+id+" #"+retryCount);
                startRadio(id);
            }
        },delay);
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

    private void resolveGalleryAndPlay(final String id,final String lookupUrl){
        resolver.execute(() -> {
            HttpURLConnection con=null;
            try{
                String next=lookupUrl;
                for(int hop=0;hop<6;hop++){
                    con=(HttpURLConnection)new URL(next).openConnection();
                    con.setConnectTimeout(10000); con.setReadTimeout(10000);
                    con.setInstanceFollowRedirects(false);
                    con.setRequestProperty("User-Agent","Radio Garden Android");
                    con.setRequestProperty("Referer","https://radio.garden/");
                    con.setRequestProperty("Origin","https://radio.garden");
                    con.setRequestProperty("Accept","*/*");
                    con.setRequestProperty("Icy-MetaData","1");
                    int code=con.getResponseCode();
                    String type=con.getContentType();
                    String loc=con.getHeaderField("Location");
                    trace("Gallery hop "+hop+" HTTP "+code+" type="+type+" loc="+loc);
                    if(code>=300 && code<400 && loc!=null){
                        URL base=new URL(next);
                        next=new URL(base,loc).toString();
                        con.disconnect(); con=null;
                        continue;
                    }
                    if(code>=200 && code<300 && type!=null && (type.toLowerCase(Locale.US).startsWith("audio/") || type.toLowerCase(Locale.US).contains("mpeg"))){
                        final String streamUrl=next;
                        if(con!=null){ con.disconnect(); con=null; }
                        runOnPlayerThread(() -> {
                            if(!userStopped && id.equals(currentRadioId))
                                playUrl(streamUrl,TITLES.get(id),"San Francisco Bay");
                        });
                        return;
                    }
                    throw new IOException("Gallery stream HTTP "+code+" type="+type);
                }
                throw new IOException("Too many Gallery redirects");
            }catch(Exception e){
                if(con!=null) con.disconnect();
                runOnPlayerThread(() -> {
                    trace("Gallery resolver error: "+e);
                    publishError("Gallery resolver: "+e.getMessage());
                    if(id.equals(currentRadioId)) scheduleRetry();
                });
            }
        });
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
                String raw=b.toString();
                trace("resolver HTTP "+con.getResponseCode()+": "+id+" bytes="+raw.length());
                String resolved=extractStreamUrl(raw);
                if(resolved==null){
                    String preview=raw.replace("\n"," ").replace("\r"," ");
                    if(preview.length()>240) preview=preview.substring(0,240);
                    trace("resolver no URL: "+id+" body="+preview);
                    throw new IOException("No stream URL in resolver response");
                }
                trace("resolver URL: "+id+" -> "+resolved);
                final String u=resolved;
                runOnPlayerThread(() -> {
                    if(!userStopped && id.equals(currentRadioId)) playUrl(u,TITLES.get(id),"Live");
                });
            }catch(Exception e){
                runOnPlayerThread(() -> {
                    session.setMetadata(new MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE,TITLES.get(id))
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,"Stream resolver error").build());
                    session.setPlaybackState(new PlaybackStateCompat.Builder()
                        .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                        .setState(PlaybackStateCompat.STATE_ERROR,0,1f)
                        .setErrorMessage(e.getMessage()).build());
                    trace("resolver error: "+id+" / "+e);
                    if(id.equals(currentRadioId)) scheduleRetry();
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
        trace("PLAYER enter: "+title);
        try{
        session.setMetadata(new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,subtitle).build());
        trace("PLAYER setMediaItem");
        player.setMediaItem(MediaItem.fromUri(url));
        trace("PLAYER prepare");
        player.prepare();
        trace("PLAYER play");
        player.play();
        publishState();
        }catch(Throwable e){ trace("PLAYER ERROR: "+e); publishError("player: "+e); }
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
            x.add(folder("radio","라디오"));
            x.add(folder("mp3","MP3"));
        } else if(parent.equals("radio")){
            String[] n={"KBS CoolFM","MBC FM4U","CBS MusicFM","MBC 표준FM","AFN EagleFM","SBS PowerFM"};
            for(int i=0;i<n.length;i++) x.add(item("kr"+(i+1),n[i],"현재 프로그램"));
            x.add(item("kiis","102.7 KIIS-FM","Los Angeles"));
            x.add(item("gallery","Jazz from Gallery 41","San Francisco Bay"));
        } else if(parent.equals("mp3")){
            x.addAll(loadLocalAudio());
        }
        result.sendResult(x);
    }

    @Override public void onDestroy(){
        retryHandler.removeCallbacksAndMessages(null);
        if(player!=null) player.release();
        resolver.shutdownNow();
        if(session!=null) session.release();
        super.onDestroy();
    }
}
