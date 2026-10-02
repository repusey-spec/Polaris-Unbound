package com.polarisunbound.app;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {
    private LinearLayout body;
    private MediaBrowserCompat browser;
    private MediaControllerCompat controller;
    private TextView status;
    private String currentPage="home";

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        browser=new MediaBrowserCompat(this,new ComponentName(this,PolarisMediaService.class),
            new MediaBrowserCompat.ConnectionCallback(){
                @Override public void onConnected(){
                    try{
                        controller=new MediaControllerCompat(MainActivity.this,browser.getSessionToken());
                        MediaControllerCompat.setMediaController(MainActivity.this,controller);
                        controller.registerCallback(new MediaControllerCompat.Callback(){
                            @Override public void onPlaybackStateChanged(PlaybackStateCompat s){ updateStatus(s); }
                        });
                        if(status!=null) status.setText("재생 서비스 연결됨");
                    }catch(Exception e){ if(status!=null) status.setText("서비스 연결 오류"); }
                }
            },null);
        browser.connect();
        showHome();
        requestAudioPermission();
    }

    private void requestAudioPermission(){
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.READ_MEDIA_AUDIO},7);
    }

    private TextView button(String s){
        TextView v=new TextView(this); v.setText(s); v.setTextSize(21); v.setGravity(17);
        v.setPadding(14,32,14,32); v.setBackgroundResource(android.R.drawable.btn_default); return v;
    }

    private void base(String title){
        ScrollView sc=new ScrollView(this); body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(24,24,24,24);
        TextView h=new TextView(this); h.setText(title); h.setTextSize(30); h.setPadding(0,10,0,16); body.addView(h);
        status=new TextView(this); status.setText(controller==null?"재생 서비스 연결 중…":"재생 준비"); status.setTextSize(16); status.setPadding(0,0,0,16); body.addView(status);
        TextView stop=button("■ 정지"); stop.setTextSize(16); stop.setOnClickListener(v->{ if(controller!=null) controller.getTransportControls().stop(); }); body.addView(stop);
        sc.addView(body); setContentView(sc);
    }

    private void playId(String id,String label){
        if(controller==null){ Toast.makeText(this,"재생 서비스 연결 중입니다",Toast.LENGTH_SHORT).show(); return; }
        status.setText(label+" 연결 중…");
        controller.getTransportControls().playFromMediaId(id,null);
    }

    private void updateStatus(PlaybackStateCompat s){
        if(status==null||s==null) return;
        int st=s.getState();
        if(st==PlaybackStateCompat.STATE_PLAYING) status.setText("▶ 재생 중");
        else if(st==PlaybackStateCompat.STATE_BUFFERING) status.setText("연결 중…");
        else if(st==PlaybackStateCompat.STATE_PAUSED) status.setText("일시정지");
        else if(st==PlaybackStateCompat.STATE_ERROR) status.setText("재생 오류: "+s.getErrorMessage());
        else if(st==PlaybackStateCompat.STATE_STOPPED) status.setText("정지");
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
        currentPage="domestic"; base("국내라디오");
        String[] n={"89.1 KBS CoolFM","91.9 MBC FM4U","93.9 CBS MusicFM","95.9 MBC 표준FM","102.7 AFN EagleFM","107.7 SBS PowerFM"};
        LinearLayout row=null;
        for(int i=0;i<n.length;i++){
            if(i%3==0){ row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); body.addView(row,new LinearLayout.LayoutParams(-1,-2)); }
            final int index=i; TextView v=button((i+1)+"\n"+n[i]+"\n현재 프로그램"); v.setTextSize(14);
            v.setOnClickListener(x->playId("kr"+(index+1),n[index]));
            row.addView(v,new LinearLayout.LayoutParams(0,-2,1));
        }
    }

    private void showForeign(){
        currentPage="foreign"; base("해외라디오");
        TextView v=button("102.7 KIIS-FM\nLos Angeles"); v.setOnClickListener(x->playId("kiis","102.7 KIIS-FM")); body.addView(v);
    }

    private void showMp3(){
        currentPage="mp3"; base("MP3");
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            TextView t=new TextView(this); t.setText("음악 권한을 허용한 뒤 MP3 메뉴를 다시 열어주세요."); t.setTextSize(18); body.addView(t); requestAudioPermission(); return;
        }
        String[] p={MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST};
        try(Cursor c=getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,p,MediaStore.Audio.Media.IS_MUSIC+" != 0",null,MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC")){
            if(c==null||c.getCount()==0){ TextView t=new TextView(this); t.setText("휴대폰에서 음악 파일을 찾지 못했습니다."); t.setTextSize(18); body.addView(t); return; }
            int idc=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID), tc=c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE), ac=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            while(c.moveToNext()){
                long id=c.getLong(idc); String title=c.getString(tc), artist=c.getString(ac);
                TextView v=button(title+"\n"+(artist==null?"":artist)); v.setTextSize(15);
                v.setOnClickListener(x->playId("mp3:"+id,title)); body.addView(v);
            }
        }
    }

    @Override public void onBackPressed(){ if("home".equals(currentPage)) super.onBackPressed(); else showHome(); }
    @Override protected void onDestroy(){ if(browser!=null){ if(browser.isConnected()) browser.disconnect(); } super.onDestroy(); }
}
