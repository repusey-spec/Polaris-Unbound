package com.polarisunbound.app;

import android.os.Bundle;
import android.content.Intent;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioFocusRequest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.support.v4.media.*;
import android.support.v4.media.session.*;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
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
    private static final String PREFS="polaris_playback_state";
    private static final String PREF_LAST_RADIO="last_radio_id";
    private static final String MP3_PREFS="polaris_mp3";
    private static final String PREF_MP3_RECENT="recent_ids";
    private static final String PREF_MP3_FAVORITES="favorite_ids";
    private final List<Long> currentMp3Queue=new ArrayList<>();
    private int currentMp3Index=-1;
    private long lastAutoResumeAt=0L;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private static final String CHANNEL_ID="polaris_playback";
    private static final int NOTIFICATION_ID=71;
    private static final String[] RADIO_ORDER={"kr1","kr2","kr3","kr4","kr5","kr6","kiis","gallery"};
    private static final Map<String,String> STREAMS=new HashMap<>();
    static {
        STREAMS.put("kr1","https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/25");
        STREAMS.put("kr2","https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=mfm&callback=jarvis.miniInfo.loadOnAirComplete");
        STREAMS.put("kr3","http://m-aac.cbs.co.kr/cbs939/_definst_/cbs939.stream/playlist.m3u8");
        STREAMS.put("kr4","https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=sfm&callback=jarvis.miniInfo.loadOnAirComplete");
        STREAMS.put("kr5","https://playerservices.streamtheworld.com/api/livestream-redirect/AFNP_DGU_SC");
        STREAMS.put("kr6","https://apis.sbs.co.kr/play-api/1.0/livestream/powerpc/powerfm?protocol=hls&ssl=Y");
        STREAMS.put("kiis","https://stream.revma.ihrhls.com/zc185");
        STREAMS.put("gallery","https://streaming.live365.com/a94394");
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
        audioManager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        player=new ExoPlayer.Builder(this).build();
        AudioAttributes audioAttributes=new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build();
        // Let ExoPlayer request/release Android audio focus for every playback session.
        player.setAudioAttributes(audioAttributes,false);
        if(Build.VERSION.SDK_INT>=26){
            android.media.AudioAttributes platformAttrs=new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
            focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(platformAttrs)
                .setOnAudioFocusChangeListener(this::onAudioFocusChange)
                .build();
        }
        session=new MediaSessionCompat(this,"PolarisUnbound");
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS|MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSessionCompat.Callback(){
            @Override public void onPlayFromMediaId(String id,Bundle extras){
                trace("SERVICE onPlayFromMediaId: "+id);
                try{
                    if(id!=null && id.startsWith("mp3:")) { playLocalAudio(id); return; }
                    currentRadioId=id;
                    getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(PREF_LAST_RADIO,id).apply();
                    enterPlaybackForeground(TITLES.get(id));
                    retryCount=0;
                    userStopped=false;
                    retryHandler.removeCallbacksAndMessages(null);
                    startRadio(id);
                }catch(Throwable e){ publishError("playFromMediaId: "+e); }
            }
            @Override public void onPlay(){ if(requestPlaybackFocus()) player.play(); publishState(); }
            @Override public void onSkipToNext(){ skipCurrent(1); }
            @Override public void onSkipToPrevious(){ skipCurrent(-1); }
            @Override public void onFastForward(){ skipCurrent(1); }
            @Override public void onRewind(){ skipCurrent(-1); }
            @Override public void onPause(){ player.pause(); publishState(); }
            @Override public void onStop(){
                userStopped=true;
                currentRadioId=null;
                retryCount=0;
                retryHandler.removeCallbacksAndMessages(null);
                leavePlaybackForeground();
                abandonPlaybackFocus();
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



    private void skipCurrent(int delta){
        if(currentRadioId!=null) skipRadio(delta);
        else skipMp3(delta);
    }

    private void skipRadio(int delta){
        if(currentRadioId==null || !STREAMS.containsKey(currentRadioId)) return;
        int at=-1;
        for(int i=0;i<RADIO_ORDER.length;i++) if(RADIO_ORDER[i].equals(currentRadioId)){ at=i; break; }
        if(at<0) return;
        int next=(at+delta+RADIO_ORDER.length)%RADIO_ORDER.length;
        String id=RADIO_ORDER[next];
        trace("RADIO skip "+currentRadioId+" -> "+id);
        currentRadioId=id;
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(PREF_LAST_RADIO,id).apply();
        retryCount=0;
        userStopped=false;
        retryHandler.removeCallbacksAndMessages(null);
        enterPlaybackForeground(TITLES.get(id));
        startRadio(id);
    }

    private boolean requestPlaybackFocus(){
        if(audioManager==null) return true;
        int result;
        if(Build.VERSION.SDK_INT>=26){
            result=audioManager.requestAudioFocus(focusRequest);
        }else{
            result=audioManager.requestAudioFocus(this::onAudioFocusChange,
                AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);
        }
        trace("AUDIO FOCUS request="+result);
        return result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void onAudioFocusChange(int change){
        runOnPlayerThread(() -> {
            trace("AUDIO FOCUS change="+change);
            if(change==AudioManager.AUDIOFOCUS_LOSS ||
               change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
               change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){
                // Radio behavior: another media source wins -> stop this stream and stay paused.
                retryHandler.removeCallbacksAndMessages(null);
                if(player!=null) player.pause();
                publishState();
            }
            // Deliberately do not auto-resume on AUDIOFOCUS_GAIN.
            // User selection or the next AA reconnect starts playback again.
        });
    }

    private void abandonPlaybackFocus(){
        if(audioManager==null) return;
        if(Build.VERSION.SDK_INT>=26 && focusRequest!=null) audioManager.abandonAudioFocusRequest(focusRequest);
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
            // Stable Live365 entry point; Media3 follows the current CDN redirect.
            trace("Gallery Live365 entry: "+url);
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
            currentRadioId=null;
            retryCount=0;
            userStopped=false;
            retryHandler.removeCallbacksAndMessages(null);

            currentMp3Queue.clear();
            currentMp3Queue.addAll(loadAllMp3Ids());
            currentMp3Index=currentMp3Queue.indexOf(mediaId);

            Uri uri=ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,mediaId);
            String title="Local audio";
            String artist="";
            String album="";
            String[] projection={MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM};
            try(Cursor c=getContentResolver().query(uri,projection,null,null,null)){
                if(c!=null && c.moveToFirst()){
                    title=safe(c.getString(0));
                    artist=safe(c.getString(1));
                    album=safe(c.getString(2));
                }
            }
            rememberRecent(mediaId);
            enterPlaybackForeground(title);
            final String t=title, a=artist, al=album;
            final Bitmap art=embeddedArt(uri);
            runOnPlayerThread(() -> {
                try{
                    MediaMetadataCompat.Builder mb=new MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE,t)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,a)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,al);
                    if(art!=null) mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART,art);
                    session.setMetadata(mb.build());
                    player.setMediaItem(MediaItem.fromUri(uri));
                    player.prepare();
                    if(requestPlaybackFocus()) player.play();
                    publishState();
                }catch(Throwable e){ publishError("local audio: "+e); }
            });
        }catch(Exception e){
            publishError("local audio: "+e.getMessage());
        }
    }

    private void skipMp3(int delta){
        if(currentMp3Queue.isEmpty()) currentMp3Queue.addAll(loadAllMp3Ids());
        if(currentMp3Queue.isEmpty()) return;
        if(currentMp3Index<0) currentMp3Index=0;
        currentMp3Index=(currentMp3Index+delta+currentMp3Queue.size())%currentMp3Queue.size();
        playLocalAudio("mp3:"+currentMp3Queue.get(currentMp3Index));
    }

    private String safe(String s){ return s==null ? "" : s; }

    private Bitmap embeddedArt(Uri uri){
        MediaMetadataRetriever mmr=new MediaMetadataRetriever();
        try{
            mmr.setDataSource(this,uri);
            byte[] data=mmr.getEmbeddedPicture();
            if(data==null||data.length==0) return null;
            Bitmap b=BitmapFactory.decodeByteArray(data,0,data.length);
            if(b==null) return null;
            if(b.getWidth()<=512 && b.getHeight()<=512) return b;
            float scale=Math.min(512f/b.getWidth(),512f/b.getHeight());
            Bitmap scaled=Bitmap.createScaledBitmap(b,Math.max(1,(int)(b.getWidth()*scale)),Math.max(1,(int)(b.getHeight()*scale)),true);
            if(scaled!=b) b.recycle();
            return scaled;
        }catch(Exception ignored){ return null; }
        finally{ try{ mmr.release(); }catch(Exception ignored){} }
    }

    private List<Long> loadAllMp3Ids(){
        List<Long> out=new ArrayList<>();
        String[] projection={MediaStore.Audio.Media._ID};
        String selection=MediaStore.Audio.Media.IS_MUSIC+" != 0";
        try(Cursor c=getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,projection,selection,null,MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC")){
            if(c!=null){ int idCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); while(c.moveToNext()) out.add(c.getLong(idCol)); }
        }catch(SecurityException ignored){}
        return out;
    }

    private void rememberRecent(long id){
        String old=getSharedPreferences(MP3_PREFS,MODE_PRIVATE).getString(PREF_MP3_RECENT,"");
        LinkedHashSet<String> ids=new LinkedHashSet<>();
        ids.add(String.valueOf(id));
        if(old!=null&&!old.isEmpty()){
            for(String x:old.split(",")) if(!x.isEmpty()&&!x.equals(String.valueOf(id))) ids.add(x);
        }
        StringBuilder b=new StringBuilder();
        int n=0;
        for(String x:ids){ if(n++>=30) break; if(b.length()>0)b.append(','); b.append(x); }
        getSharedPreferences(MP3_PREFS,MODE_PRIVATE).edit().putString(PREF_MP3_RECENT,b.toString()).apply();
    }

    private List<Long> recentIds(){
        List<Long> out=new ArrayList<>();
        String raw=getSharedPreferences(MP3_PREFS,MODE_PRIVATE).getString(PREF_MP3_RECENT,"");
        if(raw!=null&&!raw.isEmpty()) for(String x:raw.split(",")) try{ out.add(Long.parseLong(x)); }catch(Exception ignored){}
        return out;
    }

    private List<Long> favoriteIds(){
        List<Long> out=new ArrayList<>();
        Set<String> set=getSharedPreferences(MP3_PREFS,MODE_PRIVATE).getStringSet(PREF_MP3_FAVORITES,Collections.emptySet());
        for(String x:set) try{ out.add(Long.parseLong(x)); }catch(Exception ignored){}
        return out;
    }

    private List<android.support.v4.media.MediaBrowserCompat.MediaItem> loadAudioByIds(List<Long> ids){
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> out=new ArrayList<>();
        for(Long mediaId:ids){
            Uri uri=ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,mediaId);
            String[] p={MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM};
            try(Cursor c=getContentResolver().query(uri,p,null,null,null)){
                if(c!=null&&c.moveToFirst()){
                    String title=safe(c.getString(0)), artist=safe(c.getString(1)), album=safe(c.getString(2));
                    String sub=artist+(album.isEmpty()?"":" · "+album);
                    out.add(item("mp3:"+mediaId,title,sub));
                }
            }catch(SecurityException ignored){}
        }
        return out;
    }

    private List<android.support.v4.media.MediaBrowserCompat.MediaItem> loadLocalAudio(String extraSelection,String[] args,String sort){
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> out=new ArrayList<>();
        String[] projection={MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM};
        String selection=MediaStore.Audio.Media.IS_MUSIC+" != 0";
        if(extraSelection!=null&&!extraSelection.isEmpty()) selection+=" AND ("+extraSelection+")";
        try(Cursor c=getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,projection,selection,args,sort==null?MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC":sort)){
            if(c!=null){
                int idCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int albumCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                while(c.moveToNext()){
                    long mid=c.getLong(idCol);
                    String title=safe(c.getString(titleCol));
                    String artist=safe(c.getString(artistCol));
                    String album=safe(c.getString(albumCol));
                    String sub=artist+(album.isEmpty()?"":" · "+album);
                    out.add(item("mp3:"+mid,title,sub));
                }
            }
        }catch(SecurityException ignored){}
        return out;
    }

    private List<android.support.v4.media.MediaBrowserCompat.MediaItem> loadGroupItems(String column,String prefix){
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> out=new ArrayList<>();
        LinkedHashSet<String> seen=new LinkedHashSet<>();
        String[] projection={column};
        String selection=MediaStore.Audio.Media.IS_MUSIC+" != 0";
        try(Cursor c=getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,projection,selection,null,column+" COLLATE NOCASE ASC")){
            if(c!=null){
                int col=c.getColumnIndexOrThrow(column);
                while(c.moveToNext()){
                    String value=safe(c.getString(col)).trim();
                    if(value.isEmpty()||!seen.add(value)) continue;
                    String title=value;
                    if(prefix.equals("mp3_folder:")){
                        String v=value.endsWith("/")?value.substring(0,value.length()-1):value;
                        int slash=v.lastIndexOf('/');
                        title=slash>=0?v.substring(slash+1):v;
                    }
                    out.add(folder(prefix+Uri.encode(value),title));
                }
            }
        }catch(Exception ignored){}
        return out;
    }

    private List<android.support.v4.media.MediaBrowserCompat.MediaItem> loadMp3Folders(){
        String column=Build.VERSION.SDK_INT>=29 ? MediaStore.Audio.Media.RELATIVE_PATH : MediaStore.Audio.Media.DATA;
        return loadGroupItems(column,"mp3_folder:");
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
        MediaMetadataCompat.Builder mb=new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,subtitle);
        if(currentRadioId!=null && STREAMS.containsKey(currentRadioId))
            mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART,StationArt.bitmap(this,currentRadioId,256));
        session.setMetadata(mb.build());
        trace("PLAYER setMediaItem");
        player.setMediaItem(MediaItem.fromUri(url));
        trace("PLAYER prepare");
        player.prepare();
        trace("PLAYER play");
        if(requestPlaybackFocus()) player.play();
        else trace("PLAYER audio focus denied");
        publishState();
        }catch(Throwable e){ trace("PLAYER ERROR: "+e); publishError("player: "+e); }
    }

    private void publishState(){
        int state=PlaybackStateCompat.STATE_STOPPED;
        if(player!=null && player.isPlaying()) state=PlaybackStateCompat.STATE_PLAYING;
        else if(player!=null && player.getPlaybackState()==Player.STATE_BUFFERING) state=PlaybackStateCompat.STATE_BUFFERING;
        else if(player!=null && player.getPlaybackState()==Player.STATE_READY) state=PlaybackStateCompat.STATE_PAUSED;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY|PlaybackStateCompat.ACTION_PAUSE|PlaybackStateCompat.ACTION_STOP|PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID|PlaybackStateCompat.ACTION_SKIP_TO_NEXT|PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS|PlaybackStateCompat.ACTION_FAST_FORWARD|PlaybackStateCompat.ACTION_REWIND)
            .setState(state,0,1f).build());
    }

    @Override public BrowserRoot onGetRoot(String pkg,int uid,Bundle hints){
        // JC-style behavior: when Android Auto reconnects, restore the last radio source.
        if("com.google.android.projection.gearhead".equals(pkg)){
            long now=android.os.SystemClock.elapsedRealtime();
            if(now-lastAutoResumeAt>5000L){
                lastAutoResumeAt=now;
                final String last=getSharedPreferences(PREFS,MODE_PRIVATE).getString(PREF_LAST_RADIO,null);
                if(last!=null && STREAMS.containsKey(last)){
                    retryHandler.postDelayed(() -> {
                        if(!player.isPlaying()){
                            trace("AA reconnect auto-resume: "+last);
                            currentRadioId=last;
                            userStopped=false;
                            retryCount=0;
                            retryHandler.removeCallbacksAndMessages(null);
                            enterPlaybackForeground(TITLES.get(last));
                            startRadio(last);
                        }
                    },4000L);
                }
            }
        }
        Bundle style=new Bundle();
        style.putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT",1);
        style.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT",2);
        return new BrowserRoot("root",style);
    }

    private android.support.v4.media.MediaBrowserCompat.MediaItem folder(String id,String title){
        Bundle e=new Bundle();
        e.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT",2);
        return new android.support.v4.media.MediaBrowserCompat.MediaItem(
            new MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setExtras(e).build(),
            android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }
    private android.support.v4.media.MediaBrowserCompat.MediaItem item(String id,String title,String sub){
        MediaDescriptionCompat.Builder b=new MediaDescriptionCompat.Builder()
            .setMediaId(id).setTitle(title).setSubtitle(sub);
        if(STREAMS.containsKey(id)) b.setIconBitmap(StationArt.bitmap(this,id,128));
        Bundle e=new Bundle();
        e.putInt("android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT",2);
        b.setExtras(e);
        return new android.support.v4.media.MediaBrowserCompat.MediaItem(
            b.build(),android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    @Override public void onLoadChildren(String parent,Result<List<android.support.v4.media.MediaBrowserCompat.MediaItem>> result){
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> x=new ArrayList<>();
        if(parent.equals("root")){
            // Keep root children browsable so Android Auto can render them as navigation tabs.
            x.add(folder("home","홈"));
            x.add(folder("radio","라디오"));
            x.add(folder("mp3","MP3"));
        } else if(parent.equals("home")){
            x.add(folder("radio","라디오"));
            x.add(folder("mp3","MP3"));
        } else if(parent.equals("radio")){
            x.add(item("kr1","89.1 KBS CoolFM","Korea"));
            x.add(item("kr2","91.9 MBC FM4U","Korea"));
            x.add(item("kr3","93.9 CBS MusicFM","Korea"));
            x.add(item("kr4","95.9 MBC 표준FM","Korea"));
            x.add(item("kr5","102.7 AFN EagleFM","Korea"));
            x.add(item("kr6","107.7 SBS PowerFM","Korea"));
            x.add(item("kiis","102.7 KIIS-FM","Los Angeles"));
            x.add(item("gallery","Jazz from Gallery 41","San Francisco Bay"));
        } else if(parent.equals("mp3")){
            x.add(folder("mp3_recent","최근 재생"));
            x.add(folder("mp3_folders","폴더"));
            x.add(folder("mp3_albums","앨범"));
            x.add(folder("mp3_artists","아티스트"));
            x.add(folder("mp3_all","전체 곡"));
            x.add(folder("mp3_favorites","즐겨찾기"));
        } else if(parent.equals("mp3_recent")){
            x.addAll(loadAudioByIds(recentIds()));
        } else if(parent.equals("mp3_folders")){
            x.addAll(loadMp3Folders());
        } else if(parent.equals("mp3_albums")){
            x.addAll(loadGroupItems(MediaStore.Audio.Media.ALBUM,"mp3_album:"));
        } else if(parent.equals("mp3_artists")){
            x.addAll(loadGroupItems(MediaStore.Audio.Media.ARTIST,"mp3_artist:"));
        } else if(parent.equals("mp3_all")){
            x.addAll(loadLocalAudio(null,null,MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC"));
        } else if(parent.equals("mp3_favorites")){
            x.addAll(loadAudioByIds(favoriteIds()));
        } else if(parent.startsWith("mp3_album:")){
            String value=Uri.decode(parent.substring("mp3_album:".length()));
            x.addAll(loadLocalAudio(MediaStore.Audio.Media.ALBUM+"=?",new String[]{value},MediaStore.Audio.Media.TRACK+" ASC"));
        } else if(parent.startsWith("mp3_artist:")){
            String value=Uri.decode(parent.substring("mp3_artist:".length()));
            x.addAll(loadLocalAudio(MediaStore.Audio.Media.ARTIST+"=?",new String[]{value},MediaStore.Audio.Media.ALBUM+" COLLATE NOCASE ASC, "+MediaStore.Audio.Media.TRACK+" ASC"));
        } else if(parent.startsWith("mp3_folder:")){
            String value=Uri.decode(parent.substring("mp3_folder:".length()));
            if(Build.VERSION.SDK_INT>=29)
                x.addAll(loadLocalAudio(MediaStore.Audio.Media.RELATIVE_PATH+"=?",new String[]{value},MediaStore.Audio.Media.TRACK+" ASC"));
            else
                x.addAll(loadLocalAudio(MediaStore.Audio.Media.DATA+" LIKE ?",new String[]{value.endsWith("/")?value+"%":value+"/%"},MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC"));
        }
        result.sendResult(x);
    }

    @Override public void onDestroy(){
        retryHandler.removeCallbacksAndMessages(null);
        abandonPlaybackFocus();
        if(player!=null) player.release();
        resolver.shutdownNow();
        if(session!=null) session.release();
        super.onDestroy();
    }
}
