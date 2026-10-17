package com.polarisunbound.app;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import java.util.List;

/**
 * Test09 explicit-on-tap command to the EXISTING official YouTube Music
 * MediaSession. No YTM Activity launch, no direct BackgroundPlayerService
 * access, no authentication extraction, no audio player in Polaris.
 *
 * The API has a void return: successful dispatch DOES NOT acknowledge
 * playback. Always compare subsequent MediaSession state/metadata.
 */
final class PolarisYtmSearchCommandProbe {
    private static final String SOURCE="com.google.android.apps.youtube.music";
    private PolarisYtmSearchCommandProbe(){}

    static final class DispatchResult {
        final boolean submitted;
        final String message;
        DispatchResult(boolean submitted,String message){
            this.submitted=submitted;
            this.message=message;
        }
    }

    static DispatchResult dispatch(Context context,String text){
        String query=PolarisYtmSearchQuery.normalize(text);
        if(query==null)return new DispatchResult(false,
            "검색어가 비어 있거나 120자를 초과하거나 제어 문자가 포함됐습니다.");
        try{
            MediaController controller=activeOfficialController(context);
            if(controller==null)return new DispatchResult(false,
                "원본 YT Music 활성 MediaSession이 없습니다. "+
                "공식 앱에서 음악을 한 번 재생한 다음 다시 시도하세요. "+
                "원본 앱 화면을 강제로 실행하지 않았습니다.");
            PlaybackState state=controller.getPlaybackState();
            if(state==null || (state.getActions()&
                PlaybackState.ACTION_PLAY_FROM_SEARCH)==0){
                return new DispatchResult(false,
                    "원본 YT Music 세션에 PLAY_FROM_SEARCH 지원 비트가 없습니다. "+
                    "재생 요청을 보내지 않았습니다.");
            }
            String before=statusOf(controller);
            controller.getTransportControls().playFromSearch(query,Bundle.EMPTY);
            return new DispatchResult(true,
                "원본 YT Music 세션에 playFromSearch 요청 전송 완료.\n"+
                "요청 전: "+before+"\n"+
                "※ 호출은 응답 없는 명령입니다. 실제 재생·화면 유지 여부는 아래 후속 상태와 기기에서 확인하세요.");
        }catch(SecurityException e){
            return new DispatchResult(false,
                "MediaSession 접근이 거부됐습니다. YT Music 알림 접근 권한을 확인하세요.");
        }catch(Exception e){
            return new DispatchResult(false,
                "원본 YT Music 재생 요청 실패: "+e.getClass().getSimpleName());
        }
    }

    static String inspect(Context context){
        try{
            MediaController controller=activeOfficialController(context);
            if(controller==null)return "공식 YT Music 활성 세션 없음";
            return statusOf(controller);
        }catch(SecurityException e){
            return "세션 접근 거부";
        }catch(Exception e){
            return "상태 확인 실패: "+e.getClass().getSimpleName();
        }
    }

    private static MediaController activeOfficialController(Context context){
        ComponentName listener=new ComponentName(
            context,PolarisYtmSessionListener.class);
        NotificationManager notifications=(NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(notifications==null ||
            !notifications.isNotificationListenerAccessGranted(listener))
            return null;
        MediaSessionManager manager=(MediaSessionManager)
            context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if(manager==null)return null;
        List<MediaController> sessions=manager.getActiveSessions(listener);
        MediaController chosen=null;
        int best=-1;
        if(sessions!=null)for(MediaController controller:sessions){
            if(controller==null || !SOURCE.equals(controller.getPackageName()))
                continue;
            PlaybackState ps=controller.getPlaybackState();
            int state=ps==null?PlaybackState.STATE_NONE:ps.getState();
            int score=state==PlaybackState.STATE_PLAYING?5:
                state==PlaybackState.STATE_BUFFERING?4:
                state==PlaybackState.STATE_PAUSED?3:1;
            if(score>best){best=score;chosen=controller;}
        }
        return chosen;
    }

    private static String statusOf(MediaController source){
        PlaybackState state=source.getPlaybackState();
        int code=state==null?PlaybackState.STATE_NONE:state.getState();
        String label=code==PlaybackState.STATE_PLAYING?"재생 중":
            code==PlaybackState.STATE_PAUSED?"일시정지":
            code==PlaybackState.STATE_BUFFERING?"버퍼링":
            code==PlaybackState.STATE_CONNECTING?"연결 중":
            code==PlaybackState.STATE_STOPPED?"정지":"기타/불명";
        MediaMetadata metadata=source.getMetadata();
        String title=metadata==null?null:
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist=metadata==null?null:
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        return label+" / 곡: "+display(title)+" / 가수: "+display(artist);
    }

    private static String display(String value){
        if(value==null||value.trim().isEmpty())return "(미제공)";
        String clean=value.trim().replace('\n',' ').replace('\r',' ');
        return clean.length()>75?clean.substring(0,75)+"…":clean;
    }
}
