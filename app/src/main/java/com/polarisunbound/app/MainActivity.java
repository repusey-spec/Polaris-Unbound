package com.polarisunbound.app;

import android.Manifest;
import android.content.*;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.widget.*;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.util.Base64;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import android.graphics.drawable.GradientDrawable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.os.Handler;
import android.os.Looper;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

public class MainActivity extends AppCompatActivity {
    private LinearLayout body;
    private MediaBrowserCompat browser;
    private MediaControllerCompat controller;
    private TextView status;
    private LinearLayout tabsBar;
    private FrameLayout pageHost;
    private TextView scheduleView;
    private LinearLayout miniPlayer;
    private ImageView miniPlayerArt;
    private TextView miniPlayerTitle;
    private TextView miniPlayerSubtitle;
    private TextView miniPlayerToggle;
    private ImageView nowPlayerArt;
    private TextView nowPlayerTitle;
    private TextView nowPlayerArtist;
    private TextView nowPlayerLike;
    private TextView nowPlayerDislike;
    private TextView nowPlayerShuffle;
    private TextView nowPlayerRepeat;
    private TextView nowPlayerPlayPause;
    private MediaMetadataCompat lastMetadata;
    private PlaybackStateCompat lastPlaybackState;
    private SeekBar mp3Progress;
    private TextView mp3Elapsed;
    private TextView mp3Duration;
    private boolean mp3UserSeeking=false;
    private EditText voiceSearchTarget;
    private static final int REQ_VOICE_SEARCH=881;
    private final Handler progressHandler=new Handler(Looper.getMainLooper());
    private final Runnable progressTick=new Runnable(){
        @Override public void run(){
            updateMp3Progress();
            progressHandler.postDelayed(this,1000L);
        }
    };
    private final Handler scheduleHandler=new Handler(Looper.getMainLooper());
    private final ExecutorService scheduleExecutor=Executors.newSingleThreadExecutor();
    private final ExecutorService ftpExecutor=Executors.newSingleThreadExecutor();
    private String selectedRadioId=null;
    private String currentPage="home";
    private String currentFtpMp3Path="";
    private int currentFtpMp3Offset=0;
    private boolean returnToFavoritesFromPlayer=false;
    private static final String[] RADIO_NAMES={"89.1 KBS CoolFM","91.9 MBC FM4U","93.9 CBS MusicFM","95.9 MBC 표준FM","102.7 AFN EagleFM","107.7 SBS PowerFM"};
    private int presetStation(int slot){ return getSharedPreferences("radio_presets",MODE_PRIVATE).getInt("slot"+slot,slot); }
    private void choosePreset(int slot){ new androidx.appcompat.app.AlertDialog.Builder(this).setTitle((slot+1)+"번 프리셋에 저장").setItems(RADIO_NAMES,(d,which)->{ getSharedPreferences("radio_presets",MODE_PRIVATE).edit().putInt("slot"+slot,which).apply(); showDomestic(); Toast.makeText(this,(slot+1)+"번 → "+RADIO_NAMES[which],Toast.LENGTH_SHORT).show(); }).show(); }


    private final class PlayerSwipeScrollView extends ScrollView {
        private float downX;
        private float downY;
        private boolean gestureBlocked;

        PlayerSwipeScrollView(Context context){
            super(context);
            setFillViewport(false);
            setOverScrollMode(View.OVER_SCROLL_NEVER);
        }

        @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
            final int action=event.getActionMasked();

            if(action==android.view.MotionEvent.ACTION_DOWN){
                downX=event.getRawX();
                downY=event.getRawY();
                gestureBlocked=isRawPointInsideView(downX,downY,mp3Progress);
            }

            boolean handled=super.dispatchTouchEvent(event);

            if(!"mp3_now".equals(currentPage)) return handled;

            if(action==android.view.MotionEvent.ACTION_MOVE && !gestureBlocked){
                float dx=event.getRawX()-downX;
                float dy=event.getRawY()-downY;
                float ax=Math.abs(dx);
                float ay=Math.abs(dy);

                if(ax>dp(14) && ax>ay*1.25f){
                    setTranslationX(Math.max(-dp(110),Math.min(dp(110),dx*0.30f)));
                    setTranslationY(0f);
                }else if(dy>dp(14) && ay>ax*1.25f && getScrollY()<=dp(2)){
                    setTranslationY(Math.min(dp(100),dy*0.24f));
                    setTranslationX(0f);
                }
            }

            if(action==android.view.MotionEvent.ACTION_UP ||
               action==android.view.MotionEvent.ACTION_CANCEL){
                float dx=event.getRawX()-downX;
                float dy=event.getRawY()-downY;
                float ax=Math.abs(dx);
                float ay=Math.abs(dy);

                if(action==android.view.MotionEvent.ACTION_UP && !gestureBlocked){
                    if(ax>=dp(88) && ax>ay*1.25f){
                        if(dx<0) finishHorizontalPlayerSwipe(this,1);
                        else finishHorizontalPlayerSwipe(this,-1);
                        return handled;
                    }
                    if(dy>=dp(110) && ay>ax*1.25f && getScrollY()<=dp(2)){
                        finishDownPlayerSwipe(this);
                        return handled;
                    }
                }

                animate().translationX(0f).translationY(0f).alpha(1f)
                    .setDuration(120L).start();
            }

