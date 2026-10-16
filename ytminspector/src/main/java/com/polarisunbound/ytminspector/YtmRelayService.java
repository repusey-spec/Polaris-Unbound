package com.polarisunbound.ytminspector;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;

import androidx.media.MediaBrowserServiceCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 1.c01 experimental Android Auto media browser bridge.
 * This is a separate app/package from the stable Polaris v0.58.
 * Audio always comes from YouTube Music: do NOT create players, claim
 * media audio focus or launch other apps from this service.
 *
 * Android Auto binds the exported MediaBrowserServiceCompat, authenticated
 * against a small tested package allowlist + calling UID. The phone activity
 * continues the bounded 75s test keepalive; an AA media-browser binding
 * independently holds the service alive while that client is connected.
 * No foreground service or background playback guarantee.
 */
public final class YtmRelayService extends MediaBrowserServiceCompat {
    public static final String PREFS="ytm_bridge_c01";
    public static final String ACTION_PULSE="com.polarisunbound.ytminspector.action.RELAY_PULSE";
    public static final String STARTS="starts";
    public static final String PULSES="pulses";
    public static final String EVENTS="events";
    public static final String STATUS="status";
    private static final String YTM="com.google.android.apps.youtube.music";
    private static final String ROOT="ytm:bridge:root";
    private static final String NOW="ytm:bridge:now";
    private static final String[] TRUSTED_MEDIA_CLIENTS={
        "com.google.android.projection.gearhead", // Android Auto projection
        "com.google.android.gms", // AA transport on some Google builds
        "com.android.car.media", // DHU / Android Automotive media test
        "com.google.android.apps.automotive.media"
    };
    private static final String CURRENT="ytm:bridge:current";
    private static final long POLL_MS=1250L;
    private static final long BACKGROUND_GRACE_MS=75000L;
    private static final long PAUSE_SETTLE_MS=400L;
    private static final long ERROR_SETTLE_MS=1200L;

    private final Handler handler=new Handler(Looper.getMainLooper());
    private MediaSessionManager manager;
    private ComponentName permissionComponent;
    private MediaSessionCompat session;
    private MediaController ytController;
    private boolean listenerAdded=false;
    private String lastSnapshot="";
    private String lastStatus="중계 시작 전";
    private MediaMetadataCompat currentMetadata;
    private String pendingTransientKey="";
    private long pendingTransientSince=0L;
    private String lastPublishedMetadataKey="";
    private String lastPublishedBrowseKey="";
    private String lastPublishedSnapshot="";
    private long lastPublishedPosition=-1L;
    private long lastPublishedAt=0L;
    private int lastPublishedState=PlaybackState.STATE_NONE;
    private float lastPublishedSpeed=0f;
    private long lastPulseAt=0L;
    private int lastPulseStartId=0;
    private int runStarts=0;
    private int runPulses=0;
    private String lastBrowserClient="아직 AA/브라우저 접속 없음";
    private String lastBrowserRequest="";
    private int acceptedRoots=0;
    private int rejectedRoots=0;

