package com.polarisunbound.ytminspector;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Polaris YTM 1.a02 — PHONE-ONLY MediaSession remote control diagnostics.
 *
 * Uses the Android MediaSessionManager / NotificationListener access foundation
 * studied in MQ Player without copying its GPL implementation.
 * Sends commands ONLY to the live YouTube Music MediaSession package.
 * No Android Auto service and no separate media playback/audio focus.
 */
public final class InspectorActivity extends Activity {
    private static final String YTM="com.google.android.apps.youtube.music";
    private static final String STORE="ytm_inspector";
    private static final String HISTORY="log";
    private static final long SESSION_GRACE_MS=800L;
    private static final long POLL_MS=1400L;
    private static final int WHITE=Color.rgb(240,242,248);
    private static final int MUTED=Color.rgb(177,190,207);
    private static final int ACCENT=Color.rgb(104,189,255);
    private static final int CARD=Color.rgb(32,42,57);

    private MediaSessionManager manager;
    private ComponentName listenerComponent;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private MediaController selected;
    private boolean watching=false;
    private boolean activeListenerRegistered=false;
    private long lastFoundAt=0L;
    private String lastSnapshot="";
    private String lastPermissionState="";
    private String latestDiagnostic="";

    private TextView permissionText;
    private TextView sessionText;
    private TextView titleText;
    private TextView artistText;
    private TextView albumText;
    private TextView playbackText;
    private TextView actionText;
    private TextView ratingText;
    private TextView customText;
    private TextView logText;
    private ImageView artwork;
    private EditText songQueryInput;
    private TextView lastCommandText;