            return handled;
        }
    }

    private boolean isRawPointInsideView(float rawX,float rawY,View view){
        if(view==null||view.getVisibility()!=View.VISIBLE) return false;
        int[] location=new int[2];
        view.getLocationOnScreen(location);
        return rawX>=location[0] && rawX<=location[0]+view.getWidth() &&
            rawY>=location[1] && rawY<=location[1]+view.getHeight();
    }

    private void finishHorizontalPlayerSwipe(View playerView,int direction){
        float target=direction>0 ? -dp(120) : dp(120);
        playerView.animate()
            .translationX(target)
            .alpha(0.62f)
            .setDuration(105L)
            .withEndAction(()->{
                if(controller!=null){
                    if(direction>0) controller.getTransportControls().skipToNext();
                    else controller.getTransportControls().skipToPrevious();
                }
                playerView.setTranslationX(-target*0.35f);
                playerView.setAlpha(0.72f);
                playerView.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(150L)
                    .start();
            })
            .start();
    }

    private void finishDownPlayerSwipe(View playerView){
        playerView.animate()
            .translationY(Math.max(dp(150),playerView.getHeight()*0.22f))
            .alpha(0.55f)
            .setDuration(130L)
            .withEndAction(this::returnFromNowPlaying)
            .start();
    }

    private void returnFromNowPlaying(){
        if(returnToFavoritesFromPlayer){
            showMp3Favorites();
            return;
        }
        MediaMetadataCompat m=lastMetadata;
        if(m==null&&controller!=null) m=controller.getMetadata();
        String mediaId=m==null?null:m.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
        if(mediaId!=null&&mediaId.startsWith("ftpmp3:"))
            showMp3FtpDirectory(currentFtpMp3Path,currentFtpMp3Offset);
        else
            showMp3();
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        browser=new MediaBrowserCompat(this,new ComponentName(this,PolarisMediaService.class),
            new MediaBrowserCompat.ConnectionCallback(){
                @Override public void onConnected(){
                    try{
                        controller=new MediaControllerCompat(MainActivity.this,browser.getSessionToken());
                        MediaControllerCompat.setMediaController(MainActivity.this,controller);
                        controller.registerCallback(new MediaControllerCompat.Callback(){
                            @Override public void onPlaybackStateChanged(PlaybackStateCompat s){
                                lastPlaybackState=s;
                                updateStatus(s);
                                updateMiniPlayerState();
                                updateNowPlayingState();
                                updateNowPlayingModes();
                                updateMp3Progress();
                            }
                            @Override public void onMetadataChanged(MediaMetadataCompat metadata){
                                lastMetadata=metadata;
                                updateMiniPlayerMetadata();
                                updateNowPlayingMetadata();
                                updateNowPlayingModes();
                                updateMp3Progress();
                            }
                        });
                        lastMetadata=controller.getMetadata();
                        lastPlaybackState=controller.getPlaybackState();
                        updateMiniPlayerMetadata();
                        updateMiniPlayerState();
                        if(status!=null) status.setText("재생 서비스 연결됨");
                    }catch(Exception e){ if(status!=null) status.setText("서비스 연결 오류"); }
                }
            },null);
        browser.connect();
        showDomestic();
        requestAudioPermission();
    }

    private void requestAudioPermission(){
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.READ_MEDIA_AUDIO},7);
    }

    private TextView button(String s){
        TextView v=new TextView(this); v.setText(s); v.setTextSize(21); v.setGravity(17);
        v.setPadding(14,32,14,32);
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(0xE6FFFFFF);
        bg.setCornerRadius(24f);
        bg.setStroke(1,0x55FFFFFF);
        v.setTextColor(Color.rgb(35,35,35));
        v.setBackground(bg);
        return v;
    }

    private TextView stopButton(){
        TextView v=new TextView(this);
        v.setText("■  정지");
        v.setTextSize(16);
        v.setGravity(17);
        v.setPadding(24,18,24,18);
        v.setTextColor(Color.WHITE);

        GradientDrawable bg=new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xE62A2A2F,0xE64A2027});
        bg.setCornerRadius(48f);
        bg.setStroke(2,0x66FFFFFF);
        v.setBackground(bg);
        if(android.os.Build.VERSION.SDK_INT>=21) v.setElevation(7f);
        return v;
    }

    private ImageButton stationButton(String id,String description){
        ImageButton v=new ImageButton(this);
        v.setImageBitmap(StationArt.bitmap(this,id,512));
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAdjustViewBounds(true);
        v.setPadding(0,0,0,0);
        v.setBackgroundColor(Color.TRANSPARENT);
        v.setContentDescription(description);
        return v;
    }

    private TextView tab(String text,boolean active){
        TextView v=new TextView(this); v.setText(text); v.setTextSize(15); v.setGravity(17); v.setPadding(8,24,8,24);
        GradientDrawable bg=new GradientDrawable();
        bg.setCornerRadii(new float[]{18,18,18,18,0,0,0,0});
        bg.setColor(active ? Color.rgb(70,130,180) : Color.rgb(205,205,205));
        v.setTextColor(active ? Color.WHITE : Color.DKGRAY); v.setBackground(bg); return v;
    }

    private Bitmap loadBackgroundBitmap(){
        try(InputStream in=getResources().openRawResource(R.raw.polaris_background);
            ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[4096]; int n;
            while((n=in.read(buf))!=-1) out.write(buf,0,n);
            String encoded=out.toString("US-ASCII").replaceAll("\\s","");
            byte[] jpg=Base64.decode(encoded,Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(jpg,0,jpg.length);
        }catch(Exception e){ return null; }
    }

    private void ensureShell(){
        if(tabsBar!=null && pageHost!=null) return;
        FrameLayout shell=new FrameLayout(this);
        ImageView background=new ImageView(this);
        Bitmap bg=loadBackgroundBitmap();
        if(bg!=null) background.setImageBitmap(bg);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        shell.addView(background,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(this); shade.setBackgroundColor(0x66000000);
        shell.addView(shade,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            int top=insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int bottom=insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(0,top,0,bottom+dp(8));
            return insets;
        });
        tabsBar=new LinearLayout(this); tabsBar.setOrientation(LinearLayout.HORIZONTAL); tabsBar.setPadding(12,12,12,0);
        root.addView(tabsBar,new LinearLayout.LayoutParams(-1,-2));
        pageHost=new FrameLayout(this); root.addView(pageHost,new LinearLayout.LayoutParams(-1,0,1));
        miniPlayer=buildMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        LinearLayout.LayoutParams miniLp=new LinearLayout.LayoutParams(-1,-2);
        miniLp.setMargins(dp(12),dp(4),dp(12),dp(8));
        root.addView(miniPlayer,miniLp);
        shell.addView(root,new FrameLayout.LayoutParams(-1,-1));
        setContentView(shell);
    }

    private void addTab(TextView v){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
        lp.setMargins(6,0,6,0); tabsBar.addView(v,lp);
    }

    private void refreshTabs(){
        tabsBar.removeAllViews();
        TextView t1=tab("라디오","radio".equals(currentPage));
        TextView t2=tab("MP3","mp3".equals(currentPage));
        TextView t3=tab("CAN","can".equals(currentPage));
        addTab(t1); addTab(t2); addTab(t3);
        t1.setOnClickListener(v->showDomestic());
        t2.setOnClickListener(v->showMp3());
        t3.setOnClickListener(v->showCan());
    }

    private void base(String title){
        ensureShell();
        tabsBar.setVisibility(View.VISIBLE);
        refreshTabs();
        pageHost.removeAllViews();
        ScrollView sc=new ScrollView(this); body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(24,18,24,24);
        TextView h=new TextView(this); h.setText(title); h.setTextSize(28); h.setTextColor(Color.WHITE); h.setPadding(0,8,0,12); body.addView(h);
        if("mp3".equals(currentPage)||"can".equals(currentPage)){
            status=null;
            progressHandler.removeCallbacks(progressTick);
            mp3Progress=null;
            mp3Elapsed=null;
            mp3Duration=null;
        }else{
            progressHandler.removeCallbacks(progressTick);
            mp3Progress=null;
            mp3Elapsed=null;
            mp3Duration=null;
            status=new TextView(this);
            status.setText(controller==null?"재생 서비스 연결 중…":"재생 준비");
            status.setTextSize(16);
            status.setTextColor(0xDDFFFFFF);
            status.setPadding(0,0,0,12);
            body.addView(status);
            TextView stop=stopButton();
            stop.setOnClickListener(v->{ if(controller!=null) controller.getTransportControls().stop(); });
            LinearLayout.LayoutParams stopLp=new LinearLayout.LayoutParams(-1,-2);
            stopLp.setMargins(0,2,0,16);
            body.addView(stop,stopLp);
        }
        updateMiniPlayerVisibility();
        sc.addView(body); pageHost.addView(sc,new FrameLayout.LayoutParams(-1,-1));
    }
    private void playId(String id,String label){
        if(controller==null){ Toast.makeText(this,"재생 서비스 연결 중입니다",Toast.LENGTH_SHORT).show(); return; }
        if(status!=null) status.setText(label+" 연결 중…");
        if(id!=null && (id.startsWith("mp3:")||id.startsWith("ftpmp3:")||
                       id.startsWith("favlocal:")||id.startsWith("favftp:"))){
            selectedRadioId=null;
            returnToFavoritesFromPlayer=id.startsWith("favlocal:")||id.startsWith("favftp:");
            scheduleHandler.removeCallbacksAndMessages(null);
        }else{
            selectedRadioId=id;
            scheduleHandler.removeCallbacksAndMessages(null);
            refreshSchedule(id);
        }
        try{ controller.getTransportControls().playFromMediaId(id,null); }
        catch(Throwable e){ if(status!=null) status.setText("재생 요청 오류"); }
    }

    private void updateStatus(PlaybackStateCompat s){
        if(s==null) return;
        int st=s.getState();
        if(status!=null){
            if(st==PlaybackStateCompat.STATE_PLAYING) status.setText("▶ 재생 중");
            else if(st==PlaybackStateCompat.STATE_BUFFERING) status.setText("연결 중…");
            else if(st==PlaybackStateCompat.STATE_PAUSED) status.setText("일시정지");
            else if(st==PlaybackStateCompat.STATE_ERROR) status.setText("재생 오류: "+s.getErrorMessage());
            else if(st==PlaybackStateCompat.STATE_STOPPED) status.setText("정지");
        }
        lastPlaybackState=s;
        updateMiniPlayerState();
    }

    private void addMp3ProgressLine(){
        LinearLayout wrap=new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0,0,0,dp(8));

        mp3Progress=new SeekBar(this);
        mp3Progress.setMax(1000);
        mp3Progress.setProgress(0);
        mp3Progress.setPadding(0,0,0,0);
        mp3Progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar seekBar,int progress,boolean fromUser){
                if(fromUser && mp3UserSeeking){
                    long duration=mp3DurationMs();
                    if(duration>0 && mp3Elapsed!=null)
                        mp3Elapsed.setText(formatTime((duration*progress)/1000L));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar){ mp3UserSeeking=true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar){
                long duration=mp3DurationMs();
                if(controller!=null && duration>0){
                    long target=(duration*seekBar.getProgress())/1000L;
                    controller.getTransportControls().seekTo(target);
                }
                mp3UserSeeking=false;
                updateMp3Progress();
            }
        });
        wrap.addView(mp3Progress,new LinearLayout.LayoutParams(-1,dp(28)));

        LinearLayout times=new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        mp3Elapsed=new TextView(this);
        mp3Elapsed.setText("0:00");
        mp3Elapsed.setTextSize(11);
        mp3Elapsed.setTextColor(0x99FFFFFF);
        mp3Duration=new TextView(this);
        mp3Duration.setText("0:00");
        mp3Duration.setTextSize(11);
        mp3Duration.setGravity(android.view.Gravity.RIGHT);
        mp3Duration.setTextColor(0x99FFFFFF);
        times.addView(mp3Elapsed,new LinearLayout.LayoutParams(0,-2,1));
        times.addView(mp3Duration,new LinearLayout.LayoutParams(0,-2,1));
        wrap.addView(times,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout modes=new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setGravity(android.view.Gravity.CENTER_VERTICAL);
        modes.setPadding(0,dp(6),0,0);

        final TextView repeat=mp3ModeButton("");
        final TextView shuffle=mp3ModeButton("");
        updateMp3ModeButtons(repeat,shuffle);

        repeat.setOnClickListener(v->{
            android.content.SharedPreferences prefs=getSharedPreferences("polaris_mp3",MODE_PRIVATE);
            int mode=prefs.getInt("repeat_mode",PlaybackStateCompat.REPEAT_MODE_NONE);
            int next;
            if(mode==PlaybackStateCompat.REPEAT_MODE_NONE) next=PlaybackStateCompat.REPEAT_MODE_ALL;
            else if(mode==PlaybackStateCompat.REPEAT_MODE_ALL) next=PlaybackStateCompat.REPEAT_MODE_ONE;
            else next=PlaybackStateCompat.REPEAT_MODE_NONE;
            prefs.edit().putInt("repeat_mode",next).apply();
            if(controller!=null) controller.getTransportControls().setRepeatMode(next);
            updateMp3ModeButtons(repeat,shuffle);
        });

        shuffle.setOnClickListener(v->{
            android.content.SharedPreferences prefs=getSharedPreferences("polaris_mp3",MODE_PRIVATE);
            boolean enabled=!prefs.getBoolean("shuffle",false);
            prefs.edit().putBoolean("shuffle",enabled).apply();
            if(controller!=null) controller.getTransportControls().setShuffleMode(
                enabled ? PlaybackStateCompat.SHUFFLE_MODE_ALL : PlaybackStateCompat.SHUFFLE_MODE_NONE);
            updateMp3ModeButtons(repeat,shuffle);
        });

        LinearLayout.LayoutParams modeLp=new LinearLayout.LayoutParams(0,dp(38),1);
        modeLp.setMargins(0,0,dp(6),0);
        modes.addView(repeat,modeLp);
        LinearLayout.LayoutParams shuffleLp=new LinearLayout.LayoutParams(0,dp(38),1);
        shuffleLp.setMargins(dp(6),0,0,0);
        modes.addView(shuffle,shuffleLp);
        wrap.addView(modes,new LinearLayout.LayoutParams(-1,-2));

        body.addView(wrap,new LinearLayout.LayoutParams(-1,-2));
        progressHandler.removeCallbacks(progressTick);
        progressHandler.post(progressTick);
        updateMp3Progress();
    }

    private TextView mp3ModeButton(String text){
        TextView v=new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setGravity(17);
        v.setTextColor(Color.WHITE);
        v.setBackground(roundedBg(0x88303038,18));
        return v;
    }

    private void updateMp3ModeButtons(TextView repeat,TextView shuffle){
        android.content.SharedPreferences prefs=getSharedPreferences("polaris_mp3",MODE_PRIVATE);
        int mode=prefs.getInt("repeat_mode",PlaybackStateCompat.REPEAT_MODE_NONE);
        boolean shuffled=prefs.getBoolean("shuffle",false);

        if(mode==PlaybackStateCompat.REPEAT_MODE_ALL) repeat.setText("↻  전체 반복");
        else if(mode==PlaybackStateCompat.REPEAT_MODE_ONE) repeat.setText("↻  1곡 반복");
        else repeat.setText("↻  반복 끔");

        shuffle.setText(shuffled?"🔀  랜덤 켬":"🔀  랜덤 끔");
        repeat.setAlpha(mode==PlaybackStateCompat.REPEAT_MODE_NONE?0.65f:1f);
        shuffle.setAlpha(shuffled?1f:0.65f);
    }

    private long mp3DurationMs(){
        MediaMetadataCompat m=lastMetadata;
        if(m==null && controller!=null) m=controller.getMetadata();
        if(!isMp3Metadata(m)) return 0L;
        return Math.max(0L,m.getLong(MediaMetadataCompat.METADATA_KEY_DURATION));
    }

    private long mp3PositionMs(){
        PlaybackStateCompat st=lastPlaybackState;
        if(st==null && controller!=null) st=controller.getPlaybackState();
        if(st==null) return 0L;
        long pos=Math.max(0L,st.getPosition());
        if(st.getState()==PlaybackStateCompat.STATE_PLAYING){
            long elapsed=android.os.SystemClock.elapsedRealtime()-st.getLastPositionUpdateTime();
            if(elapsed>0) pos+=(long)(elapsed*st.getPlaybackSpeed());
        }
        long duration=mp3DurationMs();
        if(duration>0) pos=Math.min(pos,duration);
        return pos;
    }

    private void updateMp3Progress(){
        if(!"mp3_now".equals(currentPage) || mp3Progress==null) return;
        long duration=mp3DurationMs();
        long position=mp3PositionMs();
        if(!mp3UserSeeking){
            int progress=duration>0?(int)Math.min(1000L,(position*1000L)/duration):0;
            mp3Progress.setProgress(progress);
            if(mp3Elapsed!=null) mp3Elapsed.setText(formatTime(position));
        }
        if(mp3Duration!=null) mp3Duration.setText(formatTime(duration));
        mp3Progress.setEnabled(duration>0 && isMp3Metadata(lastMetadata));
    }

    private String formatTime(long ms){
        long total=Math.max(0L,ms)/1000L;
        long min=total/60L;
        long sec=total%60L;
        return String.format(Locale.US,"%d:%02d",min,sec);
    }

    private void showHome(){
        currentPage="home"; base("Polaris Unbound");
        String[] names={"국내라디오","해외라디오","MP3"};
        for(String s:names){
            TextView v=button(s); body.addView(v);
            if(s.equals("국내라디오")) v.setOnClickListener(x->showDomestic());
            else if(s.equals("해외라디오")) v.setOnClickListener(x->showForeign());
            else v.setOnClickListener(x->showMp3());
        }
    }

    private void showDomestic(){
        selectedRadioId=null;
        currentPage="radio"; base("라디오");
        LinearLayout row=null;
        for(int i=0;i<8;i++){
            if(i%4==0){
                row=new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                body.addView(row,new LinearLayout.LayoutParams(-1,-2));
            }
            final int slot=i;
            final String id;
            final String label;
            if(i<6){
                int station=presetStation(i);
                id="kr"+(station+1);
                label=RADIO_NAMES[station];
            }else if(i==6){
                id="kiis"; label="102.7 KIIS-FM";
            }else{
                id="gallery"; label="Jazz from Gallery 41";
            }
            ImageButton v=stationButton(id,(slot+1)+"번 "+label);
            v.setOnClickListener(x->playId(id,label));
            if(i<6){
                v.setOnLongClickListener(x->{ choosePreset(slot); return true; });
            }
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,210,1);
            bp.setMargins(10,10,10,10);
            row.addView(v,bp);
        }
        addSchedulePanel();
    }


    private void probeRadioGarden(){
        final TextView target=scheduleView;
        if(target==null) return;
        target.setText("Radio Garden / Live365 헤더 진단 중…");
        scheduleExecutor.execute(()->{
            String[] urls={
                "https://radio.garden/api/ara/content/listen/kWNLnJEl/channel.mp3",
                "https://streaming.live365.com/a94394"
            };
            StringBuilder report=new StringBuilder();
            for(String u:urls){
                HttpURLConnection c=null;
                try{
                    c=(HttpURLConnection)new URL(u).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(10000); c.setReadTimeout(10000);
                    c.setRequestProperty("User-Agent","Radio Garden Android");
                    c.setRequestProperty("Accept","*/*");
                    c.setRequestProperty("Icy-MetaData","1");
                    int code=c.getResponseCode();
                    report.append(u).append("\nHTTP ").append(code).append("\n");
                    for(Map.Entry<String,List<String>> e:c.getHeaderFields().entrySet()){
                        if(e.getKey()!=null) report.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                    }
                    String loc=c.getHeaderField("Location");
                    if(loc!=null){
                        HttpURLConnection d=(HttpURLConnection)new URL(loc).openConnection();
                        d.setInstanceFollowRedirects(false);
                        d.setConnectTimeout(10000); d.setReadTimeout(10000);
                        d.setRequestProperty("User-Agent","Radio Garden Android");
                        d.setRequestProperty("Referer","https://radio.garden/");
                        d.setRequestProperty("Origin","https://radio.garden");
                        d.setRequestProperty("Accept","*/*");
                        d.setRequestProperty("Icy-MetaData","1");
                        int dc=d.getResponseCode();
                        report.append("FOLLOW -> ").append(loc).append("\nHTTP ").append(dc).append("\n");
                        for(Map.Entry<String,List<String>> e:d.getHeaderFields().entrySet()){
                            if(e.getKey()!=null) report.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                        }
                        d.disconnect();
                    }
                }catch(Exception e){ report.append("ERROR ").append(e.getClass().getSimpleName()).append(": ").append(String.valueOf(e.getMessage())).append("\n"); }
                finally{ if(c!=null)c.disconnect(); }
                report.append("\n");
            }
            final String out=report.toString();
            runOnUiThread(()->{ if(scheduleView==target) target.setText(out); });
        });
    }

    private void probeNamuWikiAll(){
        final TextView target=scheduleView;
        if(target==null) return;
        target.setText("5개 나무위키 편성표 분석 중…");
        scheduleExecutor.execute(()->{
            String[][] docs={
                {"KBS","https://namu.wiki/w/KBS%202FM?from=KBS%20Cool%20FM"},
                {"MBC FM4U","https://namu.wiki/w/MBC%20FM4U?from=MBC%20FM"},
                {"CBS","https://namu.wiki/w/CBS%20%EC%9D%8C%EC%95%85FM?from=CBS%20FM"},
                {"MBC 표준FM","https://namu.wiki/w/MBC%20%EB%9D%BC%EB%94%94%EC%98%A4"},
                {"SBS","https://namu.wiki/w/SBS%20%ED%8C%8C%EC%9B%8CFM"}
            };
            StringBuilder report=new StringBuilder();
            for(String[] d:docs){
                try{
                    HttpURLConnection c=(HttpURLConnection)new URL(d[1]).openConnection();
                    c.setConnectTimeout(10000); c.setReadTimeout(12000);
                    c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36");
                    c.setRequestProperty("Accept-Language","ko-KR,ko;q=0.9");
                    int code=c.getResponseCode();
                    ByteArrayOutputStream b=new ByteArrayOutputStream();
                    try(InputStream in=c.getInputStream()){ byte[] z=new byte[8192]; int n; while((n=in.read(z))!=-1 && b.size()<1800000)b.write(z,0,n); }
                    String h=b.toString("UTF-8");
                    String plain=h.replaceAll("(?is)<script.*?</script>"," ").replaceAll("(?is)<style.*?</style>"," ").replaceAll("(?is)<[^>]+>"," ").replace("&nbsp;"," ").replace("&amp;","&").replaceAll("\\s+"," ").trim();
                    int p=plain.indexOf("편성표");
                    String sample=p>=0?plain.substring(p,Math.min(plain.length(),p+260)):"편성표 문구 없음";
                    java.util.regex.Matcher im=java.util.regex.Pattern.compile("(?i)(?:src|data-src)=[\\\"']([^\\\"']+)").matcher(h);
                    int imgs=0; while(im.find()) imgs++;
                    report.append(d[0]).append(": HTTP ").append(code).append(" / img ").append(imgs).append("\n").append(sample).append("\n\n");
                    c.disconnect();
                }catch(Exception e){ report.append(d[0]).append(": 실패 ").append(e.getClass().getSimpleName()).append("\n\n"); }
            }
            final String out=report.toString();
            runOnUiThread(()->{ if(scheduleView==target) target.setText(out); });
        });
    }

    private void probeNamuWiki(){
        final TextView target=scheduleView;
        if(target==null) return;
        target.setText("나무위키 접속 테스트 중…");
        scheduleExecutor.execute(()->{
            String out;
            HttpURLConnection con=null;
            try{
                URL u=new URL("https://namu.wiki/w/KBS%202FM?from=KBS%20Cool%20FM");
                con=(HttpURLConnection)u.openConnection();
                con.setConnectTimeout(10000); con.setReadTimeout(10000);
                con.setInstanceFollowRedirects(true);
                con.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36");
                con.setRequestProperty("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
                con.setRequestProperty("Accept-Language","ko-KR,ko;q=0.9,en;q=0.7");
                int code=con.getResponseCode();
                InputStream in=(code>=200&&code<400)?con.getInputStream():con.getErrorStream();
                ByteArrayOutputStream b=new ByteArrayOutputStream();
                if(in!=null){ byte[] buf=new byte[8192]; int n; while((n=in.read(buf))!=-1 && b.size()<1500000) b.write(buf,0,n); in.close(); }
                String html=b.toString("UTF-8");
                java.util.regex.Matcher im=java.util.regex.Pattern.compile("(?i)<img[^>]+(?:src|data-src)=[\\\"']([^\\\"']+)").matcher(html);
                int images=0; while(im.find()) images++;
                String title="";
                java.util.regex.Matcher tm=java.util.regex.Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
                if(tm.find()) title=tm.group(1).replaceAll("<[^>]+>"," ").replaceAll("\\s+"," ").trim();
                out="나무위키 테스트: HTTP "+code+" | "+b.size()+" bytes | 이미지 "+images+"개"+(title.isEmpty()?"":" | "+title);
            }catch(Exception e){ out="나무위키 테스트 실패: "+e.getClass().getSimpleName()+" - "+String.valueOf(e.getMessage()); }
            finally{ if(con!=null) con.disconnect(); }
            final String result=out;
            runOnUiThread(()->{ if(scheduleView==target) target.setText(result); });
        });
    }

    private void probeOfficialSchedules(){
        final TextView target=scheduleView;
        if(target==null) return;
        scheduleHandler.removeCallbacksAndMessages(null);
        target.setText("공식 편성 소스 진단 중…\nKBS / MBC FM4U / CBS / MBC 표준FM / SBS");

        scheduleExecutor.execute(()->{
            String[][] probes={
                {"1 KBS CoolFM",
                 "https://static.api.kbs.co.kr/mediafactory/v1/schedule/onair_now?rtype=jsonp&channel_code=21,22,24,25&local_station_code=00&callback=getChannelInfoList",
                 "program_title"},
                {"2 MBC FM4U",
                 "https://control.imbc.com/Schedule/Radio/Time?sType=FM4U",
                 "Title"},
                {"3 CBS MusicFM",
                 "https://www2.cbs.co.kr/radio/timetable/music.asp",
                 "CBS 음악FM 93.9MHz"},
                {"4 MBC 표준FM",
                 "https://control.imbc.com/Schedule/Radio/Time?sType=FM",
                 "Title"},
                {"6 SBS PowerFM",
                 "https://www.sbs.co.kr/live/S17",
                 "POWER FM"}
            };

            StringBuilder report=new StringBuilder();
            for(String[] q:probes){
                HttpURLConnection con=null;
                try{
                    con=(HttpURLConnection)new URL(q[1]).openConnection();
                    con.setConnectTimeout(10000);
                    con.setReadTimeout(12000);
                    con.setInstanceFollowRedirects(true);
                    con.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
                    con.setRequestProperty("Accept","*/*");
                    con.setRequestProperty("Accept-Language","ko-KR,ko;q=0.9,en-US;q=0.7");
                    int code=con.getResponseCode();
                    String type=String.valueOf(con.getContentType());

                    InputStream in=(code>=200&&code<400)?con.getInputStream():con.getErrorStream();
                    ByteArrayOutputStream bout=new ByteArrayOutputStream();
                    if(in!=null){
                        byte[] buf=new byte[4096]; int n;
                        while((n=in.read(buf))!=-1 && bout.size()<180000) bout.write(buf,0,n);
                        in.close();
                    }
                    String raw=bout.toString("UTF-8");
                    String plain=raw
                        .replaceAll("(?is)<script\\b[^>]*>.*?</script>"," ")
                        .replaceAll("(?is)<style\\b[^>]*>.*?</style>"," ")
                        .replaceAll("(?is)<[^>]+>"," ")
                        .replace("&nbsp;"," ").replace("&amp;","&")
                        .replaceAll("\\s+"," ").trim();

                    boolean marker=raw.contains(q[2])||plain.contains(q[2]);
                    String sample=officialProbeSample(q[0],raw,plain);
                    report.append(q[0]).append("\n")
                        .append("HTTP ").append(code)
                        .append(" | ").append(type)
                        .append(" | marker=").append(marker?"OK":"NO").append("\n")
                        .append(sample).append("\n\n");
                }catch(Exception e){
                    report.append(q[0]).append("\nERROR ")
                        .append(e.getClass().getSimpleName()).append(": ")
                        .append(String.valueOf(e.getMessage())).append("\n\n");
                }finally{
                    if(con!=null) con.disconnect();
                }
            }
            report.append("5 AFN EagleFM\n공식 시간표 소스 미확인 → 방송국명 유지\n");

            final String out=report.toString();
            runOnUiThread(()->{ if(scheduleView==target) target.setText(out); });
        });
    }

    private String officialProbeSample(String name,String raw,String plain){
        String src=raw==null?"":raw;
        String text=plain==null?"":plain;
        String sample="";

        if(name.startsWith("1 ")){
            int p=src.indexOf("\"channel_code\":\"25\"");
            if(p<0) p=src.indexOf("program_title");
            if(p>=0) sample=src.substring(Math.max(0,p-80),Math.min(src.length(),p+520));
        }else if(name.startsWith("2 ")||name.startsWith("4 ")){
            int p=src.indexOf("\"Title\"");
            if(p<0) p=src.indexOf("StartTime");
            if(p>=0) sample=src.substring(Math.max(0,p-80),Math.min(src.length(),p+520));
        }else if(name.startsWith("3 ")){
            int p=text.indexOf("CBS 음악FM 93.9MHz");
            if(p<0) p=text.indexOf("현재");
            if(p>=0) sample=text.substring(p,Math.min(text.length(),p+520));
        }else if(name.startsWith("6 ")){
            int p=text.indexOf("POWER FM");
            if(p>=0) sample=text.substring(p,Math.min(text.length(),p+520));
        }

        if(sample.isEmpty()){
            sample=!text.isEmpty()?text:src;
            if(sample.length()>520) sample=sample.substring(0,520);
        }
        return sample.replaceAll("\\s+"," ").trim();
    }

    private void showForeign(){
        selectedRadioId=null;
        currentPage="foreign"; base("해외라디오");
        TextView v=button("102.7 KIIS-FM\nLos Angeles"); v.setOnClickListener(x->playId("kiis","102.7 KIIS-FM")); body.addView(v);
        addSchedulePanel();
    }

    private int dp(int value){
        return Math.round(value*getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundedBg(int color,float radiusDp){
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp((int)radiusDp));
        return bg;
    }

    private TextView mp3SectionTitle(String text){
        TextView v=new TextView(this);
        v.setText(text);
        v.setTextSize(21);
        v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        v.setTextColor(Color.WHITE);
        v.setPadding(2,dp(18),2,dp(10));
        return v;
    }

    private TextView mp3Shortcut(String icon,String label){
        TextView v=new TextView(this);
        v.setText(icon+"\n"+label);
        v.setTextSize(16);
        v.setGravity(17);
        v.setTextColor(Color.WHITE);
        v.setPadding(dp(8),dp(18),dp(8),dp(18));
        v.setBackground(roundedBg(0xAA24242A,18));
        if(android.os.Build.VERSION.SDK_INT>=21) v.setElevation(dp(2));
        return v;
    }

    private View artworkView(long albumId,int sizeDp){
        int size=dp(sizeDp);
        FrameLayout frame=new FrameLayout(this);
        frame.setBackground(roundedBg(0xFF303038,16));
        frame.setClipToOutline(true);

        Bitmap art=loadAlbumArt(albumId,size);
        if(art!=null){
            ImageView img=new ImageView(this);
            img.setImageBitmap(art);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(img,new FrameLayout.LayoutParams(-1,-1));
        }else{
            TextView note=new TextView(this);
            note.setText("♫");
            note.setTextSize(Math.max(28,sizeDp/3));
            note.setGravity(17);
            note.setTextColor(0xCCFFFFFF);
            frame.addView(note,new FrameLayout.LayoutParams(-1,-1));
        }
        frame.setLayoutParams(new LinearLayout.LayoutParams(size,size));
        return frame;
    }

    private Bitmap loadAlbumArt(long albumId,int targetPx){
        if(albumId<=0) return null;
        android.net.Uri uri=ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"),albumId);
        try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null) return null;
            Bitmap b=BitmapFactory.decodeStream(in);
            if(b==null) return null;
            if(b.getWidth()<=targetPx && b.getHeight()<=targetPx) return b;
            float scale=Math.min((float)targetPx/b.getWidth(),(float)targetPx/b.getHeight());
            Bitmap out=Bitmap.createScaledBitmap(b,Math.max(1,(int)(b.getWidth()*scale)),Math.max(1,(int)(b.getHeight()*scale)),true);
            if(out!=b) b.recycle();
            return out;
        }catch(Exception ignored){ return null; }
    }

    private LinearLayout buildMiniPlayer(){
        LinearLayout bar=new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14),dp(10),dp(12),dp(10));
        bar.setBackground(roundedBg(0xF2222228,18));
        if(android.os.Build.VERSION.SDK_INT>=21) bar.setElevation(dp(8));

        miniPlayerArt=new ImageView(this);
        miniPlayerArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniPlayerArt.setBackground(roundedBg(0xFF3A3A42,10));
        LinearLayout.LayoutParams artLp=new LinearLayout.LayoutParams(dp(52),dp(52));
        bar.addView(miniPlayerArt,artLp);

        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12),0,dp(8),0);
        miniPlayerTitle=new TextView(this);
        miniPlayerTitle.setText("재생 중인 곡 없음");
        miniPlayerTitle.setTextSize(15);
        miniPlayerTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        miniPlayerTitle.setTextColor(Color.WHITE);
        miniPlayerTitle.setSingleLine(true);
        miniPlayerSubtitle=new TextView(this);
        miniPlayerSubtitle.setText("");
        miniPlayerSubtitle.setTextSize(12);
        miniPlayerSubtitle.setTextColor(0xBFFFFFFF);
        miniPlayerSubtitle.setSingleLine(true);
        labels.addView(miniPlayerTitle,new LinearLayout.LayoutParams(-1,-2));
        labels.addView(miniPlayerSubtitle,new LinearLayout.LayoutParams(-1,-2));
        bar.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        miniPlayerToggle=new TextView(this);
        miniPlayerToggle.setText("▶");
        miniPlayerToggle.setTextSize(24);
        miniPlayerToggle.setGravity(17);
        miniPlayerToggle.setTextColor(Color.WHITE);
        miniPlayerToggle.setPadding(dp(12),dp(8),dp(12),dp(8));
        miniPlayerToggle.setOnClickListener(v->{
            if(controller==null) return;
            PlaybackStateCompat st=controller.getPlaybackState();
            int state=st==null?PlaybackStateCompat.STATE_NONE:st.getState();
            if(state==PlaybackStateCompat.STATE_PLAYING ||
               state==PlaybackStateCompat.STATE_BUFFERING)
                controller.getTransportControls().pause();
            else
                controller.getTransportControls().play();
        });
        bar.addView(miniPlayerToggle,new LinearLayout.LayoutParams(dp(58),-1));

        bar.setClickable(true);
        bar.setFocusable(true);
        bar.setContentDescription("현재 재생 화면 열기");
        bar.setOnClickListener(v->showNowPlaying());
        return bar;
    }

    private boolean isMp3Metadata(MediaMetadataCompat m){
        if(m==null) return false;
        String mediaId=m.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
        return mediaId!=null && (mediaId.startsWith("mp3:")||mediaId.startsWith("ftpmp3:"));
    }

    private void updateMiniPlayerVisibility(){
        if(miniPlayer==null) return;
        miniPlayer.setVisibility("mp3".equals(currentPage) && isMp3Metadata(lastMetadata) ? View.VISIBLE : View.GONE);
    }

    private void updateMiniPlayerMetadata(){
        if(lastMetadata==null && controller!=null) lastMetadata=controller.getMetadata();
        if(miniPlayer==null) return;
        updateMiniPlayerVisibility();
        if(!isMp3Metadata(lastMetadata)) return;

        String title=lastMetadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
        String artist=lastMetadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
        if(miniPlayerTitle!=null) miniPlayerTitle.setText(title==null||title.isEmpty()?"(제목 없음)":title);
        if(miniPlayerSubtitle!=null) miniPlayerSubtitle.setText(artist==null?"":artist);
        Bitmap art=lastMetadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
        if(miniPlayerArt!=null){
            miniPlayerArt.setImageBitmap(art);
            miniPlayerArt.setBackground(roundedBg(art==null?0xFF3A3A42:0x00000000,10));
        }
    }

    private void updateMiniPlayerState(){
        if(miniPlayerToggle==null) return;
        PlaybackStateCompat st=lastPlaybackState;
        if(st==null && controller!=null) st=controller.getPlaybackState();
        int state=st==null?PlaybackStateCompat.STATE_NONE:st.getState();
        boolean active=state==PlaybackStateCompat.STATE_PLAYING ||
            state==PlaybackStateCompat.STATE_BUFFERING;
        miniPlayerToggle.setText(active?"❚❚":"▶");
        miniPlayerToggle.setContentDescription(
            state==PlaybackStateCompat.STATE_BUFFERING?"음악 준비 중 · 누르면 일시정지":
            active?"일시정지":"재생");
    }


    private static final String PHONE_ACTION_MP3_LIKE="com.polarisunbound.app.action.MP3_LIKE";
    private static final String PHONE_ACTION_MP3_DISLIKE="com.polarisunbound.app.action.MP3_DISLIKE";

    private long currentMp3MediaId(){
        MediaMetadataCompat m=lastMetadata;
        if(m==null && controller!=null) m=controller.getMetadata();
        if(!isMp3Metadata(m)) return -1L;
        String mediaId=m.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
        if(mediaId==null||!mediaId.startsWith("mp3:")) return -1L;
        try{ return Long.parseLong(mediaId.substring(4)); }
        catch(Exception ignored){ return -1L; }
    }

    private boolean currentMp3Liked(){
        MediaMetadataCompat m=lastMetadata;
        if(m==null&&controller!=null) m=controller.getMetadata();
        if(m==null) return false;
        String mediaId=m.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
        if(mediaId==null) return false;
        android.content.SharedPreferences p=getSharedPreferences("polaris_mp3",MODE_PRIVATE);
        if(mediaId.startsWith("ftpmp3:")){
            String rel=android.net.Uri.decode(mediaId.substring("ftpmp3:".length()));
            return p.getStringSet("favorite_ftp_paths",Collections.emptySet()).contains(rel);
        }
        if(mediaId.startsWith("mp3:")){
            try{
                long id=Long.parseLong(mediaId.substring(4));
                return p.getStringSet("favorite_ids",Collections.emptySet())
                    .contains(String.valueOf(id));
            }catch(Exception ignored){}
        }
        return false;
    }

    private TextView playerIconButton(String text,int textSize){
        TextView v=new TextView(this);
        v.setText(text);
        v.setTextSize(textSize);
        v.setGravity(android.view.Gravity.CENTER);
        v.setTextColor(Color.WHITE);
        v.setPadding(dp(8),dp(8),dp(8),dp(8));
        return v;
    }

    private void showNowPlaying(){
        if(lastMetadata==null && controller!=null) lastMetadata=controller.getMetadata();
        if(!isMp3Metadata(lastMetadata)){
            Toast.makeText(this,"재생 중인 MP3가 없습니다.",Toast.LENGTH_SHORT).show();
            return;
        }

        ensureShell();
        currentPage="mp3_now";
        tabsBar.setVisibility(View.GONE);
        miniPlayer.setVisibility(View.GONE);
        pageHost.removeAllViews();

        PlayerSwipeScrollView scroll=new PlayerSwipeScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(8),dp(22),dp(24));

        TextView collapse=playerIconButton("⌄",34);
        collapse.setGravity(android.view.Gravity.LEFT|android.view.Gravity.CENTER_VERTICAL);
        collapse.setContentDescription("현재 목록으로 돌아가기");
        collapse.setOnClickListener(v->returnFromNowPlaying());
        root.addView(collapse,new LinearLayout.LayoutParams(dp(64),dp(54)));

        FrameLayout artFrame=new FrameLayout(this);
        artFrame.setBackground(roundedBg(0xFF26262C,22));
        artFrame.setClipToOutline(true);
        nowPlayerArt=new ImageView(this);
        nowPlayerArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artFrame.addView(nowPlayerArt,new FrameLayout.LayoutParams(-1,-1));
        int artSize=Math.min(
            getResources().getDisplayMetrics().widthPixels-dp(44),
            dp(420));
        LinearLayout.LayoutParams artLp=new LinearLayout.LayoutParams(artSize,artSize);
        artLp.gravity=android.view.Gravity.CENTER_HORIZONTAL;
        artLp.setMargins(0,dp(6),0,dp(22));
        root.addView(artFrame,artLp);

        nowPlayerTitle=new TextView(this);
        nowPlayerTitle.setTextSize(27);
        nowPlayerTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        nowPlayerTitle.setTextColor(Color.WHITE);
        nowPlayerTitle.setSingleLine(true);
        nowPlayerTitle.setHorizontallyScrolling(true);
        nowPlayerTitle.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        nowPlayerTitle.setMarqueeRepeatLimit(-1);
        nowPlayerTitle.setSelected(true);
        nowPlayerTitle.setGravity(android.view.Gravity.CENTER_VERTICAL);
        root.addView(nowPlayerTitle,new LinearLayout.LayoutParams(-1,dp(44)));

        nowPlayerArtist=new TextView(this);
        nowPlayerArtist.setTextSize(18);
        nowPlayerArtist.setTextColor(0xBFFFFFFF);
        nowPlayerArtist.setSingleLine(true);
        nowPlayerArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowPlayerArtist.setGravity(android.view.Gravity.CENTER_VERTICAL);
        nowPlayerArtist.setPadding(0,dp(2),0,dp(10));
        root.addView(nowPlayerArtist,new LinearLayout.LayoutParams(-1,dp(38)));

        // Like / Dislike only. Lyrics and comments are intentionally omitted.
        LinearLayout reactionRow=new LinearLayout(this);
        reactionRow.setOrientation(LinearLayout.HORIZONTAL);
        reactionRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        nowPlayerLike=playerIconButton("좋아요",17);
        nowPlayerLike.setCompoundDrawablePadding(dp(8));
        nowPlayerLike.setBackground(roundedBg(0x6637373F,28));
        setThumbButtonIcon(nowPlayerLike,R.drawable.ic_thumb_up_player,false,false);
        nowPlayerLike.setOnClickListener(v->{
            if(controller!=null)
                controller.getTransportControls().sendCustomAction(PHONE_ACTION_MP3_LIKE,null);
            new Handler(Looper.getMainLooper()).postDelayed(()->{
                updateNowPlayingMetadata();
                updateNowPlayingModes();
            },180L);
        });

        nowPlayerDislike=playerIconButton("싫어요",17);
        nowPlayerDislike.setCompoundDrawablePadding(dp(8));
        nowPlayerDislike.setBackground(roundedBg(0x6637373F,28));
        setThumbButtonIcon(nowPlayerDislike,R.drawable.ic_thumb_down_player,false,true);
        nowPlayerDislike.setOnTouchListener((v,event)->{
            if(event.getAction()==android.view.MotionEvent.ACTION_DOWN)
                setThumbButtonIcon(nowPlayerDislike,R.drawable.ic_thumb_down_player,true,true);
            else if(event.getAction()==android.view.MotionEvent.ACTION_UP ||
                    event.getAction()==android.view.MotionEvent.ACTION_CANCEL)
                setThumbButtonIcon(nowPlayerDislike,R.drawable.ic_thumb_down_player,false,true);
            return false;
        });
        nowPlayerDislike.setOnClickListener(v->{
            if(controller!=null)
                controller.getTransportControls().sendCustomAction(PHONE_ACTION_MP3_DISLIKE,null);
        });

        LinearLayout.LayoutParams reactionLp=new LinearLayout.LayoutParams(0,dp(52),1);
        reactionLp.setMargins(0,0,dp(6),0);
        reactionRow.addView(nowPlayerLike,reactionLp);
        LinearLayout.LayoutParams dislikeLp=new LinearLayout.LayoutParams(0,dp(52),1);
        dislikeLp.setMargins(dp(6),0,0,0);
        reactionRow.addView(nowPlayerDislike,dislikeLp);
        root.addView(reactionRow,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout progressWrap=new LinearLayout(this);
        progressWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams progressWrapLp=new LinearLayout.LayoutParams(-1,-2);
        progressWrapLp.setMargins(0,dp(22),0,0);

        mp3Progress=new SeekBar(this);
        mp3Progress.setMax(1000);
        mp3Progress.setProgress(0);
        mp3Progress.setPadding(0,0,0,0);
        mp3Progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar seekBar,int progress,boolean fromUser){
                if(fromUser && mp3UserSeeking){
                    long duration=mp3DurationMs();
                    if(duration>0 && mp3Elapsed!=null)
                        mp3Elapsed.setText(formatTime((duration*progress)/1000L));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar){ mp3UserSeeking=true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar){
                long duration=mp3DurationMs();
                if(controller!=null && duration>0)
                    controller.getTransportControls().seekTo((duration*seekBar.getProgress())/1000L);
                mp3UserSeeking=false;
                updateMp3Progress();
            }
        });
        progressWrap.addView(mp3Progress,new LinearLayout.LayoutParams(-1,dp(34)));

        LinearLayout times=new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        mp3Elapsed=new TextView(this);
        mp3Elapsed.setText("0:00");
        mp3Elapsed.setTextSize(12);
        mp3Elapsed.setTextColor(0xBFFFFFFF);
        mp3Duration=new TextView(this);
        mp3Duration.setText("0:00");
        mp3Duration.setTextSize(12);
        mp3Duration.setTextColor(0xBFFFFFFF);
        mp3Duration.setGravity(android.view.Gravity.RIGHT);
        times.addView(mp3Elapsed,new LinearLayout.LayoutParams(0,-2,1));
        times.addView(mp3Duration,new LinearLayout.LayoutParams(0,-2,1));
        progressWrap.addView(times,new LinearLayout.LayoutParams(-1,-2));
        root.addView(progressWrap,progressWrapLp);

        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(android.view.Gravity.CENTER);
        controls.setPadding(0,dp(14),0,0);

        nowPlayerShuffle=playerIconButton("🔀",24);
        TextView previous=playerIconButton("◀|",28);
        nowPlayerPlayPause=playerIconButton("▶",34);
        TextView next=playerIconButton("|▶",28);
        nowPlayerRepeat=playerIconButton("↻",28);

        nowPlayerPlayPause.setBackground(roundedBg(0xFFF7F7F7,40));
        nowPlayerPlayPause.setTextColor(Color.BLACK);

        nowPlayerShuffle.setOnClickListener(v->{
            android.content.SharedPreferences prefs=getSharedPreferences("polaris_mp3",MODE_PRIVATE);
            boolean enabled=!prefs.getBoolean("shuffle",false);
            if(controller!=null) controller.getTransportControls().setShuffleMode(
                enabled ? PlaybackStateCompat.SHUFFLE_MODE_ALL : PlaybackStateCompat.SHUFFLE_MODE_NONE);
            prefs.edit().putBoolean("shuffle",enabled).apply();
            updateNowPlayingModes();
        });
        previous.setOnClickListener(v->{
            if(controller!=null) controller.getTransportControls().skipToPrevious();
        });
        nowPlayerPlayPause.setOnClickListener(v->{
            if(controller==null) return;
            PlaybackStateCompat st=controller.getPlaybackState();
            int state=st==null?PlaybackStateCompat.STATE_NONE:st.getState();
            if(state==PlaybackStateCompat.STATE_PLAYING ||
               state==PlaybackStateCompat.STATE_BUFFERING)
                controller.getTransportControls().pause();
            else
                controller.getTransportControls().play();
        });
        next.setOnClickListener(v->{
            if(controller!=null) controller.getTransportControls().skipToNext();
        });
        nowPlayerRepeat.setOnClickListener(v->{
            android.content.SharedPreferences prefs=getSharedPreferences("polaris_mp3",MODE_PRIVATE);
            int mode=prefs.getInt("repeat_mode",PlaybackStateCompat.REPEAT_MODE_NONE);
            int nextMode=mode==PlaybackStateCompat.REPEAT_MODE_NONE
                ? PlaybackStateCompat.REPEAT_MODE_ALL
                : mode==PlaybackStateCompat.REPEAT_MODE_ALL
                    ? PlaybackStateCompat.REPEAT_MODE_ONE
                    : PlaybackStateCompat.REPEAT_MODE_NONE;
            if(controller!=null) controller.getTransportControls().setRepeatMode(nextMode);
            prefs.edit().putInt("repeat_mode",nextMode).apply();
            updateNowPlayingModes();
        });

        controls.addView(nowPlayerShuffle,new LinearLayout.LayoutParams(0,dp(68),1));
        controls.addView(previous,new LinearLayout.LayoutParams(0,dp(68),1));
        LinearLayout.LayoutParams centerLp=new LinearLayout.LayoutParams(dp(78),dp(78));
        centerLp.setMargins(dp(5),0,dp(5),0);
        controls.addView(nowPlayerPlayPause,centerLp);
        controls.addView(next,new LinearLayout.LayoutParams(0,dp(68),1));
        controls.addView(nowPlayerRepeat,new LinearLayout.LayoutParams(0,dp(68),1));
        root.addView(controls,new LinearLayout.LayoutParams(-1,-2));

        scroll.addView(root,new ScrollView.LayoutParams(-1,-2));
        pageHost.addView(scroll,new FrameLayout.LayoutParams(-1,-1));

        updateNowPlayingMetadata();
        updateNowPlayingState();
        updateNowPlayingModes();
        progressHandler.removeCallbacks(progressTick);
        progressHandler.post(progressTick);
        updateMp3Progress();
    }

    private void setThumbButtonIcon(TextView button,int drawableRes,boolean active,boolean negative){
        if(button==null) return;
        android.graphics.drawable.Drawable d=androidx.core.content.ContextCompat.getDrawable(this,drawableRes);
        if(d==null) return;
        d=androidx.core.graphics.drawable.DrawableCompat.wrap(d.mutate());
        int tint=active
            ? (negative ? 0xFFFF7043 : 0xFF29D3C2)
            : 0xFFB8B8BC;
        androidx.core.graphics.drawable.DrawableCompat.setTint(d,tint);
        int size=dp(24);
        d.setBounds(0,0,size,size);
        button.setCompoundDrawables(d,null,null,null);
        button.setTextColor(active ? Color.WHITE : 0xFFE0E0E0);
    }

    private void updateNowPlayingMetadata(){
        if(!"mp3_now".equals(currentPage)) return;
        MediaMetadataCompat m=lastMetadata;
        if(m==null && controller!=null) m=controller.getMetadata();
        if(!isMp3Metadata(m)) return;

        if(nowPlayerTitle!=null){
            String title=m.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
            nowPlayerTitle.setText(title==null||title.isEmpty()?"(제목 없음)":title);
        }
        if(nowPlayerArtist!=null){
            String artist=m.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
            nowPlayerArtist.setText(artist==null?"":artist);
        }
        if(nowPlayerArt!=null){
            Bitmap art=m.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
            nowPlayerArt.setImageBitmap(art);
            nowPlayerArt.setBackground(roundedBg(art==null?0xFF303038:0x00000000,22));
        }
        if(nowPlayerLike!=null){
            boolean liked=currentMp3Liked();
            nowPlayerLike.setText("좋아요");
            nowPlayerLike.setAlpha(1f);
            setThumbButtonIcon(nowPlayerLike,R.drawable.ic_thumb_up_player,liked,false);
            nowPlayerLike.setBackground(roundedBg(
                liked?0xAA3A5D5A:0x6637373F,28));
        }
        if(nowPlayerDislike!=null){
            nowPlayerDislike.setText("싫어요");
            nowPlayerDislike.setAlpha(1f);
            setThumbButtonIcon(nowPlayerDislike,R.drawable.ic_thumb_down_player,false,true);
        }
    }

    private void updateNowPlayingState(){
        if(!"mp3_now".equals(currentPage) || nowPlayerPlayPause==null) return;
        PlaybackStateCompat st=lastPlaybackState;
        if(st==null && controller!=null) st=controller.getPlaybackState();
        int state=st==null?PlaybackStateCompat.STATE_NONE:st.getState();
        boolean active=state==PlaybackStateCompat.STATE_PLAYING ||
            state==PlaybackStateCompat.STATE_BUFFERING;
        nowPlayerPlayPause.setEnabled(isMp3Metadata(lastMetadata));
        nowPlayerPlayPause.setAlpha(nowPlayerPlayPause.isEnabled()?1f:0.45f);
        nowPlayerPlayPause.setText(active?"❚❚":"▶");
        nowPlayerPlayPause.setContentDescription(
            state==PlaybackStateCompat.STATE_BUFFERING?"FTP 음악 준비 중 · 누르면 일시정지":
            active?"일시정지":"재생");
    }

    private void updateNowPlayingModes(){
        if(!"mp3_now".equals(currentPage)) return;
        android.content.SharedPreferences prefs=getSharedPreferences("polaris_mp3",MODE_PRIVATE);
        boolean shuffled=prefs.getBoolean("shuffle",false);
        int repeat=prefs.getInt("repeat_mode",PlaybackStateCompat.REPEAT_MODE_NONE);

        if(nowPlayerShuffle!=null){
            nowPlayerShuffle.setText("🔀");
            nowPlayerShuffle.setAlpha(shuffled?1f:0.45f);
            nowPlayerShuffle.setContentDescription(shuffled?"랜덤 켜짐":"랜덤 꺼짐");
        }
        if(nowPlayerRepeat!=null){
            nowPlayerRepeat.setText(repeat==PlaybackStateCompat.REPEAT_MODE_ONE?"↻¹":"↻");
            nowPlayerRepeat.setAlpha(repeat==PlaybackStateCompat.REPEAT_MODE_NONE?0.45f:1f);
            nowPlayerRepeat.setContentDescription(
                repeat==PlaybackStateCompat.REPEAT_MODE_ONE?"한 곡 반복":
                repeat==PlaybackStateCompat.REPEAT_MODE_ALL?"전체 반복":"반복 꺼짐");
        }
    }

    private TextView ftpActionButton(String label){
        TextView v=new TextView(this);
        v.setText(label);
        v.setTextSize(18);
        v.setGravity(android.view.Gravity.CENTER);
        v.setTextColor(Color.WHITE);
        v.setPadding(dp(18),dp(22),dp(18),dp(22));
        v.setBackground(roundedBg(0xD02C2C33,22));
        if(android.os.Build.VERSION.SDK_INT>=21) v.setElevation(dp(3));
        return v;
    }

    private void showCan(){
        selectedRadioId=null;
        scheduleHandler.removeCallbacksAndMessages(null);
        currentPage="can";
        base("CAN");

        PolarisFtp.Config cfg=PolarisFtp.load(this);
        TextView info=new TextView(this);
        info.setText("CAN FTP Root\n"+cfg.canRoot+"\n\n이번 버전은 FTP 연결/업로드 검증용입니다.");
        info.setTextSize(16);
        info.setTextColor(0xDDFFFFFF);
        info.setPadding(dp(4),dp(8),dp(4),dp(18));
        body.addView(info,new LinearLayout.LayoutParams(-1,-2));

        TextView upload=ftpActionButton("⇧  FTP 업로드");
        upload.setContentDescription("CAN FTP 테스트 업로드");
        upload.setOnClickListener(v->uploadFtpTest("CAN",upload));
        upload.setOnLongClickListener(v->{ showFtpSettings(); return true; });
        body.addView(upload,new LinearLayout.LayoutParams(-1,dp(86)));

        TextView hint=new TextView(this);
        hint.setText("길게 누르면 FTP 설정 · 서버/계정은 공통, CAN/MP3 경로는 독립");
        hint.setTextSize(12);
        hint.setTextColor(0xAAFFFFFF);
        hint.setPadding(dp(4),dp(10),dp(4),0);
        body.addView(hint,new LinearLayout.LayoutParams(-1,-2));
    }


    private void showMp3Ftp(){
        showMp3FtpDirectory("",0);
    }

    private void showMp3FtpDirectory(String relativePath,int offset){
        final String rel=relativePath==null?"":relativePath;
        final int pageOffset=Math.max(0,offset);
        currentFtpMp3Path=rel;
        currentFtpMp3Offset=pageOffset;
        currentPage="mp3";
        base(rel.isEmpty()?"MP3 FTP":("FTP · "+PolarisFtp.displayTitle(rel)));

        TextView back=new TextView(this);
        String parent=PolarisFtp.parentRelative(rel);
        back.setText(rel.isEmpty()?"←  MP3":"←  상위 폴더");
        back.setTextSize(15);
        back.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        back.setTextColor(Color.WHITE);
        back.setPadding(dp(14),dp(11),dp(14),dp(11));
        back.setBackground(roundedBg(0x88303038,18));
        LinearLayout.LayoutParams backLp=new LinearLayout.LayoutParams(-1,-2);
        backLp.setMargins(0,0,0,dp(10));
        body.addView(back,backLp);
        back.setOnClickListener(v->{
            if(rel.isEmpty()) showMp3();
            else showMp3FtpDirectory(parent,0);
        });

        PolarisFtp.Config cfg=PolarisFtp.load(this);
        TextView location=new TextView(this);
        String remote=cfg.mp3Root+(rel.isEmpty()?"":"/"+rel);
        location.setText(remote);
        location.setSingleLine(true);
        location.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        location.setTextSize(13);
        location.setTextColor(0xAAFFFFFF);
        location.setPadding(dp(4),dp(4),dp(4),dp(10));
        location.setOnLongClickListener(v->{ showFtpSettings(); return true; });
        body.addView(location,new LinearLayout.LayoutParams(-1,-2));

        TextView loading=new TextView(this);
        loading.setText("FTP 목록 불러오는 중…");
        loading.setTextSize(16);
        loading.setTextColor(Color.WHITE);
        loading.setPadding(dp(8),dp(18),dp(8),dp(18));
        body.addView(loading,new LinearLayout.LayoutParams(-1,-2));

        ftpExecutor.execute(()->{
            try{
                PolarisFtp.DirectoryPage page=PolarisFtp.listMp3(
                    MainActivity.this,rel,pageOffset,200);
                runOnUiThread(()->{
                    if(!"mp3".equals(currentPage) ||
                       !rel.equals(currentFtpMp3Path) ||
                       pageOffset!=currentFtpMp3Offset) return;
                    body.removeView(loading);
                    renderFtpMp3Page(rel,page);
                });
            }catch(Exception e){
                final String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                runOnUiThread(()->{
                    if(!"mp3".equals(currentPage) ||
                       !rel.equals(currentFtpMp3Path) ||
                       pageOffset!=currentFtpMp3Offset) return;
                    loading.setText("FTP 목록 오류\n"+msg+"\n\n경로를 길게 눌러 FTP 설정");
                    loading.setOnLongClickListener(v->{ showFtpSettings(); return true; });
                });
            }
        });
    }

    private void renderFtpMp3Page(String rel,PolarisFtp.DirectoryPage page){
        if(page.entries.isEmpty()){
            TextView empty=new TextView(this);
            empty.setText("이 폴더에 음악 파일이 없습니다.");
            empty.setTextSize(16);
            empty.setTextColor(0xCCFFFFFF);
            empty.setPadding(dp(8),dp(20),dp(8),dp(20));
            body.addView(empty,new LinearLayout.LayoutParams(-1,-2));
            return;
        }

        TextView count=new TextView(this);
        int from=page.offset+1;
        int to=page.offset+page.entries.size();
        count.setText(from+"–"+to+" / "+page.total);
        count.setTextSize(12);
        count.setTextColor(0x99FFFFFF);
        count.setPadding(dp(4),0,dp(4),dp(8));
        body.addView(count,new LinearLayout.LayoutParams(-1,-2));

        for(PolarisFtp.Entry entry:page.entries){
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14),dp(11),dp(14),dp(11));
            row.setBackground(roundedBg(0x77232329,16));

            TextView icon=new TextView(this);
            icon.setText(entry.directory?"▤":"♫");
            icon.setTextSize(entry.directory?24:22);
            icon.setGravity(android.view.Gravity.CENTER);
            icon.setTextColor(Color.WHITE);
            row.addView(icon,new LinearLayout.LayoutParams(dp(46),dp(52)));

            LinearLayout labels=new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(8),0,0,0);

            TextView title=new TextView(this);
            title.setText(entry.directory?entry.name:PolarisFtp.displayTitle(entry.name));
            title.setTextSize(15);
            title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            title.setTextColor(Color.WHITE);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(title,new LinearLayout.LayoutParams(-1,-2));

            TextView sub=new TextView(this);
            sub.setText(entry.directory?"폴더":formatBytes(entry.size));
            sub.setTextSize(12);
            sub.setTextColor(0xAAFFFFFF);
            sub.setSingleLine(true);
            labels.addView(sub,new LinearLayout.LayoutParams(-1,-2));
            row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

            if(!entry.directory){
                TextView star=new TextView(this);
                boolean liked=isFtpFavorite(entry.relativePath);
                star.setText(liked?"★":"☆");
                star.setTextSize(24);
                star.setTextColor(liked?0xFFFFD54F:0xCCFFFFFF);
                star.setGravity(17);
                star.setPadding(dp(6),dp(6),dp(6),dp(6));
                star.setOnClickListener(v->{
                    toggleFtpFavorite(entry.relativePath);
                    boolean now=isFtpFavorite(entry.relativePath);
                    star.setText(now?"★":"☆");
                    star.setTextColor(now?0xFFFFD54F:0xCCFFFFFF);
                });
                row.addView(star,new LinearLayout.LayoutParams(dp(50),dp(52)));
            }

            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.setMargins(0,dp(4),0,dp(4));
            body.addView(row,lp);

            row.setOnClickListener(v->{
                if(entry.directory){
                    showMp3FtpDirectory(entry.relativePath,0);
                }else{
                    Toast.makeText(MainActivity.this,"FTP 음악 준비 중…",Toast.LENGTH_SHORT).show();
                    playId("ftpmp3:"+android.net.Uri.encode(entry.relativePath),
                        PolarisFtp.displayTitle(entry.name));
                }
            });
        }

        if(page.offset>0 || page.hasMore()){
            LinearLayout nav=new LinearLayout(this);
            nav.setOrientation(LinearLayout.HORIZONTAL);
            nav.setGravity(android.view.Gravity.CENTER);

            if(page.offset>0){
                TextView prev=ftpActionButton("← 이전");
                prev.setOnClickListener(v->
                    showMp3FtpDirectory(rel,Math.max(0,page.offset-page.limit)));
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(58),1);
                p.setMargins(0,dp(10),dp(5),0);
                nav.addView(prev,p);
            }

            if(page.hasMore()){
                TextView next=ftpActionButton("다음 →");
                next.setOnClickListener(v->
                    showMp3FtpDirectory(rel,page.offset+page.entries.size()));
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(58),1);
                p.setMargins(dp(5),dp(10),0,0);
                nav.addView(next,p);
            }
            body.addView(nav,new LinearLayout.LayoutParams(-1,-2));
        }
    }

    private String formatBytes(long bytes){
        if(bytes<1024L) return bytes+" B";
        double value=bytes/1024.0;
        if(value<1024.0) return String.format(Locale.US,"%.1f KB",value);
        value/=1024.0;
        if(value<1024.0) return String.format(Locale.US,"%.1f MB",value);
        return String.format(Locale.US,"%.2f GB",value/1024.0);
    }

    private EditText ftpField(String hint,String value){
        EditText e=new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0x77777777);
        e.setText(value==null?"":value);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setPadding(dp(12),dp(10),dp(12),dp(10));
        return e;
    }

    private void showFtpSettings(){
        PolarisFtp.Config cfg=PolarisFtp.load(this);

        LinearLayout form=new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18),dp(4),dp(18),0);

        EditText host=ftpField("서버 주소",cfg.host);
        EditText port=ftpField("포트",String.valueOf(cfg.port));
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText user=ftpField("사용자명",cfg.user);
        EditText pass=ftpField("비밀번호",cfg.password);
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT|
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText mp3Root=ftpField("MP3 Root",cfg.mp3Root);
        EditText canRoot=ftpField("CAN Root",cfg.canRoot);

        form.addView(host,new LinearLayout.LayoutParams(-1,-2));
        form.addView(port,new LinearLayout.LayoutParams(-1,-2));
        form.addView(user,new LinearLayout.LayoutParams(-1,-2));
        form.addView(pass,new LinearLayout.LayoutParams(-1,-2));
        form.addView(mp3Root,new LinearLayout.LayoutParams(-1,-2));
        form.addView(canRoot,new LinearLayout.LayoutParams(-1,-2));

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Synology FTP 설정")
            .setView(form)
            .setNegativeButton("취소",null)
            .setPositiveButton("저장",(d,w)->{
                try{
                    int p=21;
                    try{ p=Integer.parseInt(port.getText().toString().trim()); }
                    catch(Exception ignored){}
                    PolarisFtp.save(
                        MainActivity.this,
                        host.getText().toString(),
                        p,
                        user.getText().toString(),
                        pass.getText().toString(),
                        mp3Root.getText().toString(),
                        canRoot.getText().toString());
                    Toast.makeText(MainActivity.this,"FTP 설정 저장됨",Toast.LENGTH_SHORT).show();
                    if("can".equals(currentPage)) showCan();
                    else if("mp3".equals(currentPage)) showMp3Ftp();
                }catch(Exception e){
                    Toast.makeText(MainActivity.this,"FTP 설정 저장 실패: "+e.getMessage(),Toast.LENGTH_LONG).show();
                }
            })
            .show();
    }

    private void uploadFtpTest(String scope,TextView buttonView){
        PolarisFtp.Config cfg=PolarisFtp.load(this);
        if(!cfg.isConfigured()){
            Toast.makeText(this,"먼저 FTP 서버/계정을 설정하세요.",Toast.LENGTH_SHORT).show();
            showFtpSettings();
            return;
        }

        final String original=buttonView.getText().toString();
        buttonView.setEnabled(false);
        buttonView.setText("FTP 연결 중…");

        ftpExecutor.execute(()->{
            String result;
            boolean ok=false;
            try{
                SimpleDateFormat f=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
                String stamp=f.format(new Date());
                String prefix="MP3".equals(scope)?"mp3_test_":"can_test_";
                String filename=prefix+stamp+".txt";
                String payload="Polaris Unbound FTP test\n"
                    +"scope="+scope+"\n"
                    +"version="+BuildConfig.VERSION_NAME+"\n"
                    +"time="+stamp+"\n";
                String remote=PolarisFtp.uploadText(MainActivity.this,scope,filename,payload);
                result="업로드 완료\n"+remote;
                ok=true;
            }catch(Exception e){
                String msg=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                result="업로드 실패\n"+msg;
            }

            final String out=result;
            final boolean success=ok;
            runOnUiThread(()->{
                buttonView.setEnabled(true);
                buttonView.setText(out);
                Toast.makeText(MainActivity.this,out,success?Toast.LENGTH_SHORT:Toast.LENGTH_LONG).show();
                new Handler(Looper.getMainLooper()).postDelayed(()->{
                    if(buttonView.getWindowToken()!=null) buttonView.setText(original);
                },success?3500L:6500L);
            });
        });
    }

    private void showMp3(){
        selectedRadioId=null;
        scheduleHandler.removeCallbacksAndMessages(null);
        currentPage="mp3"; base("내 음악");

        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            TextView t=new TextView(this);
            t.setText("음악 권한을 허용한 뒤 MP3 메뉴를 다시 열어주세요.");
            t.setTextSize(18); t.setTextColor(Color.WHITE); t.setPadding(4,dp(18),4,dp(18));
            body.addView(t);
            requestAudioPermission();
            return;
        }

        addMp3Search();
        addRecentStrip();

        body.addView(mp3SectionTitle("내 라이브러리"));
        addLibraryShortcuts();

        body.addView(mp3SectionTitle("앨범"));
        addAlbumGridPreview(6);
        updateMiniPlayerMetadata();
        updateMiniPlayerState();
    }

    private void addMp3Search(){
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        EditText search=new EditText(this);
        voiceSearchTarget=search;
        search.setHint("곡, 아티스트, 앨범 검색");
        search.setHintTextColor(0x99FFFFFF);
        search.setTextColor(Color.WHITE);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(18),0,dp(12),0);
        search.setBackground(roundedBg(0xAA2B2B31,24));
        LinearLayout.LayoutParams searchLp=new LinearLayout.LayoutParams(0,dp(50),1);
        row.addView(search,searchLp);

        TextView mic=new TextView(this);
        mic.setText("🎙");
        mic.setTextSize(23);
        mic.setGravity(17);
        mic.setContentDescription("음성으로 음악 검색");
        mic.setTextColor(Color.WHITE);
        mic.setBackground(roundedBg(0xCC34343C,24));
        mic.setOnClickListener(v->startVoiceMusicSearch());
        LinearLayout.LayoutParams micLp=new LinearLayout.LayoutParams(dp(50),dp(50));
        micLp.setMargins(dp(8),0,0,0);
        row.addView(mic,micLp);

        LinearLayout.LayoutParams rowLp=new LinearLayout.LayoutParams(-1,dp(50));
        rowLp.setMargins(0,dp(4),0,dp(10));
        body.addView(row,rowLp);

        search.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_SEARCH || (event!=null && event.getAction()==android.view.KeyEvent.ACTION_DOWN)){
                String q=v.getText().toString().trim();
                if(!q.isEmpty()) showMp3Search(q);
                return true;
            }
            return false;
        });
    }

    private void startVoiceMusicSearch(){
        try{
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag());
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT,"곡, 아티스트 또는 앨범명을 말씀하세요");
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5);
            startActivityForResult(intent,REQ_VOICE_SEARCH);
        }catch(Exception e){
            Toast.makeText(this,"음성 검색을 사용할 수 없습니다.",Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQ_VOICE_SEARCH || resultCode!=RESULT_OK || data==null) return;
        ArrayList<String> results=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if(results==null||results.isEmpty()) return;
        String q=results.get(0)==null?"":results.get(0).trim();
        if(q.isEmpty()) return;
        if(voiceSearchTarget!=null) voiceSearchTarget.setText(q);
        showMp3Search(q);
    }

    private void showMp3Search(String query){
        String like="%"+query+"%";
        showMp3Tracks("검색 · "+query,
            MediaStore.Audio.Media.TITLE+" LIKE ? OR "+MediaStore.Audio.Media.ARTIST+" LIKE ? OR "+MediaStore.Audio.Media.ALBUM+" LIKE ?",
            new String[]{like,like,like},
            MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC");
    }

    private void addRecentStrip(){
        body.addView(mp3SectionTitle("최근 재생"));
        List<Long> ids=mp3RecentIds();
        if(ids.isEmpty()){
            TextView empty=new TextView(this);
            empty.setText("아직 재생한 곡이 없습니다.");
            empty.setTextSize(14); empty.setTextColor(0xAAFFFFFF); empty.setPadding(2,dp(4),2,dp(12));
            body.addView(empty);
            return;
        }

        HorizontalScrollView sc=new HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int shown=0;
        for(Long id:ids){
            if(shown++>=10) break;
            addRecentCard(row,id);
        }
        sc.addView(row,new HorizontalScrollView.LayoutParams(-2,-2));
        body.addView(sc,new LinearLayout.LayoutParams(-1,-2));
    }

    private void addRecentCard(LinearLayout row,long mediaId){
        android.net.Uri uri=ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,mediaId);
        String[] p={MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM_ID};
        try(Cursor c=getContentResolver().query(uri,p,null,null,null)){
            if(c==null||!c.moveToFirst()) return;
            String title=c.getString(0)==null?"(제목 없음)":c.getString(0);
            String artist=c.getString(1)==null?"":c.getString(1);
            long albumId=c.getLong(2);

            LinearLayout card=new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(0,0,0,dp(6));
            card.addView(artworkView(albumId,128));

            TextView t=new TextView(this);
            t.setText(title);
            t.setTextSize(14);
            t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            t.setTextColor(Color.WHITE);
            t.setMaxLines(1);
            t.setPadding(0,dp(7),0,0);
            card.addView(t,new LinearLayout.LayoutParams(dp(128),-2));

            TextView a=new TextView(this);
            a.setText(artist);
            a.setTextSize(12);
            a.setTextColor(0xAAFFFFFF);
            a.setMaxLines(1);
            card.addView(a,new LinearLayout.LayoutParams(dp(128),-2));

            card.setOnClickListener(v->playId("mp3:"+mediaId,title));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(128),-2);
            lp.setMargins(0,0,dp(12),0);
            row.addView(card,lp);
        }catch(Exception ignored){}
    }

    private void addLibraryShortcuts(){
        String[][] items={
            {"★","좋아요"},{"♫","전체 곡"},
            {"⇧","FTP"},{"▣","앨범"},
            {"♬","아티스트"},{"▤","폴더"},
            {"↻","최근 추가"},{"",""}
        };
        for(int r=0;r<4;r++){
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for(int c=0;c<2;c++){
                final int at=r*2+c;
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
                lp.setMargins(c==0?0:dp(6),dp(5),c==0?dp(6):0,dp(5));

                if(at==7){
                    View spacer=new View(this);
                    spacer.setVisibility(View.INVISIBLE);
                    row.addView(spacer,lp);
                    continue;
                }

                TextView v=mp3Shortcut(items[at][0],items[at][1]);
                row.addView(v,lp);
                v.setOnClickListener(x->{
                    if(at==0) showMp3Favorites();
                    else if(at==1) showMp3Tracks("전체 곡",null,null,MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC");
                    else if(at==2) showMp3Ftp();
                    else if(at==3) showMp3Albums();
                    else if(at==4) showMp3Artists();
                    else if(at==5) showMp3Groups("폴더",true);
                    else if(at==6) showMp3Tracks("최근 추가",null,null,MediaStore.Audio.Media.DATE_ADDED+" DESC");
                });
            }
            body.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
    }

    private void addAlbumGridPreview(int limit){
        String[] p={MediaStore.Audio.Albums._ID,MediaStore.Audio.Albums.ALBUM,MediaStore.Audio.Albums.ARTIST,MediaStore.Audio.Albums.NUMBER_OF_SONGS};
        try(Cursor c=getContentResolver().query(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,p,null,null,MediaStore.Audio.Albums.ALBUM+" COLLATE NOCASE ASC")){
            if(c==null||!c.moveToFirst()){
                addMp3Empty();
                return;
            }
            LinearLayout row=null;
            int count=0;
            do{
                if(count>=limit) break;
                if(count%2==0){
                    row=new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    body.addView(row,new LinearLayout.LayoutParams(-1,-2));
                }
                long albumId=c.getLong(0);
                String album=c.getString(1)==null?"(앨범 없음)":c.getString(1);
                String artist=c.getString(2)==null?"":c.getString(2);
                int songs=c.getInt(3);
                addAlbumCard(row,albumId,album,artist,songs);
                count++;
            }while(c.moveToNext());

            TextView all=new TextView(this);
            all.setText("모든 앨범 보기  →");
            all.setTextSize(15);
            all.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            all.setTextColor(Color.WHITE);
            all.setGravity(android.view.Gravity.CENTER);
            all.setPadding(dp(12),dp(14),dp(12),dp(14));
            all.setBackground(roundedBg(0x88303038,18));
            all.setOnClickListener(v->showMp3Albums());
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.setMargins(0,dp(8),0,dp(8));
            body.addView(all,lp);
        }catch(Exception e){ addMp3Empty(); }
    }

    private void addAlbumCard(LinearLayout row,long albumId,String album,String artist,int songs){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(4),dp(4),dp(4),dp(12));

        View art=artworkView(albumId,158);
        LinearLayout.LayoutParams artLp=new LinearLayout.LayoutParams(-1,dp(158));
        card.addView(art,artLp);

        TextView title=new TextView(this);
        title.setText(album);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setMaxLines(1);
        title.setPadding(0,dp(8),0,0);
        card.addView(title);

        TextView sub=new TextView(this);
        sub.setText(artist+(songs>0?" · "+songs+"곡":""));
        sub.setTextSize(12);
        sub.setTextColor(0xAAFFFFFF);
        sub.setMaxLines(1);
        card.addView(sub);

        card.setOnClickListener(v->showMp3Album(albumId,album,artist));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
        lp.setMargins(row.getChildCount()==0?0:dp(6),dp(4),row.getChildCount()==0?dp(6):0,dp(4));
        row.addView(card,lp);
    }

    private void showMp3Albums(){
        currentPage="mp3"; base("앨범"); addMp3Back();
        String[] p={MediaStore.Audio.Albums._ID,MediaStore.Audio.Albums.ALBUM,MediaStore.Audio.Albums.ARTIST,MediaStore.Audio.Albums.NUMBER_OF_SONGS};
        try(Cursor c=getContentResolver().query(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,p,null,null,MediaStore.Audio.Albums.ALBUM+" COLLATE NOCASE ASC")){
            if(c==null||!c.moveToFirst()){ addMp3Empty(); return; }
            LinearLayout row=null;
            int count=0;
            do{
                if(count%2==0){
                    row=new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    body.addView(row,new LinearLayout.LayoutParams(-1,-2));
                }
                addAlbumCard(row,c.getLong(0),c.getString(1)==null?"(앨범 없음)":c.getString(1),c.getString(2)==null?"":c.getString(2),c.getInt(3));
                count++;
            }while(c.moveToNext());
        }catch(Exception e){ addMp3Empty(); }
    }

    private void showMp3Album(long albumId,String album,String artist){
        currentPage="mp3"; base(album); addMp3Back();

        LinearLayout hero=new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        View art=artworkView(albumId,230);
        hero.addView(art,new LinearLayout.LayoutParams(dp(230),dp(230)));

        TextView t=new TextView(this);
        t.setText(album);
        t.setTextSize(24); t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); t.setTextColor(Color.WHITE); t.setGravity(17);
        t.setPadding(0,dp(14),0,dp(2));
        hero.addView(t,new LinearLayout.LayoutParams(-1,-2));

        TextView a=new TextView(this);
        a.setText(artist);
        a.setTextSize(14); a.setTextColor(0xBBFFFFFF); a.setGravity(17);
        hero.addView(a,new LinearLayout.LayoutParams(-1,-2));
        body.addView(hero,new LinearLayout.LayoutParams(-1,-2));

        body.addView(mp3SectionTitle("곡"));
        addMp3TracksToBody(MediaStore.Audio.Media.ALBUM_ID+"=?",new String[]{String.valueOf(albumId)},MediaStore.Audio.Media.TRACK+" ASC");
    }

    private void showMp3Artists(){
        currentPage="mp3"; base("아티스트"); addMp3Back();
        String[] p={MediaStore.Audio.Artists._ID,MediaStore.Audio.Artists.ARTIST,MediaStore.Audio.Artists.NUMBER_OF_TRACKS,MediaStore.Audio.Artists.NUMBER_OF_ALBUMS};
        try(Cursor c=getContentResolver().query(MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,p,null,null,MediaStore.Audio.Artists.ARTIST+" COLLATE NOCASE ASC")){
            if(c==null||!c.moveToFirst()){ addMp3Empty(); return; }
            do{
                long artistId=c.getLong(0);
                String artist=c.getString(1)==null?"(아티스트 없음)":c.getString(1);
                int tracks=c.getInt(2), albums=c.getInt(3);

                TextView v=new TextView(this);
                v.setText("♬  "+artist+"\n     "+albums+"앨범 · "+tracks+"곡");
                v.setTextSize(16);
                v.setTextColor(Color.WHITE);
                v.setPadding(dp(18),dp(16),dp(18),dp(16));
                v.setBackground(roundedBg(0xAA292930,18));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
                lp.setMargins(0,dp(5),0,dp(5));
                body.addView(v,lp);
                v.setOnClickListener(x->showMp3Artist(artistId,artist));
            }while(c.moveToNext());
        }catch(Exception e){ addMp3Empty(); }
    }

    private void showMp3Artist(long artistId,String artist){
        currentPage="mp3"; base(artist); addMp3Back();
        addMp3TracksToBody(MediaStore.Audio.Media.ARTIST_ID+"=?",new String[]{String.valueOf(artistId)},MediaStore.Audio.Media.ALBUM+" COLLATE NOCASE ASC, "+MediaStore.Audio.Media.TRACK+" ASC");
    }

    private void addMp3Back(){
        TextView back=new TextView(this);
        back.setText("←  MP3");
        back.setTextSize(15);
        back.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        back.setTextColor(Color.WHITE);
        back.setPadding(dp(14),dp(11),dp(14),dp(11));
        back.setBackground(roundedBg(0x88303038,18));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,0,0,dp(10));
        body.addView(back,lp);
        back.setOnClickListener(v->showMp3());
    }

    private void showMp3Tracks(String title,String extraSelection,String[] args,String sort){
        currentPage="mp3"; base(title); addMp3Back();
        addMp3TracksToBody(extraSelection,args,sort);
    }

    private void addMp3TracksToBody(String extraSelection,String[] args,String sort){
        String[] p={
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID
        };
        String sel=MediaStore.Audio.Media.IS_MUSIC+" != 0";
        if(extraSelection!=null&&!extraSelection.isEmpty()) sel+=" AND ("+extraSelection+")";
        try(Cursor c=getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,p,sel,args,sort)){
            if(c==null||!c.moveToFirst()){ addMp3Empty(); return; }
            do{
                addMp3Track(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4));
            }while(c.moveToNext());
        }catch(Exception e){ addMp3Empty(); }
    }

    private void showMp3Ids(String title,List<Long> ids){
        currentPage="mp3"; base(title); addMp3Back();
        if(ids.isEmpty()){ addMp3Empty(); return; }
        for(Long id:ids){
            android.net.Uri uri=ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,id);
            String[] p={MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM,MediaStore.Audio.Media.ALBUM_ID};
            try(Cursor c=getContentResolver().query(uri,p,null,null,null)){
                if(c!=null&&c.moveToFirst()) addMp3Track(id,c.getString(0),c.getString(1),c.getString(2),c.getLong(3));
            }catch(Exception ignored){}
        }
    }

    private void showMp3Groups(String title,boolean folders){
        currentPage="mp3"; base(title); addMp3Back();
        final String column=android.os.Build.VERSION.SDK_INT>=29 ? MediaStore.Audio.Media.RELATIVE_PATH : MediaStore.Audio.Media.DATA;
        LinkedHashSet<String> groups=new LinkedHashSet<>();
        try(Cursor c=getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,new String[]{column},MediaStore.Audio.Media.IS_MUSIC+" != 0",null,column+" COLLATE NOCASE ASC")){
            if(c!=null){
                int ci=c.getColumnIndexOrThrow(column);
                while(c.moveToNext()){
                    String v=c.getString(ci);
                    if(v!=null&&!v.trim().isEmpty()) groups.add(v);
                }
            }
        }catch(Exception ignored){}

        if(groups.isEmpty()){ addMp3Empty(); return; }
        for(String g:groups){
            String clean=g.endsWith("/")?g.substring(0,g.length()-1):g;
            int slash=clean.lastIndexOf('/');
            String name=slash>=0?clean.substring(slash+1):clean;

            TextView v=new TextView(this);
            v.setText("▤  "+name);
            v.setTextSize(16);
            v.setTextColor(Color.WHITE);
            v.setPadding(dp(18),dp(16),dp(18),dp(16));
            v.setBackground(roundedBg(0xAA292930,18));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.setMargins(0,dp(5),0,dp(5));
            body.addView(v,lp);
            v.setOnClickListener(x->{
                if(android.os.Build.VERSION.SDK_INT>=29)
                    showMp3Tracks(name,MediaStore.Audio.Media.RELATIVE_PATH+"=?",new String[]{g},MediaStore.Audio.Media.TRACK+" ASC");
                else
                    showMp3Tracks(name,MediaStore.Audio.Media.DATA+" LIKE ?",new String[]{g.endsWith("/")?g+"%":g+"/%"},MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC");
            });
        }
    }

    private void addMp3Track(long id,String title,String artist,String album,long albumId){
        addMp3Track(id,title,artist,album,albumId,"mp3:"+id);
    }

    private void addMp3Track(
        long id,String title,String artist,String album,long albumId,String playMediaId){
        String t=title==null||title.isEmpty()?"(제목 없음)":title;
        String a=artist==null?"":artist;
        String al=album==null?"":album;
        boolean fav=mp3FavoriteIds().contains(id);

        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8),dp(8),dp(8),dp(8));
        row.setBackground(roundedBg(0x77232329,16));

        View art=artworkView(albumId,58);
        row.addView(art,new LinearLayout.LayoutParams(dp(58),dp(58)));

        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12),0,dp(8),0);
        TextView tt=new TextView(this);
        tt.setText(t); tt.setTextSize(15); tt.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        tt.setTextColor(Color.WHITE); tt.setMaxLines(1);
        TextView sub=new TextView(this);
        sub.setText(a+(al.isEmpty()?"":" · "+al)); sub.setTextSize(12);
        sub.setTextColor(0xAAFFFFFF); sub.setMaxLines(1);
        labels.addView(tt);
        labels.addView(sub);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        TextView star=new TextView(this);
        star.setText(fav?"★":"☆");
        star.setTextSize(25);
        star.setTextColor(fav?0xFFFFD54F:0xCCFFFFFF);
        star.setGravity(17);
        star.setPadding(dp(8),dp(8),dp(8),dp(8));
        star.setOnClickListener(v->{
            toggleMp3Favorite(id);
            boolean now=mp3FavoriteIds().contains(id);
            star.setText(now?"★":"☆");
            star.setTextColor(now?0xFFFFD54F:0xCCFFFFFF);
        });
        row.addView(star,new LinearLayout.LayoutParams(dp(54),-1));

        row.setOnClickListener(v->playId(playMediaId,t));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(4),0,dp(4));
        body.addView(row,lp);
    }

    private List<String> ftpFavoritePaths(){
        List<String> out=new ArrayList<>(
            getSharedPreferences("polaris_mp3",MODE_PRIVATE)
                .getStringSet("favorite_ftp_paths",Collections.emptySet()));
        Collections.sort(out,String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private boolean isFtpFavorite(String rel){
        return getSharedPreferences("polaris_mp3",MODE_PRIVATE)
            .getStringSet("favorite_ftp_paths",Collections.emptySet())
            .contains(rel);
    }

    private void toggleFtpFavorite(String rel){
        android.content.SharedPreferences p=getSharedPreferences("polaris_mp3",MODE_PRIVATE);
        Set<String> set=new HashSet<>(
            p.getStringSet("favorite_ftp_paths",Collections.emptySet()));
        if(set.contains(rel)) set.remove(rel); else set.add(rel);
        p.edit().putStringSet("favorite_ftp_paths",set).apply();
    }

    private void showMp3Favorites(){
        currentPage="mp3";
        base("좋아요");
        addMp3Back();

        int shown=0;
        for(Long id:mp3FavoriteIds()){
            android.net.Uri uri=ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,id);
            String[] p={
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID
            };
            try(Cursor c=getContentResolver().query(uri,p,null,null,null)){
                if(c!=null&&c.moveToFirst()){
                    addMp3Track(id,c.getString(0),c.getString(1),c.getString(2),c.getLong(3),
                        "favlocal:"+id);
                    shown++;
                }
            }catch(Exception ignored){}
        }

        for(String rel:ftpFavoritePaths()){
            addFtpFavoriteRow(rel);
            shown++;
        }

        if(shown==0) addMp3Empty();
    }

    private void addFtpFavoriteRow(String rel){
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12),dp(10),dp(8),dp(10));
        row.setBackground(roundedBg(0x77232329,16));

        TextView icon=new TextView(this);
        icon.setText("♫");
        icon.setTextSize(24);
        icon.setTextColor(Color.WHITE);
        icon.setGravity(17);
        row.addView(icon,new LinearLayout.LayoutParams(dp(50),dp(54)));

        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title=new TextView(this);
        title.setText(PolarisFtp.displayTitle(rel));
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView sub=new TextView(this);
        String parent=PolarisFtp.parentRelative(rel);
        sub.setText(parent.isEmpty()?"FTP":"FTP · "+parent);
        sub.setTextSize(12);
        sub.setTextColor(0xAAFFFFFF);
        sub.setSingleLine(true);
        labels.addView(title);
        labels.addView(sub);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));

        TextView star=new TextView(this);
        star.setText("★");
        star.setTextSize(25);
        star.setTextColor(0xFFFFD54F);
        star.setGravity(17);
        star.setPadding(dp(8),dp(8),dp(8),dp(8));
        star.setOnClickListener(v->{
            toggleFtpFavorite(rel);
            showMp3Favorites();
        });
        row.addView(star,new LinearLayout.LayoutParams(dp(54),-1));

        row.setOnClickListener(v->
            playId("favftp:"+android.net.Uri.encode(rel),PolarisFtp.displayTitle(rel)));

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(4),0,dp(4));
        body.addView(row,lp);
    }

    private void addMp3Empty(){
        TextView t=new TextView(this);
        t.setText("표시할 음악 파일이 없습니다.");
        t.setTextSize(16);
        t.setTextColor(0xAAFFFFFF);
        t.setGravity(17);
        t.setPadding(dp(8),dp(28),dp(8),dp(28));
        body.addView(t);
    }

    private List<Long> mp3RecentIds(){
        List<Long> out=new ArrayList<>();
        String raw=getSharedPreferences("polaris_mp3",MODE_PRIVATE).getString("recent_ids","");
        if(raw!=null&&!raw.isEmpty())
            for(String x:raw.split(",")) try{ out.add(Long.parseLong(x)); }catch(Exception ignored){}
        return out;
    }

    private List<Long> mp3FavoriteIds(){
        List<Long> out=new ArrayList<>();
        Set<String> set=getSharedPreferences("polaris_mp3",MODE_PRIVATE).getStringSet("favorite_ids",Collections.emptySet());
        for(String x:set) try{ out.add(Long.parseLong(x)); }catch(Exception ignored){}
        return out;
    }

    private void toggleMp3Favorite(long id){
        android.content.SharedPreferences p=getSharedPreferences("polaris_mp3",MODE_PRIVATE);
        Set<String> set=new HashSet<>(p.getStringSet("favorite_ids",Collections.emptySet()));
        String key=String.valueOf(id);
        if(set.contains(key)) set.remove(key); else set.add(key);
        p.edit().putStringSet("favorite_ids",set).apply();
    }

    private void addSchedulePanel(){
        scheduleView=new TextView(this); scheduleView.setText("편성정보"); scheduleView.setTextSize(17);
        scheduleView.setTextColor(Color.WHITE); scheduleView.setPadding(12,24,12,20);
        body.addView(scheduleView,new LinearLayout.LayoutParams(-1,-2));
        if(selectedRadioId!=null) refreshSchedule(selectedRadioId);
    }
    private void refreshSchedule(final String id){
        final TextView target=scheduleView;
        if(target==null) return;

        CurrentProgramResolver.Result cached=CurrentProgramResolver.cached(this,id);
        if(cached!=null) target.setText(cached.phoneText(id));
        else target.setText("편성정보 불러오는 중…");

        if("kr5".equals(id)){
            target.setText(CurrentProgramResolver.stationName(id));
            return;
        }

        scheduleExecutor.execute(()->{
            CurrentProgramResolver.Result result=null;
            String out;
            try{
                result=CurrentProgramResolver.resolve(id);
                CurrentProgramResolver.save(MainActivity.this,id,result);
                out=result.phoneText(id);
            }catch(Exception e){
                CurrentProgramResolver.Result fallback=CurrentProgramResolver.cached(MainActivity.this,id);
                out=fallback!=null ? fallback.phoneText(id) : "편성정보를 불러오지 못했습니다";
            }

            final CurrentProgramResolver.Result finalResult=result;
            final String finalOut=out;
            runOnUiThread(()->{
                if(scheduleView==target && id.equals(selectedRadioId)) target.setText(finalOut);
                if(id.equals(selectedRadioId)){
                    scheduleHandler.removeCallbacksAndMessages(null);
                    long delay=CurrentProgramResolver.nextRefreshDelay(id,finalResult);
                    scheduleHandler.postDelayed(()->{
                        if(id.equals(selectedRadioId)) refreshSchedule(id);
                    },delay);
                }
            });
        });
    }
    @Override public void onBackPressed(){
        if("mp3_now".equals(currentPage)){
            returnFromNowPlaying();
            return;
        }
        super.onBackPressed();
    }
    @Override protected void onDestroy(){
        progressHandler.removeCallbacksAndMessages(null);
        scheduleHandler.removeCallbacksAndMessages(null);
        scheduleExecutor.shutdownNow();
        ftpExecutor.shutdownNow();
        if(browser!=null && browser.isConnected()) browser.disconnect(); super.onDestroy();
    }
}