    private final MediaController.Callback ytCallback=new MediaController.Callback(){
        @Override public void onMetadataChanged(MediaMetadata m){handler.post(()->refresh());}
        @Override public void onPlaybackStateChanged(PlaybackState s){handler.post(()->refresh());}
        @Override public void onSessionDestroyed(){handler.post(()->refresh());}
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener listChanged=
        controllers -> handler.post(()->refresh());

    private final Runnable poll=new Runnable(){
        @Override public void run(){
            refresh();
            handler.postDelayed(this,POLL_MS);
        }
    };

    private final Runnable idleStop=new Runnable(){
        @Override public void run(){
            long elapsed=SystemClock.elapsedRealtime()-lastPulseAt;
            if(lastPulseAt!=0L && elapsed>=BACKGROUND_GRACE_MS){
                record("BRIDGE IDLE STOP · no Inspector heartbeat for "+
                    (elapsed/1000L)+"s");
                stopSelfResult(lastPulseStartId);
            }else if(lastPulseAt!=0L){
                handler.postDelayed(this,Math.max(1000L,BACKGROUND_GRACE_MS-elapsed));
            }
        }
    };

    private final MediaSessionCompat.Callback bridgeCallback=new MediaSessionCompat.Callback(){
        @Override public void onPlay(){
            forward("PLAY",PlaybackState.ACTION_PLAY,t->t.play());
        }
        @Override public void onPause(){
            forward("PAUSE",PlaybackState.ACTION_PAUSE,t->t.pause());
        }
        @Override public void onSkipToNext(){
            forward("NEXT",PlaybackState.ACTION_SKIP_TO_NEXT,t->t.skipToNext());
        }
        @Override public void onSkipToPrevious(){
            forward("PREVIOUS",PlaybackState.ACTION_SKIP_TO_PREVIOUS,t->t.skipToPrevious());
        }
        @Override public void onSeekTo(long pos){
            forward("SEEK "+pos+"ms",PlaybackState.ACTION_SEEK_TO,t->t.seekTo(Math.max(0,pos)));
        }
        @Override public void onPlayFromMediaId(String id,Bundle extras){
            if(CURRENT.equals(id)){
                forward("PLAY_FROM_MEDIA_ID",PlaybackState.ACTION_PLAY,t->t.play());
            }else record("BRIDGE rejected mediaId="+id);
        }
        @Override public void onSetRating(RatingCompat rating){
            if(rating==null || rating.getRatingStyle()!=RatingCompat.RATING_THUMB_UP_DOWN||
                !rating.isRated()){
                record("BRIDGE rating ignored: unsupported style");
                return;
            }
            MediaController target=commandTarget(PlaybackState.ACTION_SET_RATING);
            if(target==null)return;
            try{
                boolean up=rating.isThumbUp();
                target.getTransportControls().setRating(Rating.newThumbRating(up));
                record("BRIDGE FORWARDED SET_RATING "+(up?"UP":"DOWN")+
                    " · 계정 반영은 YTM에서 확인");
                refresh();
            }catch(Exception e){record("BRIDGE ERROR RATING "+e.getClass().getSimpleName());}
        }
        @Override public void onCustomAction(String id,Bundle extras){
            if(!("thumbs_up_action".equals(id)||"thumbs_down_action".equals(id)||
                "shuffle_action".equals(id)||"loop_mode_action".equals(id))){
                record("BRIDGE rejected unknown custom action="+id);
                return;
            }
            MediaController target=commandTarget(0L);
            if(target==null)return;
            PlaybackState state=target.getPlaybackState();
            boolean found=false;
            if(state!=null&&state.getCustomActions()!=null){
                for(PlaybackState.CustomAction action:state.getCustomActions()){
                    if(id.equals(action.getAction())){found=true;break;}
                }
            }
            if(!found){
                record("BRIDGE rejected unavailable custom action="+id);
                return;
            }
            try{
                target.getTransportControls().sendCustomAction(id,Bundle.EMPTY);
                record("BRIDGE FORWARDED CUSTOM "+id);
                refresh();
            }catch(Exception e){record("BRIDGE ERROR CUSTOM "+e.getClass().getSimpleName());}
        }
    };

    private interface Command{
        void run(MediaController.TransportControls t);
    }

    @Override public void onCreate(){
        super.onCreate();
        manager=(MediaSessionManager)getSystemService(Context.MEDIA_SESSION_SERVICE);
        permissionComponent=new ComponentName(this,SessionAccessListener.class);
        session=new MediaSessionCompat(this,"PolarisYTMRelay1c01AA");
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS|
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(bridgeCallback,handler);
        session.setPlaybackState(new PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_NONE,0,0f).setActions(0).build());
        session.setActive(true);
        setSessionToken(session.getSessionToken());
        runStarts=getSharedPreferences(PREFS,MODE_PRIVATE).getInt(STARTS,0)+1;
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt(STARTS,runStarts).apply();
        record("BRIDGE START #"+runStarts+
            " · 자체 오디오 재생 없음 / YTM 전용 / 외부 비공개");
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putString(STATUS,"중계 실행 #"+runStarts+" · YTM 연결 대기").apply();
        if(allowed()&&manager!=null){
            try{
                manager.addOnActiveSessionsChangedListener(listChanged,permissionComponent,handler);
                listenerAdded=true;
            }catch(Exception e){record("BRIDGE listener unavailable "+e.getClass().getSimpleName());}
        }
        handler.post(poll);
    }

