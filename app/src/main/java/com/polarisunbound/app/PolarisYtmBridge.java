package com.polarisunbound.app;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * YT Music MediaSession mirror for the standalone Polaris integration TEST.
 * Only runs after the user selects the YTM source; no playback engine or audio focus.
 * Radio/NAS/CAN code is intentionally not shared with this helper.
 */
final class PolarisYtmBridge {
    static final String SOURCE="com.google.android.apps.youtube.music";
    static final String MEDIA_ID="ytm:current";
    static final String ROOT="ytm";
    private static final long POLL_MS=1300L;
    private final Context context;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final MediaSessionManager manager;
    private final ComponentName accessComponent;
    private final Listener listener;
    private MediaController current;
    private boolean running=false;
    private boolean listening=false;
    private String lastSnapshot="";
    private String lastMetadataSignature="";
    private String lastStateSignature="";
    private long lastReportedPosition=-1L;
    private long lastReportedAt=0L;
    private float lastReportedSpeed=0f;
    private boolean lastReportedPlaying=false;
    private MediaMetadataCompat lastMetadata;

    interface Listener {
        void onMirror(MediaMetadataCompat metadata,PlaybackStateCompat playback);
        void onTrace(String event);
        void onBrowserChildrenChanged();
    }

    PolarisYtmBridge(Context context,Listener listener){
        this.context=context;
        this.listener=listener;
        manager=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        accessComponent=new ComponentName(context,PolarisYtmSessionListener.class);
    }

    boolean hasAccess(){
        try{
            NotificationManager nm=(NotificationManager)context.getSystemService(
                Context.NOTIFICATION_SERVICE);
            return nm!=null&&nm.isNotificationListenerAccessGranted(accessComponent);
        }catch(Exception e){return false;}
    }

