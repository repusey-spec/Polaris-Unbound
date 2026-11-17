package dev.polaris.aauiprobe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
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
                pausedDemo("UI TEST · "+mediaId, "실제 음원 및 방송 연결 없음");
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
            list.add(folder("home","Home","CAN·라디오 / CAN·스트리밍 요약","H","#2FAFA8",LIST,null));
            list.add(folder("can","CAN","서플라이 / 전비기록 / 상태 / 로깅","C","#6389EB",LIST,null));
            list.add(folder("radio","Radio","국내 / 해외","R","#E4A156",LIST,null));
            list.add(folder("streaming","Streaming","좋아요 / 저장 / 추천 / 최근재생","S","#A58AEB",LIST,null));
            break;
        case "home":
            list.add(folder("home/radio","CAN + Radio","SOC 62% · 전비 18.5 · 국내 FM (가상)","C+R","#2FAFA8",LIST,"요약"));
            list.add(folder("home/streaming","CAN + Streaming","SOC 62% · 전비 18.5 · 음악 미리보기 (가상)","C+S","#2FAFA8",LIST,"요약"));
            break;
        case "can":
            list.add(folder("can/supply","서플라이","공급 전원 · 데모","V","#6389EB",CATEGORY_GRID,"CAN 메뉴"));
            list.add(folder("can/efficiency","전비기록","주행 기록 · 데모","EV","#6389EB",CATEGORY_GRID,"CAN 메뉴"));
            list.add(folder("can/status","상태","차량 상태 · 데모","ST","#6389EB",CATEGORY_GRID,"CAN 메뉴"));
            list.add(folder("can/log","로깅","신호 로깅 · 데모","LOG","#6389EB",CATEGORY_GRID,"CAN 메뉴"));
            list.add(info("can/sample/soc","SOC 62% · 가상 데이터","실제 CAN·OBD 연결 없음","미리보기"));
            list.add(info("can/sample/eff","평균 전비 18.5 kWh/100km · 가상","실제 측정값 아님","미리보기"));
            break;
        case "radio":
            list.add(folder("radio/kr","국내","국내 방송국 메뉴","KR","#E4A156",CATEGORY_GRID,"Radio 메뉴"));
            list.add(folder("radio/world","해외","해외 방송국 메뉴","INT","#E4A156",CATEGORY_GRID,"Radio 메뉴"));
            list.add(info("radio/demo1","89.1 KBS Cool FM","국내 · 가상 방송 목록","미리보기"));
            list.add(info("radio/demo2","KIIS-FM LA","해외 · 가상 방송 목록","미리보기"));
            break;
        case "streaming":
            list.add(folder("streaming/liked","좋아요","좋아요 표시한 곡","♥","#A58AEB",CATEGORY_GRID,"Streaming 메뉴"));
            list.add(folder("streaming/saved","저장","저장한 곡","↓","#A58AEB",CATEGORY_GRID,"Streaming 메뉴"));
            list.add(folder("streaming/rec","추천","추천 음악","★","#A58AEB",CATEGORY_GRID,"Streaming 메뉴"));
            list.add(folder("streaming/recent","최근재생","최근 재생","↺","#A58AEB",CATEGORY_GRID,"Streaming 메뉴"));
            list.add(info("streaming/demo1","Demo Track A","샘플 음악 · 실제 재생 없음","미리보기"));
            list.add(info("streaming/demo2","Demo Track B","샘플 음악 · 실제 재생 없음","미리보기"));
            list.add(info("streaming/demo3","Demo Track C","샘플 음악 · 실제 재생 없음","미리보기"));
            break;
        case "home/radio":
            list.add(info("home/radio/soc","CAN · SOC 62% (가상)","실제 차량 데이터 아님","요약"));
            list.add(info("home/radio/now","Radio · 89.1 Cool FM (가상)","라디오 신호 연결 없음","요약"));
            break;
        case "home/streaming":
            list.add(info("home/streaming/soc","CAN · 전비 18.5 (가상)","실제 차량 데이터 아님","요약"));
            list.add(info("home/streaming/now","Streaming · Demo Track A","실제 음악 재생 없음","요약"));
            break;
        case "can/supply": list.add(info("demo/supply","13.8 V (가상)","모의 전원 공급 정보","서플라이"));break;
        case "can/efficiency": list.add(info("demo/eff","18.5 kWh/100km (가상)","모의 전비 기록","전비기록"));break;
        case "can/status": list.add(info("demo/status","SOC 62% · 연결 끊김 (가상)","실제 CAN 수신 없음","상태"));break;
        case "can/log": list.add(info("demo/log","로깅 대기 · 0 프레임","CAN 신호 수집 없음","로깅"));break;
        case "radio/kr": list.add(info("demo/kr1","89.1 KBS Cool FM","가상 국내 라디오","국내")); list.add(info("demo/kr2","107.7 SBS PowerFM","가상 국내 라디오","국내"));break;
        case "radio/world": list.add(info("demo/us1","KIIS-FM LA","가상 해외 라디오","해외"));list.add(info("demo/us2","Gallery 41","가상 해외 라디오","해외"));break;
        case "streaming/liked":
        case "streaming/saved":
        case "streaming/rec":
        case "streaming/recent":
            String section = parentId.substring(parentId.lastIndexOf('/')+1);
            list.add(info(parentId+"/a","Demo Track A","["+section+"] 실제 음원 없음","목록"));
            list.add(info(parentId+"/b","Demo Track B","["+section+"] 실제 음원 없음","목록"));
            break;
        }
        Log.i(TAG,"children: "+parentId+" count="+list.size());
        result.sendResult(list);
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
