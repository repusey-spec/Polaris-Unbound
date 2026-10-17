package com.polarisunbound.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test 03: read-only, unauthenticated YouTube Music browsing experiment.
 * Adapted as an independent implementation of the browse tree documented by
 * Hush/Metrolist. No account cookies, no fictitious recommendations, no streams.
 *
 * PERSONAL LIBRARY IS NOT PROVIDED BY THIS ENDPOINT. A separate opt-in
 * AccessibilityService captures the YTM application's actual visible entries.
 */
final class PolarisYtmCatalog {
    interface Callback {
        void done(List<MediaBrowserCompat.MediaItem> items, String error);
    }

    private static volatile PolarisYtmCatalog shared;
    static PolarisYtmCatalog get(Context context) {
        if (shared == null) synchronized (PolarisYtmCatalog.class) {
            if (shared == null) shared = new PolarisYtmCatalog(context.getApplicationContext());
        }
        return shared;
    }

    static final String HOME = "ytm:home";
    static final String LIBRARY = "ytm:library";
    static final String SHELF = "ytm:shelf:";
    static final String PLAYLIST = "ytm:playlist:";
    static final String BROWSE = "ytm:browse:";
    static final String PLAY = "ytm:play:";
    static final String OPEN_PLAYLIST = "ytm:openplaylist:";
    static final String CAPTURED = "ytm:captured:";
    private static final String TAG = "PolarisYtmCatalog";
    private static final long CACHE_MS = 5 * 60 * 1000L;
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final int MAX_CHILDREN = 120;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String,CacheEntry> cache = new LinkedHashMap<>();
    private List<Section> homeSections = Collections.emptyList();
    private long homeAt = 0;
    private long bootstrapAt = 0;
    private String visitorData = "";
    private String apiKey = "";
    private String clientVersion = "";
    private String bootstrapStatus = "not attempted";
    private static final String WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

    private PolarisYtmCatalog(Context context) { this.context = context; }
    private static final class Section {
        final String title;
        final List<MediaBrowserCompat.MediaItem> items;
        Section(String title,List<MediaBrowserCompat.MediaItem> items) {
            this.title=title; this.items=items;
        }
    }
    private static final class CacheEntry {
        final long timestamp;
        final List<MediaBrowserCompat.MediaItem> items;
        CacheEntry(List<MediaBrowserCompat.MediaItem> items) {
            this.timestamp=System.currentTimeMillis(); this.items=items;
        }
    }

    void load(String parent, Callback callback) {
        worker.execute(() -> {
            List<MediaBrowserCompat.MediaItem> items=Collections.emptyList();
            String error=null;
            try {
                if (LIBRARY.equals(parent)) {
                    items=PolarisYtmSnapshot.items(context);
                } else if (parent.startsWith(CAPTURED)) {
                    // A UI snapshot has names but *not* a YT Music playlist ID.
                    // Never claim this row is playable or silently select a different track.
                    items=Collections.emptyList();
                } else if (HOME.equals(parent) || parent.startsWith(SHELF)) {
                    ensureHome();
                    if (HOME.equals(parent)) {
                        List<MediaBrowserCompat.MediaItem> folders=new ArrayList<>();
                        for (int i=0;i<homeSections.size();i++)
                            folders.add(folder(SHELF+i,homeSections.get(i).title,null,null));
                        items=folders;
                    } else {
                        int i=Integer.parseInt(parent.substring(SHELF.length()));
                        if (i>=0 && i<homeSections.size())
                            items=homeSections.get(i).items;
                    }
                } else if (parent.startsWith(PLAYLIST)) {
                    String playlistId=Uri.decode(parent.substring(PLAYLIST.length()));
                    if (validId(playlistId)) {
                        JSONObject data=request("VL"+(playlistId.startsWith("VL")
                            ? playlistId.substring(2):playlistId));
                        List<MediaBrowserCompat.MediaItem> songs=new ArrayList<>();
                        songs.add(playable(OPEN_PLAYLIST+Uri.encode(playlistId),
                            "▶ 원본 YT Music에서 재생목록 열기",
                            "선택 후 원본 앱에서 재생",null));
                        parseRows(data,songs,playlistId);
                        items=songs;
                    }
                } else if (parent.startsWith(BROWSE)) {
                    String browseId=Uri.decode(parent.substring(BROWSE.length()));
                    if (validId(browseId)) {
                        JSONObject data=request(browseId);
                        List<MediaBrowserCompat.MediaItem> rows=new ArrayList<>();
                        parseRows(data,rows,null);
                        items=rows;
                    }
                }
            } catch (Exception ex) {
                error=ex.getClass().getSimpleName()+": "+ex.getMessage();
                Log.w(TAG,"browse "+parent+" failed",ex);
            }
            final List<MediaBrowserCompat.MediaItem> response=items;
            final String problem=error;
            main.post(() -> callback.done(response,problem));
        });
    }