    private final MediaController.Callback callback=new MediaController.Callback(){
        @Override public void onMetadataChanged(MediaMetadata md){handler.post(()->refresh());}
        @Override public void onPlaybackStateChanged(PlaybackState state){handler.post(()->refresh());}
        @Override public void onSessionDestroyed(){handler.post(()->refresh());}
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged=
        sessions -> handler.post(()->refresh());
    private final Runnable polling=new Runnable(){
        @Override public void run(){
            if(!running)return;
            refresh();
            handler.postDelayed(this,POLL_MS);
        }
    };

    void start(){
        if(running){refresh();return;}
        running=true;
        lastSnapshot="";
        lastMetadataSignature="";
        lastStateSignature="";
        lastReportedPosition=-1L;
        if(hasAccess()&&manager!=null){
            try{
                manager.addOnActiveSessionsChangedListener(
                    sessionsChanged,accessComponent,handler);
                listening=true;
            }catch(Exception e){
                listener.onTrace("YTM session list watcher error "+e.getClass().getSimpleName());
            }
        }else listener.onTrace("YTM notification listener access needed");
        handler.post(polling);
    }

    void stop(){
        running=false;
        handler.removeCallbacks(polling);
        if(listening&&manager!=null){
            try{manager.removeOnActiveSessionsChangedListener(sessionsChanged);}
            catch(Exception ignored){}
        }
        listening=false;
        detach();
        lastSnapshot="";
    }

    private void detach(){
        if(current!=null){
            try{current.unregisterCallback(callback);}catch(Exception ignored){}
        }
        current=null;
    }

    private MediaController best(){
        if(!hasAccess()||manager==null)return null;
        try{
            List<MediaController> sessions=manager.getActiveSessions(accessComponent);
            if(sessions==null)return null;
            MediaController best=null;
            int maxScore=Integer.MIN_VALUE;
            for(MediaController candidate:sessions){
                if(candidate==null||!SOURCE.equals(candidate.getPackageName()))continue;
                PlaybackState state=candidate.getPlaybackState();
                int code=state==null?PlaybackState.STATE_NONE:state.getState();
                int score=code==PlaybackState.STATE_PLAYING?600:
                    code==PlaybackState.STATE_BUFFERING?500:
                    code==PlaybackState.STATE_PAUSED?400:100;
                if(current!=null&&candidate.getSessionToken().equals(current.getSessionToken()))
                    score+=10;
                if(score>maxScore){best=candidate;maxScore=score;}
            }
            return best;
        }catch(Exception e){
            listener.onTrace("YTM session read error "+e.getClass().getSimpleName());
            return null;
        }
    }

    private static String valid(String s){return s==null||s.trim().isEmpty()?"(정보 없음)":s;}
    private static String sourceCustomSignature(PlaybackState state){
        if(state==null||state.getCustomActions()==null)return "";
        List<String> actionNames=new ArrayList<>();
        for(PlaybackState.CustomAction a:state.getCustomActions())
            actionNames.add(a.getAction()+"="+a.getName());
        return TextUtils.join("|",actionNames);
    }

    private void refresh(){
        if(!running)return;
        MediaController source=best();
        if(source==null){
            if(!"offline".equals(lastSnapshot)){
                lastSnapshot="offline";
                lastMetadataSignature="";
                lastStateSignature="";
                lastReportedPosition=-1L;
                String title=hasAccess()?"YT Music에서 음악을 재생하세요":
                    "YT Music 알림 접근 권한을 허용하세요";
                lastMetadata=new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID,MEDIA_ID)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE,title).build();
                listener.onMirror(lastMetadata,new PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PAUSED,0,0f)
                    .setActions(0).build());
                listener.onBrowserChildrenChanged();
                listener.onTrace("YTM no accessible active source");
            }
            detach();
            return;
        }
        if(current==null || !current.getSessionToken().equals(source.getSessionToken())){
            detach();
            current=source;
            try{current.registerCallback(callback,handler);}catch(Exception ignored){}
            listener.onTrace("YTM attached live source session");
        }
        MediaMetadata md;
        PlaybackState ps;
        try{md=source.getMetadata();ps=source.getPlaybackState();}
        catch(Exception e){listener.onTrace("YTM metadata error "+e);return;}
        String title=md==null?null:md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist=md==null?null:md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String album=md==null?null:md.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration=md==null?0:md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        int state=ps==null?PlaybackState.STATE_NONE:ps.getState();
        long srcActions=ps==null?0:ps.getActions();
        String metadataSig=valid(title)+"|"+valid(artist)+"|"+valid(album)+"|"+duration;
        Bitmap sourceArt=null;
        if(md!=null){
            try{
                sourceArt=md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if(sourceArt==null)sourceArt=md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }catch(Exception ignored){}
        }
        // Official YTM can publish the title *before* the album bitmap.
        // Text fields alone cannot be used as the metadata cache key.
        boolean sameTrack=metadataSig.equals(lastMetadataSignature);
        Bitmap previousArt=lastMetadata==null?null:
            lastMetadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
        boolean lateArtwork=sameTrack && sourceArt!=null && previousArt==null;
        boolean rebuildMetadata=!sameTrack||lastMetadata==null||lateArtwork;
        if(rebuildMetadata){
            lastMetadataSignature=metadataSig;
            MediaMetadataCompat.Builder builder=new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID,MEDIA_ID)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,valid(title))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,valid(artist))
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,valid(album))
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,Math.max(0,duration));
            Bitmap artwork=sourceArt!=null?sourceArt:(sameTrack?previousArt:null);
            if(artwork!=null){
                try{
                    int width=artwork.getWidth(),height=artwork.getHeight();
                    if(width>256||height>256){
                        double scale=Math.min(256d/width,256d/height);
                        artwork=Bitmap.createScaledBitmap(artwork,
                            Math.max(1,(int)(width*scale)),
                            Math.max(1,(int)(height*scale)),true);
                    }
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART,artwork);
                }catch(Exception ignored){}
            }
            lastMetadata=builder.build();
            listener.onBrowserChildrenChanged();
        }
        long exposed=0;
        if((srcActions&PlaybackState.ACTION_PLAY)!=0)exposed|=PlaybackStateCompat.ACTION_PLAY;
        if((srcActions&PlaybackState.ACTION_PAUSE)!=0)exposed|=PlaybackStateCompat.ACTION_PAUSE;
        if((srcActions&PlaybackState.ACTION_SKIP_TO_NEXT)!=0)
            exposed|=PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        if((srcActions&PlaybackState.ACTION_SKIP_TO_PREVIOUS)!=0)
            exposed|=PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        if((srcActions&PlaybackState.ACTION_SEEK_TO)!=0)
            exposed|=PlaybackStateCompat.ACTION_SEEK_TO;
        if((srcActions&PlaybackState.ACTION_SET_RATING)!=0)
            exposed|=PlaybackStateCompat.ACTION_SET_RATING;
        int mapped=state==PlaybackState.STATE_PLAYING?PlaybackStateCompat.STATE_PLAYING:
            state==PlaybackState.STATE_PAUSED?PlaybackStateCompat.STATE_PAUSED:
            state==PlaybackState.STATE_BUFFERING?PlaybackStateCompat.STATE_BUFFERING:
            state==PlaybackState.STATE_CONNECTING?PlaybackStateCompat.STATE_CONNECTING:
            state==PlaybackState.STATE_STOPPED?PlaybackStateCompat.STATE_STOPPED:
            PlaybackStateCompat.STATE_NONE;
        long position=ps==null?0:Math.max(0,ps.getPosition());
        float speed=ps==null?0:ps.getPlaybackSpeed();
        PlaybackStateCompat.Builder builder=new PlaybackStateCompat.Builder()
            .setActions(exposed)
            .setState(mapped,position,speed,SystemClock.elapsedRealtime());
        // Keep related controls together instead of YTM's inconsistent action order.
        String[] keys={"thumbs_up_action","thumbs_down_action",
            "shuffle_action","loop_mode_action"};
        int[] icons={R.drawable.ic_mp3_like_off,R.drawable.ic_mp3_dislike,
            R.drawable.ic_mp3_shuffle_off,R.drawable.ic_mp3_repeat_off};
        if(ps!=null&&ps.getCustomActions()!=null){
            for(int i=0;i<keys.length;i++){
                for(PlaybackState.CustomAction action:ps.getCustomActions()){
                    if(!keys[i].equals(action.getAction()))continue;
                    try{
                        builder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                            keys[i],action.getName(),icons[i]).build());
                    }catch(Exception ignored){}
                    break;
                }
            }
        }
        String stateSignature=metadataSig+"|"+mapped+"|"+exposed+"|"+
            sourceCustomSignature(ps);
        long now=SystemClock.elapsedRealtime();
        long expected=lastReportedPosition;
        if(lastReportedPlaying&&expected>=0)
            expected+=Math.round((now-lastReportedAt)*lastReportedSpeed);
        boolean seekOrDrift=expected<0||Math.abs(position-expected)>2500;
        if(rebuildMetadata||!stateSignature.equals(lastStateSignature)||seekOrDrift){
            lastStateSignature=stateSignature;
            lastReportedPosition=position;
            lastReportedAt=now;
            lastReportedSpeed=speed;
            lastReportedPlaying=mapped==PlaybackStateCompat.STATE_PLAYING;
            listener.onMirror(lastMetadata,builder.build());
        }
        if(!metadataSig.equals(lastSnapshot)){
            lastSnapshot=metadataSig;
            listener.onTrace("YTM mirror "+valid(title)+" / "+valid(artist)+
                " / state="+mapped);
        }
    }

    private MediaController commandTarget(long needed){
        MediaController target=best();
        if(target==null){
            listener.onTrace("YTM command rejected: no source session");
            refresh();
            return null;
        }
        PlaybackState state=target.getPlaybackState();
        if(needed!=0&&(state==null||(state.getActions()&needed)==0)){
            listener.onTrace("YTM source action not available: 0x"+Long.toHexString(needed));
            return null;
        }
        return target;
    }
    private interface TransportCommand {
        void run(MediaController.TransportControls control);
    }
    private void forward(String label,long flag,TransportCommand command){
        MediaController target=commandTarget(flag);
        if(target==null)return;
        try{
            command.run(target.getTransportControls());
            listener.onTrace("YTM forwarded "+label);
            handler.postDelayed(()->refresh(),250);
        }catch(Exception e){listener.onTrace("YTM command "+label+" failed "+e);}
    }
    /**
     * Phone-only no-activity playback request. This is safe to try only when
     * the installed YT Music exposes ACTION_PLAY_FROM_URI on an active session.
     *
     * A successful dispatch is NOT proof the source accepted the URI or
     * actually selected the requested track/playlist.
     */
    boolean tryPlayFromUriInBackground(android.net.Uri uri){
        if(uri==null)return false;
        MediaController target=best();
        if(target==null){
            listener.onTrace("YTM background URI unavailable: no active YTM session");
            return false;
        }
        PlaybackState state=target.getPlaybackState();
        if(state==null || (state.getActions() & PlaybackState.ACTION_PLAY_FROM_URI)==0){
            listener.onTrace("YTM background URI unavailable: action not supported");
            return false;
        }
        try{
            target.getTransportControls().playFromUri(uri,Bundle.EMPTY);
            listener.onTrace("YTM background URI command dispatched, playback unverified");
            handler.postDelayed(()->refresh(),500);
            return true;
        }catch(Exception e){
            listener.onTrace("YTM background URI dispatch failed: "+
                e.getClass().getSimpleName());
            return false;
        }
    }

    void play(){forward("PLAY",PlaybackState.ACTION_PLAY,t->t.play());}
    void pause(){forward("PAUSE",PlaybackState.ACTION_PAUSE,t->t.pause());}
    void next(){forward("NEXT",PlaybackState.ACTION_SKIP_TO_NEXT,t->t.skipToNext());}
    void previous(){
        forward("PREVIOUS",PlaybackState.ACTION_SKIP_TO_PREVIOUS,t->t.skipToPrevious());
    }
    void seek(long position){
        forward("SEEK",PlaybackState.ACTION_SEEK_TO,t->t.seekTo(Math.max(0,position)));
    }
    void custom(String id){
        if(!("thumbs_up_action".equals(id)||"thumbs_down_action".equals(id)||
            "shuffle_action".equals(id)||"loop_mode_action".equals(id)))return;
        MediaController target=commandTarget(0);
        if(target==null)return;
        PlaybackState state=target.getPlaybackState();
        boolean exposed=false;
        if(state!=null&&state.getCustomActions()!=null){
            for(PlaybackState.CustomAction action:state.getCustomActions()){
                if(id.equals(action.getAction())){exposed=true;break;}
            }
        }
        if(!exposed){listener.onTrace("YTM custom action absent "+id);return;}
        try{
            target.getTransportControls().sendCustomAction(id,Bundle.EMPTY);
            listener.onTrace("YTM forwarded CUSTOM "+id);
            handler.postDelayed(()->refresh(),300);
        }catch(Exception e){listener.onTrace("YTM custom action error "+e);}
    }
    String currentTrackName(){
        return lastMetadata==null?"현재 곡 정보 없음":
            lastMetadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
    }
    MediaMetadataCompat currentMetadata(){return lastMetadata;}
}