    @Override public int onStartCommand(android.content.Intent intent,int flags,int startId){
        super.onStartCommand(intent,flags,startId);
        if(intent==null || !ACTION_PULSE.equals(intent.getAction())){
            record("BRIDGE unexpected start request · ignored");
            return START_NOT_STICKY;
        }
        lastPulseAt=SystemClock.elapsedRealtime();
        lastPulseStartId=startId;
        runPulses++;
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt(PULSES,runPulses).apply();
        handler.removeCallbacks(idleStop);
        handler.postDelayed(idleStop,BACKGROUND_GRACE_MS);
        if(runPulses==1)
            record("BRIDGE KEEPALIVE armed · last pulse expires in 75s");
        return START_NOT_STICKY;
    }

    @Override public void onDestroy(){
        record("BRIDGE DESTROY · lifecycle end");
        handler.removeCallbacks(poll);
        handler.removeCallbacks(idleStop);
        if(listenerAdded&&manager!=null){
            try{manager.removeOnActiveSessionsChangedListener(listChanged);}
            catch(Exception ignored){}
        }
        detach();
        if(session!=null){
            session.setActive(false);
            session.release();
            session=null;
        }
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(STATUS,"중계 종료").apply();
        super.onDestroy();
    }

    private boolean allowed(){
        try{
            NotificationManager nm=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
            return nm!=null&&nm.isNotificationListenerAccessGranted(permissionComponent);
        }catch(Exception e){return false;}
    }

    private void detach(){
        if(ytController!=null){
            try{ytController.unregisterCallback(ytCallback);}catch(Exception ignored){}
            ytController=null;
        }
    }

    /** Each command independently resolves a LIVE YouTube Music session. */
    private MediaController bestController(){
        if(!allowed()||manager==null)return null;
        try{
            List<MediaController> items=manager.getActiveSessions(permissionComponent);
            MediaController best=null;
            int bestScore=Integer.MIN_VALUE;
            if(items==null)return null;
            for(MediaController item:items){
                if(item==null || !YTM.equals(item.getPackageName()))continue;
                PlaybackState state=item.getPlaybackState();
                int code=state==null?PlaybackState.STATE_NONE:state.getState();
                int score=code==PlaybackState.STATE_PLAYING?6000:
                    code==PlaybackState.STATE_BUFFERING?5000:
                    code==PlaybackState.STATE_PAUSED?4000:1000;
                if(ytController!=null&&
                   ytController.getSessionToken().equals(item.getSessionToken()))score+=100;
                if(score>bestScore){best=item;bestScore=score;}
            }
            return best;
        }catch(Exception e){
            record("BRIDGE session read error "+e.getClass().getSimpleName());
            return null;
        }
    }

    private MediaController commandTarget(long requiredAction){
        MediaController target=bestController();
        if(target==null){record("BRIDGE command rejected: active YTM session unavailable");return null;}
        PlaybackState state=target.getPlaybackState();
        if(requiredAction!=0L &&(state==null||(state.getActions()&requiredAction)==0L)){
            record("BRIDGE command rejected: source action not advertised 0x"+
                Long.toHexString(requiredAction));
            return null;
        }
        return target;
    }

    private void forward(String name,long required,Command command){
        MediaController target=commandTarget(required);
        if(target==null)return;
        try{
            command.run(target.getTransportControls());
            record("BRIDGE FORWARDED "+name+" → "+YTM);
            refresh();
        }catch(Exception e){
            record("BRIDGE ERROR "+name+" "+e.getClass().getSimpleName());
        }
    }

