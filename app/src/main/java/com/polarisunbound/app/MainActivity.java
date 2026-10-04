package com.polarisunbound.app;

import android.Manifest;
import android.content.*;
import android.content.ContentUris;
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
        TextView t1=tab("1 라디오","radio".equals(currentPage));
        TextView t2=tab("2 MP3","mp3".equals(currentPage));
        addTab(t1); addTab(t2);
        t1.setOnClickListener(v->showDomestic()); t2.setOnClickListener(v->showMp3());
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
        if(id!=null && id.startsWith("mp3:")){
            selectedRadioId=null;
            scheduleHandler.removeCallbacksAndMessages(null);
        }else{
            selectedRadioId=id;
            scheduleHandler.removeCallbacksAndMessages(null);
            refreshSchedule(id);
        }
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

    private void showForeign(){
        selectedRadioId=null;
        currentPage="foreign"; base("해외라디오");
        TextView v=button("102.7 KIIS-FM\nLos Angeles"); v.setOnClickListener(x->playId("kiis","102.7 KIIS-FM")); body.addView(v);
        addSchedulePanel();
    }

    private void showMp3(){
        selectedRadioId=null;
        scheduleHandler.removeCallbacksAndMessages(null);
        galleryHandler.removeCallbacksAndMessages(null);
        currentPage="mp3"; base("MP3");
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            TextView t=new TextView(this); t.setText("음악 권한을 허용한 뒤 MP3 메뉴를 다시 열어주세요."); t.setTextSize(18); t.setTextColor(Color.WHITE); body.addView(t); requestAudioPermission(); return;
        }
        String[] names={"최근 재생","폴더","앨범","아티스트","전체 곡","즐겨찾기"};
        for(int r=0;r<2;r++){
            LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            body.addView(row,new LinearLayout.LayoutParams(-1,-2));
            for(int c=0;c<3;c++){
                final int at=r*3+c;
                TextView v=button(names[at]); v.setTextSize(16);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1); lp.setMargins(8,8,8,8); row.addView(v,lp);
                v.setOnClickListener(x->{
                    if(at==0) showMp3Ids("최근 재생",mp3RecentIds());
                    else if(at==1) showMp3Groups("폴더",true);
                    else if(at==2) showMp3NamedGroups("앨범",MediaStore.Audio.Media.ALBUM);
                    else if(at==3) showMp3NamedGroups("아티스트",MediaStore.Audio.Media.ARTIST);
                    else if(at==4) showMp3Tracks("전체 곡",null,null,MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC");
                    else showMp3Ids("즐겨찾기",mp3FavoriteIds());
                });
            }
        }
    }

    private void addMp3Back(){
        TextView back=button("← MP3"); back.setTextSize(15); back.setOnClickListener(v->showMp3()); body.addView(back);
    }

    private void showMp3Tracks(String title,String extraSelection,String[] args,String sort){
        currentPage="mp3"; base(title); addMp3Back();
        String[] p={MediaStore.Audio.Media._ID,MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM};
        String sel=MediaStore.Audio.Media.IS_MUSIC+" != 0";
        if(extraSelection!=null&&!extraSelection.isEmpty()) sel+=" AND ("+extraSelection+")";
        try(Cursor c=getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,p,sel,args,sort)){
            if(c==null||c.getCount()==0){ addMp3Empty(); return; }
            int idc=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID), tc=c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE), ac=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST), alc=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            while(c.moveToNext()) addMp3Track(c.getLong(idc),c.getString(tc),c.getString(ac),c.getString(alc));
        }catch(Exception e){ addMp3Empty(); }
    }

    private void showMp3Ids(String title,List<Long> ids){
        currentPage="mp3"; base(title); addMp3Back();
        if(ids.isEmpty()){ addMp3Empty(); return; }
        for(Long id:ids){
            android.net.Uri uri=ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,id);
            String[] p={MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM};
            try(Cursor c=getContentResolver().query(uri,p,null,null,null)){
                if(c!=null&&c.moveToFirst()) addMp3Track(id,c.getString(0),c.getString(1),c.getString(2));
            }catch(Exception ignored){}
        }
    }

    private void showMp3NamedGroups(String title,String column){
        currentPage="mp3"; base(title); addMp3Back();
        LinkedHashSet<String> groups=new LinkedHashSet<>();
        try(Cursor c=getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,new String[]{column},MediaStore.Audio.Media.IS_MUSIC+" != 0",null,column+" COLLATE NOCASE ASC")){
            if(c!=null){ int ci=c.getColumnIndexOrThrow(column); while(c.moveToNext()){ String v=c.getString(ci); if(v!=null&&!v.trim().isEmpty()) groups.add(v); } }
        }catch(Exception ignored){}
        if(groups.isEmpty()){ addMp3Empty(); return; }
        for(String g:groups){
            TextView v=button(g); v.setTextSize(16); body.addView(v);
            v.setOnClickListener(x->showMp3Tracks(g,column+"=?",new String[]{g},MediaStore.Audio.Media.TRACK+" ASC"));
        }
    }

    private void showMp3Groups(String title,boolean folders){
        currentPage="mp3"; base(title); addMp3Back();
        final String column=android.os.Build.VERSION.SDK_INT>=29 ? MediaStore.Audio.Media.RELATIVE_PATH : MediaStore.Audio.Media.DATA;
        LinkedHashSet<String> groups=new LinkedHashSet<>();
        try(Cursor c=getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,new String[]{column},MediaStore.Audio.Media.IS_MUSIC+" != 0",null,column+" COLLATE NOCASE ASC")){
            if(c!=null){ int ci=c.getColumnIndexOrThrow(column); while(c.moveToNext()){ String v=c.getString(ci); if(v!=null&&!v.trim().isEmpty()) groups.add(v); } }
        }catch(Exception ignored){}
        if(groups.isEmpty()){ addMp3Empty(); return; }
        for(String g:groups){
            String clean=g.endsWith("/")?g.substring(0,g.length()-1):g;
            int slash=clean.lastIndexOf('/'); String name=slash>=0?clean.substring(slash+1):clean;
            TextView v=button(name); v.setTextSize(16); body.addView(v);
            v.setOnClickListener(x->{
                if(android.os.Build.VERSION.SDK_INT>=29) showMp3Tracks(name,MediaStore.Audio.Media.RELATIVE_PATH+"=?",new String[]{g},MediaStore.Audio.Media.TRACK+" ASC");
                else showMp3Tracks(name,MediaStore.Audio.Media.DATA+" LIKE ?",new String[]{g.endsWith("/")?g+"%":g+"/%"},MediaStore.Audio.Media.TITLE+" COLLATE NOCASE ASC");
            });
        }
    }

    private void addMp3Track(long id,String title,String artist,String album){
        String t=title==null||title.isEmpty()?"(제목 없음)":title;
        String a=artist==null?"":artist, al=album==null?"":album;
        String sub=a+(al.isEmpty()?"":" · "+al);
        boolean fav=mp3FavoriteIds().contains(id);
        TextView v=button((fav?"★ ":"")+t+"\n"+sub); v.setTextSize(15);
        v.setOnClickListener(x->playId("mp3:"+id,t));
        v.setOnLongClickListener(x->{ toggleMp3Favorite(id); Toast.makeText(this,"즐겨찾기 변경: "+t,Toast.LENGTH_SHORT).show(); return true; });
        body.addView(v);
    }

    private void addMp3Empty(){
        TextView t=new TextView(this); t.setText("표시할 음악 파일이 없습니다."); t.setTextSize(18); t.setTextColor(Color.WHITE); t.setPadding(8,24,8,24); body.addView(t);
    }

    private List<Long> mp3RecentIds(){
        List<Long> out=new ArrayList<>();
        String raw=getSharedPreferences("polaris_mp3",MODE_PRIVATE).getString("recent_ids","");
        if(raw!=null&&!raw.isEmpty()) for(String x:raw.split(",")) try{ out.add(Long.parseLong(x)); }catch(Exception ignored){}
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
    @Override public void onBackPressed(){ super.onBackPressed(); }
    @Override protected void onDestroy(){
        scheduleHandler.removeCallbacksAndMessages(null); scheduleExecutor.shutdownNow();
        if(browser!=null && browser.isConnected()) browser.disconnect(); super.onDestroy();
    }
}
