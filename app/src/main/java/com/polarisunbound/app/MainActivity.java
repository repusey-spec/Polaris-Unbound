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
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import android.graphics.drawable.GradientDrawable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.view.View;
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
    private final Handler scheduleHandler=new Handler(Looper.getMainLooper());
    private final ExecutorService scheduleExecutor=Executors.newSingleThreadExecutor();
    private String selectedRadioId=null;
    private String currentPage="home";
    private static final String[] RADIO_NAMES={"89.1 KBS CoolFM","91.9 MBC FM4U","93.9 CBS MusicFM","95.9 MBC 표준FM","102.7 AFN EagleFM","107.7 SBS PowerFM"};
    private int presetStation(int slot){ return getSharedPreferences("radio_presets",MODE_PRIVATE).getInt("slot"+slot,slot); }
    private void choosePreset(int slot){ new androidx.appcompat.app.AlertDialog.Builder(this).setTitle((slot+1)+"번 프리셋에 저장").setItems(RADIO_NAMES,(d,which)->{ getSharedPreferences("radio_presets",MODE_PRIVATE).edit().putInt("slot"+slot,which).apply(); showDomestic(); Toast.makeText(this,(slot+1)+"번 → "+RADIO_NAMES[which],Toast.LENGTH_SHORT).show(); }).show(); }


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
            v.setPadding(0,top,0,0); return insets;
        });
        tabsBar=new LinearLayout(this); tabsBar.setOrientation(LinearLayout.HORIZONTAL); tabsBar.setPadding(12,12,12,0);
        root.addView(tabsBar,new LinearLayout.LayoutParams(-1,-2));
        pageHost=new FrameLayout(this); root.addView(pageHost,new LinearLayout.LayoutParams(-1,0,1));
        shell.addView(root,new FrameLayout.LayoutParams(-1,-1));
        setContentView(shell);
    }

    private void addTab(TextView v){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
        lp.setMargins(6,0,6,0); tabsBar.addView(v,lp);
    }

    private void refreshTabs(){
        tabsBar.removeAllViews();
        TextView t1=tab("1 국내라디오","domestic".equals(currentPage));
        TextView t2=tab("2 해외라디오","foreign".equals(currentPage));
        TextView t3=tab("3 MP3","mp3".equals(currentPage));
        addTab(t1); addTab(t2); addTab(t3);
        t1.setOnClickListener(v->showDomestic()); t2.setOnClickListener(v->showForeign()); t3.setOnClickListener(v->showMp3());
    }

    private void base(String title){
        ensureShell(); refreshTabs(); pageHost.removeAllViews();
        ScrollView sc=new ScrollView(this); body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(24,18,24,24);
        TextView h=new TextView(this); h.setText(title); h.setTextSize(28); h.setTextColor(Color.WHITE); h.setPadding(0,8,0,12); body.addView(h);
        status=new TextView(this); status.setText(controller==null?"재생 서비스 연결 중…":"재생 준비"); status.setTextSize(16); status.setTextColor(Color.WHITE); status.setPadding(0,0,0,12); body.addView(status);
        TextView stop=button("■ 정지"); stop.setTextSize(16); stop.setOnClickListener(v->{ if(controller!=null) controller.getTransportControls().stop(); }); body.addView(stop);
        sc.addView(body); pageHost.addView(sc,new FrameLayout.LayoutParams(-1,-1));
    }
    private void playId(String id,String label){
        if(controller==null){ Toast.makeText(this,"재생 서비스 연결 중입니다",Toast.LENGTH_SHORT).show(); return; }
        status.setText(label+" 연결 중…");
        selectedRadioId=id;
        refreshSchedule(id);
        try{ controller.getTransportControls().playFromMediaId(id,null); }
        catch(Throwable e){ status.setText("재생 요청 오류"); }
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
        selectedRadioId=null;
        currentPage="domestic"; base("국내라디오");
        LinearLayout row=null;
        for(int i=0;i<6;i++){
            if(i%3==0){ row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); body.addView(row,new LinearLayout.LayoutParams(-1,-2)); }
            final int slot=i, station=presetStation(i);
            TextView v=button(RADIO_NAMES[station]); v.setTextSize(14);
            v.setOnClickListener(x->playId("kr"+(station+1),RADIO_NAMES[station]));
            v.setOnLongClickListener(x->{ choosePreset(slot); return true; });
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,-2,1);
            bp.setMargins(12,12,12,12);
            row.addView(v,bp);
        }
        addSchedulePanel();
        TextView probe=button("나무위키 접근 테스트");
        probe.setTextSize(14);
        probe.setOnClickListener(v->probeNamuWiki());
        body.addView(probe);
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

    private void showForeign(){
        selectedRadioId=null;
        currentPage="foreign"; base("해외라디오");
        TextView v=button("102.7 KIIS-FM\nLos Angeles"); v.setOnClickListener(x->playId("kiis","102.7 KIIS-FM")); body.addView(v);
        addSchedulePanel();
    }

    private void showMp3(){
        selectedRadioId=null;
        scheduleHandler.removeCallbacksAndMessages(null);
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

    private void addSchedulePanel(){
        scheduleView=new TextView(this); scheduleView.setText("편성정보"); scheduleView.setTextSize(17);
        scheduleView.setTextColor(Color.WHITE); scheduleView.setPadding(12,24,12,20);
        body.addView(scheduleView,new LinearLayout.LayoutParams(-1,-2));
        if(selectedRadioId!=null) refreshSchedule(selectedRadioId);
        scheduleNextRefresh();
    }
    private void scheduleNextRefresh(){
        scheduleHandler.removeCallbacksAndMessages(null);
        Calendar c=Calendar.getInstance(); int m=c.get(Calendar.MINUTE), sec=c.get(Calendar.SECOND);
        int[] marks={0,5,10,30,35,60}; int next=60;
        for(int x:marks) if(x>m){ next=x; break; }
        long delay=((next-m)*60L-sec)*1000L; if(delay<1000) delay=1000;
        scheduleHandler.postDelayed(()->{ if(selectedRadioId!=null) refreshSchedule(selectedRadioId); scheduleNextRefresh(); },delay);
    }
    private String scheduleUrl(String id){
        if("kr1".equals(id)) return "https://program.kbs.co.kr/2fm/radio/schedule.html";
        if("kr2".equals(id)||"kr4".equals(id)) return "https://m.imbc.com/radiomain";
        if("kr3".equals(id)) return "https://www.cbs.co.kr/schedule?type=musicFm";
        if("kr6".equals(id)) return "https://www.sbs.co.kr/live/S17";
        if("kiis".equals(id)) return "https://kiisfm.iheart.com/schedule/";
        return null;
    }
    private void refreshSchedule(final String id){
        final TextView target=scheduleView; if(target==null) return;
        if("kr5".equals(id)){ target.setText("102.7 AFN EagleFM"); return; }
        target.setText("편성정보 불러오는 중…");
        scheduleExecutor.execute(()->{
            String line;
            try{
                HttpURLConnection con=(HttpURLConnection)new URL(scheduleUrl(id)).openConnection();
                con.setConnectTimeout(7000); con.setReadTimeout(7000); con.setRequestProperty("User-Agent","Mozilla/5.0");
                StringBuilder b=new StringBuilder();
                try(BufferedReader r=new BufferedReader(new InputStreamReader(con.getInputStream()))){ String x; while((x=r.readLine())!=null) b.append(x).append(' '); }
                line=parseSchedule(id,b.toString());
            }catch(Exception e){ line="편성정보를 불러오지 못했습니다"; }
            final String out=line; runOnUiThread(()->{ if(scheduleView==target && id.equals(selectedRadioId)) target.setText(out); });
        });
    }
    private String parseSchedule(String id,String html){
        String text=html.replaceAll("(?is)<script.*?</script>"," ").replaceAll("(?is)<style.*?</style>"," ").replaceAll("(?is)<[^>]+>"," ").replace("&nbsp;"," ").replace("&amp;","&").replaceAll("\\s+"," ").trim();
        if("kr6".equals(id)){
            text=text.replaceAll("(?i)SBS 라이브"," ").replaceAll("(?i)인인권리의 편편투데이"," ")
                .replaceAll("(?i)헤더 메뉴|본문 콘텐츠|푸터 메뉴|플레이어 키보드 단축키 안내"," ")
                .replaceAll("(?i)재생/정지|영상 10초 앞으로|영상 10초 뒤로"," ").replaceAll("\\s+"," ").trim();
        }
        String station="kiis".equals(id)?"102.7 KIIS-FM":RADIO_NAMES[Math.max(0,Integer.parseInt(id.substring(2))-1)];
        String now="";
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(.{0,70}?)(\\d{1,2}:\\d{2}\\s*(?:AM|PM)?\\s*(?:-|~|–)\\s*\\d{1,2}:\\d{2}\\s*(?:AM|PM)?)(.{0,70})",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if(m.find()) now=(m.group(1)+" "+m.group(2)+" "+m.group(3)).replaceAll("\\s+"," ").trim();
        if(now.length()>150) now=now.substring(0,150);
        if(now.isEmpty()) now="현재 프로그램 정보 확인 중";
        String out=station+"  |  "+now;
        if("kiis".equals(id)){ SimpleDateFormat f=new SimpleDateFormat("HH:mm",Locale.US); f.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles")); out+="  |  현지시간 "+f.format(new Date()); }
        return out;
    }
    @Override public void onBackPressed(){ super.onBackPressed(); }
    @Override protected void onDestroy(){
        scheduleHandler.removeCallbacksAndMessages(null); scheduleExecutor.shutdownNow();
        if(browser!=null && browser.isConnected()) browser.disconnect(); super.onDestroy();
    }
}