    private void ensureHome() throws Exception {
        if (!homeSections.isEmpty() && System.currentTimeMillis()-homeAt<CACHE_MS)
            return;
        JSONObject response=request("FEmusic_home");
        JSONObject section=findHomeSectionList(response);
        JSONArray contents=section==null?null:section.optJSONArray("contents");
        List<Section> fresh=new ArrayList<>();
        int renderers=0;
        if(contents!=null)for(int i=0;i<contents.length() && fresh.size()<24;i++){
            JSONObject item=contents.optJSONObject(i);
            if(item==null)continue;
            for(String type:new String[]{"musicCarouselShelfRenderer",
                    "musicShelfRenderer","gridRenderer","musicCardShelfRenderer"}){
                JSONObject renderer=item.optJSONObject(type);
                if(renderer==null)continue;
                renderers++;
                String title=text(path(renderer,"header",
                    "musicCarouselShelfBasicHeaderRenderer","title"));
                if(title.isEmpty())title=text(path(renderer,"header",
                    "musicShelfBasicHeaderRenderer","title"));
                if(title.isEmpty())title=text(renderer.optJSONObject("title"));
                // Do not fabricate titles or recommendations.
                if(title.isEmpty())continue;
                List<MediaBrowserCompat.MediaItem> rows=new ArrayList<>();
                parseRows(renderer.optJSONArray("contents"),rows,null);
                if(!rows.isEmpty())fresh.add(new Section(title,rows));
            }
        }
        homeSections=fresh;
        homeAt=System.currentTimeMillis();
        if(fresh.isEmpty()){
            throw new IllegalStateException(
                "YT Music home returned no usable browse shelves. "+
                "topKeys="+jsonKeys(response)+
                "; contentsKeys="+jsonKeys(response.optJSONObject("contents"))+
                "; sectionNodes="+(contents==null?"missing":contents.length())+
                "; nodeTypes="+firstNodeTypes(contents)+
                "; shelfTypes="+renderers+
                "; visitor="+(!visitorData.isEmpty())+
                "; bootstrap="+bootstrapStatus);
        }
        Log.i(TAG,"public home sections="+fresh.size());
    }

    /** Both one-column and two-column Home variants exist. */
    private static JSONObject findHomeSectionList(JSONObject root){
        JSONObject contents=root.optJSONObject("contents");
        for(JSONObject renderer:new JSONObject[]{
            contents==null?null:contents.optJSONObject("singleColumnBrowseResultsRenderer"),
            contents==null?null:contents.optJSONObject("twoColumnBrowseResultsRenderer")}){
            if(renderer==null)continue;
            JSONArray tabs=renderer.optJSONArray("tabs");
            if(tabs!=null)for(int i=0;i<tabs.length();i++){
                JSONObject section=path(tabs.optJSONObject(i),
                    "tabRenderer","content","sectionListRenderer");
                if(section!=null)return section;
            }
            JSONObject secondary=path(renderer,
                "secondaryContents","sectionListRenderer");
            if(secondary!=null)return secondary;
        }
        JSONObject continuation=path(root,
            "continuationContents","sectionListContinuation");
        return continuation!=null?continuation:root.optJSONObject("sectionListRenderer");
    }

    private static String firstNodeTypes(JSONArray contents){
        if(contents==null)return "(none)";
        StringBuilder result=new StringBuilder();
        for(int i=0;i<contents.length() && i<4;i++){
            if(i>0)result.append("; ");
            JSONObject node=contents.optJSONObject(i);
            result.append(i).append(":").append(jsonKeys(node));
            if(node!=null){
                JSONObject nested=node.optJSONObject("itemSectionRenderer");
                if(nested!=null){
                    JSONArray inner=nested.optJSONArray("contents");
                    result.append(" nested=")
                        .append(inner==null?"(none)":inner.length())
                        .append(" first=").append(
                            jsonKeys(inner==null?null:inner.optJSONObject(0)));
                }
            }
        }
        return result.toString();
    }