    private final MediaController.Callback selectedCallback=new MediaController.Callback(){
        @Override public void onMetadataChanged(MediaMetadata metadata){ handler.post(()->scan(false)); }
        @Override public void onPlaybackStateChanged(PlaybackState state){handler.post(()->scan(false));}
        @Override public void onSessionDestroyed(){handler.post(()->scan(true));}
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged=
        controllers -> handler.post(()->scan(true));

    private final Runnable poll=new Runnable(){
        @Override public void run(){
            if(!watching) return;
            scan(false);
            handler.postDelayed(this,POLL_MS);
        }
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        manager=(MediaSessionManager)getSystemService(Context.MEDIA_SESSION_SERVICE);
        listenerComponent=new ComponentName(this,SessionAccessListener.class);
        constructUi();
        scan(false);
    }

    @Override protected void onResume(){
        super.onResume();
        watching=true;
        handler.removeCallbacks(poll);
        scan(true);
        handler.postDelayed(poll,POLL_MS);
    }

    @Override protected void onPause(){
        watching=false;
        handler.removeCallbacks(poll);
        removeSessionsListener();
        super.onPause();
    }

    @Override protected void onDestroy(){
        detach();
        super.onDestroy();
    }

    private int dp(int value){
        return Math.round(getResources().getDisplayMetrics().density*value);
    }

    private GradientDrawable background(int color,int radius){
        GradientDrawable drawable=new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private TextView text(String value,int sp,int color,boolean strong){
        TextView t=new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if(strong) t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setTextIsSelectable(true);
        return t;
    }

    private void addSpace(LinearLayout host,int pixels){
        View spacer=new View(this);
        host.addView(spacer,new LinearLayout.LayoutParams(1,dp(pixels)));
    }

    private LinearLayout card(LinearLayout parent){
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14),dp(13),dp(14),dp(13));
        c.setBackground(background(CARD,14));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,0,0,dp(12));
        parent.addView(c,lp);
        return c;
    }

    private void constructUi(){
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(17,24,34));
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(18),dp(16),dp(24));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(text("Polaris YTM Inspector",25,WHITE,true));
        addSpace(root,4);
        root.addView(text("1.a02 · 폰 전용 · YT Music 리모컨 테스트",14,ACCENT,true));
        addSpace(root,8);
        root.addView(text(
            "YT Music 세션에서 광고한 명령을 실제로 전송합니다. "
            +"좋아요·싫어요는 계정에 반영될 수 있습니다. "
            +"명령 전송과 실제 처리 성공은 구분해 기록합니다.",14,MUTED,false));
        addSpace(root,14);

        LinearLayout permissions=card(root);
        permissions.addView(text("알림 접근 권한",16,WHITE,true));
        addSpace(permissions,6);
        permissionText=text("확인 중…",14,MUTED,false);
        permissions.addView(permissionText);
        addSpace(permissions,10);
        Button grant=new Button(this);
        grant.setText("Android 알림 접근 설정 열기");
        grant.setAllCaps(false);
        grant.setOnClickListener(v->{
            try{startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));}
            catch(Exception e){Toast.makeText(this,"설정 화면을 열 수 없습니다",Toast.LENGTH_LONG).show();}
        });
        permissions.addView(grant,new LinearLayout.LayoutParams(-1,dp(52)));

        LinearLayout now=card(root);
        now.addView(text("YT Music 세션",16,WHITE,true));
        addSpace(now,10);
        LinearLayout hero=new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        artwork=new ImageView(this);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setBackground(background(Color.rgb(49,61,76),8));
        hero.addView(artwork,new LinearLayout.LayoutParams(dp(82),dp(82)));
        LinearLayout meta=new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(dp(12),0,0,0);
        titleText=text("YT Music 실행을 기다리는 중",18,WHITE,true);
        artistText=text("",14,MUTED,false);
        albumText=text("",13,MUTED,false);
        meta.addView(titleText);
        addSpace(meta,5);
        meta.addView(artistText);
        meta.addView(albumText);
        hero.addView(meta,new LinearLayout.LayoutParams(0,-2,1));
        now.addView(hero);
        addSpace(now,10);
        playbackText=text("세션 없음",15,ACCENT,true);
        now.addView(playbackText);
        addSpace(now,8);
        sessionText=text("실행 중인 미디어 세션 검색 중…",13,MUTED,false);
        now.addView(sessionText);

        addRemoteControls(root);

        LinearLayout details=card(root);
        details.addView(text("MediaSession 지원 명령 분석",16,WHITE,true));
        addSpace(details,9);
        actionText=text("아직 확인되지 않았습니다.",14,WHITE,false);
        details.addView(actionText);
        addSpace(details,12);
        ratingText=text("평가 방식: 알 수 없음",14,MUTED,false);
        details.addView(ratingText);
        addSpace(details,10);
        customText=text("Custom actions: 없음",13,MUTED,false);
        details.addView(customText);

        LinearLayout diagnostics=card(root);
        diagnostics.addView(text("세션 진단 및 기록",16,WHITE,true));
        addSpace(diagnostics,7);
        diagnostics.addView(text(
            "곡 제목·패키지명·재생 상태·지원 명령의 변경 이력을 기기에만 저장합니다. "
            +"외부 전송은 없으며 복사 버튼으로 직접 공유할 수 있습니다.",12,MUTED,false));
        addSpace(diagnostics,8);
        LinearLayout buttons=new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh=new Button(this);
        refresh.setAllCaps(false);
        refresh.setText("새로고침");
        refresh.setOnClickListener(v->scan(true));
        buttons.addView(refresh,new LinearLayout.LayoutParams(0,dp(50),1));
        Button copy=new Button(this);
        copy.setAllCaps(false);
        copy.setText("진단 복사");
        copy.setOnClickListener(v->copyDiagnostics());
        buttons.addView(copy,new LinearLayout.LayoutParams(0,dp(50),1));
        diagnostics.addView(buttons);
        addSpace(diagnostics,8);
        logText=text("",12,MUTED,false);
        diagnostics.addView(logText);
        showLog();
    }


    private Button controlButton(String name,Runnable action){
        Button button=new Button(this);
        button.setAllCaps(false);
        button.setText(name);
        button.setTextSize(13);
        button.setMinHeight(dp(48));
        button.setOnClickListener(v->action.run());
        return button;
    }

    private void addButtons(LinearLayout root,String[] titles,Runnable[] actions){
        if(titles.length!=actions.length)throw new IllegalArgumentException("Button mismatch");
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for(int i=0;i<titles.length;i++){
            Button button=controlButton(titles[i],actions[i]);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(53),1);
            lp.setMargins(dp(2),0,dp(2),0);
            row.addView(button,lp);
        }
        root.addView(row,new LinearLayout.LayoutParams(-1,-2));
        addSpace(root,6);
    }

    /** Real transport commands are tested only in this 1.a02 experimental app. */
    private void addRemoteControls(LinearLayout root){
        LinearLayout controls=card(root);
        controls.addView(text("YT Music 원격 제어",17,WHITE,true));
        addSpace(controls,5);
        controls.addView(text("아래 버튼은 YT Music 앱에 실제 명령을 보냅니다. "
            +"Polaris 라디오·NAS에는 명령을 보내지 않습니다.",13,MUTED,false));
        addSpace(controls,10);
        addButtons(controls,
            new String[]{"◀ 이전","▶ 재생","Ⅱ 일시정지","다음 ▶"},
            new Runnable[]{
                ()->sendTransport("이전 곡",PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                    t->t.skipToPrevious()),
                ()->sendTransport("재생",PlaybackState.ACTION_PLAY,t->t.play()),
                ()->sendTransport("일시정지",PlaybackState.ACTION_PAUSE,t->t.pause()),
                ()->sendTransport("다음 곡",PlaybackState.ACTION_SKIP_TO_NEXT,
                    t->t.skipToNext())
            });
        addButtons(controls,
            new String[]{"-10초"," +30초"},
            new Runnable[]{
                ()->seekRelative(-10000L),
                ()->seekRelative(30000L)
            });
        controls.addView(text("YT Music 자체 Custom Action",13,ACCENT,true));
        addSpace(controls,6);
        addButtons(controls,
            new String[]{"👍 좋아요","👎 싫어요"},
            new Runnable[]{
                ()->sendCustom("좋아요","thumbs_up_action"),
                ()->sendCustom("싫어요","thumbs_down_action")
            });
        addButtons(controls,
            new String[]{"⇄ 셔플","↻ 반복"},
            new Runnable[]{
                ()->sendCustom("셔플","shuffle_action"),
                ()->sendCustom("반복","loop_mode_action")
            });
        controls.addView(text("SetRating 비교 시험 (위 Custom Action과 별개의 경로)",12,MUTED,false));
        addButtons(controls,
            new String[]{"SetRating 👍","SetRating 👎"},
            new Runnable[]{
                ()->sendThumbRating(true),
                ()->sendThumbRating(false)
            });

        controls.addView(text("곡명 검색 / PlayFromSearch",13,ACCENT,true));
        addSpace(controls,6);
        LinearLayout queryRow=new LinearLayout(this);
        queryRow.setOrientation(LinearLayout.HORIZONTAL);
        songQueryInput=new EditText(this);
        songQueryInput.setSingleLine(true);
        songQueryInput.setTextColor(WHITE);
        songQueryInput.setHintTextColor(MUTED);
        songQueryInput.setTextSize(15);
        songQueryInput.setHint("곡명 또는 가수 + 곡명");
        songQueryInput.setInputType(InputType.TYPE_CLASS_TEXT);
        songQueryInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        songQueryInput.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_SEARCH){
                sendSearch();
                return true;
            }
            return false;
        });
        queryRow.addView(songQueryInput,new LinearLayout.LayoutParams(0,dp(52),1));
        Button search=controlButton("검색 재생",this::sendSearch);
        queryRow.addView(search,new LinearLayout.LayoutParams(dp(104),dp(52)));
        controls.addView(queryRow);
        addSpace(controls,8);
        lastCommandText=text("명령 대기 중",13,ACCENT,false);
        controls.addView(lastCommandText);
    }

    /** Never send a command to an old session or a session of another app. */
    private MediaController commandTarget(){
        if(!permitted()||manager==null){
            noteCommand("권한 없음 · 알림 접근을 허용하세요");
            return null;
        }
        try{
            List<MediaController> live=manager.getActiveSessions(listenerComponent);
            MediaController target=null;
            int highest=Integer.MIN_VALUE;
            for(MediaController item:live){
                if(item==null || !YTM.equals(item.getPackageName()))continue;
                PlaybackState state=item.getPlaybackState();
                int type=state==null?PlaybackState.STATE_NONE:state.getState();
                int score=type==PlaybackState.STATE_PLAYING?5000:
                    type==PlaybackState.STATE_BUFFERING?4000:
                    type==PlaybackState.STATE_PAUSED?3000:1000;
                if(selected!=null && item.getSessionToken().equals(selected.getSessionToken()))
                    score+=250;
                if(score>highest){highest=score;target=item;}
            }
            if(target==null){
                noteCommand("YT Music 활성 세션 없음 · 명령 미전송");
                return null;
            }
            attach(target);
            return target;
        }catch(Exception e){
            noteCommand("YT Music 세션 검색 오류 · "+e.getClass().getSimpleName());
            return null;
        }
    }

    private interface TransportCommand {
        void run(MediaController.TransportControls transport);
    }

    private String observed(MediaController c){
        try{
            PlaybackState st=c.getPlaybackState();
            MediaMetadata md=c.getMetadata();
            String song=md==null?"":md.getString(MediaMetadata.METADATA_KEY_TITLE);
            long position=st==null?-1:st.getPosition();
            return safe(song)+" / "+(st==null?"상태 없음":stateName(st.getState()))+
                " / "+position+"ms";
        }catch(Exception e){
            return "관측 실패 "+e.getClass().getSimpleName();
        }
    }

    private void noteCommand(String message){
        if(lastCommandText!=null)lastCommandText.setText(message);
        log("제어 · "+message);
    }

    private void sent(MediaController target,String label,String before){
        noteCommand(label+" → 명령 전달 (처리 성공은 별도 확인)");
        handler.postDelayed(()->{
            MediaController live=commandTargetSilent(target);
            if(live==null){
                log("명령 후 관측 · "+label+" → 원래 세션 사라짐/변경됨");
                return;
            }
            String after=observed(live);
            log("명령 후 관측 · "+label+" / 이전: "+before+" / 이후: "+after+
                " / ※ 계정 반영은 YT Music에서 직접 확인 필요");
            scan(false);
        },1300L);
    }

    private MediaController commandTargetSilent(MediaController previous){
        if(previous==null||!permitted())return null;
        try{
            for(MediaController live:manager.getActiveSessions(listenerComponent)){
                if(YTM.equals(live.getPackageName()) &&
                   live.getSessionToken().equals(previous.getSessionToken()))
                    return live;
            }
        }catch(Exception ignored){}
        return null;
    }

    private void sendTransport(String label,long required,TransportCommand operation){
        MediaController target=commandTarget();
        if(target==null)return;
        PlaybackState state=target.getPlaybackState();
        if(state==null||(state.getActions()&required)==0){
            noteCommand(label+" → MediaSession에서 지원한다고 표시하지 않음 · 미전송");
            return;
        }
        String before=observed(target);
        try{
            operation.run(target.getTransportControls());
            sent(target,label,before);
        }catch(Exception e){
            noteCommand(label+" 전송 예외: "+e.getClass().getSimpleName());
        }
    }

    private void seekRelative(long offset){
        MediaController target=commandTarget();
        if(target==null)return;
        PlaybackState state=target.getPlaybackState();
        if(state==null||(state.getActions()&PlaybackState.ACTION_SEEK_TO)==0){
            noteCommand("탐색(Seek) 미지원 · 미전송");
            return;
        }
        long position=state.getPosition();
        if(position<0){
            noteCommand("재생 위치 미제공 · 탐색 미전송");
            return;
        }
        long to=Math.max(0,position+offset);
        MediaMetadata metadata=target.getMetadata();
        long duration=metadata==null?-1:
            metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if(duration>0)to=Math.min(to,Math.max(0,duration-500));
        String before=observed(target);
        try{
            target.getTransportControls().seekTo(to);
            sent(target,"탐색 "+(offset>=0?"+":"")+(offset/1000)+"초",before);
        }catch(Exception e){
            noteCommand("탐색 전송 오류: "+e.getClass().getSimpleName());
        }
    }

    private void sendCustom(String label,String actionId){
        MediaController target=commandTarget();
        if(target==null)return;
        PlaybackState state=target.getPlaybackState();
        boolean exposed=false;
        if(state!=null && state.getCustomActions()!=null){
            for(PlaybackState.CustomAction action:state.getCustomActions()){
                if(actionId.equals(action.getAction())){exposed=true;break;}
            }
        }
        if(!exposed){
            noteCommand(label+" → Custom Action 미노출 · 명령 미전송");
            return;
        }
        String before=observed(target);
        try{
            target.getTransportControls().sendCustomAction(actionId,Bundle.EMPTY);
            sent(target,label+" ["+actionId+"]",before);
        }catch(Exception e){
            noteCommand(label+" Custom Action 전송 오류: "+e.getClass().getSimpleName());
        }
    }

    private void sendThumbRating(boolean up){
        MediaController target=commandTarget();
        if(target==null)return;
        PlaybackState state=target.getPlaybackState();
        if(state==null ||
           (state.getActions()&PlaybackState.ACTION_SET_RATING)==0 ||
           target.getRatingType()!=Rating.RATING_THUMB_UP_DOWN){
            noteCommand("SetRating → 사용 가능한 THUMB_UP_DOWN 인터페이스 없음 · 미전송");
            return;
        }
        String before=observed(target);
        try{
            target.getTransportControls().setRating(Rating.newThumbRating(up));
            sent(target,"SetRating "+(up?"좋아요":"싫어요"),before);
        }catch(Exception e){
            noteCommand("SetRating 오류: "+e.getClass().getSimpleName());
        }
    }

    private void sendSearch(){
        String query=songQueryInput==null?"":songQueryInput.getText().toString().trim();
        if(query.isEmpty()){
            noteCommand("검색어를 입력하세요");
            return;
        }
        MediaController target=commandTarget();
        if(target==null)return;
        PlaybackState state=target.getPlaybackState();
        if(state==null||
           (state.getActions()&PlaybackState.ACTION_PLAY_FROM_SEARCH)==0){
            noteCommand("PlayFromSearch 미지원 · 미전송");
            return;
        }
        String before=observed(target);
        try{
            target.getTransportControls().playFromSearch(query,Bundle.EMPTY);
            sent(target,"곡명 검색 ["+query+"]",before);
        }catch(Exception e){
            noteCommand("PlayFromSearch 오류: "+e.getClass().getSimpleName());
        }
    }

    private boolean permitted(){
        try{
            NotificationManager notifications=(NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
            return notifications!=null &&
                notifications.isNotificationListenerAccessGranted(listenerComponent);
        }catch(Exception ignored){return false;}
    }

    private void addSessionsListener(){
        if(activeListenerRegistered||manager==null) return;
        try{
            manager.addOnActiveSessionsChangedListener(sessionsChanged,listenerComponent,handler);
            activeListenerRegistered=true;
        }catch(SecurityException e){
            log("세션 변경 리스너 접근 실패: "+e.getClass().getSimpleName());
        }
    }

    private void removeSessionsListener(){
        if(!activeListenerRegistered||manager==null) return;
        try{manager.removeOnActiveSessionsChangedListener(sessionsChanged);}
        catch(Exception ignored){}
        activeListenerRegistered=false;
    }

    private void detach(){
        if(selected==null) return;
        try{selected.unregisterCallback(selectedCallback);}catch(Exception ignored){}
        selected=null;
    }

    private void attach(MediaController found){
        if(found==null){detach();return;}
        if(selected!=null){
            try{
                if(selected.getSessionToken().equals(found.getSessionToken())) return;
            }catch(Exception ignored){}
        }
        detach();
        selected=found;
        try{selected.registerCallback(selectedCallback,handler);}
        catch(Exception e){log("YT Music 세션 콜백 오류: "+e.getClass().getSimpleName());}
    }

    private static String safe(String value){
        return value==null||value.trim().isEmpty()?"(정보 없음)":value.trim();
    }

    private static String stateName(int state){
        switch(state){
            case PlaybackState.STATE_PLAYING:return "재생 중";
            case PlaybackState.STATE_PAUSED:return "일시정지";
            case PlaybackState.STATE_STOPPED:return "정지";
            case PlaybackState.STATE_BUFFERING:return "버퍼링";
            case PlaybackState.STATE_CONNECTING:return "연결 중";
            case PlaybackState.STATE_SKIPPING_TO_NEXT:return "다음 곡 이동 중";
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:return "이전 곡 이동 중";
            case PlaybackState.STATE_FAST_FORWARDING:return "빨리 감기";
            case PlaybackState.STATE_REWINDING:return "되감기";
            case PlaybackState.STATE_ERROR:return "오류";
            default:return "상태 코드 "+state;
        }
    }

    private static String ratingName(int rating){
        switch(rating){
            case Rating.RATING_HEART:return "HEART (좋아요 후보)";
            case Rating.RATING_THUMB_UP_DOWN:return "THUMB_UP_DOWN (좋아요/싫어요 후보)";
            case Rating.RATING_3_STARS:return "3_STARS";
            case Rating.RATING_4_STARS:return "4_STARS";
            case Rating.RATING_5_STARS:return "5_STARS";
            case Rating.RATING_PERCENTAGE:return "PERCENTAGE";
            case Rating.RATING_NONE:return "NONE";
            default:return "형식 코드 "+rating;
        }
    }

    private static void flag(List<String> names,long actions,long mask,String title){
        names.add(title+": "+((actions&mask)!=0?"지원 표시":"미지원 표시"));
    }

    private static String actionDetails(long actions){
        List<String> x=new ArrayList<>();
        flag(x,actions,PlaybackState.ACTION_PLAY,"재생");
        flag(x,actions,PlaybackState.ACTION_PAUSE,"일시정지");
        flag(x,actions,PlaybackState.ACTION_SKIP_TO_PREVIOUS,"이전 곡");
        flag(x,actions,PlaybackState.ACTION_SKIP_TO_NEXT,"다음 곡");
        flag(x,actions,PlaybackState.ACTION_SEEK_TO,"탐색(Seek)");
        flag(x,actions,PlaybackState.ACTION_PLAY_FROM_SEARCH,"곡 검색 PlayFromSearch");
        flag(x,actions,PlaybackState.ACTION_SET_RATING,"평가 SetRating");
        x.add("반복·셔플: 기본 PlaybackState에는 지원 플래그가 없어 별도 검증 필요");
        x.add("원본 actions 비트값: 0x"+Long.toHexString(actions));
        return android.text.TextUtils.join("\n",x);
    }

    private static String customActions(PlaybackState state){
        if(state==null||state.getCustomActions()==null||
            state.getCustomActions().isEmpty()) return "Custom actions: 없음";
        List<String> custom=new ArrayList<>();
        for(PlaybackState.CustomAction action:state.getCustomActions()){
            custom.add(safe(String.valueOf(action.getName()))+
                "\n  ID: "+action.getAction());
        }
        return "Custom actions ("+custom.size()+"):\n"+
            android.text.TextUtils.join("\n",custom);
    }

    private void scan(boolean manual){
        boolean granted=permitted();
        permissionText.setText(granted
            ?"허용됨 · MediaSessionManager 조회 가능"
            :"미허용 · 위 버튼에서 Polaris YT Music 세션 읽기를 허용하세요");
        permissionText.setTextColor(granted?Color.rgb(135,225,182):Color.rgb(255,184,112));
        String permState=granted?"granted":"denied";
        if(!permState.equals(lastPermissionState)){
            lastPermissionState=permState;
            log("알림 접근 권한: "+(granted?"허용":"미허용"));
        }

        if(!granted){
            removeSessionsListener();
            detach();
            titleText.setText("알림 접근 권한 필요");
            artistText.setText("YouTube Music을 실행한 뒤 권한을 허용하세요");
            albumText.setText("");
            artwork.setImageDrawable(null);
            playbackText.setText("대기 중");
            sessionText.setText("활성 세션을 읽으려면 Android 알림 접근 권한이 필요합니다.");
            actionText.setText("권한 허용 후 조회할 수 있습니다.");
            ratingText.setText("평가 방식: 알 수 없음");
            customText.setText("Custom actions: 없음");
            latestDiagnostic="권한 미허용";
            return;
        }

        if(watching) addSessionsListener();

        List<MediaController> active;
        try{
            active=manager.getActiveSessions(listenerComponent);
            if(active==null) active=new ArrayList<>();
        }catch(Exception e){
            sessionText.setText("세션 조회 오류: "+e.getClass().getSimpleName()+
                (e.getMessage()==null?"":" · "+e.getMessage()));
            if(manual) log("MediaSessionManager 조회 오류: "+e);
            return;
        }
        MediaController match=null;
        int best=Integer.MIN_VALUE;
        ArrayList<String> packages=new ArrayList<>();
        for(MediaController c:active){
            String pkg;
            try{pkg=c.getPackageName();}catch(Exception e){continue;}
            if(pkg==null||pkg.equals(getPackageName()))continue;
            PlaybackState st=null;
            try{st=c.getPlaybackState();}catch(Exception ignored){}
            int state=st==null?PlaybackState.STATE_NONE:st.getState();
            packages.add(pkg+" ["+stateName(state)+"]");
            if(!YTM.equals(pkg))continue;
            int score=state==PlaybackState.STATE_PLAYING?5000:
                state==PlaybackState.STATE_BUFFERING?4000:
                state==PlaybackState.STATE_CONNECTING?3900:
                state==PlaybackState.STATE_PAUSED?3000:1000;
            if(score>best){best=score;match=c;}
        }

        String packageText=packages.isEmpty()?"없음":
            android.text.TextUtils.join("\n",packages);
        sessionText.setText("감지된 외부 미디어 세션: "+packages.size()+
            "\n"+packageText);

        if(match==null){
            if(selected!=null &&
               SystemClock.elapsedRealtime()-lastFoundAt<SESSION_GRACE_MS){
                playbackText.setText("세션 전환 중 · 800ms 유예");
                return;
            }
            detach();
            titleText.setText("YouTube Music 세션 없음");
            artistText.setText("YT Music에서 먼저 곡을 재생하거나 일시정지해 보세요");
            albumText.setText("");
            artwork.setImageDrawable(null);
            playbackText.setText("미감지");
            actionText.setText("활성 YT Music 세션 없음");
            ratingText.setText("평가 방식: 알 수 없음");
            customText.setText("Custom actions: 없음");
            latestDiagnostic="YT Music 세션 없음\n모든 활성 세션:\n"+packageText;
            if(!"absent".equals(lastSnapshot)){
                lastSnapshot="absent";
                log("YT Music 세션 없음 · 외부 세션 "+packages.size()+"개");
            }
            return;
        }

        lastFoundAt=SystemClock.elapsedRealtime();
        attach(match);
        MediaMetadata metadata=null;
        PlaybackState state=null;
        int rating=Rating.RATING_NONE;
        try{
            metadata=match.getMetadata();
            state=match.getPlaybackState();
            rating=match.getRatingType();
        }catch(Exception e){log("세션 읽기 오류: "+e.getClass().getSimpleName());}

        String title=metadata==null?"":metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        if((title==null||title.isEmpty())&&metadata!=null)
            title=metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist=metadata==null?"":metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String album=metadata==null?"":metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration=metadata==null?0:metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String stateLabel=state==null?"상태 없음":stateName(state.getState());
        long actions=state==null?0L:state.getActions();
        String actionDump=actionDetails(actions);
        String customDump=customActions(state);
        titleText.setText(safe(title));
        artistText.setText("아티스트 · "+safe(artist));
        albumText.setText("앨범 · "+safe(album));
        playbackText.setText(stateLabel+
            " · "+(duration>0?(duration/1000)+"초":"재생시간 미제공"));
        actionText.setText(actionDump);
        ratingText.setText("Rating type: "+ratingName(rating)+
            "\n※ 지원 표시만으로 YT Music 계정 반영이 확인되지는 않습니다.");
        customText.setText(customDump);

        if(metadata!=null){
            Bitmap picture=metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if(picture==null) picture=metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if(picture==null) picture=metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            artwork.setImageBitmap(picture);
        }else artwork.setImageDrawable(null);

        latestDiagnostic="YT Music 패키지: "+YTM+
            "\n곡: "+safe(title)+
            "\n아티스트: "+safe(artist)+
            "\n앨범: "+safe(album)+
            "\n재생: "+stateLabel+
            "\n시간(ms): "+duration+
            "\n"+actionDump+
            "\nRating: "+ratingName(rating)+
            "\n"+customDump+
            "\n전체 활성 세션 ("+packages.size()+"):\n"+packageText;

        String signature=safe(title)+"|"+safe(artist)+"|"+stateLabel+
            "|"+actions+"|"+rating+"|"+customDump+"|"+packages.size();
        if(!signature.equals(lastSnapshot)){
            lastSnapshot=signature;
            log("YT Music · "+safe(title)+" / "+stateLabel+
                " / actions 0x"+Long.toHexString(actions)+
                " / rating "+ratingName(rating));
        }else if(manual) log("수동 갱신 · YT Music 세션 조회 성공");
    }

    private void log(String message){
        String time=new SimpleDateFormat("MM-dd HH:mm:ss",Locale.KOREA).format(new Date());
        SharedPreferences prefs=getSharedPreferences(STORE,MODE_PRIVATE);
        String history=prefs.getString(HISTORY,"");
        history+=time+"  "+message+"\n";
        if(history.length()>14000) history=history.substring(history.length()-14000);
        prefs.edit().putString(HISTORY,history).apply();
        showLog();
    }

    private void showLog(){
        if(logText==null)return;
        String history=getSharedPreferences(STORE,MODE_PRIVATE).getString(HISTORY,"");
        String[] rows=history.split("\n");
        int start=Math.max(0,rows.length-14);
        StringBuilder visible=new StringBuilder();
        for(int i=start;i<rows.length;i++) if(!rows[i].isEmpty())
            visible.append(rows[i]).append('\n');
        logText.setText(visible.length()==0?"아직 기록 없음":visible.toString());
    }

    private void copyDiagnostics(){
        String history=getSharedPreferences(STORE,MODE_PRIVATE).getString(HISTORY,"");
        String payload="Polaris YTM Inspector v1.a02\nAndroid API "+Build.VERSION.SDK_INT+
            "\n\n"+latestDiagnostic+"\n\n세션 변경 로그:\n"+history;
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard==null)return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Polaris YTM diagnostic",payload));
        Toast.makeText(this,"진단 결과를 복사했습니다",Toast.LENGTH_SHORT).show();
    }
}
