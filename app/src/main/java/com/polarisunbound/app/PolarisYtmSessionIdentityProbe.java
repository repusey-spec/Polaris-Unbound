package com.polarisunbound.app;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * On-demand, read-only inspection of ONLY the installed, active official
 * YT Music MediaSession via the already authorized NotificationListener.
 *
 * No credentials, playlist-name guessing, Accessibility automation,
 * media key presses, playlist enumeration APIs or network requests.
 * Never print arbitrary metadata/URI/Bundle values (might be sensitive).
 */
final class PolarisYtmSessionIdentityProbe {
    private static final String YTM="com.google.android.apps.youtube.music";
    private PolarisYtmSessionIdentityProbe(){}

    static String inspect(Context context){
        StringBuilder result=new StringBuilder();
        result.append("원본 YT Music MediaSession ID 진단\n");
        result.append("공식 YTM 앱을 실행해 원하는 재생목록의 곡을 재생한 상태에서 측정합니다.\n");
        try{
            ComponentName listener=new ComponentName(
                context,PolarisYtmSessionListener.class);
            NotificationManager notifications=(NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            if(notifications==null ||
                !notifications.isNotificationListenerAccessGranted(listener)){
                return result.append("알림 접근 권한: 없음. 기존 YT Music 중계 권한이 필요합니다.")
                    .toString();
            }
            MediaSessionManager manager=(MediaSessionManager)
                context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if(manager==null)
                return result.append("시스템 MediaSessionManager 없음").toString();
            List<MediaController> controllers=manager.getActiveSessions(listener);
            MediaController chosen=null;
            int highest=-1;
            if(controllers!=null)for(MediaController controller:controllers){
                if(controller==null||!YTM.equals(controller.getPackageName()))
                    continue;
                PlaybackState state=controller.getPlaybackState();
                int code=state==null?PlaybackState.STATE_NONE:state.getState();
                int score=code==PlaybackState.STATE_PLAYING?5:
                    code==PlaybackState.STATE_BUFFERING?4:
                    code==PlaybackState.STATE_PAUSED?3:1;
                if(score>highest){highest=score;chosen=controller;}
            }
            if(chosen==null)
                return result.append("원본 YT Music 활성 MediaSession: 없음\n")
                    .append("기존 YT Music 앱에서 곡을 재생한 뒤 다시 실행하세요.")
                    .toString();

            result.append("원본 YT Music 활성 MediaSession: 있음\n");
            PlaybackState playback=chosen.getPlaybackState();
            int state=playback==null?PlaybackState.STATE_NONE:playback.getState();
            result.append("재생 상태: ").append(stateName(state)).append("\n");

            LinkedHashSet<String> verified=new LinkedHashSet<>();
            MediaMetadata metadata=chosen.getMetadata();
            String rawMediaId=metadata==null?null:
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            result.append("원본 곡 Media ID: ")
                .append(PolarisYtmSessionIdClassifier.opaqueMediaIdPresent(rawMediaId)?
                    "노출됨 (재생목록 ID인지는 알 수 없음)":"노출되지 않음")
                .append("\n");
            String metadataUri=null;
            if(metadata!=null && Build.VERSION.SDK_INT>=26)
                metadataUri=metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
            String listId=PolarisYtmSessionIdClassifier.explicitPlaylistId(metadataUri);
            if(listId!=null)verified.add(listId);
            result.append("원본 Media URI: ")
                .append(metadataUri==null?"없음":
                    (listId==null?"있지만 공식 재생목록 주소 아님":
                        "공식 재생목록 문맥 ID 확인")).append("\n");

            // A media app may expose its currently playing URI through the
            // synthesized MediaDescription even without METADATA_KEY_MEDIA_URI.
            // This is still read-only and restricted to the original YTM app.
            MediaDescription current=metadata==null?null:metadata.getDescription();
            Uri currentUri=current==null?null:current.getMediaUri();
            String currentListId=PolarisYtmSessionIdClassifier.explicitPlaylistId(
                currentUri==null?null:currentUri.toString());
            if(currentListId!=null)verified.add(currentListId);
            result.append("원본 재생 항목 설명 URI: ")
                .append(currentUri==null?"없음":
                    (currentListId==null?"있지만 공식 재생목록 주소 아님":
                        "공식 재생목록 문맥 ID 확인")).append("\n");

            List<MediaSession.QueueItem> queue=chosen.getQueue();
            int count=queue==null?-1:queue.size();
            result.append("공개된 현재 재생 큐: ")
                .append(count<0?"없음":count+"개").append("\n");
            int inspected=0, queueMediaIds=0, queueUris=0, queuePlaylistUrls=0;
            if(queue!=null)for(MediaSession.QueueItem item:queue){
                if(inspected++>=200)break; // bound diagnostics and work
                if(item==null)continue;
                MediaDescription desc=item.getDescription();
                if(desc==null)continue;
                if(PolarisYtmSessionIdClassifier.opaqueMediaIdPresent(desc.getMediaId()))
                    queueMediaIds++;
                Uri uri=desc.getMediaUri();
                if(uri==null)continue;
                queueUris++;
                String id=PolarisYtmSessionIdClassifier.explicitPlaylistId(uri.toString());
                if(id!=null){
                    queuePlaylistUrls++;
                    if(verified.size()<5)verified.add(id);
                }
            }
            result.append("검사한 큐 항목: ").append(Math.min(Math.max(count,0),200))
                .append("개 / 개별 Media ID 노출: ").append(queueMediaIds)
                .append("개 / Media URI: ").append(queueUris).append("개\n");
            // Queue item IDs identify an item in the active session queue;
            // they do not constitute YouTube playlist IDs.
            if(playback!=null){
                long activeId=playback.getActiveQueueItemId();
                result.append("활성 큐 항목 ID: ")
                    .append(activeId>=0?"노출됨 (재생목록 ID 아님)":
                        "노출되지 않음").append("\n");
            }
            result.append("공식 재생목록 주소가 확인된 큐 항목: ")
                .append(queuePlaylistUrls).append("개\n");

            CharSequence queueTitle=chosen.getQueueTitle();
            result.append("재생 큐 이름: ").append(queueTitle==null?
                "노출되지 않음":"노출됨 (이름만으로 ID 식별 불가)").append("\n");
            Bundle extras=chosen.getExtras();
            result.append("세션 추가 정보: ").append(extras==null?
                "노출되지 않음":"존재함 (값과 인증 정보는 읽지 않음)").append("\n");

            if(verified.isEmpty()){
                result.append("공식 재생목록 ID 자동 확인: 0개\n");
                result.append("현재 MediaSession에서는 검증 가능한 재생목록 URL을 찾지 못했습니다.\n");
            }else{
                result.append("공식 재생목록 ID 자동 확인: ")
                    .append(verified.size()).append("개\n");
                for(String id:verified)result.append("원본 URI의 list ID: ")
                    .append(id).append("\n");
            }

            // Test07 obtained citypop's real ID by a user-confirmed share.
            // Compare source-exposed IDs against those independently proven
            // links; a match is evidence, not permission to import a library.
            java.util.LinkedHashMap<String,String> userLinked=
                PolarisYtmPlaylistBindings.all(context);
            int comparisons=0,matched=0;
            for(java.util.Map.Entry<String,String> entry:userLinked.entrySet()){
                comparisons++;
                if(verified.contains(entry.getValue())){
                    matched++;
                    result.append("기존 공유 연결과 일치: ")
                        .append(entry.getKey()).append("\n");
                }
            }
            if(comparisons>0){
                result.append("기존 공유 연결 ").append(comparisons)
                    .append("개 중 ID 일치 ").append(matched)
                    .append("개\n");
            }
            result.append("주의: 현재 재생 세션과 큐만 검사했습니다. ")
                .append("전체 YT Music 보관함이나 개인화 홈을 조회한 결과는 아닙니다. ")
                .append("watch 링크의 list 값은 현재 재생 문맥일 뿐 ")
                .append("사용자 보관함의 소유 목록이라는 증거는 아닙니다. ")
                .append("내부 곡 ID를 재생목록 ID로 임의 변환하지 않습니다.");
        }catch(SecurityException denied){
            result.append("접근 거부: 알림 접근 권한/MediaSession 접근 상태 확인 필요");
        }catch(Exception e){
            result.append("진단 예외: ").append(e.getClass().getSimpleName())
                .append(" (원본 데이터는 출력하지 않음)");
        }
        return result.toString();
    }

    private static String stateName(int state){
        if(state==PlaybackState.STATE_PLAYING)return "재생 중";
        if(state==PlaybackState.STATE_PAUSED)return "일시정지";
        if(state==PlaybackState.STATE_BUFFERING)return "버퍼링";
        if(state==PlaybackState.STATE_STOPPED)return "정지";
        if(state==PlaybackState.STATE_CONNECTING)return "연결 중";
        return "기타 / 불명";
    }
}