    private static String jsonKeys(JSONObject obj){
        if(obj==null)return "(none)";
        JSONArray names=obj.names();
        if(names==null)return "[]";
        StringBuilder text=new StringBuilder("[");
        for(int i=0;i<names.length() && i<12;i++){
            if(i>0)text.append(",");
            text.append(names.optString(i));
        }
        return text.append("]").toString();
    }

    private static String defaultClientVersion(){
        SimpleDateFormat formatter=new SimpleDateFormat("yyyyMMdd",Locale.US);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "1."+formatter.format(new Date())+".01.00";
    }

    /**
     * The anonymous ytmusicapi browser uses SOCS=CAI and first fetches
     * VISITOR_DATA from public ytcfg. This reads public web configuration,
     * never an installed app's login data, cookies, or personal information.
     */
    private void bootstrapWebVisitor(){
        if(bootstrapAt>0 && System.currentTimeMillis()-bootstrapAt<CACHE_MS)
            return;
        bootstrapAt=System.currentTimeMillis();
        clientVersion=defaultClientVersion();
        bootstrapStatus="config unavailable";
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)new URL(
                "https://music.youtube.com/").openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Cookie","SOCS=CAI");
            connection.setRequestProperty("Accept","text/html");
            connection.setRequestProperty("User-Agent",WEB_USER_AGENT);
            int status=connection.getResponseCode();
            if(status!=200){
                bootstrapStatus="website HTTP "+status;
                return;
            }
            try(InputStream in=connection.getInputStream();
                ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];
                int read;
                while((read=in.read(buf))!=-1){
                    if(output.size()+read>1024*1024)break;
                    output.write(buf,0,read);
                }
                String html=output.toString("UTF-8");
                int pos=0;
                for(int tries=0;tries<12;tries++){
                    int marker=html.indexOf("ytcfg.set(",pos);
                    if(marker<0)break;
                    pos=marker+10;
                    int start=html.indexOf("{",pos);
                    int end=html.indexOf(");",start);
                    if(start<0||end<=start||end-start>200000)continue;
                    JSONObject cfg;
                    try{cfg=new JSONObject(html.substring(start,end));}
                    catch(Exception ignored){continue;}
                    String visitor=cfg.optString("VISITOR_DATA","");
                    JSONObject cfgClient=path(cfg,"INNERTUBE_CONTEXT","client");
                    if(visitor.isEmpty() && cfgClient!=null)
                        visitor=cfgClient.optString("visitorData","");
                    if(!visitor.isEmpty())visitorData=visitor;
                    String version=cfg.optString("INNERTUBE_CONTEXT_CLIENT_VERSION","");
                    if(version.isEmpty() && cfgClient!=null)
                        version=cfgClient.optString("clientVersion","");
                    if(version.matches("[0-9][0-9.]+"))clientVersion=version;
                    String key=cfg.optString("INNERTUBE_API_KEY","");
                    if(key.matches("[A-Za-z0-9_-]{20,80}"))apiKey=key;
                }
                bootstrapStatus="site HTTP 200, visitor="+(!visitorData.isEmpty());
            }
        }catch(Exception error){
            bootstrapStatus="website "+error.getClass().getSimpleName();
        }finally{
            if(connection!=null)connection.disconnect();
        }
    }

    private JSONObject request(String browseId) throws Exception {
        if(!validId(browseId))throw new IllegalArgumentException("invalid browseId");
        bootstrapWebVisitor();
        String endpoint="https://music.youtube.com/youtubei/v1/browse?prettyPrint=false";
        if(!apiKey.isEmpty())endpoint+="&key="+apiKey;
        URL url=new URL(endpoint);
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/json; charset=UTF-8");
        connection.setRequestProperty("Cookie","SOCS=CAI");
        if(!visitorData.isEmpty())
            connection.setRequestProperty("X-Goog-Visitor-Id",visitorData);
        connection.setRequestProperty("Accept","application/json");
        connection.setRequestProperty("Origin","https://music.youtube.com");
        connection.setRequestProperty("Referer","https://music.youtube.com/");
        connection.setRequestProperty("X-Goog-Api-Format-Version","1");
        connection.setRequestProperty("X-YouTube-Client-Name","67");
        connection.setRequestProperty("X-YouTube-Client-Version",clientVersion);
        connection.setRequestProperty("User-Agent",WEB_USER_AGENT);
        String country=Locale.getDefault().getCountry();
        String language=Locale.getDefault().getLanguage();
        JSONObject client=new JSONObject()
            .put("clientName","WEB_REMIX")
            .put("clientVersion",clientVersion)
            .put("hl",language.isEmpty()?"ko":language)
            .put("gl",country.isEmpty()?"KR":country);
        if(!visitorData.isEmpty())client.put("visitorData",visitorData);
        JSONObject body=new JSONObject()
            .put("context",new JSONObject().put("client",client)
                .put("user",new JSONObject()))
            .put("browseId",browseId);
        try {
            byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream out=connection.getOutputStream()){out.write(bytes);}
            int code=connection.getResponseCode();
            if(code!=200)throw new IllegalStateException("InnerTube HTTP "+code);
            try(InputStream in=connection.getInputStream();
                ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192]; int count;
                while((count=in.read(buffer))!=-1) {
                    if(output.size()+count>MAX_BYTES)
                        throw new IllegalStateException("browse response too large");
                    output.write(buffer,0,count);
                }
                return new JSONObject(output.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }

    private static boolean validId(String id) {
        return id!=null && id.length()>0 && id.length()<=180 &&
            id.matches("[a-zA-Z0-9_=-]+");
    }
    private static JSONObject object(Object value) {
        return value instanceof JSONObject?(JSONObject)value:null;
    }
    private static JSONObject at(JSONArray a,int index) {
        return a==null?null:a.optJSONObject(index);
    }
    private static JSONObject path(JSONObject base,String... keys) {
        JSONObject value=base;
        for(String key:keys){
            if(value==null)return null;
            value=value.optJSONObject(key);
        }
        return value;
    }
    private static JSONArray pathArray(JSONObject base,String... keys) {
        if(base==null||keys.length==0)return null;
        JSONObject node=base;
        for(int i=0;i<keys.length-1;i++) {
            node=node==null?null:node.optJSONObject(keys[i]);
        }
        return node==null?null:node.optJSONArray(keys[keys.length-1]);
    }
    private static String text(JSONObject runs) {
        if(runs==null)return "";
        JSONArray a=runs.optJSONArray("runs");
        if(a==null)return "";
        StringBuilder builder=new StringBuilder();
        for(int i=0;i<a.length();i++){
            JSONObject row=a.optJSONObject(i);
            if(row!=null)builder.append(row.optString("text",""));
        }
        return builder.toString().trim();
    }
    private static String thumbnail(JSONObject obj) {
        JSONObject thumb=path(obj,"thumbnailRenderer","musicThumbnailRenderer","thumbnail");
        if(thumb==null)thumb=path(obj,"thumbnail","musicThumbnailRenderer","thumbnail");
        JSONArray array=thumb==null?null:thumb.optJSONArray("thumbnails");
        JSONObject last=at(array,array==null?0:array.length()-1);
        return last==null?null:last.optString("url",null);
    }
    private static MediaBrowserCompat.MediaItem folder(String id,String title,
            String subtitle,String art) {
        return createItem(id,title,subtitle,art,true);
    }
    private static MediaBrowserCompat.MediaItem playable(String id,String title,
            String subtitle,String art) {
        return createItem(id,title,subtitle,art,false);
    }
    private static MediaBrowserCompat.MediaItem createItem(String id,String title,
            String subtitle,String art,boolean browsing) {
        MediaDescriptionCompat.Builder b=new MediaDescriptionCompat.Builder()
            .setMediaId(id).setTitle(title).setSubtitle(subtitle);
        if(art!=null && art.startsWith("https://")) {
            try{ b.setIconUri(Uri.parse(art)); }catch(Exception ignored){}
        }
        return new MediaBrowserCompat.MediaItem(b.build(),browsing?
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE:
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
    }

    private static List<MediaBrowserCompat.MediaItem> parseTileList(
            JSONArray tiles,String currentPlaylist) {
        if(tiles==null)return Collections.emptyList();
        LinkedHashMap<String,MediaBrowserCompat.MediaItem> out=new LinkedHashMap<>();
        for(int i=0;i<tiles.length() && out.size()<MAX_CHILDREN;i++) {
            JSONObject obj=tiles.optJSONObject(i);
            if(obj==null)continue;
            MediaBrowserCompat.MediaItem item=parseTwoRow(
                obj.optJSONObject("musicTwoRowItemRenderer"));
            if(item==null)item=parseResponsive(
                obj.optJSONObject("musicResponsiveListItemRenderer"),currentPlaylist);
            if(item==null)continue;
            out.put(item.getMediaId(),item);
        }
        return new ArrayList<>(out.values());
    }

    private static MediaBrowserCompat.MediaItem parseTwoRow(JSONObject row) {
        if(row==null)return null;
        String title=text(row.optJSONObject("title"));
        if(title.isEmpty())return null;
        String subtitle=text(row.optJSONObject("subtitle"));
        JSONObject nav=row.optJSONObject("navigationEndpoint");
        JSONObject browse=nav==null?null:nav.optJSONObject("browseEndpoint");
        JSONObject watch=nav==null?null:nav.optJSONObject("watchEndpoint");
        String art=thumbnail(row);
        if(watch!=null) {
            String videoId=watch.optString("videoId","");
            if(validId(videoId))
                return playable(PLAY+Uri.encode(videoId),title,subtitle,art);
        }
        if(browse==null)return null;
        String browseId=browse.optString("browseId","");
        if(!validId(browseId))return null;
        String pageType="";
        JSONObject configs=path(browse,"browseEndpointContextSupportedConfigs",
            "browseEndpointContextMusicConfig");
        if(configs!=null)pageType=configs.optString("pageType","");
        if(browseId.startsWith("VL")||pageType.contains("PLAYLIST")) {
            String playlistId=browseId.startsWith("VL")?browseId.substring(2):browseId;
            return folder(PLAYLIST+Uri.encode(playlistId),title,subtitle,art);
        }
        return folder(BROWSE+Uri.encode(browseId),title,subtitle,art);
    }

    private static MediaBrowserCompat.MediaItem parseResponsive(JSONObject row,
            String currentPlaylist) {
        if(row==null)return null;
        JSONArray columns=row.optJSONArray("flexColumns");
        JSONObject first=at(columns,0);
        String title=text(path(first,
            "musicResponsiveListItemFlexColumnRenderer","text"));
        if(title.isEmpty())return null;
        String subtitle=text(path(at(columns,1),
            "musicResponsiveListItemFlexColumnRenderer","text"));
        JSONObject nav=row.optJSONObject("navigationEndpoint");
        JSONObject watch=nav==null?null:nav.optJSONObject("watchEndpoint");
        JSONObject playlistData=row.optJSONObject("playlistItemData");
        String videoId=playlistData==null?"":playlistData.optString("videoId","");
        if(videoId.isEmpty() && watch!=null)videoId=watch.optString("videoId","");
        if(!validId(videoId))return null;
        String id=PLAY+Uri.encode(videoId);
        if(currentPlaylist!=null && validId(currentPlaylist))
            id=id+"?list="+Uri.encode(currentPlaylist);
        return playable(id,title,subtitle,thumbnail(row));
    }

    private static void parseRows(Object node,
        List<MediaBrowserCompat.MediaItem> result,String playlistId) {
        // JSON tree search is bounded. The original page renderer can place
        // the playlist shelf in either column, depending on the account.
        if(result.size()>=MAX_CHILDREN)return;
        if(node instanceof JSONObject) {
            JSONObject o=(JSONObject)node;
            JSONObject responsive=o.optJSONObject("musicResponsiveListItemRenderer");
            if(responsive!=null) {
                MediaBrowserCompat.MediaItem row=parseResponsive(responsive,playlistId);
                if(row!=null && !containsId(result,row.getMediaId()))result.add(row);
            }
            JSONObject two=o.optJSONObject("musicTwoRowItemRenderer");
            if(two!=null) {
                MediaBrowserCompat.MediaItem item=parseTwoRow(two);
                if(item!=null && !containsId(result,item.getMediaId()))result.add(item);
            }
            JSONArray names=o.names();
            if(names!=null)for(int i=0;i<names.length() && result.size()<MAX_CHILDREN;i++) {
                String key=names.optString(i,"");
                if("header".equals(key)||"menu".equals(key)||"playerOverlays".equals(key))
                    continue;
                Object child=o.opt(key);
                if(child instanceof JSONObject||child instanceof JSONArray)
                    parseRows(child,result,playlistId);
            }
        } else if(node instanceof JSONArray) {
            JSONArray a=(JSONArray)node;
            for(int i=0;i<a.length() && i<150 && result.size()<MAX_CHILDREN;i++)
                parseRows(a.opt(i),result,playlistId);
        }
    }
    private static boolean containsId(List<MediaBrowserCompat.MediaItem> result,String id) {
        for(MediaBrowserCompat.MediaItem item:result)
            if(id.equals(item.getMediaId()))return true;
        return false;
    }
}
