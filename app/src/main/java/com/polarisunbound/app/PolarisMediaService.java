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
import androidx.media.utils.MediaConstants;
import androidx.car.app.connection.CarConnection;
import androidx.lifecycle.Observer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
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
    private final ExecutorService ftpExecutor=Executors.newFixedThreadPool(2);
    private final android.os.Handler retryHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler resolverRefreshHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler radioBufferingHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler programHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler positionHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler restoreHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private String currentRadioId=null;
    private volatile String currentResolvedRadioUrl=null;
    private final Map<String,String> standbyResolvedUrls=new ConcurrentHashMap<>();
    private final Map<String,Long> standbyResolvedAt=new ConcurrentHashMap<>();
    private int retryCount=0;
    private int radioSoftRetryCount=0;
    private boolean radioHasPlayed=false;
    private boolean userStopped=false;
    private boolean resumeAfterTransientFocusLoss=false;
    private static final String PREFS="polaris_playback_state";
    private static final String PREF_LAST_RADIO="last_radio_id";
    private static final String PREF_LAST_SOURCE="last_source";
    private static final String PREF_LAST_MP3_ID="last_mp3_id";
    private static final String PREF_LAST_MP3_POSITION="last_mp3_position_ms";
    private static final String PREF_RESUME_ALLOWED="resume_allowed";
    private static final String SOURCE_RADIO="RADIO";
    private static final String SOURCE_MP3="MP3";
    private static final String ACTION_MP3_LIKE="com.polarisunbound.app.action.MP3_LIKE";
    private static final String ACTION_MP3_SHUFFLE="com.polarisunbound.app.action.MP3_SHUFFLE";
    private static final String ACTION_MP3_REPEAT="com.polarisunbound.app.action.MP3_REPEAT";
    private static final String ACTION_MP3_DISLIKE="com.polarisunbound.app.action.MP3_DISLIKE";
    private static final String MP3_PREFS="polaris_mp3";
    private static final String PREF_MP3_RECENT="recent_ids";
    private static final String PREF_MP3_FAVORITES="favorite_ids";
    private static final String PREF_MP3_FTP_FAVORITES="favorite_ftp_paths";
    private static final String PREF_MP3_REPEAT_MODE="repeat_mode";
    private static final String PREF_MP3_SHUFFLE="shuffle";
    private final List<Long> currentMp3Queue=new ArrayList<>();
    private final android.util.LruCache<Long,Bitmap> mp3AlbumArtCache=new android.util.LruCache<>(48);
    private int currentMp3Index=-1;
    private long currentMp3Id=-1L;
    private volatile String currentFtpMp3Path=null;
    private volatile String currentFtpMp3Directory=null;
    private final List<String> currentFtpMp3Queue=new ArrayList<>();
    private volatile int currentFtpMp3Index=-1;
    private volatile boolean ftpMp3Preparing=false;
    private final List<String> currentFavoriteQueue=new ArrayList<>();
    private int currentFavoriteIndex=-1;
    private boolean favoriteQueueActive=false;
    private int mp3RepeatMode=PlaybackStateCompat.REPEAT_MODE_NONE;
    private boolean mp3Shuffle=false;
    private boolean mp3EndHandled=false;
    private final Random mp3Random=new Random();
    private CarConnection carConnection;
    private Observer<Integer> carConnectionObserver;
    private boolean aaProjectionConnected=false;
    private boolean sessionRestoreConsumed=false;
    private volatile String canFtpStatus="Synology FTP";
    private volatile String mp3FtpStatus="Synology FTP";
    private final DefaultLoadErrorHandlingPolicy radioLoadErrorPolicy=
        new DefaultLoadErrorHandlingPolicy(8);
    private static final long RADIO_HLS_TARGET_OFFSET_MS=20000L;
    private static final long RADIO_RESOLVER_PREFETCH_MS=120000L;
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
        DefaultHttpDataSource.Factory playerHttpFactory=new DefaultHttpDataSource.Factory()
            .setUserAgent("Polaris-Unbound/"+BuildConfig.VERSION_NAME)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(15000);
        DefaultDataSource.Factory playerDataFactory=new DefaultDataSource.Factory(this,playerHttpFactory);
        DefaultMediaSourceFactory mediaSourceFactory=new DefaultMediaSourceFactory(playerDataFactory)
            .setLoadErrorHandlingPolicy(radioLoadErrorPolicy);
        DefaultLoadControl loadControl=new DefaultLoadControl.Builder()
            // Keep a large reservoir, but do not force a five-second refill after
            // a transient HLS gap. Resume once ~1.5 s of audio is available.
            .setBufferDurationsMs(30000,60000,1500,1500)
            .build();
        player=new ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build();
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
        mp3RepeatMode=getSharedPreferences(MP3_PREFS,MODE_PRIVATE)
            .getInt(PREF_MP3_REPEAT_MODE,PlaybackStateCompat.REPEAT_MODE_NONE);
        mp3Shuffle=getSharedPreferences(MP3_PREFS,MODE_PRIVATE)
            .getBoolean(PREF_MP3_SHUFFLE,false);
        session.setRepeatMode(mp3RepeatMode);
        session.setShuffleMode(mp3Shuffle ? PlaybackStateCompat.SHUFFLE_MODE_ALL : PlaybackStateCompat.SHUFFLE_MODE_NONE);
        session.setCallback(new MediaSessionCompat.Callback(){
            @Override public void onPlayFromMediaId(String id,Bundle extras){
                trace("SERVICE onPlayFromMediaId: "+id);
                try{
                    if("can:ftp_upload".equals(id)){ uploadFtpFromAa("CAN","can"); return; }
                    if("mp3:ftp_upload".equals(id)){ uploadFtpFromAa("MP3","mp3_ftp"); return; }
                    if(id!=null && id.startsWith("favlocal:")){
                        startFavoriteQueueAt("local:"+id.substring("favlocal:".length()));
                        return;
                    }
                    if(id!=null && id.startsWith("favftp:")){
                        startFavoriteQueueAt("ftp:"+Uri.decode(id.substring("favftp:".length())));
                        return;
                    }
                    if(id!=null && id.startsWith("ftpmp3:")){
                        favoriteQueueActive=false;
                        playFtpAudio(id);
                        return;
                    }
                    if(id!=null && id.startsWith("mp3:")){
                        favoriteQueueActive=false;
                        playLocalAudio(id);
                        return;
                    }
                    favoriteQueueActive=false;
                    currentFtpMp3Path=null;
                    currentRadioId=id;
                    currentMp3Id=-1L;
                    rememberRadioForResume(id);
                    enterPlaybackForeground(TITLES.get(id));
                    retryCount=0;
                    radioSoftRetryCount=0;
                    userStopped=false;
                    retryHandler.removeCallbacksAndMessages(null);
                    resolverRefreshHandler.removeCallbacksAndMessages(null);
                    startRadio(id);
                }catch(Throwable e){ publishError("playFromMediaId: "+e); }
            }
            @Override public void onPlayFromSearch(String query,Bundle extras){
                trace("SERVICE onPlayFromSearch: query="+query+" extras="+String.valueOf(extras));
                handleVoicePlaySearch(query,extras);
            }
            @Override public void onPlay(){
                userStopped=false;
                if(currentFtpMp3Path!=null){
                    if(!ftpMp3Preparing && requestPlaybackFocus()) player.play();
                    publishState();
                    return;
                }
                setResumeAllowed(true);
                if(currentRadioId==null && currentMp3Id<0){
                    sessionRestoreConsumed=false;
                    restoreLastSourceIfAllowed();
                    return;
                }
                if(currentRadioId!=null) rememberRadioForResume(currentRadioId);
                else if(currentMp3Id>=0) rememberMp3ForResume(currentMp3Id,currentPlayerPosition());
                if(requestPlaybackFocus()) player.play();
                publishState();
            }
            @Override public void onSkipToNext(){ skipCurrent(1); }
            @Override public void onSkipToPrevious(){ skipCurrent(-1); }
            @Override public void onFastForward(){ skipCurrent(1); }
            @Override public void onRewind(){ skipCurrent(-1); }
            @Override public void onPause(){
                saveLastMp3Position();
                userStopped=true;
                resumeAfterTransientFocusLoss=false;
                setResumeAllowed(false);
                retryHandler.removeCallbacksAndMessages(null);
                resolverRefreshHandler.removeCallbacksAndMessages(null);
                restoreHandler.removeCallbacksAndMessages(null);
                player.pause();
                publishState();
                trace("USER pause -> automatic session restore disabled");
            }
            @Override public void onSeekTo(long pos){
                if(player!=null){
                    player.seekTo(Math.max(0L,pos));
                    saveLastMp3Position();
                    publishState();
                }
            }
            @Override public void onSetRepeatMode(int repeatMode){
                if(repeatMode!=PlaybackStateCompat.REPEAT_MODE_ONE &&
                   repeatMode!=PlaybackStateCompat.REPEAT_MODE_ALL)
                    repeatMode=PlaybackStateCompat.REPEAT_MODE_NONE;
                mp3RepeatMode=repeatMode;
                getSharedPreferences(MP3_PREFS,MODE_PRIVATE).edit()
                    .putInt(PREF_MP3_REPEAT_MODE,mp3RepeatMode).apply();
                session.setRepeatMode(mp3RepeatMode);
                trace("MP3 repeat mode="+mp3RepeatMode);
                publishState();
            }
            @Override public void onSetShuffleMode(int shuffleMode){
                mp3Shuffle=shuffleMode!=PlaybackStateCompat.SHUFFLE_MODE_NONE;
                getSharedPreferences(MP3_PREFS,MODE_PRIVATE).edit()
                    .putBoolean(PREF_MP3_SHUFFLE,mp3Shuffle).apply();
                session.setShuffleMode(mp3Shuffle ? PlaybackStateCompat.SHUFFLE_MODE_ALL : PlaybackStateCompat.SHUFFLE_MODE_NONE);
                trace("MP3 shuffle="+mp3Shuffle);
                publishState();
            }
            @Override public void onCustomAction(String action,Bundle extras){
                if(ACTION_MP3_LIKE.equals(action)){
                    toggleFavoriteCurrent();
                }else if(ACTION_MP3_SHUFFLE.equals(action)){
                    toggleShuffleMode();
                }else if(ACTION_MP3_REPEAT.equals(action)){
                    cycleRepeatMode();
                }else if(ACTION_MP3_DISLIKE.equals(action)){
                    dislikeCurrent();
                }
            }
            @Override public boolean onMediaButtonEvent(Intent mediaButtonIntent){
                if(mediaButtonIntent!=null){
                    android.view.KeyEvent event=
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if(event!=null && event.getAction()==android.view.KeyEvent.ACTION_DOWN){
                        int code=event.getKeyCode();
                        if(code==android.view.KeyEvent.KEYCODE_MEDIA_NEXT){
                            trace("MEDIA BUTTON next");
                            skipCurrent(1);
                            return true;
                        }
                        if(code==android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS){
                            trace("MEDIA BUTTON previous");
                            skipCurrent(-1);
                            return true;
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
            @Override public void onStop(){
                saveLastMp3Position();
                userStopped=true;
                resumeAfterTransientFocusLoss=false;
                setResumeAllowed(false);
                currentRadioId=null;
                currentMp3Id=-1L;
                currentFtpMp3Path=null;
                currentFtpMp3Directory=null;
                currentFtpMp3Index=-1;
                ftpMp3Preparing=false;
                synchronized(currentFtpMp3Queue){ currentFtpMp3Queue.clear(); }
                retryCount=0;
                radioSoftRetryCount=0;
                retryHandler.removeCallbacksAndMessages(null);
                restoreHandler.removeCallbacksAndMessages(null);
                leavePlaybackForeground();
                abandonPlaybackFocus();
                player.stop();
                publishState();
                trace("USER stop -> automatic session restore disabled");
            }
        });
        player.addListener(new Player.Listener(){
            @Override public void onIsPlayingChanged(boolean playing){
                if(playing && currentRadioId!=null){
                    radioHasPlayed=true;
                    radioSoftRetryCount=0;
                    retryCount=0;
                    radioBufferingHandler.removeCallbacksAndMessages(null);
                    traceRadioHealth("PLAYING");
                }
                publishState();
            }
            @Override public void onPlaybackStateChanged(int state){
                if(currentRadioId!=null){
                    if(state==Player.STATE_BUFFERING){
                        traceRadioHealth("BUFFERING");
                        scheduleRadioBufferHandoff();
                    }else if(state==Player.STATE_READY){
                        radioBufferingHandler.removeCallbacksAndMessages(null);
                    }
                }
                publishState();
                if(state==Player.STATE_ENDED && !mp3EndHandled){
                    if(favoriteQueueActive){
                        mp3EndHandled=true;
                        handleFavoriteEnded();
                    }else if(currentFtpMp3Path!=null){
                        mp3EndHandled=true;
                        skipFtpMp3(1);
                    }else if(currentMp3Id>=0){
                        mp3EndHandled=true;
                        handleMp3Ended();
                    }
                }
            }
            @Override public void onPlayerError(PlaybackException error){
                radioBufferingHandler.removeCallbacksAndMessages(null);
                String msg=describePlaybackError(error);
                trace("PLAYER ERROR: "+msg);
                if(currentRadioId!=null && !userStopped){
                    traceRadioHealth("ERROR "+msg);
                    publishRadioRecovering(msg);
                    recoverRadioAfterError(error);
                }else{
                    publishError(msg);
                }
            }
        });
        setSessionToken(session.getSessionToken());
        session.setActive(true);
        publishState();
        startProgramRefreshLoop();
        startLocalRadioClockLoop();
        startPositionSaver();
        observeCarConnection();
    }


    private long currentPlayerPosition(){
        if(player==null) return 0L;
        try{ return Math.max(0L,player.getCurrentPosition()); }
        catch(Exception ignored){ return 0L; }
    }

    private void setResumeAllowed(boolean allowed){
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putBoolean(PREF_RESUME_ALLOWED,allowed).apply();
    }

    private boolean isResumeAllowed(android.content.SharedPreferences p){
        if(p.contains(PREF_RESUME_ALLOWED))
            return p.getBoolean(PREF_RESUME_ALLOWED,false);
        // Migration from v0.36: an existing last radio means the old app intended auto-resume.
        return p.getString(PREF_LAST_RADIO,null)!=null;
    }

    private void rememberRadioForResume(String id){
        if(id==null||!STREAMS.containsKey(id)) return;
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putString(PREF_LAST_SOURCE,SOURCE_RADIO)
            .putString(PREF_LAST_RADIO,id)
            .putBoolean(PREF_RESUME_ALLOWED,true)
            .apply();
    }

    private void rememberMp3ForResume(long mediaId,long positionMs){
        if(mediaId<0) return;
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putString(PREF_LAST_SOURCE,SOURCE_MP3)
            .putLong(PREF_LAST_MP3_ID,mediaId)
            .putLong(PREF_LAST_MP3_POSITION,Math.max(0L,positionMs))
            .putBoolean(PREF_RESUME_ALLOWED,true)
            .apply();
    }

    private void saveLastMp3Position(){
        if(currentMp3Id<0||player==null) return;
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putString(PREF_LAST_SOURCE,SOURCE_MP3)
            .putLong(PREF_LAST_MP3_ID,currentMp3Id)
            .putLong(PREF_LAST_MP3_POSITION,currentPlayerPosition())
            .apply();
    }

    private void startPositionSaver(){
        positionHandler.postDelayed(new Runnable(){
            @Override public void run(){
                saveLastMp3Position();
                positionHandler.postDelayed(this,5000L);
            }
        },5000L);
    }

    private void observeCarConnection(){
        try{
            carConnection=new CarConnection(this);
            carConnectionObserver=type -> restoreHandler.post(() ->
                handleCarConnection(type==null ?
                    CarConnection.CONNECTION_TYPE_NOT_CONNECTED : type));
            carConnection.getType().observeForever(carConnectionObserver);
        }catch(Throwable e){
            trace("CarConnection unavailable: "+e);
        }
    }

    private void handleCarConnection(int type){
        boolean projected=type==CarConnection.CONNECTION_TYPE_PROJECTION;
        if(projected){
            if(!aaProjectionConnected){
                aaProjectionConnected=true;
                sessionRestoreConsumed=false;
                restoreHandler.removeCallbacksAndMessages(null);
                restoreHandler.postDelayed(this::restoreLastSourceIfAllowed,2500L);
                trace("AA projection connected");
            }
            return;
        }

        if(aaProjectionConnected){
            trace("AA projection disconnected");
            saveLastMp3Position();
            aaProjectionConnected=false;
            sessionRestoreConsumed=false;
            resumeAfterTransientFocusLoss=false;
            retryHandler.removeCallbacksAndMessages(null);
            restoreHandler.removeCallbacksAndMessages(null);
            if(player!=null){
                try{ player.pause(); }catch(Exception ignored){}
                try{ player.stop(); }catch(Exception ignored){}
            }
            currentRadioId=null;
            currentMp3Id=-1L;
            currentFtpMp3Path=null;
            retryCount=0;
            radioSoftRetryCount=0;
            leavePlaybackForeground();
            abandonPlaybackFocus();
            publishState();
        }
    }

    private void restoreLastSourceIfAllowed(){
        if(sessionRestoreConsumed) return;
        sessionRestoreConsumed=true;
        if(!aaProjectionConnected) return;

        android.content.SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
        if(!isResumeAllowed(p)){
            trace("AA restore skipped: manual pause/stop");
            return;
        }
        if(currentRadioId!=null || currentMp3Id>=0 || currentFtpMp3Path!=null ||
           (player!=null && player.isPlaying())){
            trace("AA restore skipped: media already active");
            return;
        }

        String source=p.getString(PREF_LAST_SOURCE,null);
        if(source==null && p.getString(PREF_LAST_RADIO,null)!=null)
            source=SOURCE_RADIO;

        if(SOURCE_MP3.equals(source)){
            long mediaId=p.getLong(PREF_LAST_MP3_ID,-1L);
            long position=p.getLong(PREF_LAST_MP3_POSITION,0L);
            if(mediaId>=0){
                trace("AA restore MP3 id="+mediaId+" pos="+position);
                userStopped=false;
                playLocalAudioId(mediaId,position,true);
            }
            return;
        }

        if(SOURCE_RADIO.equals(source)){
            String id=p.getString(PREF_LAST_RADIO,null);
            if(id!=null && STREAMS.containsKey(id)){
                trace("AA restore radio="+id);
                currentRadioId=id;
                currentMp3Id=-1L;
                retryCount=0;
                radioSoftRetryCount=0;
                userStopped=false;
                rememberRadioForResume(id);
                enterPlaybackForeground(TITLES.get(id));
                startRadio(id);
            }
        }
    }

    private boolean isFavorite(long mediaId){
        if(mediaId<0) return false;
        Set<String> set=getSharedPreferences(MP3_PREFS,MODE_PRIVATE)
            .getStringSet(PREF_MP3_FAVORITES,Collections.emptySet());
        return set.contains(String.valueOf(mediaId));
    }

    private void setFavorite(long mediaId,boolean favorite){
        if(mediaId<0) return;
        Set<String> stored=getSharedPreferences(MP3_PREFS,MODE_PRIVATE)
            .getStringSet(PREF_MP3_FAVORITES,Collections.emptySet());
        Set<String> copy=new HashSet<>(stored);
        String key=String.valueOf(mediaId);
        if(favorite) copy.add(key); else copy.remove(key);
        getSharedPreferences(MP3_PREFS,MODE_PRIVATE).edit()
            .putStringSet(PREF_MP3_FAVORITES,copy).apply();
        notifyChildrenChanged("mp3_favorites");
        trace("MP3 like id="+mediaId+" -> "+favorite);
    }

    private List<String> ftpFavoritePaths(){
        Set<String> stored=getSharedPreferences(MP3_PREFS,MODE_PRIVATE)
            .getStringSet(PREF_MP3_FTP_FAVORITES,Collections.emptySet());
        List<String> out=new ArrayList<>(stored);
        Collections.sort(out,String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private boolean isFtpFavorite(String relativePath){
        if(relativePath==null) return false;
        Set<String> stored=getSharedPreferences(MP3_PREFS,MODE_PRIVATE)
            .getStringSet(PREF_MP3_FTP_FAVORITES,Collections.emptySet());
        return stored.contains(relativePath);
    }

    private void setFtpFavorite(String relativePath,boolean favorite){
        if(relativePath==null||relativePath.trim().isEmpty()) return;
        Set<String> stored=getSharedPreferences(MP3_PREFS,MODE_PRIVATE)
            .getStringSet(PREF_MP3_FTP_FAVORITES,Collections.emptySet());
        Set<String> copy=new HashSet<>(stored);
        if(favorite) copy.add(relativePath); else copy.remove(relativePath);
        getSharedPreferences(MP3_PREFS,MODE_PRIVATE).edit()
            .putStringSet(PREF_MP3_FTP_FAVORITES,copy).apply();
        notifyChildrenChanged("mp3_favorites");
        trace("FTP MP3 like path="+relativePath+" -> "+favorite);
    }

    private boolean isCurrentFavorite(){
        if(currentFtpMp3Path!=null) return isFtpFavorite(currentFtpMp3Path);
        return currentMp3Id>=0 && isFavorite(currentMp3Id);
    }

    private String currentFavoriteKey(){
        if(currentFtpMp3Path!=null) return "ftp:"+currentFtpMp3Path;
        if(currentMp3Id>=0) return "local:"+currentMp3Id;
        return null;
    }

    private List<String> favoriteQueueSnapshot(){
        List<String> out=new ArrayList<>();
        for(Long id:favoriteIdsInLibraryOrder()) out.add("local:"+id);
        for(String rel:ftpFavoritePaths()) out.add("ftp:"+rel);
        return out;
    }

    private void startFavoriteQueueAt(String key){
        List<String> queue=favoriteQueueSnapshot();
        int index=queue.indexOf(key);
        if(index<0){
            publishError("좋아요 항목을 찾지 못했습니다");
            return;
        }
        synchronized(currentFavoriteQueue){
            currentFavoriteQueue.clear();
            currentFavoriteQueue.addAll(queue);
            currentFavoriteIndex=index;
        }
        favoriteQueueActive=true;
        playFavoriteQueueEntry(key);
    }

    private void playFavoriteQueueEntry(String key){
        if(key==null) return;
        favoriteQueueActive=true;
        mp3EndHandled=false;
        if(key.startsWith("local:")){
            try{
                long id=Long.parseLong(key.substring("local:".length()));
                playLocalAudioId(id);
            }catch(Exception e){
                publishError("좋아요 로컬 MP3 오류: "+e.getMessage());
            }
        }else if(key.startsWith("ftp:")){
            String rel=key.substring("ftp:".length());
            playFtpAudio("ftpmp3:"+Uri.encode(rel));
        }
    }

    private void skipFavoriteQueue(int delta){
        String nextKey=null;
        synchronized(currentFavoriteQueue){
            if(currentFavoriteQueue.isEmpty()){
                currentFavoriteQueue.addAll(favoriteQueueSnapshot());
            }
            if(currentFavoriteQueue.isEmpty()) return;
            String current=currentFavoriteKey();
            int at=currentFavoriteQueue.indexOf(current);
            if(at<0) at=Math.max(0,Math.min(currentFavoriteIndex,currentFavoriteQueue.size()-1));
            int next;
            if(mp3Shuffle && delta>0 && currentFavoriteQueue.size()>1){
                next=at;
                for(int tries=0;tries<8&&next==at;tries++)
                    next=mp3Random.nextInt(currentFavoriteQueue.size());
                if(next==at) next=(at+1)%currentFavoriteQueue.size();
            }else{
                next=(at+delta+currentFavoriteQueue.size())%currentFavoriteQueue.size();
            }
            currentFavoriteIndex=next;
            nextKey=currentFavoriteQueue.get(next);
        }
        playFavoriteQueueEntry(nextKey);
    }

    private void handleFavoriteEnded(){
        List<String> queue;
        int at;
        synchronized(currentFavoriteQueue){
            queue=new ArrayList<>(currentFavoriteQueue);
            at=currentFavoriteIndex;
        }
        if(queue.isEmpty()){
            favoriteQueueActive=false;
            return;
        }
        if(mp3RepeatMode==PlaybackStateCompat.REPEAT_MODE_ONE){
            playFavoriteQueueEntry(queue.get(Math.max(0,Math.min(at,queue.size()-1))));
            return;
        }
        if(mp3Shuffle){
            skipFavoriteQueue(1);
            return;
        }
        int next=at+1;
        if(next<queue.size()){
            synchronized(currentFavoriteQueue){ currentFavoriteIndex=next; }
            playFavoriteQueueEntry(queue.get(next));
            return;
        }
        if(mp3RepeatMode==PlaybackStateCompat.REPEAT_MODE_ALL){
            synchronized(currentFavoriteQueue){ currentFavoriteIndex=0; }
            playFavoriteQueueEntry(queue.get(0));
            return;
        }
        favoriteQueueActive=false;
        setResumeAllowed(false);
        abandonPlaybackFocus();
        leavePlaybackForeground();
        publishState();
    }

    private void toggleFavoriteCurrent(){
        if(currentFtpMp3Path!=null){
            setFtpFavorite(currentFtpMp3Path,!isFtpFavorite(currentFtpMp3Path));
        }else if(currentMp3Id>=0){
            setFavorite(currentMp3Id,!isFavorite(currentMp3Id));
        }else{
            return;
        }
        publishState();
    }

    private void toggleShuffleMode(){
        mp3Shuffle=!mp3Shuffle;
        getSharedPreferences(MP3_PREFS,MODE_PRIVATE).edit()
            .putBoolean(PREF_MP3_SHUFFLE,mp3Shuffle).apply();
        session.setShuffleMode(mp3Shuffle ?
            PlaybackStateCompat.SHUFFLE_MODE_ALL :
            PlaybackStateCompat.SHUFFLE_MODE_NONE);
        trace("MP3 custom shuffle="+mp3Shuffle);
        publishState();
    }

    private void cycleRepeatMode(){
        if(mp3RepeatMode==PlaybackStateCompat.REPEAT_MODE_NONE)
            mp3RepeatMode=PlaybackStateCompat.REPEAT_MODE_ALL;
        else if(mp3RepeatMode==PlaybackStateCompat.REPEAT_MODE_ALL)
            mp3RepeatMode=PlaybackStateCompat.REPEAT_MODE_ONE;
        else
            mp3RepeatMode=PlaybackStateCompat.REPEAT_MODE_NONE;

        getSharedPreferences(MP3_PREFS,MODE_PRIVATE).edit()
            .putInt(PREF_MP3_REPEAT_MODE,mp3RepeatMode).apply();
        session.setRepeatMode(mp3RepeatMode);
        trace("MP3 custom repeat="+mp3RepeatMode);
        publishState();
    }

    private void dislikeCurrent(){
        String current=currentFavoriteKey();
        if(current==null) return;

        if(current.startsWith("ftp:"))
            setFtpFavorite(current.substring("ftp:".length()),false);
        else if(current.startsWith("local:")){
            try{ setFavorite(Long.parseLong(current.substring("local:".length())),false); }
            catch(Exception ignored){}
        }

        trace("MP3 dislike -> next");
        if(favoriteQueueActive){
            String nextKey=null;
            synchronized(currentFavoriteQueue){
                int at=currentFavoriteQueue.indexOf(current);
                if(at>=0) currentFavoriteQueue.remove(at);
                if(!currentFavoriteQueue.isEmpty()){
                    int next=Math.min(Math.max(0,at),currentFavoriteQueue.size()-1);
                    currentFavoriteIndex=next;
                    nextKey=currentFavoriteQueue.get(next);
                }
            }
            if(nextKey!=null) playFavoriteQueueEntry(nextKey);
            else{
                favoriteQueueActive=false;
                player.stop();
                publishState();
            }
        }else if(currentFtpMp3Path!=null){
            skipFtpMp3(1);
        }else{
            skipMp3(1);
        }
    }

    private void uploadFtpFromAa(String scope,String parentId){
        boolean mp3="MP3".equals(scope);
        if(mp3) mp3FtpStatus="업로드 중…"; else canFtpStatus="업로드 중…";
        notifyChildrenChanged(parentId);

        ftpExecutor.execute(() -> {
            String status;
            try{
                java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
                String stamp=f.format(new Date());
                String prefix=mp3?"mp3_test_":"can_test_";
                String fileName=prefix+stamp+".txt";
                String payload="Polaris Unbound FTP test\n"
                    +"scope="+scope+"\n"
                    +"version="+BuildConfig.VERSION_NAME+"\n"
                    +"time="+stamp+"\n";
                String remote=PolarisFtp.uploadText(this,scope,fileName,payload);
                status="완료 · "+remote;
                trace(scope+" FTP upload OK: "+remote);
            }catch(Exception e){
                String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                if(msg.length()>70) msg=msg.substring(0,70);
                status="실패 · "+msg;
                trace(scope+" FTP upload error: "+e);
            }

            final String finalStatus=status;
            programHandler.post(() -> {
                if(mp3) mp3FtpStatus=finalStatus; else canFtpStatus=finalStatus;
                notifyChildrenChanged(parentId);
            });
        });
    }

    private void startLocalRadioClockLoop(){
        programHandler.postDelayed(new Runnable(){
            @Override public void run(){
                String id=currentRadioId;
                if("kiis".equals(id)||"gallery".equals(id)){
                    applyCurrentRadioMetadata(id);
                    notifyChildrenChanged("radio");
                }
                programHandler.postDelayed(this,60L*1000L);
            }
        },60L*1000L);
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
        String title=r.aaTitle(id);
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


    private String normalizeVoiceQuery(String raw){
        String q=raw==null?"":raw.toLowerCase(Locale.KOREA).trim();
        q=q.replace("엠피쓰리","mp3")
           .replace("엠피3","mp3")
           .replace("에프엠","fm")
           .replace("에이엠","am")
           .replace("점",".")
           .replaceAll("\\s+"," ");
        q=q.replaceAll("(?i)\\b(틀어줘|재생해줘|재생|플레이|play|please)\\b"," ");
        q=q.replaceAll("\\s+"," ").trim();
        return q;
    }

    private String radioIdFromVoice(String raw){
        String q=normalizeVoiceQuery(raw);
        String tight=q.replace(" ","");

        // Overseas names first because AFN and KIIS both use 102.7.
        if(tight.contains("kiis")||tight.contains("키스fm")||tight.contains("키스에프엠")||
           tight.contains("키이스")||tight.contains("키스라디오")) return "kiis";
        if(tight.contains("gallery41")||tight.contains("갤러리41")||
           tight.contains("갤러리포티원")||tight.contains("gallery")||
           tight.contains("갤러리")) return "gallery";

        if(tight.contains("89.1")||tight.contains("891")||
           tight.contains("kbs")||tight.contains("케이비에스")||
           tight.contains("coolfm")||tight.contains("쿨fm")||tight.contains("쿨에프엠")) return "kr1";

        if(tight.contains("91.9")||tight.contains("919")||
           tight.contains("fm4u")||tight.contains("fm포유")||tight.contains("에프엠포유")) return "kr2";

        if(tight.contains("93.9")||tight.contains("939")||
           tight.contains("cbs")||tight.contains("씨비에스")||
           tight.contains("musicfm")||tight.contains("뮤직fm")||tight.contains("음악fm")) return "kr3";

        if(tight.contains("95.9")||tight.contains("959")||
           tight.contains("표준fm")||tight.contains("표준에프엠")||tight.contains("standardfm")) return "kr4";

        if(tight.contains("102.7")||tight.contains("1027")||
           tight.contains("afn")||tight.contains("에이에프엔")||
           tight.contains("eaglefm")||tight.contains("이글fm")) return "kr5";

        if(tight.contains("107.7")||tight.contains("1077")||
           tight.contains("sbs")||tight.contains("에스비에스")||
           tight.contains("powerfm")||tight.contains("파워fm")||tight.contains("파워에프엠")) return "kr6";

        // Bare MBC is ambiguous; prefer FM4U. "표준" above already resolves standard FM.
        if(tight.contains("mbc")||tight.contains("엠비씨")) return "kr2";
        return null;
    }

    private String stripVoiceMp3Prefix(String raw){
        String q=normalizeVoiceQuery(raw);
        q=q.replaceFirst("(?i)^\\s*(mp3|음악|노래)\\s*","").trim();
        q=q.replaceAll("(?i)\\s*(틀어줘|재생해줘|재생|플레이|play)\\s*$","").trim();
        return q;
    }

    private long findMp3Title(String wanted){
        if(wanted==null||wanted.trim().isEmpty()) return -1L;
        String q=wanted.trim();
        String[] projection={
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME
        };
        String selection=MediaStore.Audio.Media.IS_MUSIC+" != 0";
        long contains=-1L;
        try(Cursor c=getContentResolver().query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,selection,null,
            MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC")){
            if(c!=null){
                while(c.moveToNext()){
                    long id=c.getLong(0);
                    String title=safe(c.getString(1)).trim();
                    String file=safe(c.getString(2)).trim();
                    String fileStem=file.replaceFirst("(?i)\\.[a-z0-9]{1,6}$","");
                    if(title.equalsIgnoreCase(q)||fileStem.equalsIgnoreCase(q)) return id;
                    if(contains<0 &&
                       (title.toLowerCase(Locale.KOREA).contains(q.toLowerCase(Locale.KOREA)) ||
                        fileStem.toLowerCase(Locale.KOREA).contains(q.toLowerCase(Locale.KOREA))))
                        contains=id;
                }
            }
        }catch(Exception e){
            trace("VOICE MP3 search error: "+e);
        }
        return contains;
    }

    private List<Long> favoriteIdsInLibraryOrder(){
        Set<String> favorites=getSharedPreferences(MP3_PREFS,MODE_PRIVATE)
            .getStringSet(PREF_MP3_FAVORITES,Collections.emptySet());
        List<Long> out=new ArrayList<>();
        for(Long id:loadAllMp3Ids()){
            if(favorites.contains(String.valueOf(id))) out.add(id);
        }
        return out;
    }

    private void startVoiceLikedMp3(){
        List<String> queue=favoriteQueueSnapshot();
        if(queue.isEmpty()){
            publishError("좋아요 MP3가 없습니다");
            trace("VOICE MP3 liked playlist empty");
            return;
        }
        int index=0;
        if(mp3Shuffle && queue.size()>1) index=mp3Random.nextInt(queue.size());
        synchronized(currentFavoriteQueue){
            currentFavoriteQueue.clear();
            currentFavoriteQueue.addAll(queue);
            currentFavoriteIndex=index;
        }
        favoriteQueueActive=true;
        trace("VOICE liked playlist size="+queue.size()+" index="+index);
        playFavoriteQueueEntry(queue.get(index));
    }

    private void playVoiceRadio(String id){
        if(id==null||!STREAMS.containsKey(id)){
            publishError("라디오 채널을 찾지 못했습니다");
            return;
        }
        favoriteQueueActive=false;
        currentFtpMp3Path=null;
        ftpMp3Preparing=false;
        currentRadioId=id;
        currentMp3Id=-1L;
        rememberRadioForResume(id);
        enterPlaybackForeground(TITLES.get(id));
        retryCount=0;
        radioSoftRetryCount=0;
        userStopped=false;
        retryHandler.removeCallbacksAndMessages(null);
        resolverRefreshHandler.removeCallbacksAndMessages(null);
        trace("VOICE radio -> "+id+" "+TITLES.get(id));
        startRadio(id);
    }

    private void handleVoicePlaySearch(String query,Bundle extras){
        String raw=query==null?"":query.trim();
        String normalized=normalizeVoiceQuery(raw);

        boolean asksRadio=normalized.contains("라디오")||normalized.contains("radio");
        boolean asksMp3=normalized.contains("mp3")||normalized.contains("음악")||normalized.contains("노래");

        String radioId=radioIdFromVoice(raw);
        if(asksRadio || radioId!=null){
            if(radioId!=null){
                playVoiceRadio(radioId);
                return;
            }
            publishError("라디오 채널을 말씀해 주세요");
            return;
        }

        if(asksMp3){
            String wanted=stripVoiceMp3Prefix(raw);
            if(wanted.isEmpty()){
                startVoiceLikedMp3();
                return;
            }
            long id=findMp3Title(wanted);
            if(id>=0){
                trace("VOICE MP3 title -> "+wanted+" / "+id);
                favoriteQueueActive=false;
                ensureMp3Queue(id);
                playLocalAudioId(id);
            }else{
                publishError("MP3에서 "+wanted+"을 찾지 못했습니다");
            }
            return;
        }

        // If Assistant routes a plain title to this app, treat it as an MP3 title.
        if(!normalized.isEmpty()){
            long id=findMp3Title(normalized);
            if(id>=0){
                trace("VOICE plain title -> "+normalized+" / "+id);
                favoriteQueueActive=false;
                ensureMp3Queue(id);
                playLocalAudioId(id);
                return;
            }
        }

        // Empty/default MP3 play request opens the user's 좋아요 playlist.
        startVoiceLikedMp3();
    }

    private void skipCurrent(int delta){
        if(currentRadioId!=null) skipRadio(delta);
        else if(favoriteQueueActive) skipFavoriteQueue(delta);
        else if(currentFtpMp3Path!=null) skipFtpMp3(delta);
        else if(currentMp3Id>=0 || !currentMp3Queue.isEmpty()) skipMp3(delta);
    }

    private void prepareFtpQueueAsync(String relativePath){
        final String rel=relativePath;
        final String directory=PolarisFtp.parentRelative(rel);
        ftpExecutor.execute(() -> {
            try{
                List<String> queue=PolarisFtp.listMp3AudioPaths(this,directory);
                if(currentFtpMp3Path==null ||
                   !directory.equals(PolarisFtp.parentRelative(currentFtpMp3Path))) return;
                synchronized(currentFtpMp3Queue){
                    currentFtpMp3Queue.clear();
                    currentFtpMp3Queue.addAll(queue);
                    currentFtpMp3Directory=directory;
                    currentFtpMp3Index=currentFtpMp3Queue.indexOf(currentFtpMp3Path);
                }
                trace("FTP MP3 queue "+directory+" size="+queue.size()+
                    " index="+currentFtpMp3Index);
            }catch(Exception e){
                trace("FTP MP3 queue error: "+e);
            }
        });
    }

    private void skipFtpMp3(int delta){
        final String current=currentFtpMp3Path;
        if(current==null) return;
        final String directory=PolarisFtp.parentRelative(current);

        ftpExecutor.execute(() -> {
            try{
                List<String> queue;
                synchronized(currentFtpMp3Queue){
                    if(directory.equals(currentFtpMp3Directory) &&
                       currentFtpMp3Queue.contains(current)){
                        queue=new ArrayList<>(currentFtpMp3Queue);
                    }else{
                        queue=null;
                    }
                }
                if(queue==null){
                    queue=PolarisFtp.listMp3AudioPaths(this,directory);
                    synchronized(currentFtpMp3Queue){
                        currentFtpMp3Queue.clear();
                        currentFtpMp3Queue.addAll(queue);
                        currentFtpMp3Directory=directory;
                    }
                }

                if(queue.isEmpty()) return;
                int at=queue.indexOf(current);
                if(at<0) return;

                int next=(at+delta+queue.size())%queue.size();
                final String target=queue.get(next);
                synchronized(currentFtpMp3Queue){ currentFtpMp3Index=next; }

                programHandler.post(() -> {
                    if(current.equals(currentFtpMp3Path))
                        playFtpAudio("ftpmp3:"+Uri.encode(target));
                });
            }catch(Exception e){
                trace("FTP MP3 skip error: "+e);
            }
        });
    }

    private void skipRadio(int delta){
        if(currentRadioId==null || !STREAMS.containsKey(currentRadioId)) return;
        int at=-1;
        for(int i=0;i<RADIO_ORDER.length;i++) if(RADIO_ORDER[i].equals(currentRadioId)){ at=i; break; }
        if(at<0) return;
        int next=(at+delta+RADIO_ORDER.length)%RADIO_ORDER.length;
        String id=RADIO_ORDER[next];
        trace("RADIO skip "+currentRadioId+" -> "+id);
        currentFtpMp3Path=null;
        currentRadioId=id;
        currentMp3Id=-1L;
        rememberRadioForResume(id);
        retryCount=0;
        radioSoftRetryCount=0;
        radioHasPlayed=false;
        userStopped=false;
        retryHandler.removeCallbacksAndMessages(null);
        radioBufferingHandler.removeCallbacksAndMessages(null);
        resolverRefreshHandler.removeCallbacksAndMessages(null);
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

            if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
               change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){
                retryHandler.removeCallbacksAndMessages(null);
                resumeAfterTransientFocusLoss=
                    !userStopped && player!=null &&
                    (player.isPlaying() || player.getPlayWhenReady());
                if(player!=null) player.pause();
                publishState();
                return;
            }

            if(change==AudioManager.AUDIOFOCUS_LOSS){
                resumeAfterTransientFocusLoss=false;
                retryHandler.removeCallbacksAndMessages(null);
                if(player!=null) player.pause();
                publishState();
                return;
            }

            if(change==AudioManager.AUDIOFOCUS_GAIN &&
               resumeAfterTransientFocusLoss && !userStopped && player!=null){
                resumeAfterTransientFocusLoss=false;
                player.play();
                publishState();
            }
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

    private boolean isResolverRadio(String id){
        return "kr1".equals(id)||"kr2".equals(id)||"kr4".equals(id)||"kr6".equals(id);
    }

    private boolean looksLikeHls(String url){
        if(url==null) return false;
        String lower=url.toLowerCase(Locale.US);
        return lower.contains(".m3u8") || lower.contains("format=m3u8") || lower.contains("protocol=hls");
    }

    private String radioUrlLabel(String url){
        if(url==null||url.isEmpty()) return "-";
        try{
            Uri u=Uri.parse(url);
            String host=u.getHost()==null?"":u.getHost();
            String path=u.getPath()==null?"":u.getPath();
            return host+path;
        }catch(Exception e){
            int q=url.indexOf('?');
            return q>0?url.substring(0,q):url;
        }
    }

    private void traceRadioHealth(String event){
        if(currentRadioId==null||player==null) return;
        long live=C.TIME_UNSET;
        long buffered=0L;
        try{ live=player.getCurrentLiveOffset(); }catch(Exception ignored){}
        try{ buffered=player.getTotalBufferedDuration(); }catch(Exception ignored){}
        trace("RADIO "+event+" id="+currentRadioId+
            " liveOffset="+(live==C.TIME_UNSET?"unset":live+"ms")+
            " buffered="+buffered+"ms src="+radioUrlLabel(currentResolvedRadioUrl));
    }

    private int httpCodeFromError(Throwable error){
        Throwable cause=error;
        while(cause!=null){
            if(cause instanceof HttpDataSource.InvalidResponseCodeException)
                return ((HttpDataSource.InvalidResponseCodeException)cause).responseCode;
            cause=cause.getCause();
        }
        return -1;
    }

    private boolean isPlaylistLifecycleError(Throwable error){
        Throwable cause=error;
        while(cause!=null){
            String name=cause.getClass().getSimpleName();
            if(name.contains("PlaylistStuck")||name.contains("PlaylistReset")) return true;
            cause=cause.getCause();
        }
        return false;
    }

    private String describePlaybackError(PlaybackException error){
        String msg="Media3 "+error.getErrorCodeName()+": "+String.valueOf(error.getMessage());
        int code=httpCodeFromError(error);
        if(code>0) msg+=" HTTP "+code;
        if(isPlaylistLifecycleError(error)) msg+=" playlist-refresh";
        return msg;
    }

    private void publishRadioRecovering(String message){
        long actions=PlaybackStateCompat.ACTION_PLAY|
            PlaybackStateCompat.ACTION_PAUSE|
            PlaybackStateCompat.ACTION_STOP|
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID|
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH|
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT|
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS|
            PlaybackStateCompat.ACTION_FAST_FORWARD|
            PlaybackStateCompat.ACTION_REWIND;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(PlaybackStateCompat.STATE_BUFFERING,currentPlayerPosition(),0f,
                android.os.SystemClock.elapsedRealtime())
            .setErrorMessage(message)
            .build());
    }

    private void startRadio(String id){
        String url=STREAMS.get(id);
        if(url==null || userStopped) return;
        radioBufferingHandler.removeCallbacksAndMessages(null);
        radioHasPlayed=false;
        // A manual/new tune must not inherit an old standby token for the same station.
        standbyResolvedUrls.remove(id);
        standbyResolvedAt.remove(id);
        trace("STREAM selected: "+id);
        if("gallery".equals(id)){
            currentResolvedRadioUrl=null;
            trace("Gallery Live365 resolver start: "+url);
            resolveGalleryAndPlay(id,url);
        } else if(isResolverRadio(id)){
            trace("resolver start: "+id);
            resolveAndPlay(id,url);
        } else {
            trace("direct play start: "+id);
            currentResolvedRadioUrl=url;
            playUrl(url,TITLES.get(id),"kiis".equals(id) ? "Los Angeles" : "Live");
        }
    }

    private boolean hasFreshStandby(String id){
        if(id==null) return false;
        String standby=standbyResolvedUrls.get(id);
        Long at=standbyResolvedAt.get(id);
        if(standby==null||at==null) return false;
        long age=System.currentTimeMillis()-at;
        return age>=0L && age<4L*60L*1000L &&
            !standby.equals(currentResolvedRadioUrl);
    }

    private void scheduleRadioBufferHandoff(){
        if(userStopped||currentRadioId==null||player==null) return;
        if(!radioHasPlayed||!isResolverRadio(currentRadioId)) return;

        final String id=currentRadioId;
        radioBufferingHandler.removeCallbacksAndMessages(null);
        radioBufferingHandler.postDelayed(() -> {
            if(userStopped||!id.equals(currentRadioId)||player==null) return;
            if(player.getPlaybackState()!=Player.STATE_BUFFERING) return;

            traceRadioHealth("BUFFERING-HANDOFF");
            publishRadioRecovering("라이브 스트림 연결 전환 중");
            retryHandler.removeCallbacksAndMessages(null);
            radioSoftRetryCount=0;
            // Prefer the pre-resolved source. If it is unavailable, resolve a fresh
            // source immediately instead of waiting for a long rebuffer window.
            recoverResolverRadio(id);
        },1200L);
    }

    private void recoverRadioAfterError(PlaybackException error){
        if(userStopped||currentRadioId==null) return;
        int http=httpCodeFromError(error);
        boolean staleSource=(http==401||http==403||isPlaylistLifecycleError(error));

        if(isResolverRadio(currentRadioId)){
            if(hasFreshStandby(currentRadioId)){
                trace("RADIO error -> immediate standby handoff: http="+http);
                radioSoftRetryCount=0;
                recoverResolverRadio(currentRadioId);
                return;
            }
            if(staleSource){
                trace("RADIO stale source -> immediate fresh resolver: http="+http+
                    " playlist="+isPlaylistLifecycleError(error));
                radioSoftRetryCount=0;
                recoverResolverRadio(currentRadioId);
                return;
            }
        }
        scheduleSoftRadioRetry();
    }

    private void scheduleSoftRadioRetry(){
        if(userStopped || currentRadioId==null || player==null) return;

        final String id=currentRadioId;
        radioSoftRetryCount++;
        int maxAttempts=isResolverRadio(id)?1:2;
        if(radioSoftRetryCount>maxAttempts){
            trace("RADIO soft retry exhausted -> fresh/hard source");
            radioSoftRetryCount=0;
            scheduleHardRadioRetry(isResolverRadio(id)?100L:250L);
            return;
        }

        final int attempt=radioSoftRetryCount;
        final long failedPosition=currentPlayerPosition();
        final boolean seekable=player.isCurrentMediaItemSeekable();
        long delay=isResolverRadio(id) ? 300L : (attempt==1 ? 500L : 1500L);

        trace("RADIO soft retry #"+attempt+" same source in "+delay+
            "ms pos="+failedPosition+" seekable="+seekable);
        retryHandler.removeCallbacksAndMessages(null);
        retryHandler.postDelayed(() -> {
            if(userStopped || !id.equals(currentRadioId)) return;
            try{
                if(seekable && failedPosition>0L) player.seekTo(failedPosition);
                player.prepare();
                if(requestPlaybackFocus()) player.play();
                publishState();
                traceRadioHealth("SOFT-RETRY");
            }catch(Throwable e){
                trace("RADIO soft retry exception: "+e);
                scheduleSoftRadioRetry();
            }
        },delay);
    }

    private void scheduleHardRadioRetry(long delay){
        if(userStopped || currentRadioId==null) return;
        if("gallery".equals(currentRadioId) && retryCount>=3){
            trace("Gallery retry limit reached");
            return;
        }
        retryCount++;
        final String id=currentRadioId;
        trace("RADIO HARD retry scheduled: "+id+" in "+delay+"ms");
        retryHandler.removeCallbacksAndMessages(null);
        retryHandler.postDelayed(() -> {
            if(userStopped || !id.equals(currentRadioId)) return;
            trace("RADIO HARD retry start: "+id+" #"+retryCount);
            if(isResolverRadio(id)) recoverResolverRadio(id);
            else startRadio(id);
        },Math.max(0L,delay));
    }

    private void scheduleRetry(){
        long delay=retryCount==0 ? 750L : retryCount==1 ? 2000L : 5000L;
        scheduleHardRadioRetry(delay);
    }

    private void recoverResolverRadio(String id){
        radioBufferingHandler.removeCallbacksAndMessages(null);
        String standby=standbyResolvedUrls.remove(id);
        Long at=standbyResolvedAt.remove(id);
        long age=at==null?Long.MAX_VALUE:(System.currentTimeMillis()-at);
        if(standby!=null && age<4L*60L*1000L &&
           !standby.equals(currentResolvedRadioUrl)){
            trace("RADIO standby source -> "+id+" age="+age+"ms");
            currentResolvedRadioUrl=standby;
            playUrl(standby,TITLES.get(id),"Live");
            return;
        }
        trace("RADIO standby unavailable -> resolver now: "+id);
        resolveAndPlay(id,STREAMS.get(id));
    }

    private void scheduleResolverPrefetch(String id){
        if(!isResolverRadio(id)) return;
        resolverRefreshHandler.removeCallbacksAndMessages(null);
        resolverRefreshHandler.postDelayed(() -> {
            if(userStopped||!id.equals(currentRadioId)) return;
            final String lookup=STREAMS.get(id);
            resolver.execute(() -> {
                try{
                    String fresh=resolveStreamUrl(id,lookup);
                    if(fresh!=null && id.equals(currentRadioId)){
                        standbyResolvedUrls.put(id,fresh);
                        standbyResolvedAt.put(id,System.currentTimeMillis());
                        trace("RADIO standby refreshed: "+id+" -> "+radioUrlLabel(fresh));
                    }
                }catch(Exception e){
                    trace("RADIO standby refresh error: "+id+" / "+e);
                }finally{
                    runOnPlayerThread(() -> {
                        if(!userStopped&&id.equals(currentRadioId))
                            scheduleResolverPrefetch(id);
                    });
                }
            });
        },RADIO_RESOLVER_PREFETCH_MS);
    }

    private void playFtpAudio(String id){
        String encoded=id.substring("ftpmp3:".length());
        String relative=Uri.decode(encoded);
        if(relative==null||relative.trim().isEmpty()){
            publishError("FTP 음악 경로가 비었습니다");
            return;
        }

        final String rel=relative.trim();
        final String fallbackTitle=PolarisFtp.displayTitle(rel);
        radioBufferingHandler.removeCallbacksAndMessages(null);
        resolverRefreshHandler.removeCallbacksAndMessages(null);
        radioHasPlayed=false;
        currentRadioId=null;
        currentMp3Id=-1L;
        currentMp3Queue.clear();
        currentMp3Index=-1;
        currentFtpMp3Path=rel;
        currentFtpMp3Directory=PolarisFtp.parentRelative(rel);
        ftpMp3Preparing=true;
        prepareFtpQueueAsync(rel);
        retryCount=0;
        radioSoftRetryCount=0;
        userStopped=false;
        mp3EndHandled=false;
        resumeAfterTransientFocusLoss=false;
        setResumeAllowed(false);
        retryHandler.removeCallbacksAndMessages(null);
        restoreHandler.removeCallbacksAndMessages(null);
        enterPlaybackForeground(fallbackTitle);

        MediaMetadataCompat initial=new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID,"ftpmp3:"+Uri.encode(rel))
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,fallbackTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,"Synology FTP")
            .build();
        session.setMetadata(initial);

        runOnPlayerThread(() -> {
            try{
                player.stop();
                publishState();
            }catch(Exception ignored){}
        });

        trace("FTP MP3 prepare: "+rel);
        ftpExecutor.execute(() -> {
            try{
                File cached=PolarisFtp.downloadMp3ToCache(this,rel);
                if(!rel.equals(currentFtpMp3Path)) return;
                ftpMp3Preparing=false;

                String title=fallbackTitle;
                String artist="Synology FTP";
                String album="";
                long duration=0L;
                Bitmap art=null;

                MediaMetadataRetriever mmr=new MediaMetadataRetriever();
                try{
                    mmr.setDataSource(cached.getAbsolutePath());
                    String metaTitle=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    String metaArtist=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    String metaAlbum=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                    String metaDuration=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if(metaTitle!=null&&!metaTitle.trim().isEmpty()) title=metaTitle.trim();
                    if(metaArtist!=null&&!metaArtist.trim().isEmpty()) artist=metaArtist.trim();
                    if(metaAlbum!=null) album=metaAlbum.trim();
                    try{ duration=Math.max(0L,Long.parseLong(metaDuration)); }catch(Exception ignored){}
                    byte[] picture=mmr.getEmbeddedPicture();
                    if(picture!=null&&picture.length>0){
                        Bitmap raw=BitmapFactory.decodeByteArray(picture,0,picture.length);
                        if(raw!=null){
                            if(raw.getWidth()>512||raw.getHeight()>512){
                                float scale=Math.min(512f/raw.getWidth(),512f/raw.getHeight());
                                art=Bitmap.createScaledBitmap(raw,
                                    Math.max(1,(int)(raw.getWidth()*scale)),
                                    Math.max(1,(int)(raw.getHeight()*scale)),true);
                                if(art!=raw) raw.recycle();
                            }else art=raw;
                        }
                    }
                }finally{
                    try{ mmr.release(); }catch(Exception ignored){}
                }

                final String t=title;
                final String a=artist;
                final String al=album;
                final long dur=duration;
                final Bitmap cover=art;
                final Uri uri=Uri.fromFile(cached);

                runOnPlayerThread(() -> {
                    if(!rel.equals(currentFtpMp3Path)) return;
                    try{
                        MediaMetadataCompat.Builder mb=new MediaMetadataCompat.Builder()
                            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID,"ftpmp3:"+Uri.encode(rel))
                            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,t)
                            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,a)
                            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,al)
                            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,dur);
                        if(cover!=null) mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART,cover);
                        session.setMetadata(mb.build());

                        player.setMediaItem(MediaItem.fromUri(uri));
                        player.prepare();
                        if(!userStopped && requestPlaybackFocus()) player.play();
                        publishState();
                        trace("FTP MP3 cached play: "+rel+" -> "+cached.getName());
                    }catch(Throwable e){
                        publishError("FTP 음악 재생 오류: "+e.getMessage());
                    }
                });
            }catch(Exception e){
                if(rel.equals(currentFtpMp3Path)){
                    ftpMp3Preparing=false;
                    trace("FTP MP3 download error: "+rel+" / "+e);
                    publishError("FTP 음악 다운로드 오류: "+
                        (e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));
                }
            }
        });
    }

    private void playLocalAudio(String id){
        try{
            long mediaId=Long.parseLong(id.substring(4));
            ensureMp3Queue(mediaId);
            playLocalAudioId(mediaId);
        }catch(Exception e){
            publishError("local audio: "+e.getMessage());
        }
    }

    private void ensureMp3Queue(long mediaId){
        if(currentMp3Queue.isEmpty() || !currentMp3Queue.contains(mediaId)){
            currentMp3Queue.clear();
            currentMp3Queue.addAll(loadAllMp3Ids());
        }
        currentMp3Index=currentMp3Queue.indexOf(mediaId);
        if(currentMp3Index<0 && !currentMp3Queue.isEmpty()){
            currentMp3Queue.add(mediaId);
            currentMp3Index=currentMp3Queue.size()-1;
        }
    }

    private void playLocalAudioId(long mediaId){
        playLocalAudioId(mediaId,0L,false);
    }

    private void playLocalAudioId(long mediaId,long startPositionMs,boolean restoring){
        try{
            radioBufferingHandler.removeCallbacksAndMessages(null);
            resolverRefreshHandler.removeCallbacksAndMessages(null);
            radioHasPlayed=false;
            currentFtpMp3Path=null;
            ftpMp3Preparing=false;
            currentRadioId=null;
            currentMp3Id=mediaId;
            retryCount=0;
            radioSoftRetryCount=0;
            userStopped=false;
            mp3EndHandled=false;
            retryHandler.removeCallbacksAndMessages(null);

            if(currentMp3Queue.isEmpty() || !currentMp3Queue.contains(mediaId))
                ensureMp3Queue(mediaId);
            else
                currentMp3Index=currentMp3Queue.indexOf(mediaId);

            Uri uri=ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,mediaId);
            String title="Local audio";
            String artist="";
            String album="";
            long duration=0L;
            String[] projection={
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION
            };
            try(Cursor c=getContentResolver().query(uri,projection,null,null,null)){
                if(c!=null && c.moveToFirst()){
                    title=safe(c.getString(0));
                    artist=safe(c.getString(1));
                    album=safe(c.getString(2));
                    duration=Math.max(0L,c.getLong(3));
                }
            }

            long resumePosition=Math.max(0L,startPositionMs);
            if(duration>0L && resumePosition>=duration-1000L) resumePosition=0L;
            rememberMp3ForResume(mediaId,resumePosition);
            rememberRecent(mediaId);
            enterPlaybackForeground(title);
            final String t=title, a=artist, al=album;
            final long dur=duration;
            final long startAt=resumePosition;
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
                    if(startAt>0L) player.seekTo(startAt);
                    player.prepare();
                    if(requestPlaybackFocus()) player.play();
                    publishState();
                    trace("MP3 play id="+mediaId+" index="+currentMp3Index+"/"+currentMp3Queue.size()+
                        (restoring?" restore@"+startAt:""));
                }catch(Throwable e){
                    publishError("local audio: "+e);
                }
            });
        }catch(Exception e){
            publishError("local audio: "+e.getMessage());
        }
    }

    private int randomMp3Index(){
        if(currentMp3Queue.isEmpty()) return -1;
        if(currentMp3Queue.size()==1) return 0;
        int next=currentMp3Index;
        for(int tries=0;tries<8 && next==currentMp3Index;tries++)
            next=mp3Random.nextInt(currentMp3Queue.size());
        if(next==currentMp3Index)
            next=(currentMp3Index+1)%currentMp3Queue.size();
        return next;
    }

    private void handleMp3Ended(){
        if(currentMp3Queue.isEmpty()){
            currentMp3Queue.addAll(loadAllMp3Ids());
            currentMp3Index=currentMp3Queue.indexOf(currentMp3Id);
        }
        if(currentMp3Queue.isEmpty()) return;

        if(mp3RepeatMode==PlaybackStateCompat.REPEAT_MODE_ONE){
            trace("MP3 end -> repeat one");
            playLocalAudioId(currentMp3Id);
            return;
        }

        if(mp3Shuffle){
            int next=randomMp3Index();
            if(next>=0){
                currentMp3Index=next;
                trace("MP3 end -> shuffle index="+next);
                playLocalAudioId(currentMp3Queue.get(next));
            }
            return;
        }

        int next=currentMp3Index+1;
        if(next<currentMp3Queue.size()){
            currentMp3Index=next;
            trace("MP3 end -> next index="+next);
            playLocalAudioId(currentMp3Queue.get(next));
            return;
        }

        if(mp3RepeatMode==PlaybackStateCompat.REPEAT_MODE_ALL){
            currentMp3Index=0;
            trace("MP3 end -> repeat all");
            playLocalAudioId(currentMp3Queue.get(0));
            return;
        }

        trace("MP3 end -> queue finished");
        setResumeAllowed(false);
        abandonPlaybackFocus();
        leavePlaybackForeground();
        publishState();
    }

    private void skipMp3(int delta){
        if(currentMp3Queue.isEmpty()){
            currentMp3Queue.addAll(loadAllMp3Ids());
            currentMp3Index=currentMp3Queue.indexOf(currentMp3Id);
        }
        if(currentMp3Queue.isEmpty()) return;

        int next;
        if(mp3Shuffle && delta>0){
            next=randomMp3Index();
        }else{
            if(currentMp3Index<0) currentMp3Index=0;
            next=(currentMp3Index+delta+currentMp3Queue.size())%currentMp3Queue.size();
        }
        if(next<0) return;
        currentMp3Index=next;
        playLocalAudioId(currentMp3Queue.get(next));
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

    private List<android.support.v4.media.MediaBrowserCompat.MediaItem> loadFavoriteMediaItems(){
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> out=new ArrayList<>();
        for(Long mediaId:favoriteIdsInLibraryOrder()){
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
                    String sub=artist+(album.isEmpty()?"":" · "+album);
                    out.add(listTextItem("favlocal:"+mediaId,title,sub));
                }
            }catch(SecurityException ignored){}
        }
        for(String rel:ftpFavoritePaths()){
            String parent=PolarisFtp.parentRelative(rel);
            String sub=parent.isEmpty()?"FTP":"FTP · "+parent;
            out.add(listTextItem(
                "favftp:"+Uri.encode(rel),
                PolarisFtp.displayTitle(rel),
                sub));
        }
        return out;
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

    private String folderTitleFromPath(String value){
        if(value==null) return "";
        String v=value.trim();
        while(v.endsWith("/")) v=v.substring(0,v.length()-1);
        int slash=v.lastIndexOf('/');
        return slash>=0 ? v.substring(slash+1) : v;
    }

    private List<android.support.v4.media.MediaBrowserCompat.MediaItem> loadMp3Folders(){
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> out=new ArrayList<>();
        LinkedHashSet<String> seen=new LinkedHashSet<>();

        if(Build.VERSION.SDK_INT>=29){
            String[] projection={
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.DATA
            };
            String selection=MediaStore.Audio.Media.IS_MUSIC+" != 0";
            try(Cursor c=getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,selection,null,
                MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC")){
                if(c!=null){
                    int relCol=c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH);
                    int dataCol=c.getColumnIndex(MediaStore.Audio.Media.DATA);
                    while(c.moveToNext()){
                        String rel=relCol>=0 ? safe(c.getString(relCol)).trim() : "";
                        if(!rel.isEmpty()){
                            String key="rel|"+rel;
                            if(seen.add(key))
                                out.add(folder("mp3_folder:"+Uri.encode(key),folderTitleFromPath(rel)));
                            continue;
                        }

                        String data=dataCol>=0 ? safe(c.getString(dataCol)).trim() : "";
                        if(!data.isEmpty()){
                            File parent=new File(data).getParentFile();
                            if(parent!=null){
                                String dir=parent.getAbsolutePath();
                                String key="data|"+dir;
                                if(seen.add(key))
                                    out.add(folder("mp3_folder:"+Uri.encode(key),folderTitleFromPath(dir)));
                            }
                        }
                    }
                }
            }catch(Exception e){
                trace("MP3 folder query error: "+e);
            }
        }else{
            String[] projection={MediaStore.Audio.Media.DATA};
            String selection=MediaStore.Audio.Media.IS_MUSIC+" != 0";
            try(Cursor c=getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,selection,null,
                MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC")){
                if(c!=null){
                    int dataCol=c.getColumnIndex(MediaStore.Audio.Media.DATA);
                    while(c.moveToNext()){
                        String data=dataCol>=0 ? safe(c.getString(dataCol)).trim() : "";
                        if(data.isEmpty()) continue;
                        File parent=new File(data).getParentFile();
                        if(parent==null) continue;
                        String dir=parent.getAbsolutePath();
                        String key="data|"+dir;
                        if(seen.add(key))
                            out.add(folder("mp3_folder:"+Uri.encode(key),folderTitleFromPath(dir)));
                    }
                }
            }catch(Exception e){
                trace("MP3 legacy folder query error: "+e);
            }
        }

        trace("MP3 folders="+out.size());
        return out;
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
                .setLoadErrorHandlingPolicy(radioLoadErrorPolicy)
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


    private String resolveStreamUrl(String id,String lookupUrl) throws Exception {
        if(lookupUrl==null||lookupUrl.isEmpty()) throw new IOException("No resolver URL");
        HttpURLConnection con=(HttpURLConnection)new URL(lookupUrl).openConnection();
        try{
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent","Mozilla/5.0");
            StringBuilder b=new StringBuilder();
            try(BufferedReader r=new BufferedReader(new InputStreamReader(con.getInputStream()))){
                String line;
                while((line=r.readLine())!=null) b.append(line);
            }
            String raw=b.toString();
            int response=con.getResponseCode();
            trace("resolver HTTP "+response+": "+id+" bytes="+raw.length());
            String resolved=extractStreamUrl(raw);
            if(resolved==null){
                String preview=raw.replace("\n"," ").replace("\r"," ");
                if(preview.length()>240) preview=preview.substring(0,240);
                trace("resolver no URL: "+id+" body="+preview);
                throw new IOException("No stream URL in resolver response");
            }
            trace("resolver URL: "+id+" -> "+radioUrlLabel(resolved));
            return resolved;
        } finally {
            con.disconnect();
        }
    }

    private void resolveAndPlay(final String id,final String lookupUrl){
        resolver.execute(() -> {
            try{
                final String resolved=resolveStreamUrl(id,lookupUrl);
                runOnPlayerThread(() -> {
                    if(!userStopped && id.equals(currentRadioId)){
                        currentResolvedRadioUrl=resolved;
                        playUrl(resolved,TITLES.get(id),"Live");
                    }
                });
            }catch(Exception e){
                runOnPlayerThread(() -> {
                    trace("resolver error: "+id+" / "+e);
                    if(id.equals(currentRadioId)){
                        publishRadioRecovering("resolver: "+e.getMessage());
                        scheduleRetry();
                    }
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
        trace("PLAYER enter: "+title+" src="+radioUrlLabel(url));
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
                mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART,
                    StationArt.bitmap(this,currentRadioId,256));
            session.setMetadata(mb.build());

            MediaItem.Builder itemBuilder=new MediaItem.Builder().setUri(url);
            if(looksLikeHls(url)){
                itemBuilder
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(RADIO_HLS_TARGET_OFFSET_MS)
                        .build());
                trace("RADIO HLS target live offset="+RADIO_HLS_TARGET_OFFSET_MS+"ms");
            }
            currentResolvedRadioUrl=url;
            player.setMediaItem(itemBuilder.build());
            player.prepare();
            if(requestPlaybackFocus()) player.play();
            else trace("PLAYER audio focus denied");
            publishState();
            if(currentRadioId!=null) scheduleResolverPrefetch(currentRadioId);
        }catch(Throwable e){
            trace("PLAYER ERROR: "+e);
            publishError("player: "+e);
        }
    }

    private void publishState(){
        int state=PlaybackStateCompat.STATE_STOPPED;
        if(currentFtpMp3Path!=null && ftpMp3Preparing && !userStopped)
            state=PlaybackStateCompat.STATE_BUFFERING;
        else if(player!=null && player.isPlaying()) state=PlaybackStateCompat.STATE_PLAYING;
        else if(currentFtpMp3Path!=null && userStopped)
            state=PlaybackStateCompat.STATE_PAUSED;
        else if(player!=null && player.getPlaybackState()==Player.STATE_BUFFERING)
            state=PlaybackStateCompat.STATE_BUFFERING;
        else if(player!=null && player.getPlaybackState()==Player.STATE_READY)
            state=PlaybackStateCompat.STATE_PAUSED;

        long position=currentPlayerPosition();
        float speed=state==PlaybackStateCompat.STATE_PLAYING?1f:0f;

        long actions=PlaybackStateCompat.ACTION_PLAY|
            PlaybackStateCompat.ACTION_PAUSE|
            PlaybackStateCompat.ACTION_STOP|
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID|
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH|
            PlaybackStateCompat.ACTION_SEEK_TO;

        if(currentRadioId!=null){
            actions|=PlaybackStateCompat.ACTION_SKIP_TO_NEXT|
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS|
                PlaybackStateCompat.ACTION_FAST_FORWARD|
                PlaybackStateCompat.ACTION_REWIND;
        }else if(currentMp3Id>=0 || currentFtpMp3Path!=null){
            // Local and FTP MP3 both expose the normal transport row.
            actions|=PlaybackStateCompat.ACTION_SKIP_TO_NEXT|
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        }

        PlaybackStateCompat.Builder b=new PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state,position,speed,android.os.SystemClock.elapsedRealtime());

        if(currentMp3Id>=0 || currentFtpMp3Path!=null){
            boolean liked=isCurrentFavorite();
            b.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                ACTION_MP3_LIKE,
                liked ? "좋아요 해제" : "좋아요",
                liked ? R.drawable.ic_mp3_like : R.drawable.ic_mp3_like_off).build());

            b.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                ACTION_MP3_SHUFFLE,
                mp3Shuffle ? "랜덤 켜짐" : "랜덤 꺼짐",
                mp3Shuffle ? R.drawable.ic_mp3_shuffle : R.drawable.ic_mp3_shuffle_off).build());

            int repeatIcon=R.drawable.ic_mp3_repeat_off;
            String repeatName="반복 꺼짐";
            if(mp3RepeatMode==PlaybackStateCompat.REPEAT_MODE_ALL){
                repeatIcon=R.drawable.ic_mp3_repeat_all;
                repeatName="전체 반복";
            }else if(mp3RepeatMode==PlaybackStateCompat.REPEAT_MODE_ONE){
                repeatIcon=R.drawable.ic_mp3_repeat_one;
                repeatName="한 곡 반복";
            }
            b.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                ACTION_MP3_REPEAT,repeatName,repeatIcon).build());

            b.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                ACTION_MP3_DISLIKE,"싫어요 · 다음 곡",R.drawable.ic_mp3_dislike).build());
        }

        session.setPlaybackState(b.build());
    }

    @Override public BrowserRoot onGetRoot(String pkg,int uid,Bundle hints){
        // Browsing is side-effect free. Rebinding the AA MediaBrowser must never start,
        // restart, or replace the current source.
        Bundle style=new Bundle();
        style.putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT",1);
        style.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT",2);
        style.putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED,true);
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

    private android.support.v4.media.MediaBrowserCompat.MediaItem listTextFolder(
        String id,String title){
        Bundle e=new Bundle();
        e.putInt("android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT",1);
        e.putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT",1);
        e.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT",1);
        return new android.support.v4.media.MediaBrowserCompat.MediaItem(
            new MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setExtras(e)
                .build(),
            android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private android.support.v4.media.MediaBrowserCompat.MediaItem listTextItem(
        String id,String title,String sub){
        Bundle e=new Bundle();
        e.putInt("android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT",1);
        e.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT",1);
        return new android.support.v4.media.MediaBrowserCompat.MediaItem(
            new MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(sub)
                .setExtras(e)
                .build(),
            android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
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

    private boolean isFtpMp3BrowserParent(String parent){
        return "mp3_ftp".equals(parent) ||
            (parent!=null && parent.startsWith("ftpdir:")) ||
            (parent!=null && parent.startsWith("ftppage:"));
    }

    private void loadFtpMp3ChildrenAsync(
        String parent,
        Result<List<android.support.v4.media.MediaBrowserCompat.MediaItem>> result){
        result.detach();
        ftpExecutor.execute(() -> {
            List<android.support.v4.media.MediaBrowserCompat.MediaItem> out=new ArrayList<>();
            try{
                String rel="";
                int offset=0;

                if(parent.startsWith("ftpdir:")){
                    rel=Uri.decode(parent.substring("ftpdir:".length()));
                }else if(parent.startsWith("ftppage:")){
                    String rest=parent.substring("ftppage:".length());
                    int colon=rest.indexOf(':');
                    if(colon<=0) throw new IllegalArgumentException("FTP 페이지 ID 오류");
                    offset=Integer.parseInt(rest.substring(0,colon));
                    rel=Uri.decode(rest.substring(colon+1));
                }

                PolarisFtp.DirectoryPage page=PolarisFtp.listMp3(this,rel,offset,200);
                for(PolarisFtp.Entry entry:page.entries){
                    if(entry.directory){
                        out.add(listTextFolder(
                            "ftpdir:"+Uri.encode(entry.relativePath),
                            entry.name));
                    }else{
                        out.add(listTextItem(
                            "ftpmp3:"+Uri.encode(entry.relativePath),
                            PolarisFtp.displayTitle(entry.name),
                            "FTP · "+formatFtpBytes(entry.size)));
                    }
                }

                if(page.hasMore()){
                    int next=page.offset+page.entries.size();
                    out.add(listTextFolder(
                        "ftppage:"+next+":"+Uri.encode(rel),
                        "다음 200개 →"));
                } 
            }catch(Exception e){
                String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                trace("FTP browse error: "+parent+" / "+e);
                out.clear();
            }
            result.sendResult(out);
        });
    }

    private String formatFtpBytes(long bytes){
        if(bytes<1024L) return bytes+" B";
        double value=bytes/1024.0;
        if(value<1024.0) return String.format(Locale.US,"%.1f KB",value);
        value/=1024.0;
        if(value<1024.0) return String.format(Locale.US,"%.1f MB",value);
        return String.format(Locale.US,"%.2f GB",value/1024.0);
    }

    @Override public void onLoadChildren(String parent,Result<List<android.support.v4.media.MediaBrowserCompat.MediaItem>> result){
        if(isFtpMp3BrowserParent(parent)){
            loadFtpMp3ChildrenAsync(parent,result);
            return;
        }
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> x=new ArrayList<>();
        if(parent.equals("root")){
            // v0.41 order: Radio | MP3 | CAN. CAN may become the first index later.
            x.add(folder("radio","라디오"));
            x.add(folder("mp3","MP3"));
            x.add(folder("can","CAN"));
        } else if(parent.equals("home")){
            x.add(folder("radio","라디오"));
            x.add(folder("mp3","MP3"));
            x.add(folder("can","CAN"));
        } else if(parent.equals("can")){
            PolarisFtp.Config cfg=PolarisFtp.load(this);
            String sub=cfg.isConfigured() ? cfg.canRoot+" · "+canFtpStatus : "폰에서 FTP 설정 필요";
            x.add(item("can:ftp_upload","FTP 업로드",sub));
        } else if(parent.equals("radio")){
            for(int slot=0;slot<6;slot++){
                String id=presetRadioId(slot);
                x.add(item(id,radioProgramTitle(id),TITLES.get(id)+" · Preset "+(slot+1)));
            }
            x.add(item("kiis",radioProgramTitle("kiis"),TITLES.get("kiis")));
            x.add(item("gallery",radioProgramTitle("gallery"),TITLES.get("gallery")));
        } else if(parent.equals("mp3")){
            x.add(folder("mp3_favorites","좋아요"));
            x.add(folder("mp3_ftp","FTP"));
            x.add(folder("mp3_recent","최근 재생"));
            x.add(folder("mp3_albums","앨범"));
            x.add(folder("mp3_artists","아티스트"));
            x.add(folder("mp3_folders","폴더"));
            x.add(folder("mp3_all","전체 곡"));
        } else if(parent.equals("mp3_ftp")){
            PolarisFtp.Config cfg=PolarisFtp.load(this);
            String sub=cfg.isConfigured() ? cfg.mp3Root+" · "+mp3FtpStatus : "폰에서 FTP 설정 필요";
            x.add(item("mp3:ftp_upload","FTP 테스트 업로드",sub));
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
            x.addAll(loadFavoriteMediaItems());
        } else if(parent.startsWith("mp3_album:")){
            String value=Uri.decode(parent.substring("mp3_album:".length()));
            x.addAll(loadLocalAudio(MediaStore.Audio.Media.ALBUM+"=?",new String[]{value},MediaStore.Audio.Media.TRACK+" ASC"));
        } else if(parent.startsWith("mp3_artist:")){
            String value=Uri.decode(parent.substring("mp3_artist:".length()));
            x.addAll(loadLocalAudio(MediaStore.Audio.Media.ARTIST+"=?",new String[]{value},MediaStore.Audio.Media.ALBUM+" COLLATE NOCASE ASC, "+MediaStore.Audio.Media.TRACK+" ASC"));
        } else if(parent.startsWith("mp3_folder:")){
            String value=Uri.decode(parent.substring("mp3_folder:".length()));
            if(value.startsWith("rel|")){
                String rel=value.substring(4);
                x.addAll(loadLocalAudio(
                    MediaStore.Audio.Media.RELATIVE_PATH+"=?",
                    new String[]{rel},
                    MediaStore.Audio.Media.TRACK+" ASC"));
            }else if(value.startsWith("data|")){
                String dir=value.substring(5);
                String prefix=dir.endsWith("/") ? dir+"%" : dir+"/%";
                x.addAll(loadLocalAudio(
                    MediaStore.Audio.Media.DATA+" LIKE ?",
                    new String[]{prefix},
                    MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC"));
            }
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
        saveLastMp3Position();
        retryHandler.removeCallbacksAndMessages(null);
        resolverRefreshHandler.removeCallbacksAndMessages(null);
        radioBufferingHandler.removeCallbacksAndMessages(null);
        programHandler.removeCallbacksAndMessages(null);
        positionHandler.removeCallbacksAndMessages(null);
        restoreHandler.removeCallbacksAndMessages(null);
        if(carConnection!=null && carConnectionObserver!=null){
            try{ carConnection.getType().removeObserver(carConnectionObserver); }
            catch(Exception ignored){}
        }
        abandonPlaybackFocus();
        if(player!=null) player.release();
        resolver.shutdownNow();
        programExecutor.shutdownNow();
        ftpExecutor.shutdownNow();
        if(session!=null) session.release();
        super.onDestroy();
    }
}