    private void refresh(){
        if(session==null)return;
        MediaController found=bestController();
        if(found==null){
            detach();
            pendingTransientKey="";
            lastSnapshot="";
            lastPublishedMetadataKey="";
            lastPublishedBrowseKey="";
            lastPublishedSnapshot="";
            lastPublishedPosition=-1L;
            lastStatus=allowed()?"YT Music 활성 세션 없음":"알림 접근 권한 없음";
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(STATUS,lastStatus).apply();
            session.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,"YT Music 연결 대기").build());
            session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_NONE,0L,0f).setActions(0L).build());
            if(currentMetadata!=null)notifyChildrenChanged(NOW);
            currentMetadata=null;
            return;
        }
        boolean replaced=ytController==null;
        if(!replaced){
            try{replaced=!ytController.getSessionToken().equals(found.getSessionToken());}
            catch(Exception e){replaced=true;}
        }
        if(replaced){
            detach();
            ytController=found;
            try{ytController.registerCallback(ytCallback,handler);}catch(Exception ignored){}
            record("BRIDGE attached YT Music session");
        }

        MediaMetadata metadata=null;
        PlaybackState playback=null;
        try{metadata=found.getMetadata();playback=found.getPlaybackState();}
        catch(Exception e){record("BRIDGE metadata read "+e.getClass().getSimpleName());}
        String title=metadata==null?null:metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist=metadata==null?null:metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String album=metadata==null?null:metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration=metadata==null?0L:metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        int state=playback==null?PlaybackState.STATE_NONE:playback.getState();
        long actions=playback==null?0L:playback.getActions();

        // Do not propagate 100ms PAUSED transitions during track changes,
        // or YTM's brief STATE_ERROR generated by rejected PlayFromSearch.
        // Explicit, sustained pause/error will still be mirrored.
        long settle=state==PlaybackState.STATE_PAUSED?PAUSE_SETTLE_MS:
            state==PlaybackState.STATE_ERROR?ERROR_SETTLE_MS:0L;
        if(settle>0L){
            String transientKey=safe(title)+"|"+safe(artist)+"|"+state;
            long now=SystemClock.elapsedRealtime();
            if(!transientKey.equals(pendingTransientKey)){
                pendingTransientKey=transientKey;
                pendingTransientSince=now;
                handler.postDelayed(()->refresh(),settle);
                return;
            }
            if(now-pendingTransientSince<settle)return;
        }else{
            pendingTransientKey="";
            pendingTransientSince=0L;
        }
        String snapshot=String.valueOf(title)+"|"+artist+"|"+album+"|"+duration+"|"+
            state+"|"+actions+customSignature(playback);
        boolean mirrorChanged=!snapshot.equals(lastSnapshot);
        if(mirrorChanged){
            lastSnapshot=snapshot;
            record("BRIDGE MIRROR "+safe(title)+" / "+safe(artist)+
                " / "+stateName(state)+" / actions=0x"+Long.toHexString(actions));
        }

        MediaMetadataCompat.Builder md=new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID,CURRENT)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,safe(title))
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,safe(artist))
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,safe(album))
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,Math.max(0L,duration));
        if(metadata!=null){
            Bitmap art=metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if(art==null)art=metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if(art!=null){
                try{
                    int w=art.getWidth(),h=art.getHeight();
                    if(w>256||h>256){
                        double scale=Math.min(256.0/w,256.0/h);
                        art=Bitmap.createScaledBitmap(art,Math.max(1,(int)(w*scale)),
                            Math.max(1,(int)(h*scale)),true);
                    }
                    md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART,art);
                }catch(Exception ignored){}
            }
        }
        String metadataKey=safe(title)+"|"+safe(artist)+"|"+safe(album)+"|"+duration;
        if(!metadataKey.equals(lastPublishedMetadataKey)||currentMetadata==null){
            currentMetadata=md.build();
            session.setMetadata(currentMetadata);
            lastPublishedMetadataKey=metadataKey;
        }
        if(!metadataKey.equals(lastPublishedBrowseKey)){
            lastPublishedBrowseKey=metadataKey;
            notifyChildrenChanged(NOW);
        }

        PlaybackStateCompat.Builder ps=new PlaybackStateCompat.Builder();
        long exposed=0L;
        if((actions&PlaybackState.ACTION_PLAY)!=0)exposed|=PlaybackStateCompat.ACTION_PLAY;
        if((actions&PlaybackState.ACTION_PAUSE)!=0)exposed|=PlaybackStateCompat.ACTION_PAUSE;
        if((actions&PlaybackState.ACTION_SKIP_TO_NEXT)!=0)
            exposed|=PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        if((actions&PlaybackState.ACTION_SKIP_TO_PREVIOUS)!=0)
            exposed|=PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        if((actions&PlaybackState.ACTION_SEEK_TO)!=0)exposed|=PlaybackStateCompat.ACTION_SEEK_TO;
        if((actions&PlaybackState.ACTION_SET_RATING)!=0)
            exposed|=PlaybackStateCompat.ACTION_SET_RATING;
        ps.setActions(exposed);
        int mapped=state==PlaybackState.STATE_PLAYING?PlaybackStateCompat.STATE_PLAYING:
            state==PlaybackState.STATE_PAUSED?PlaybackStateCompat.STATE_PAUSED:
            state==PlaybackState.STATE_BUFFERING?PlaybackStateCompat.STATE_BUFFERING:
            state==PlaybackState.STATE_CONNECTING?PlaybackStateCompat.STATE_CONNECTING:
            state==PlaybackState.STATE_STOPPED?PlaybackStateCompat.STATE_STOPPED:
            state==PlaybackState.STATE_ERROR?PlaybackStateCompat.STATE_ERROR:
            PlaybackStateCompat.STATE_NONE;
        long position=playback==null?0L:Math.max(0L,playback.getPosition());
        float speed=playback==null?0f:playback.getPlaybackSpeed();
        ps.setState(mapped,position,speed,SystemClock.elapsedRealtime());
        if(state==PlaybackState.STATE_ERROR && playback!=null){
            CharSequence error=playback.getErrorMessage();
            if(error!=null)ps.setErrorMessage(error);
        }
        if(playback!=null&&playback.getCustomActions()!=null){
            for(PlaybackState.CustomAction action:playback.getCustomActions()){
                String id=action.getAction();
                if("thumbs_up_action".equals(id)||"thumbs_down_action".equals(id)||
                   "shuffle_action".equals(id)||"loop_mode_action".equals(id)){
                    try{
                        ps.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                            id,action.getName(),android.R.drawable.ic_media_play).build());
                    }catch(Exception ignored){}
                }
            }
        }
        // Only republish playback state on meaningful changes, not every poll.
        // A normal advancing position is interpolated from playback speed/timestamp.
        long now=SystemClock.elapsedRealtime();
        long expected=lastPublishedPosition;
        if(lastPublishedState==PlaybackState.STATE_PLAYING && expected>=0L){
            expected+=Math.round((now-lastPublishedAt)*lastPublishedSpeed);
        }
        boolean seekOrDrift=expected<0L || Math.abs(position-expected)>2200L;
        if(!snapshot.equals(lastPublishedSnapshot)||seekOrDrift){
            session.setPlaybackState(ps.build());
            lastPublishedPosition=position;
            lastPublishedAt=now;
            lastPublishedState=state;
            lastPublishedSpeed=speed;
            lastPublishedSnapshot=snapshot;
        }
        lastStatus="YTM "+safe(title)+" / "+stateName(state)+
            " / relay actions 0x"+Long.toHexString(exposed)+
            " / starts="+runStarts+" / pulses="+runPulses+
            " / AA roots="+acceptedRoots+" reject="+rejectedRoots;
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(STATUS,lastStatus).apply();
    }

    private static String customSignature(PlaybackState state){
        if(state==null||state.getCustomActions()==null)return "";
        List<String> x=new ArrayList<>();
        for(PlaybackState.CustomAction action:state.getCustomActions())
            x.add(action.getAction()+"="+action.getName());
        return TextUtils.join("|",x);
    }

    private static String safe(String x){return x==null||x.trim().isEmpty()?"(정보 없음)":x;}
    private static String stateName(int s){
        switch(s){
            case PlaybackState.STATE_PLAYING:return "재생 중";
            case PlaybackState.STATE_PAUSED:return "일시정지";
            case PlaybackState.STATE_BUFFERING:return "버퍼링";
            case PlaybackState.STATE_STOPPED:return "정지";
            case PlaybackState.STATE_ERROR:return "오류";
            default:return "상태 "+s;
        }
    }

    private void record(String message){
        String time=new SimpleDateFormat("MM-dd HH:mm:ss",Locale.KOREA).format(new Date());
        String line=time+"  "+message+"\n";
        String history=getSharedPreferences(PREFS,MODE_PRIVATE).getString(EVENTS,"")+line;
        if(history.length()>12500)history=history.substring(history.length()-12500);
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(EVENTS,history).apply();
    }


    /**
     * AA clients bind through MediaBrowserService. The Android supplied client
     * package name is additionally checked against the uid's actual packages.
     * Unknown clients are intentionally rejected and logged for car testing.
     */
    private boolean trustedClient(String clientPackage,int uid){
        if(clientPackage==null || clientPackage.isEmpty() || uid<0)return false;
        String[] uidPkgs;
        try{uidPkgs=getPackageManager().getPackagesForUid(uid);}
        catch(Exception e){return false;}
        if(uidPkgs==null)return false;
        boolean uidMatches=false;
        for(String p:uidPkgs){
            if(clientPackage.equals(p)){uidMatches=true;break;}
        }
        if(!uidMatches)return false;
        if(getPackageName().equals(clientPackage))return true;
        for(String allowedPackage:TRUSTED_MEDIA_CLIENTS)
            if(allowedPackage.equals(clientPackage))return true;
        return false;
    }

    @Override public BrowserRoot onGetRoot(String clientPackageName,int clientUid,Bundle rootHints){
        boolean accept=trustedClient(clientPackageName,clientUid);
        if(accept)acceptedRoots++;else rejectedRoots++;
        lastBrowserClient=(accept?"ACCEPT ":"REJECT ")+
            String.valueOf(clientPackageName)+" / uid="+clientUid;
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putString("aa_client",lastBrowserClient).apply();
        record("AA BROWSER ROOT "+lastBrowserClient);
        if(!accept)return null;

        // A player-less proxy exposes only the selected YTM queue's current
        // track, not any invented NAS/radio or music search results.
        Bundle extras=new Bundle();
        extras.putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED",true);
        handler.post(()->refresh());
        return new BrowserRoot(ROOT,extras);
    }

    @Override public void onLoadChildren(String parentId,
            Result<List<MediaBrowserCompat.MediaItem>> result){
        List<MediaBrowserCompat.MediaItem> items=new ArrayList<>();
        if(ROOT.equals(parentId)){
            MediaDescriptionCompat folder=new MediaDescriptionCompat.Builder()
                .setMediaId(NOW)
                .setTitle("YT Music 현재 재생")
                .setSubtitle("YT Music에서 재생 중인 곡")
                .build();
            items.add(new MediaBrowserCompat.MediaItem(folder,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        }else if(NOW.equals(parentId)&&currentMetadata!=null){
            MediaDescriptionCompat.Builder builder=new MediaDescriptionCompat.Builder()
                .setMediaId(CURRENT)
                .setTitle(currentMetadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
                .setSubtitle(currentMetadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
                .setDescription(currentMetadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM));
            items.add(new MediaBrowserCompat.MediaItem(builder.build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        String event=parentId+" / items="+items.size();
        if(!event.equals(lastBrowserRequest)){
            lastBrowserRequest=event;
            record("AA BROWSER CHILDREN "+event);
        }
        result.sendResult(items);
    }
}
