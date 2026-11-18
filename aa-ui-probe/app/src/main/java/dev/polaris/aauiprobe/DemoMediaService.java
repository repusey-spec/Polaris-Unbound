package dev.polaris.aauiprobe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UI-only Android Auto MediaBrowserService. This intentionally never reads CAN,
 * talks to Bluetooth/OBD, opens network connections, or emits audio.
 *
 * The car host decides the layout: we request category grid items and
 * list items at the same browse depth to test how a REAL host renders them.
 */
public final class DemoMediaService extends MediaBrowserService {
    public static final String ROOT = "polaris_probe/root";
    private static final String TAG = "PolarisUIProbe";
    private static final String STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
    private static final String STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    private static final String STYLE_SINGLE = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT";
    private static final String STYLE_GROUP = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT";
    private static final int LIST = 1;
    private static final int CATEGORY_GRID = 4;
    private static final ConcurrentHashMap<String,Bitmap> ICONS = new ConcurrentHashMap<>();
    private MediaSession session;

    @Override public void onCreate() {
        super.onCreate();
        session = new MediaSession(this,"PolarisUIOnlyProbe");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { pausedDemo("UI TEST · 재생 기능 없음", "모의 화면 전용"); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if(mediaId!=null && mediaId.startsWith("radio/station/")) {
                    String id=mediaId.substring("radio/station/".length());
                    String title=stationName(id);
                    if(title!=null){
                        rememberRadio(id,title);
                        pausedDemo(title,"라디오 UI 테스트 · 실제 재생 없음");
                        notifyChildrenChanged(ROOT);
                        notifyChildrenChanged("radio");
                        notifyChildrenChanged("home");
                        return;
                    }
                }
                if(mediaId!=null && mediaId.startsWith("streaming/liked/")) {
                    String index=mediaId.substring("streaming/liked/".length());
                    String title="좋아요 항목 "+index+" (샘플)";
                    getSharedPreferences("ui_probe",Context.MODE_PRIVATE).edit()
                        .putString("current_label",title).apply();
                    pausedDemo(title,"스트리밍 UI 테스트 · 실제 재생 없음");
                    notifyChildrenChanged("home");
                    return;
                }
                pausedDemo("UI TEST · "+mediaId,"실제 음원 및 방송 연결 없음");
            }
        });
        session.setPlaybackState(new PlaybackState.Builder()
            .setState(PlaybackState.STATE_PAUSED,0,1)
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID).build());
        session.setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE,"Polaris UI Test")
            .putString(MediaMetadata.METADATA_KEY_ARTIST,"가상 화면 · 소리 없음")
            .build());
        session.setActive(true);
        setSessionToken(session.getSessionToken());
        Log.i(TAG, "service created; NO CAN, NO RADIO, NO STREAMING, NO AUDIO");
    }
    private void pausedDemo(String title,String subtitle) {
        session.setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE,title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST,subtitle).build());
        session.setPlaybackState(new PlaybackState.Builder()
            .setState(PlaybackState.STATE_PAUSED,0,0)
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID).build());
        Log.i(TAG,"play selection ignored: "+title);
    }
    @Override public BrowserRoot onGetRoot(String clientPackageName,int clientUid,Bundle rootHints) {
        Bundle extras = new Bundle();
        extras.putInt(STYLE_BROWSABLE,LIST);
        extras.putInt(STYLE_PLAYABLE,LIST);
        Log.i(TAG,"root requested by "+clientPackageName+" hints="+(rootHints==null?"none":rootHints.keySet()));
        return new BrowserRoot(ROOT,extras);
    }
    @Override public void onLoadChildren(String parentId,Result<List<MediaBrowser.MediaItem>> result) {
        List<MediaBrowser.MediaItem> list = new ArrayList<>();
        switch(parentId) {
        case ROOT:
            list.add(folder("home","Home","현재 선택한 콘텐츠","H","#2FAFA8",LIST,null));
            list.add(folder("radio","Radio","국내 / 해외 및 최근 재생","R","#E4A156",LIST,null));
            list.add(folder("streaming","Streaming","플레이리스트 / 다운로드 및 좋아요","S","#A58AEB",LIST,null));
            list.add(folder("can","CAN","향후 CAN 기능","C","#6389EB",LIST,null));
            break;
        case "home": {
            String current=getSharedPreferences("ui_probe",Context.MODE_PRIVATE)
                .getString("current_label","");
            if(current.isEmpty())list.add(info("home/none","현재 재생 중인 콘텐츠 없음","UI 테스트 앱 · 오디오 연결 전","현재 콘텐츠"));
            else list.add(info("home/current",current,"마지막 선택 · 실제 재생 없음","현재 콘텐츠"));
            break;
        }
        case "radio": {
            list.add(folder("radio/kr","국내","국내 6개 방송국","KR","#E4A156",CATEGORY_GRID,"Radio 메뉴"));
            list.add(folder("radio/world","해외","해외 2개 방송국","INT","#E4A156",CATEGORY_GRID,"Radio 메뉴"));
            String recent=getSharedPreferences("ui_probe",Context.MODE_PRIVATE).getString("recent_radio","");
            if(recent.isEmpty())list.add(info("radio/empty","최근 재생 기록 없음","국내·해외에서 채널을 선택하세요","최근 재생"));
            else for(String id:recent.split(",")){
                String title=stationName(id);
                if(title!=null)list.add(stationItem(id,title,"최근 선택 · 미리보기",LIST,"최근 재생"));
            }
            break;
        }
        case "radio/kr":
            for(String id:new String[]{"kr1","kr2","kr3","kr4","kr5","kr6"})
                list.add(stationItem(id,stationName(id),"국내 · 라디오 UI",CATEGORY_GRID,"국내"));
            break;
        case "radio/world":
            for(String id:new String[]{"kiis","gallery"})
                list.add(stationItem(id,stationName(id),"해외 · 라디오 UI",CATEGORY_GRID,"해외"));
            break;
        case "streaming":
            list.add(folder("streaming/playlists","플레이리스트","플레이리스트 목록","PL","#A58AEB",CATEGORY_GRID,"Streaming 메뉴"));
            list.add(folder("streaming/downloads","다운로드","다운로드한 음악","↓","#A58AEB",CATEGORY_GRID,"Streaming 메뉴"));
            for(int i=1;i<=10;i++){
                String n=String.format(java.util.Locale.ROOT,"%02d",i);
                list.add(info("streaming/liked/"+n,"좋아요 항목 "+n+" (샘플)","실제 좋아요 데이터 연결 전","좋아요"));
            }
            break;
        case "streaming/playlists":
            list.add(info("streaming/playlists/empty","플레이리스트 연결 전","실제 스트리밍 엔진 연결 후 목록 표시","플레이리스트"));
            break;
        case "streaming/downloads":
            list.add(info("streaming/downloads/empty","다운로드 목록 연결 전","실제 다운로드 데이터 연결 후 표시","다운로드"));
            break;
        case "can":
            // Future CAN display: intentionally blank, with no fake measurements.
            break;
        }
        Log.i(TAG,"children: "+parentId+" count="+list.size());
        result.sendResult(list);
    }

    private void rememberRadio(String id,String title) {
        SharedPreferences p=getSharedPreferences("ui_probe",Context.MODE_PRIVATE);
        java.util.LinkedHashSet<String> ordered=new java.util.LinkedHashSet<>();
        ordered.add(id);
        String old=p.getString("recent_radio","");
        if(!old.isEmpty())for(String x:old.split(","))if(stationName(x)!=null)ordered.add(x);
        StringBuilder sb=new StringBuilder();
        int count=0;
        for(String x:ordered){if(count++>=8)break;if(sb.length()>0)sb.append(',');sb.append(x);}
        p.edit().putString("recent_radio",sb.toString())
            .putString("current_label",title).apply();
    }
    static String stationName(String id) {
        if(id==null)return null;
        switch(id){
        case "kr1":return "89.1 KBS CoolFM";
        case "kr2":return "91.9 MBC FM4U";
        case "kr3":return "93.9 CBS MusicFM";
        case "kr4":return "95.9 MBC 표준FM";
        case "kr5":return "102.7 AFN EagleFM";
        case "kr6":return "107.7 SBS PowerFM";
        case "kiis":return "102.7 KIIS-FM LA";
        case "gallery":return "Gallery 41";
        default:return null;
        }
    }
    private MediaBrowser.MediaItem stationItem(String id,String title,String subtitle,int style,String group){
        Bundle extras=new Bundle();
        extras.putInt(STYLE_SINGLE,style);
        extras.putString(STYLE_GROUP,group);
        MediaDescription d=new MediaDescription.Builder().setMediaId("radio/station/"+id)
            .setTitle(title).setSubtitle(subtitle)
            .setIconBitmap(StationArt.bitmap(this,id,112)).setExtras(extras).build();
        return new MediaBrowser.MediaItem(d,MediaBrowser.MediaItem.FLAG_PLAYABLE);
    }
    private static MediaBrowser.MediaItem folder(String id,String title,String subtitle,
            String glyph,String color,int style,String group) {
        Bundle extras = new Bundle();
        extras.putInt(STYLE_SINGLE,style);
        extras.putInt(STYLE_BROWSABLE,LIST);
        extras.putInt(STYLE_PLAYABLE,LIST);
        if(group!=null)extras.putString(STYLE_GROUP,group);
        MediaDescription d=new MediaDescription.Builder().setMediaId(id).setTitle(title)
            .setSubtitle(subtitle).setIconBitmap(icon(glyph,color)).setExtras(extras).build();
        return new MediaBrowser.MediaItem(d,MediaBrowser.MediaItem.FLAG_BROWSABLE);
    }
    private static MediaBrowser.MediaItem info(String id,String title,String subtitle,String group) {
        Bundle extras=new Bundle();
        extras.putInt(STYLE_SINGLE,LIST);
        if(group!=null)extras.putString(STYLE_GROUP,group);
        MediaDescription d=new MediaDescription.Builder().setMediaId(id).setTitle(title)
            .setSubtitle(subtitle).setIconBitmap(icon("•","#384456")).setExtras(extras).build();
        return new MediaBrowser.MediaItem(d,MediaBrowser.MediaItem.FLAG_PLAYABLE);
    }
    private static Bitmap icon(String glyph,String colorHex) {
        String cacheKey=glyph+":"+colorHex;
        Bitmap existing=ICONS.get(cacheKey);
        if(existing!=null)return existing;
        final int n=112;
        Bitmap bitmap=Bitmap.createBitmap(n,n,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor(colorHex));
        canvas.drawRoundRect(new RectF(7,7,n-7,n-7),23,23,p);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(glyph.length()<=2?44:23);
        canvas.drawText(glyph,n/2f,n/2f-(p.ascent()+p.descent())/2f,p);
        ICONS.putIfAbsent(cacheKey,bitmap);
        return ICONS.get(cacheKey);
    }
    @Override public void onDestroy(){
        if(session!=null){session.setActive(false);session.release();}
        super.onDestroy();
    }
}
