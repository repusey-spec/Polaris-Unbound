package com.polarisunbound.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opt-in, read-only browser for the account *separately authenticated inside
 * Polaris*. No access to original YTM app storage, no password collection,
 * no playback stream retrieval and no HTTP logging of auth headers/cookies.
 *
 * This is an independent compact protocol implementation informed by publicly
 * available YT Music Web/InnerTube clients (not a copy of Metrolist code).
 * InnerTube is an unsupported/private service and can change without notice.
 */
final class PolarisYtmAccountCatalog {
    private static final String MUSIC_ORIGIN="https://music.youtube.com";
    private static final String YTM_BROWSER="https://music.youtube.com/youtubei/v1/browse?prettyPrint=false";
    private static final String USER_AGENT="Mozilla/5.0 (X11; Linux x86_64) "+
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int PAGE_LIMIT=30;
    private static final int MAX_RESULTS=900;
    private static final int MAX_RESPONSE=4*1024*1024;
    private static volatile PolarisYtmAccountCatalog singleton;

    interface Callback {
        void done(List<MediaBrowserCompat.MediaItem> items,String error);
    }

    static PolarisYtmAccountCatalog get(Context context){
        if(singleton==null)synchronized(PolarisYtmAccountCatalog.class){
            if(singleton==null)singleton=new PolarisYtmAccountCatalog();
        }
        return singleton;
    }

    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());

    /** Distinct account browse routes. The LM list is *likes*, not saved songs. */
    private static final String MODE_PLAYLISTS="playlists";
    private static final String MODE_PLAYLIST="playlist";
    private static final String MODE_SONGS="songs";
    private static final String MODE_LIKES="likes";

    private static final class Route {
        final String browseId,mode,playlistId,label;
        Route(String browseId,String mode,String playlistId,String label){
            this.browseId=browseId;
            this.mode=mode;
            this.playlistId=playlistId;
            this.label=label;
        }
    }

    private static Route route(String parent){
        if(PolarisYtmCatalog.ACCOUNT_LIBRARY.equals(parent))
            return new Route("FEmusic_liked_playlists",MODE_PLAYLISTS,null,"플레이리스트");
        if(PolarisYtmCatalog.ACCOUNT_SONGS.equals(parent))
            return new Route("FEmusic_liked_videos",MODE_SONGS,null,"저장한 노래");
        if(PolarisYtmCatalog.ACCOUNT_LIKED.equals(parent))
            return new Route("VLLM",MODE_LIKES,"LM","좋아요 음악");
        if(parent!=null && parent.startsWith(PolarisYtmCatalog.ACCOUNT_PLAYLIST)){
            String id=Uri.decode(parent.substring(
                PolarisYtmCatalog.ACCOUNT_PLAYLIST.length()));
            if(!PolarisYtmSharedPlaylist.validId(id))
                throw new IllegalArgumentException("잘못된 재생목록 ID");
            return new Route("VL"+id,MODE_PLAYLIST,id,"재생목록 곡");
        }
        throw new IllegalArgumentException("지원되지 않는 계정 보관함 위치");
    }

    void load(String parent,Callback callback){
        executor.execute(()->{
            List<MediaBrowserCompat.MediaItem> result=new ArrayList<>();
            String error=null;
            try{
                Route route=route(parent);
                Auth auth=authenticated();
                JSONObject cfg=webConfig(auth.cookie);
                String continuation=null;
                String previousContinuation=null;
                Map<String,MediaBrowserCompat.MediaItem> collected=new LinkedHashMap<>();
                for(int page=0;page<PAGE_LIMIT && collected.size()<MAX_RESULTS;page++){
                    JSONObject response=api(auth,cfg,route.browseId,continuation);
                    collectEntries(response,route.mode,route.playlistId,collected);
                    String next=findContinuation(response,0,new int[]{0});
                    if(next==null||next.isEmpty()||next.equals(continuation)||
                        next.equals(previousContinuation))break;
                    previousContinuation=continuation;
                    continuation=next;
                }
                result.addAll(collected.values());
                if(result.isEmpty())
                    error="로그인은 확인됐지만 실제 "+route.label+
                        " 항목을 읽지 못했습니다. 계정 선택 또는 응답 형식이 "+
                        "일치하지 않을 수 있습니다.";
            }catch(Exception ex){
                // Do not surface exception messages; those might include
                // request URLs/headers if a platform implementation changes.
                String detail=ex.getMessage();
                // Display only codes and markers generated by our HTTP client.
                // Arbitrary platform exception text could contain private URLs.
                String safeHttp=(detail!=null &&
                    detail.matches("YTM HTTP status [1-5][0-9]{2}"))?
                    detail.replace("YTM HTTP status ","HTTP "):null;
                String kind=ex instanceof AuthMissing?"로그인 세션 없음":
                    ex instanceof Unauthorized?"세션 만료 또는 접근 거부":
                    ex instanceof java.net.SocketTimeoutException?"네트워크 시간 초과":
                    ex instanceof java.net.UnknownHostException?"네트워크 연결 불가":
                    "Response too large".equals(detail)?"서버 응답 크기 초과":
                    safeHttp!=null?"계정 API "+safeHttp:
                    ex instanceof IllegalArgumentException?"요청 형식 오류":
                    "조회 실패 ("+ex.getClass().getSimpleName()+")";
                error=kind+
                    (ex instanceof AuthMissing?
                      " · 먼저 Polaris 안에서 YT Music 계정에 로그인하세요.":"");
            }
            final List<MediaBrowserCompat.MediaItem> data=result;
            final String message=error;
            main.post(()->callback.done(data,message));
        });
    }

    /**
     * Read the user's personalized YT Music home using the same account
     * context that already fetches their saved playlists.
     */
    static JSONObject fetchAuthenticatedHome() throws Exception {
        Auth auth=authenticated();
        JSONObject cfg=webConfig(auth.cookie);
        return api(auth,cfg,"FEmusic_home",null);
    }

    static boolean hasLoginCookie(){
        try{
            String cookies=CookieManager.getInstance().getCookie(MUSIC_ORIGIN);
            return secretFromCookie(cookies)!=null;
        }catch(Throwable ignored){return false;}
    }

    private static final class AuthMissing extends Exception {}
    private static final class Unauthorized extends Exception {}

    private static final class Auth {
        final String cookie;
        final String sapisid;
        Auth(String cookie,String sapisid){this.cookie=cookie;this.sapisid=sapisid;}
    }

    private static Auth authenticated() throws Exception {
        String cookie=CookieManager.getInstance().getCookie(MUSIC_ORIGIN);
        String sapisid=secretFromCookie(cookie);
        if(sapisid==null)throw new AuthMissing();
        return new Auth(cookie,sapisid);
    }

    private static String secretFromCookie(String cookie){
        if(cookie==null)return null;
        for(String key:new String[]{"SAPISID","__Secure-3PAPISID","__Secure-1PAPISID"}){
            for(String part:cookie.split(";")){
                String t=part.trim();
                int eq=t.indexOf('=');
                if(eq>0 && t.substring(0,eq).equals(key)){
                    String v=t.substring(eq+1).trim();
                    if(v.length()>8 && v.length()<500)return v;
                }
            }
        }
        return null;
    }

    private static String authorization(String sapisid) throws Exception {
        long timestamp=System.currentTimeMillis()/1000L;
        String input=timestamp+" "+sapisid+" "+MUSIC_ORIGIN;
        byte[] digest=MessageDigest.getInstance("SHA-1").digest(
            input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();
        for(byte b:digest)hex.append(String.format(Locale.US,"%02x",b&0xff));
        return "SAPISIDHASH "+timestamp+"_"+hex;
    }

    private static String defaultVersion(){
        SimpleDateFormat f=new SimpleDateFormat("yyyyMMdd",Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "1."+f.format(new Date())+".01.00";
    }

    /** Read current ytcfg from same account's YT Music web origin. */
    private static JSONObject webConfig(String cookie) throws Exception {
        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)new URL(MUSIC_ORIGIN+"/").openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(13000);
            connection.setRequestProperty("Cookie",cookie);
            connection.setRequestProperty("User-Agent",USER_AGENT);
            connection.setRequestProperty("Accept","text/html");
            if(connection.getResponseCode()==401||
                connection.getResponseCode()==403)throw new Unauthorized();
            if(connection.getResponseCode()!=200)return defaultConfig();
            String html=readLimited(connection.getInputStream(),1700000);
            int pos=0;
            for(int tries=0;tries<20;tries++){
                int marker=html.indexOf("ytcfg.set(",pos);
                if(marker<0)break;
                pos=marker+10;
                int start=html.indexOf('{',pos);
                int end=html.indexOf(");",start);
                if(start<0||end<=start||end-start>400000)continue;
                try{
                    JSONObject cfg=new JSONObject(html.substring(start,end));
                    if(cfg.optJSONObject("INNERTUBE_CONTEXT")!=null ||
                        cfg.has("INNERTUBE_API_KEY"))
                        return cfg;
                }catch(Exception ignored){}
            }
            return defaultConfig();
        }finally{
            if(connection!=null)connection.disconnect();
        }
    }

    private static JSONObject defaultConfig() throws Exception {
        return new JSONObject().put("INNERTUBE_CONTEXT_CLIENT_VERSION",
            defaultVersion());
    }

    private static String selectedAccountIndex(JSONObject cfg){
        Object raw=cfg.opt("SESSION_INDEX");
        String index=raw==null?"0":String.valueOf(raw);
        return index.matches("[0-9]{1,2}")?index:"0";
    }

    private static JSONObject api(Auth auth,JSONObject cfg,String browseId,
                                   String continuation) throws Exception {
        JSONObject clientCfg=path(cfg,"INNERTUBE_CONTEXT","client");
        String version=cfg.optString("INNERTUBE_CONTEXT_CLIENT_VERSION","");
        if(version.isEmpty()&&clientCfg!=null)
            version=clientCfg.optString("clientVersion","");
        if(!version.matches("[0-9][0-9.]{5,45}"))version=defaultVersion();
        String visitor=cfg.optString("VISITOR_DATA","");
        if(visitor.isEmpty()&&clientCfg!=null)
            visitor=clientCfg.optString("visitorData","");
        String key=cfg.optString("INNERTUBE_API_KEY","");
        String endpoint=YTM_BROWSER;
        if(key.matches("[A-Za-z0-9_-]{20,80}"))endpoint+="&key="+key;
        JSONObject client=new JSONObject()
            .put("clientName","WEB_REMIX").put("clientVersion",version)
            .put("hl","ko").put("gl","KR");
        if(!visitor.isEmpty())client.put("visitorData",visitor);
        JSONObject body=new JSONObject()
            .put("context",new JSONObject().put("client",client));
        if(continuation!=null)body.put("continuation",continuation);
        else body.put("browseId",browseId);

        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Content-Type","application/json; charset=UTF-8");
            connection.setRequestProperty("Accept","application/json");
            connection.setRequestProperty("Cookie",auth.cookie);
            connection.setRequestProperty("Authorization",authorization(auth.sapisid));
            connection.setRequestProperty("X-Origin",MUSIC_ORIGIN);
            connection.setRequestProperty("Origin",MUSIC_ORIGIN);
            connection.setRequestProperty("Referer",MUSIC_ORIGIN+"/");
            connection.setRequestProperty("X-Goog-Api-Format-Version","1");
            connection.setRequestProperty("X-Goog-AuthUser",
                selectedAccountIndex(cfg));
            String delegated=cfg.optString("DELEGATED_SESSION_ID","");
            if(delegated.matches("[A-Za-z0-9_-]{8,180}"))
                connection.setRequestProperty("X-Goog-PageId",delegated);
            connection.setRequestProperty("X-YouTube-Client-Name","67");
            connection.setRequestProperty("X-YouTube-Client-Version",version);
            if(!visitor.isEmpty())
                connection.setRequestProperty("X-Goog-Visitor-Id",visitor);
            connection.setRequestProperty("User-Agent",USER_AGENT);
            byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream out=connection.getOutputStream()){
                out.write(payload);
            }
            int code=connection.getResponseCode();
            if(code==401||code==403)throw new Unauthorized();
            if(code!=200)throw new IllegalStateException("YTM HTTP status "+code);
            return new JSONObject(readLimited(connection.getInputStream(),MAX_RESPONSE));
        }finally{
            if(connection!=null)connection.disconnect();
        }
    }

    private static String readLimited(InputStream input,int max) throws Exception {
        try(InputStream in=input;ByteArrayOutputStream output=
                new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];
            int n;
            while((n=in.read(buf))!=-1){
                if(output.size()+n>max)throw new IllegalStateException("Response too large");
                output.write(buf,0,n);
            }
            return output.toString("UTF-8");
        }
    }

    private static void collectEntries(JSONObject response,String mode,String playlist,
        Map<String,MediaBrowserCompat.MediaItem> result){
        walk(response,mode,playlist,result,0,new int[]{0});
    }

    private static void walk(Object value,String mode,String playlist,
            Map<String,MediaBrowserCompat.MediaItem> result,int depth,int[] seen){
        if(value==null||depth>22||seen[0]++>45000||
            result.size()>=MAX_RESULTS)return;
        if(value instanceof JSONArray){
            JSONArray array=(JSONArray)value;
            for(int i=0;i<array.length()&&i<1800;i++)
                walk(array.opt(i),mode,playlist,result,depth+1,seen);
            return;
        }
        if(!(value instanceof JSONObject))return;
        JSONObject node=(JSONObject)value;
        if(MODE_PLAYLISTS.equals(mode)){
            addPlaylist(node.optJSONObject("musicTwoRowItemRenderer"),result);
            addPlaylist(node.optJSONObject("musicResponsiveListItemRenderer"),result);
        }else{
            addSong(node.optJSONObject("musicResponsiveListItemRenderer"),
                playlist,result);
        }
        JSONArray keys=node.names();
        if(keys!=null)for(int i=0;i<keys.length()&&i<130;i++)
            walk(node.opt(keys.optString(i)),mode,playlist,result,depth+1,seen);
    }

    private static void addPlaylist(JSONObject row,
        Map<String,MediaBrowserCompat.MediaItem> result){
        if(row==null)return;
        JSONObject nav=row.optJSONObject("navigationEndpoint");
        String browse=nav==null?"":str(path(nav,"browseEndpoint"),"browseId");
        if(!browse.startsWith("VL")){
            JSONArray flex=row.optJSONArray("flexColumns");
            browse=str(path(flex==null?null:flex.optJSONObject(0),
                "musicResponsiveListItemFlexColumnRenderer","text",
                "runs"),"browseId");
        }
        if(!browse.startsWith("VL")){
            browse=findBrowseId(row,0,new int[]{0});
        }
        // Only real playlist browse IDs; do not invent placeholder titles.
        if(!browse.startsWith("VL"))return;
        String id=PolarisYtmAccountIds.playlist(browse);
        if(id==null||id.equals("LM")||id.equals("SE"))return;
        String title=text(row.optJSONObject("title"));
        if(title.isEmpty()){
            JSONArray flex=row.optJSONArray("flexColumns");
            title=text(path(flex==null?null:flex.optJSONObject(0),
                "musicResponsiveListItemFlexColumnRenderer","text"));
        }
        if(title.isEmpty())return;
        String subtitle=text(row.optJSONObject("subtitle"));
        if(subtitle.isEmpty()){
            JSONArray flex=row.optJSONArray("flexColumns");
            subtitle=text(path(flex==null?null:flex.optJSONObject(1),
                "musicResponsiveListItemFlexColumnRenderer","text"));
        }
        String key=PolarisYtmCatalog.ACCOUNT_PLAYLIST+Uri.encode(id);
        result.put(key,folder(key,title,subtitle));
    }

    private static void addSong(JSONObject row,String playlist,
        Map<String,MediaBrowserCompat.MediaItem> result){
        if(row==null)return;
        JSONArray flex=row.optJSONArray("flexColumns");
        String title=text(path(flex==null?null:flex.optJSONObject(0),
            "musicResponsiveListItemFlexColumnRenderer","text"));
        if(title.isEmpty())return;
        String artist=text(path(flex==null?null:flex.optJSONObject(1),
            "musicResponsiveListItemFlexColumnRenderer","text"));
        JSONObject data=row.optJSONObject("playlistItemData");
        String video=data==null?"":data.optString("videoId","");
        if(video.isEmpty()){
            JSONObject nav=row.optJSONObject("navigationEndpoint");
            video=str(nav==null?null:nav.optJSONObject("watchEndpoint"),"videoId");
        }
        if(video.isEmpty()){
            video=str(path(row,"overlay","musicItemThumbnailOverlayRenderer",
                "content","musicPlayButtonRenderer","playNavigationEndpoint",
                "watchEndpoint"),"videoId");
        }
        if(PolarisYtmAccountIds.video(video)==null)return;
        String key=PolarisYtmCatalog.PLAY+Uri.encode(video)+
            (playlist==null?"":"?list="+Uri.encode(playlist));
        // Preserve repeated occurrences in playlist if source specifies
        // playlistSetVideoId; otherwise identical videos are collapsed.
        String setId=row.optString("playlistSetVideoId","");
        String dedup=key+(setId.isEmpty()?"":"#"+setId);
        result.put(dedup,playable(key,title,artist));
    }

    private static JSONObject path(JSONObject start,String... names){
        JSONObject node=start;
        for(String k:names){
            node=node==null?null:node.optJSONObject(k);
            if(node==null)return null;
        }
        return node;
    }

    private static String str(JSONObject obj,String key){
        return obj==null?"":obj.optString(key,"");
    }

    private static String text(JSONObject obj){
        if(obj==null)return "";
        JSONArray runs=obj.optJSONArray("runs");
        if(runs!=null){
            StringBuilder out=new StringBuilder();
            for(int i=0;i<runs.length()&&i<50;i++){
                JSONObject run=runs.optJSONObject(i);
                if(run!=null)out.append(run.optString("text",""));
            }
            return out.toString().trim();
        }
        return obj.optString("simpleText","").trim();
    }

    private static String findBrowseId(Object value,int depth,int[] seen){
        if(value==null||depth>15||seen[0]++>9000)return "";
        if(value instanceof JSONArray){
            JSONArray a=(JSONArray)value;
            for(int i=0;i<a.length()&&i<100;i++){
                String id=findBrowseId(a.opt(i),depth+1,seen);
                if(id.startsWith("VL"))return id;
            }
            return "";
        }
        if(!(value instanceof JSONObject))return "";
        JSONObject obj=(JSONObject)value;
        String direct=str(obj.optJSONObject("browseEndpoint"),"browseId");
        if(direct.startsWith("VL"))return direct;
        JSONArray keys=obj.names();
        if(keys!=null)for(int i=0;i<keys.length()&&i<60;i++){
            String id=findBrowseId(obj.opt(keys.optString(i)),depth+1,seen);
            if(id.startsWith("VL"))return id;
        }
        return "";
    }

    private static String findContinuation(Object value,int depth,int[] seen){
        if(value==null||depth>24||seen[0]++>70000)return null;
        if(value instanceof JSONArray){
            JSONArray a=(JSONArray)value;
            for(int i=0;i<a.length()&&i<1800;i++){
                String found=findContinuation(a.opt(i),depth+1,seen);
                if(found!=null)return found;
            }
            return null;
        }
        if(!(value instanceof JSONObject))return null;
        JSONObject obj=(JSONObject)value;
        String[] paths={"continuationItemRenderer","continuationEndpoint",
            "continuationCommand"};
        String fromItem=str(path(obj,paths),"token");
        if(!fromItem.isEmpty())return fromItem;
        String fromDirect=str(path(obj,"nextContinuationData"),"continuation");
        if(!fromDirect.isEmpty())return fromDirect;
        String fromReload=str(path(obj,"reloadContinuationData"),"continuation");
        if(!fromReload.isEmpty())return fromReload;
        JSONArray keys=obj.names();
        if(keys!=null)for(int i=0;i<keys.length()&&i<100;i++){
            String found=findContinuation(obj.opt(keys.optString(i)),
                depth+1,seen);
            if(found!=null)return found;
        }
        return null;
    }

    private static MediaBrowserCompat.MediaItem folder(
            String id,String title,String subtitle){
        MediaDescriptionCompat.Builder b=new MediaDescriptionCompat.Builder()
            .setMediaId(id).setTitle(title);
        if(subtitle!=null&&!subtitle.isEmpty())b.setSubtitle(subtitle);
        return new MediaBrowserCompat.MediaItem(b.build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private static MediaBrowserCompat.MediaItem playable(
            String id,String title,String artist){
        MediaDescriptionCompat.Builder b=new MediaDescriptionCompat.Builder()
            .setMediaId(id).setTitle(title);
        if(artist!=null&&!artist.isEmpty())b.setSubtitle(artist);
        return new MediaBrowserCompat.MediaItem(b.build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }
}
