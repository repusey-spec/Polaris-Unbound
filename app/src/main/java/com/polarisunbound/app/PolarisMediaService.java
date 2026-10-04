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
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import java.util.concurrent.*;
import java.util.regex.*;

public class PolarisMediaService extends MediaBrowserServiceCompat {
    private void trace(String step){
        android.util.Log.i("PolarisUnbound",step);
        Intent i=new Intent("com.polarisunbound.app.DIAG");
        i.setPackage(getPackageName());
        i.putExtra("step",step);
        sendBroadcast(i);
    }
    private MediaSessionCompat session;
    private ExoPlayer player;
    private final ExecutorService resolver=Executors.newSingleThreadExecutor();
    private final ExecutorService programExecutor=Executors.newSingleThreadExecutor();
    private final android.os.Handler retryHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler programHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private String currentRadioId=null;
    private int retryCount=0;
    private boolean userStopped=false;
    private static final String PREFS="polaris_playback_state";
    private static final String PREF_LAST_RADIO="last_radio_id";
    private static final String MP3_PREFS="polaris_mp3";
    private static final String PREF_MP3_RECENT="recent_ids";
    private static final String PREF_MP3_FAVORITES="favorite_ids";
    private final List<Long> currentMp3Queue=new ArrayList<>();
    private final android.util.LruCache<Long,Bitmap> mp3AlbumArtCache=new android.util.LruCache<>(48);
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
            @Override public void onSeekTo(long pos){
                if(player!=null){
                    player.seekTo(Math.max(0L,pos));
                    publishState();
                }
            }
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
        startProgramRefreshLoop();
    }



    private void startProgramRefreshLoop(){
        long stagger=0L;
        for(String id:RADIO_ORDER){
            if("kr5".equals(id)) continue;
            final String stationId=id;
            programHandler.postDelayed(() -> refreshProgramAsync(stationId),stagger);
            stagger+=350L;
        }
    }

    private void refreshProgramAsync(final String id){
        if(id==null||"kr5".equals(id)) return;
        programExecutor.execute(() -> {
            try{
                CurrentProgramResolver.Result old=CurrentProgramResolver.cached(this,id);
                String oldTitle=old==null ? null : old.aaTitle();
                CurrentProgramResolver.Result r=CurrentProgramResolver.resolve(id);
                CurrentProgramResolver.save(this,id,r);
                boolean changed=oldTitle==null || !oldTitle.equals(r.aaTitle());
                programHandler.post(() -> {
                    trace("PROGRAM "+id+" -> "+r.aaTitle()+(changed?" [changed]":""));
                    if(changed) notifyChildrenChanged("radio");
                    if(changed && id.equals(currentRadioId)) applyCurrentRadioMetadata(id);
                    long delay=CurrentProgramResolver.nextRefreshDelay(id,r);
                    programHandler.postDelayed(() -> refreshProgramAsync(id),delay);
                });
            }catch(Exception e){
                trace("PROGRAM resolver error: "+id+" / "+e);
                programHandler.postDelayed(() -> refreshProgramAsync(id),10L*60L*1000L);
            }
        });
    }

    private String radioProgramTitle(String id){
        CurrentProgramResolver.Result r=CurrentProgramResolver.cached(this,id);
        if(r==null) return TITLES.get(id);
        String title=r.aaTitle();
        return title==null||title.trim().isEmpty()?TITLES.get(id):title;
    }

    private void applyCurrentRadioMetadata(String id){
        if(id==null||!id.equals(currentRadioId)||!STREAMS.containsKey(id)) return;
        MediaMetadataCompat.Builder mb=new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,radioProgramTitle(id))
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,TITLES.get(id))
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART,StationArt.bitmap(this,id,256));
        session.setMetadata(mb.build());
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
            trace("Gallery Live365 resolver start: "+url);
            resolveGalleryAndPlay(id,url);
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
        if("gallery".equals(currentRadioId) && retryCount>=3){
            trace("Gallery retry limit reached");
            return;
        }
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
            long duration=0L;
            String[] projection={MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM,MediaStore.Audio.Media.DURATION};
            try(Cursor c=getContentResolver().query(uri,projection,null,null,null)){
                if(c!=null && c.moveToFirst()){
                    title=safe(c.getString(0));
                    artist=safe(c.getString(1));
                    album=safe(c.getString(2));
                    duration=Math.max(0L,c.getLong(3));
                }
            }
            rememberRecent(mediaId);
            enterPlaybackForeground(title);
            final String t=title, a=artist, al=album;
            final long dur=duration;
            final Bitmap art=embeddedArt(uri);
            runOnPlayerThread(() -> {
                try{
                    MediaMetadataCompat.Builder mb=new MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID,"mp3:"+mediaId)
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE,t)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,a)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,al)
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,dur);
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

    private Bitmap mp3AlbumArt(long albumId){
        if(albumId<=0) return null;
        Bitmap cached=mp3AlbumArtCache.get(albumId);
        if(cached!=null && !cached.isRecycled()) return cached;

        Uri artUri=ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"),albumId);
        try(InputStream in=getContentResolver().openInputStream(artUri)){
            if(in==null) return null;
            Bitmap raw=BitmapFactory.decodeStream(in);
            if(raw==null) return null;
            int target=160;
            Bitmap out=raw;
            if(raw.getWidth()>target || raw.getHeight()>target){
                float scale=Math.min((float)target/raw.getWidth(),(float)target/raw.getHeight());
                out=Bitmap.createScaledBitmap(raw,
                    Math.max(1,(int)(raw.getWidth()*scale)),
                    Math.max(1,(int)(raw.getHeight()*scale)),true);
                if(out!=raw) raw.recycle();
            }
            mp3AlbumArtCache.put(albumId,out);
            return out;
        }catch(Exception ignored){
            return null;
        }
    }

    private android.support.v4.media.MediaBrowserCompat.MediaItem folderWithArt(
        String id,String title,String sub,Bitmap art,boolean preferGrid){
        MediaDescriptionCompat.Builder b=new MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title);
        if(sub!=null&&!sub.isEmpty()) b.setSubtitle(sub);
        if(art!=null) b.setIconBitmap(art);

        Bundle e=new Bundle();
        if(preferGrid) e.putInt("android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT",2);
        b.setExtras(e);
        return new android.support.v4.media.MediaBrowserCompat.MediaItem(
            b.build(),android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private android.support.v4.media.MediaBrowserCompat.MediaItem itemWithAlbumArt(
        String id,String title,String sub,long albumId){
        MediaDescriptionCompat.Builder b=new MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(sub);
        Bitmap art=mp3AlbumArt(albumId);
        if(art!=null) b.setIconBitmap(art);

        Bundle e=new Bundle();
        e.putInt("android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT",2);
        b.setExtras(e);
        return new android.support.v4.media.MediaBrowserCompat.MediaItem(
            b.build(),android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    private List<android.support.v4.media.MediaBrowserCompat.MediaItem> loadAudioByIds(List<Long> ids){
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> out=new ArrayList<>();
        for(Long mediaId:ids){
            Uri uri=ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,mediaId);
            String[] p={
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID
            };
            try(Cursor c=getContentResolver().query(uri,p,null,null,null)){
                if(c!=null&&c.moveToFirst()){
                    String title=safe(c.getString(0));
                    String artist=safe(c.getString(1));
                    String album=safe(c.getString(2));
                    long albumId=c.getLong(3);
                    String sub=artist+(album.isEmpty()?"":" · "+album);
                    out.add(itemWithAlbumArt("mp3:"+mediaId,title,sub,albumId));
                }
            }catch(SecurityException ignored){}
        }
        return out;
    }

    private List<android.support.v4.media.MediaBrowserCompat.MediaItem> loadLocalAudio(String extraSelection,String[] args,String sort){
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> out=new ArrayList<>();
        String[] projection={
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID
        };
        String selection=MediaStore.Audio.Media.IS_MUSIC+" != 0";
        if(extraSelection!=null&&!extraSelection.isEmpty()) selection+=" AND ("+extraSelection+")";
        try(Cursor c=getContentResolver().query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,selection,args,
            sort==null?MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC":sort)){
            if(c!=null){
                int idCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int albumCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int albumIdCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                while(c.moveToNext()){
                    long mid=c.getLong(idCol);
                    String title=safe(c.getString(titleCol));
                    String artist=safe(c.getString(artistCol));
                    String album=safe(c.getString(albumCol));
                    long albumId=c.getLong(albumIdCol);
                    String sub=artist+(album.isEmpty()?"":" · "+album);
                    out.add(itemWithAlbumArt("mp3:"+mid,title,sub,albumId));
                }
            }
        }catch(SecurityException ignored){}
        return out;
    }

    private List<android.support.v4.media.MediaBrowserCompat.MediaItem> loadMp3Albums(){
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> out=new ArrayList<>();
        String[] projection={
            MediaStore.Audio.Albums._ID,
            MediaStore.Audio.Albums.ALBUM,
            MediaStore.Audio.Albums.ARTIST,
            MediaStore.Audio.Albums.NUMBER_OF_SONGS
        };
        try(Cursor c=getContentResolver().query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            projection,null,null,
            MediaStore.Audio.Albums.ALBUM+" COLLATE NOCASE ASC")){
            if(c!=null){
                while(c.moveToNext()){
                    long albumId=c.getLong(0);
                    String album=safe(c.getString(1)).trim();
                    String artist=safe(c.getString(2)).trim();
                    int songs=c.getInt(3);
                    if(album.isEmpty()) album="(앨범 없음)";
                    String sub=artist+(songs>0?(artist.isEmpty()?"":" · ")+songs+"곡":"");
                    out.add(folderWithArt(
                        "mp3_album:"+Uri.encode(album),
                        album,sub,mp3AlbumArt(albumId),true));
                }
            }
        }catch(Exception ignored){}
        return out;
    }

    private long representativeAlbumIdForArtist(long artistId){
        Uri uri=MediaStore.Audio.Artists.Albums.getContentUri("external",artistId);
        try(Cursor c=getContentResolver().query(
            uri,new String[]{MediaStore.Audio.Albums._ID},
            null,null,MediaStore.Audio.Albums.ALBUM+" COLLATE NOCASE ASC")){
            if(c!=null&&c.moveToFirst()) return c.getLong(0);
        }catch(Exception ignored){}
        return -1L;
    }

    private List<android.support.v4.media.MediaBrowserCompat.MediaItem> loadMp3Artists(){
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> out=new ArrayList<>();
        String[] projection={
            MediaStore.Audio.Artists._ID,
            MediaStore.Audio.Artists.ARTIST,
            MediaStore.Audio.Artists.NUMBER_OF_ALBUMS,
            MediaStore.Audio.Artists.NUMBER_OF_TRACKS
        };
        try(Cursor c=getContentResolver().query(
            MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
            projection,null,null,
            MediaStore.Audio.Artists.ARTIST+" COLLATE NOCASE ASC")){
            if(c!=null){
                while(c.moveToNext()){
                    long artistId=c.getLong(0);
                    String artist=safe(c.getString(1)).trim();
                    int albums=c.getInt(2);
                    int tracks=c.getInt(3);
                    if(artist.isEmpty()) artist="(아티스트 없음)";
                    long albumId=representativeAlbumIdForArtist(artistId);
                    String sub=albums+"앨범 · "+tracks+"곡";
                    out.add(folderWithArt(
                        "mp3_artist:"+Uri.encode(artist),
                        artist,sub,mp3AlbumArt(albumId),true));
                }
            }
        }catch(Exception ignored){}
        return out;
    }

    private List<android.support.v4.media.MediaBrowserCompat.MediaItem> loadGroupItems(String column,String prefix){
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> out=new ArrayList<>();
        LinkedHashSet<String> seen=new LinkedHashSet<>();
        String[] projection={column};
        String selection=MediaStore.Audio.Media.IS_MUSIC+" != 0";
        try(Cursor c=getContentResolver().query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,selection,null,column+" COLLATE NOCASE ASC")){
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

    private void applyLive365Headers(HttpURLConnection con){
        con.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14; SM-N900S) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        con.setRequestProperty("Referer","https://live365.com/");
        con.setRequestProperty("Accept","*/*");
        con.setRequestProperty("Icy-MetaData","1");
        con.setRequestProperty("Connection","keep-alive");
    }

    private Map<String,String> live365PlayerHeaders(){
        Map<String,String> h=new HashMap<>();
        h.put("Referer","https://live365.com/");
        h.put("Accept","*/*");
        h.put("Icy-MetaData","1");
        h.put("Connection","keep-alive");
        return h;
    }

    private void resolveGalleryAndPlay(final String id,final String lookupUrl){
        resolver.execute(() -> {
            HttpURLConnection con=null;
            try{
                String next=lookupUrl;
                for(int hop=0;hop<6;hop++){
                    con=(HttpURLConnection)new URL(next).openConnection();
                    con.setConnectTimeout(10000);
                    con.setReadTimeout(10000);
                    con.setInstanceFollowRedirects(false);
                    applyLive365Headers(con);

                    int code=con.getResponseCode();
                    String type=con.getContentType();
                    String loc=con.getHeaderField("Location");
                    trace("Gallery hop "+hop+" HTTP "+code+" type="+type+" loc="+loc);

                    if(code>=300 && code<400 && loc!=null){
                        URL base=new URL(next);
                        next=new URL(base,loc).toString();
                        con.disconnect();
                        con=null;
                        continue;
                    }

                    if(code>=200 && code<300){
                        final String streamUrl=next;
                        if(con!=null){
                            con.disconnect();
                            con=null;
                        }
                        trace("Gallery resolved CDN: "+streamUrl);
                        runOnPlayerThread(() -> {
                            if(!userStopped && id.equals(currentRadioId))
                                playGalleryUrl(streamUrl,TITLES.get(id),"San Francisco Bay");
                        });
                        return;
                    }

                    throw new IOException("Gallery resolver HTTP "+code+" type="+type);
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

    private void playGalleryUrl(String url,String title,String subtitle){
        trace("Gallery player enter: "+url);
        try{
            MediaMetadataCompat.Builder mb=new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,radioProgramTitle("gallery"))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,TITLES.get("gallery"))
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART,StationArt.bitmap(this,"gallery",256));
            session.setMetadata(mb.build());

            DefaultHttpDataSource.Factory httpFactory=new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14; SM-N900S) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(live365PlayerHeaders());
            DefaultDataSource.Factory dataFactory=new DefaultDataSource.Factory(this,httpFactory);
            MediaSource source=new ProgressiveMediaSource.Factory(dataFactory)
                .createMediaSource(MediaItem.fromUri(url));

            player.setMediaSource(source);
            player.prepare();
            if(requestPlaybackFocus()) player.play();
            else trace("Gallery audio focus denied");
            publishState();
        }catch(Throwable e){
            trace("Gallery PLAYER ERROR: "+e);
            publishError("Gallery player: "+e);
            scheduleRetry();
        }
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
        String metaTitle=title;
        String metaArtist=subtitle;
        if(currentRadioId!=null && STREAMS.containsKey(currentRadioId)){
            metaTitle=radioProgramTitle(currentRadioId);
            metaArtist=TITLES.get(currentRadioId);
        }
        MediaMetadataCompat.Builder mb=new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,metaTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,metaArtist);
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

        long position=0L;
        if(player!=null){
            try{ position=Math.max(0L,player.getCurrentPosition()); }catch(Exception ignored){}
        }
        float speed=state==PlaybackStateCompat.STATE_PLAYING?1f:0f;

        session.setPlaybackState(new PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY|PlaybackStateCompat.ACTION_PAUSE|PlaybackStateCompat.ACTION_STOP|PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID|PlaybackStateCompat.ACTION_SKIP_TO_NEXT|PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS|PlaybackStateCompat.ACTION_FAST_FORWARD|PlaybackStateCompat.ACTION_REWIND|PlaybackStateCompat.ACTION_SEEK_TO)
            .setState(state,position,speed,android.os.SystemClock.elapsedRealtime()).build());
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

    private String presetRadioId(int slot){
        int station=getSharedPreferences("radio_presets",MODE_PRIVATE).getInt("slot"+slot,slot);
        if(station<0||station>5) station=slot;
        return "kr"+(station+1);
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
            for(int slot=0;slot<6;slot++){
                String id=presetRadioId(slot);
                x.add(item(id,radioProgramTitle(id),TITLES.get(id)+" · Preset "+(slot+1)));
            }
            x.add(item("kiis",radioProgramTitle("kiis"),TITLES.get("kiis")));
            x.add(item("gallery",radioProgramTitle("gallery"),TITLES.get("gallery")));
        } else if(parent.equals("mp3")){
            x.add(folder("mp3_favorites","즐겨찾기"));
            x.add(folder("mp3_recent","최근 재생"));
            x.add(folder("mp3_albums","앨범"));
            x.add(folder("mp3_artists","아티스트"));
            x.add(folder("mp3_folders","폴더"));
            x.add(folder("mp3_all","전체 곡"));
        } else if(parent.equals("mp3_recent")){
            x.addAll(loadAudioByIds(recentIds()));
        } else if(parent.equals("mp3_folders")){
            x.addAll(loadMp3Folders());
        } else if(parent.equals("mp3_albums")){
            x.addAll(loadMp3Albums());
        } else if(parent.equals("mp3_artists")){
            x.addAll(loadMp3Artists());
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

    @Override public void onSearch(String query,Bundle extras,Result<List<android.support.v4.media.MediaBrowserCompat.MediaItem>> result){
        String q=query==null?"":query.trim();
        if(q.isEmpty()){
            result.sendResult(Collections.emptyList());
            return;
        }
        String like="%"+q+"%";
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> matches=loadLocalAudio(
            "("+MediaStore.Audio.Media.TITLE+" LIKE ? OR "+
                MediaStore.Audio.Media.ARTIST+" LIKE ? OR "+
                MediaStore.Audio.Media.ALBUM+" LIKE ?)",
            new String[]{like,like,like},
            MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC");
        result.sendResult(matches);
    }

    @Override public void onDestroy(){
        retryHandler.removeCallbacksAndMessages(null);
        programHandler.removeCallbacksAndMessages(null);
        abandonPlaybackFocus();
        if(player!=null) player.release();
        resolver.shutdownNow();
        programExecutor.shutdownNow();
        if(session!=null) session.release();
        super.onDestroy();
    }
}
