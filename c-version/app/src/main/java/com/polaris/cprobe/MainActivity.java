package com.polaris.cprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Isolated C06 experiment: one text field -> public YT Music search ->
 * official YT Music deep-link launch. No player, cookies or Polaris service.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "PolarisC06";
    private static final String YTM_PACKAGE = "com.google.android.apps.youtube.music";
    private static final String YTM_SEARCH = YtmSearchProtocol.SEARCH_URL;
    // Public WEB_REMIX key embedded in the YT Music web client, not a user secret.
    private static final String YTM_PUBLIC_KEY =
        "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";
    private static final String CLIENT_VERSION = "1.20260114.03.00";
    private static final String SONGS_FILTER =
        YtmSearchProtocol.SONGS_FILTER;
    private static final int MAX_JSON_BYTES = 3 * 1024 * 1024;
    private static final int MAX_BOOTSTRAP_BYTES = 2 * 1024 * 1024;
    // Mirror public ytmusicapi 1.12.3 unauthenticated WEB_REMIX requests.
    private static final String WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) " +
        "Gecko/20100101 Firefox/88.0";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText text;
    private Button execute;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding(dp(24), dp(24), dp(24), dp(24));
        page.setBackgroundColor(Color.rgb(21, 23, 28));

        text = new EditText(this);
        text.setHint("곡명 / 아티스트 입력");
        text.setHintTextColor(0xFFAAAAAA);
        text.setTextColor(Color.WHITE);
        text.setSingleLine(true);
        text.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text.setImeOptions(EditorInfo.IME_ACTION_GO);
        page.addView(text, new LinearLayout.LayoutParams(-1, dp(56)));

        execute = new Button(this);
        execute.setText("실행");
        LinearLayout.LayoutParams buttonParams =
            new LinearLayout.LayoutParams(-1, dp(58));
        buttonParams.topMargin = dp(16);
        page.addView(execute, buttonParams);

        execute.setOnClickListener(v -> runSearchAndPlay());
        text.setOnEditorActionListener((v, action, event) -> {
            if(action == EditorInfo.IME_ACTION_GO) {
                runSearchAndPlay();
                return true;
            }
            return false;
        });
        setContentView(page);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void tell(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void runSearchAndPlay() {
        final String query = text.getText().toString().trim();
        if(query.isEmpty()) {
            tell("검색할 곡명을 입력하세요.");
            return;
        }
        if(!isYtmInstalled()) {
            tell("공식 YouTube Music 앱이 설치되어 있지 않습니다.");
            return;
        }
        if(!execute.isEnabled()) return;
        execute.setEnabled(false);
        tell("YT Music에서 곡 검색 중…");

        worker.execute(() -> {
            SearchOutcome outcome = null;
            String error = null;
            try {
                outcome = searchMatchingSong(query);
                if(outcome.song == null)
                    error = "일치하는 곡 없음 · " + outcome.diagnostic;
            } catch (Exception e) {
                error = "검색 오류: " + e.getClass().getSimpleName() +
                    (e.getMessage() != null ? " (" + e.getMessage() + ")" : "");
                Log.w(TAG, "C06 search request failed", e);
            }
            final SongSelector.Candidate selected =
                outcome == null ? null : outcome.song;
            final String failure = error;
            runOnUiThread(() -> {
                if(isFinishing() || isDestroyed()) return;
                execute.setEnabled(true);
                if(selected == null) {
                    showFailureReport(query, failure == null ? "검색 실패" : failure);
                    return;
                }
                openOfficialYtmTrack(selected);
            });
        });
    }

    // Keep the main screen at EXACTLY one text field and one Execute button.
    // On failure only, let the user inspect and copy what the *phone itself*
    // received; no PC ADB required, no login cookies or private tokens logged.
    private void showFailureReport(String query,String details){
        String report="C06 · YT Music 검색 진단\n\n" +
            "실제 입력: " + query + "\n\n" + details +
            "\n\n잘못된 곡은 자동 재생하지 않았습니다.";
        TextView info=new TextView(this);
        info.setText(report);
        info.setTextIsSelectable(true);
        info.setTextSize(14f);
        info.setPadding(dp(18),dp(14),dp(18),dp(14));
        ScrollView scrolling=new ScrollView(this);
        scrolling.addView(info);
        new AlertDialog.Builder(this)
            .setTitle("검색 실패 · C06 진단")
            .setView(scrolling)
            .setPositiveButton("진단 복사",(dialog,which)->{
                ClipboardManager cm=(ClipboardManager)getSystemService(
                    Context.CLIPBOARD_SERVICE);
                if(cm!=null)cm.setPrimaryClip(
                    ClipData.newPlainText("Polaris C06 Search",report));
                tell("검색 결과 진단을 복사했습니다.");
            })
            .setNegativeButton("닫기",null)
            .show();
    }

    private boolean isYtmInstalled() {
        try {
            getPackageManager().getPackageInfo(YTM_PACKAGE, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openOfficialYtmTrack(SongSelector.Candidate song) {
        // The only playback action is handed to the *installed official app*.
        // startActivity succeeding is NOT evidence that YT Music actually played.
        Uri link = Uri.parse("https://music.youtube.com/watch?v=" + song.id);
        Intent request = new Intent(Intent.ACTION_VIEW, link);
        request.setPackage(YTM_PACKAGE);
        request.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(request);
            Log.i(TAG, "C06 matched " + song.title + " / " + song.artist +
                " -> " + song.id);
            tell("재생 요청: " + song.artist + " - " + song.title);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Official YT Music cannot open watch URI", e);
            tell("YT Music 앱이 곡 링크를 열지 못했습니다.");
        }
    }

    private static final class SearchOutcome {
        final SongSelector.Candidate song;
        final String diagnostic;
        SearchOutcome(SongSelector.Candidate song,String diagnostic){
            this.song=song;
            this.diagnostic=diagnostic;
        }
    }

    private static final class ScanStats {
        int allRows;
        int validVideoIds;
        int missingVideoIds;
        int emptyTitles;
        int emptyArtists;
        int accepted;
        int songRows;
        int videoRows;
        int episodeRows;
        int unknownRows;
        int musicShelves;
        int twoRowItems;
        int otherShelves;
        int itemSections;
        int messageRenderers;
        int tabbedSections;
    }

    private static String resultCategory(JSONArray columns){
        if(columns==null||columns.length()<2)return "unknown";
        JSONObject flex=columns.optJSONObject(1);
        JSONObject x=flex==null?null:
            flex.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        JSONObject text=x==null?null:x.optJSONObject("text");
        JSONArray runs=text==null?null:text.optJSONArray("runs");
        JSONObject first=runs==null?null:runs.optJSONObject(0);
        return first==null?"":first.optString("text","");
    }

    private static final class SearchClient {
        final String apiKey, version, label;
        String visitorId;
        SearchClient(String apiKey,String version,String visitorId,String label){
            this.apiKey=apiKey;
            this.version=version;
            this.visitorId=visitorId;
            this.label=label;
        }
    }

    private static final SearchClient LEGACY_CLIENT = new SearchClient(
        YTM_PUBLIC_KEY,CLIENT_VERSION,"","기존");

    private static String htmlConfig(String html,String key) {
        Matcher matcher=Pattern.compile(
            "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]{1,256})\"")
            .matcher(html);
        return matcher.find()?matcher.group(1):"";
    }

    /** Anonymous music.youtube.com session bootstrap: never read login cookies. */
    private static SearchClient discoverPublicWebClient() throws Exception {
        HttpURLConnection http=(HttpURLConnection)
            new URL("https://music.youtube.com/").openConnection();
        http.setRequestMethod("GET");
        http.setConnectTimeout(8000);
        http.setReadTimeout(8000);
        http.setRequestProperty("User-Agent",WEB_USER_AGENT);
        http.setRequestProperty("Accept","text/html");
        http.setRequestProperty("Cookie",YtmSearchProtocol.CONSENT_COOKIE);
        try {
            int code=http.getResponseCode();
            if(code!=200)throw new IOException("YTM bootstrap HTTP "+code);
            String html;
            try(InputStream input=http.getInputStream();
                ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] bytes=new byte[8192];
                int n;
                while((n=input.read(bytes))!=-1){
                    if(output.size()+n>MAX_BOOTSTRAP_BYTES)
                        throw new IOException("YTM bootstrap too large");
                    output.write(bytes,0,n);
                }
                html=output.toString(StandardCharsets.UTF_8.name());
            }
            String key=htmlConfig(html,"INNERTUBE_API_KEY");
            String version=htmlConfig(html,"INNERTUBE_CONTEXT_CLIENT_VERSION");
            if(version.isEmpty())version=htmlConfig(html,"clientVersion");
            String visitor=htmlConfig(html,"VISITOR_DATA");
            if(visitor.isEmpty())visitor=htmlConfig(html,"visitorData");
            if(!key.matches("[A-Za-z0-9_-]{30,100}") ||
                    !version.matches("[0-9][A-Za-z0-9._-]{4,60}"))
                throw new IOException("YTM bootstrap client config missing");
            if(!visitor.matches("[A-Za-z0-9_%-]{8,512}"))visitor="";
            return new SearchClient(key,version,visitor,"새 세션");
        } finally {http.disconnect();}
    }

    private static SongSelector.Candidate collectAttempt(
            String original,String q,boolean filtered,
            SearchClient client,String country,String language,
            Map<String,SongSelector.Candidate> merged,
            List<String> diagnostics,int pass){
        ScanStats stats=new ScanStats();
        String label="#"+pass+" "+client.label+" "+country+" ["+
            (filtered?"노래":"전체")+"] \""+q+"\"";
        try {
            JSONObject response=postYtmSearch(q,filtered,client,country,language);
            // YouTube may mint a public visitor ID in the first response,
            // even when the anonymous page lacks VISITOR_DATA. Persist only
            // this session identifier, never auth cookies or account data.
            JSONObject responseContext=response.optJSONObject("responseContext");
            String seen=responseContext==null?"":
                responseContext.optString("visitorData","");
            if(client.visitorId.isEmpty() &&
               seen.matches("[A-Za-z0-9_%-]{8,512}"))
                client.visitorId=seen;
            List<SongSelector.Candidate> items=
                collectSongs(response,stats,filtered);
            for(SongSelector.Candidate song:items)
                merged.putIfAbsent(song.id,song);
            StringBuilder status=new StringBuilder(label).append(" : ")
                .append("목록 ").append(stats.allRows)
                .append(" / 음악폴더 ").append(stats.musicShelves)
                .append(" / 아이템섹션 ").append(stats.itemSections)
                .append(" / 탭 ").append(stats.tabbedSections)
                .append(" / 안내메시지 ").append(stats.messageRenderers)
                .append(" / 카드 ").append(stats.twoRowItems)
                .append(" / 노래 ").append(stats.songRows)
                .append(" / 영상 ").append(stats.videoRows)
                .append(" / 에피소드 ").append(stats.episodeRows)
                .append(" / 기타 ").append(stats.unknownRows)
                .append(" / ID ").append(stats.validVideoIds)
                .append(" / ID누락 ").append(stats.missingVideoIds)
                .append(" / 음악수집 ").append(stats.accepted)
                .append(" / 누적 ").append(merged.size())
                .append(" / 방문자 ").append(
                    client.visitorId.isEmpty()?"없음":"있음");
            if(stats.allRows==0)
                status.append(" / 응답최상위 ").append(rootKeys(response));
            diagnostics.add(status.toString());
            SongSelector.Selection chosen=SongSelector.select(original,
                new ArrayList<>(merged.values()));
            if(chosen!=null){
                Log.i(TAG,"C06 matched "+chosen.song.title+
                    " / "+chosen.song.artist+" score="+chosen.score);
                return chosen.song;
            }
        } catch(Exception ex){
            diagnostics.add(label+" : 응답/파싱 오류 "+
                ex.getClass().getSimpleName()+" "+safeError(ex.getMessage()));
            Log.w(TAG,"C06 search "+label+" failed",ex);
        }
        return null;
    }

    private static String rootKeys(JSONObject json){
        List<String> keys=new ArrayList<>();
        Iterator<String> iterator=json.keys();
        while(iterator.hasNext() && keys.size()<8)
            keys.add(iterator.next());
        return keys.toString();
    }

    private static SearchOutcome searchMatchingSong(String query) throws Exception {
        // Never fabricate a song ID and never play the first arbitrary video.
        Map<String,SongSelector.Candidate> songs=new LinkedHashMap<>();
        List<String> attempts=new ArrayList<>();
        int pass=0;
        SongSelector.Candidate selected;

        // Stage 1: the C01-compatible public client that returned only
        // podcast episodes on the user's phone, though it worked in CI.
        for(boolean filtered:new boolean[]{true,false}){
            selected=collectAttempt(query,query,filtered,LEGACY_CLIENT,
                "KR","ko",songs,attempts,++pass);
            if(selected!=null)return new SearchOutcome(selected,"");
        }

        // Stage 2: bootstrap current public WEB_REMIX key, client version
        // and visitor token from the official YT Music webpage.
        SearchClient live=null;
        try{
            live=discoverPublicWebClient();
            attempts.add("음악 웹 클라이언트: 최신 버전 확인"+
                (live.visitorId.isEmpty()?" (방문자 ID 없음)":" (방문자 ID 있음)"));
        }catch(Exception e){
            attempts.add("음악 웹 클라이언트 갱신 실패: "+
                e.getClass().getSimpleName());
        }
        if(live!=null) {
            for(boolean filtered:new boolean[]{true,false}){
                selected=collectAttempt(query,query,filtered,live,
                    "KR","ko",songs,attempts,++pass);
                if(selected!=null)return new SearchOutcome(selected,"");
            }
        }

        // Stage 3: a second country/language view can expose actual songs
        // when the local anonymous results unexpectedly contain podcasts.
        SearchClient fallback=live==null?LEGACY_CLIENT:live;
        for(boolean filtered:new boolean[]{true,false}){
            selected=collectAttempt(query,query,filtered,fallback,
                "US","en",songs,attempts,++pass);
            if(selected!=null)return new SearchOutcome(selected,"");
        }

        // Stage 4: only a couple of safe query variants, with exact matching
        // always applied to the ORIGINAL user text.
        String[] parts=query.trim().split("\\s+");
        LinkedHashSet<String> variants=new LinkedHashSet<>();
        if(parts.length==2){
            variants.add(parts[1]+" "+parts[0]);
            variants.add(parts[0]+" \""+parts[1]+"\"");
        }
        if(parts.length>=2) {
            String tail=parts[parts.length-1];
            if(tail.length()>2 && (tail.endsWith("는")||
                    tail.endsWith("은")||tail.endsWith("을")||
                    tail.endsWith("를")))
                variants.add(query.substring(0,query.length()-1));
        }
        for(String variant:variants){
            selected=collectAttempt(query,variant,true,fallback,
                "KR","ko",songs,attempts,++pass);
            if(selected!=null)return new SearchOutcome(selected,"");
            if(pass>=9)break;
        }

        StringBuilder report=new StringBuilder("검색 단계별 결과\n");
        for(String status:attempts)
            report.append(status).append('\n');
        report.append("\n음악 후보 총 ").append(songs.size()).append("개\n");
        List<SongSelector.Candidate> ranked=new ArrayList<>(songs.values());
        ranked.sort((x,y)->Integer.compare(
            SongSelector.inspectScore(query,y),
            SongSelector.inspectScore(query,x)));
        report.append("\n음악 후보 (곡명 / 가수 / 일치 점수)\n");
        for(int i=0;i<Math.min(12,ranked.size());i++){
            SongSelector.Candidate item=ranked.get(i);
            report.append(i+1).append(". ")
                .append(item.title).append(" / ").append(item.artist)
                .append(" / ").append(SongSelector.inspectScore(query,item))
                .append('\n');
        }
        if(ranked.isEmpty())report.append("(수집된 음악 후보 없음)\n");
        report.append("\n로그인은 시도하지 않았고 임의 곡도 실행하지 않았습니다.");
        return new SearchOutcome(null,report.toString());
    }

    private static String safeError(String input){
        if(input==null)return "";
        // Error is local-only in a user-approved diagnostic dialog. Avoid
        // exposing request URLs or verbose authentication/network internals.
        if(input.matches("YT Music HTTP [1-5][0-9]{2}"))
            return input;
        return "";
    }

    private static JSONObject postYtmSearch(String query,boolean songsOnly,
            SearchClient cfg,String country,String language)throws Exception {
        JSONObject client = new JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion",cfg.version)
            .put("hl",language)
            .put("gl",country);
        if(!cfg.visitorId.isEmpty())
            client.put("visitorData",cfg.visitorId);
        JSONObject context = new JSONObject()
            .put("client",client).put("user",new JSONObject());
        JSONObject payload = new JSONObject()
            .put("context", context)
            .put("query", query);
        if(songsOnly) payload.put("params", SONGS_FILTER);
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection http = (HttpURLConnection) new URL(YTM_SEARCH+"&key="+cfg.apiKey)
            .openConnection();
        http.setRequestMethod("POST");
        http.setConnectTimeout(10000);
        http.setReadTimeout(12000);
        http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        http.setRequestProperty("Accept", "application/json");
        http.setRequestProperty("Accept-Language",
            language.equals("ko")?"ko-KR,ko;q=0.9,en;q=0.6":
                "en-US,en;q=0.9,ko;q=0.6");
        http.setRequestProperty("X-YouTube-Client-Name", "67");
        http.setRequestProperty("X-YouTube-Client-Version",cfg.version);
        http.setRequestProperty("X-Goog-Api-Key",cfg.apiKey);
        if(!cfg.visitorId.isEmpty())
            http.setRequestProperty("X-Goog-Visitor-Id",cfg.visitorId);
        http.setRequestProperty("Origin", "https://music.youtube.com");
        http.setRequestProperty("Referer", "https://music.youtube.com/");
        http.setRequestProperty("Cookie",YtmSearchProtocol.CONSENT_COOKIE);
        http.setRequestProperty("User-Agent",WEB_USER_AGENT);
        http.setDoOutput(true);
        try {
            try(OutputStream output = http.getOutputStream()) {
                output.write(bytes);
            }
            int status = http.getResponseCode();
            if(status != HttpURLConnection.HTTP_OK)
                throw new IOException("YT Music HTTP " + status);
            try(InputStream response = http.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while((n = response.read(buffer)) != -1) {
                    if(out.size() + n > MAX_JSON_BYTES)
                        throw new IOException("YT Music response too large");
                    out.write(buffer, 0, n);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            http.disconnect();
        }
    }

    private static List<SongSelector.Candidate> collectSongs(
            JSONObject response,ScanStats stats,boolean filtered){
        Map<String,SongSelector.Candidate> byId=new LinkedHashMap<>();
        walk(response,new int[]{0},0,byId,stats,filtered);
        return new ArrayList<>(byId.values());
    }

    private static void walk(Object node,int[] visited,int depth,
            Map<String,SongSelector.Candidate> result,ScanStats stats,
            boolean filtered){
        if(node==null || depth>32 || ++visited[0]>30000 ||
                result.size()>=100)return;
        if(node instanceof JSONObject){
            JSONObject object=(JSONObject)node;
            if(object.has("musicShelfRenderer"))stats.musicShelves++;
            if(object.has("musicTwoRowItemRenderer"))stats.twoRowItems++;
            if(object.has("shelfRenderer"))stats.otherShelves++;
            if(object.has("itemSectionRenderer"))stats.itemSections++;
            if(object.has("messageRenderer"))stats.messageRenderers++;
            if(object.has("tabbedSearchResultsRenderer"))stats.tabbedSections++;
            JSONObject renderer=object.optJSONObject("musicResponsiveListItemRenderer");
            if(renderer!=null){
                stats.allRows++;
                JSONArray columns=renderer.optJSONArray("flexColumns");
                String label=resultCategory(columns);
                String videoType=findMusicVideoType(renderer);
                String category=MusicRowType.resolve(label,videoType,filtered);
                if("song".equals(category))stats.songRows++;
                else if("video".equals(category))stats.videoRows++;
                else if("episode".equals(category))stats.episodeRows++;
                else stats.unknownRows++;

                String id=findVideoId(renderer);
                if(isVideoId(id)){
                    stats.validVideoIds++;
                    // Neither podcasts nor arbitrary videos should EVER
                    // become song candidates, even with a playable videoId.
                    if("song".equals(category)){
                        String title=columns==null?"":
                            firstText(columns.optJSONObject(0));
                        String artist=columns==null?"":
                            firstArtist(columns);
                        String album=columns==null?"":
                            firstAlbum(columns);
                        if(title.isEmpty())stats.emptyTitles++;
                        if(artist.isEmpty())stats.emptyArtists++;
                        if(!title.isEmpty()&&!artist.isEmpty()&&!result.containsKey(id)){
                            result.put(id,new SongSelector.Candidate(
                                id,title,artist,album,result.size()));
                            stats.accepted++;
                        }
                    }
                }else stats.missingVideoIds++;
            }
            Iterator<String> keys=object.keys();
            while(keys.hasNext()){
                walk(object.opt(keys.next()),visited,depth+1,result,stats,filtered);
                if(result.size()>=100)return;
            }
        }else if(node instanceof JSONArray){
            JSONArray arr=(JSONArray)node;
            for(int i=0;i<arr.length();i++){
                walk(arr.opt(i),visited,depth+1,result,stats,filtered);
                if(result.size()>=100)return;
            }
        }
    }

    private static String firstArtist(JSONArray columns) {
        if(columns.length()<=1)return "";
        List<String> credited=new ArrayList<>();
        // YT Music supplies mixed "Song · singer · album · duration" runs.
        // Verified artist navigation is authoritative; unlinked artists are
        // accepted only in the FIRST plausible singer position.
        for(int i=1;i<Math.min(4,columns.length());i++) {
            JSONObject col=columns.optJSONObject(i);
            JSONObject flex=col==null?null:
                col.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
            JSONObject text=flex==null?null:flex.optJSONObject("text");
            if(text==null)continue;
            JSONArray runs=text.optJSONArray("runs");
            if(runs==null) {
                String simple=text.optString("simpleText","").trim();
                if(i==1 && isLikelyArtist(simple) && credited.isEmpty())
                    credited.add(simple);
                continue;
            }
            boolean sawArtistInColumn=false;
            for(int j=0;j<runs.length();j++) {
                JSONObject run=runs.optJSONObject(j);
                if(run==null)continue;
                String value=run.optString("text","").trim();
                if(!isLikelyArtist(value))continue;
                JSONObject nav=run.optJSONObject("navigationEndpoint");
                JSONObject browse=nav==null?null:nav.optJSONObject("browseEndpoint");
                String browseId=browse==null?"":browse.optString("browseId","");
                if(browseId.startsWith("MPRE") ||
                   browseId.startsWith("MPRD"))break; // Album is not a singer.
                boolean verified=browseId.startsWith("MPLA") ||
                    browseId.startsWith("UC") ||
                    browseId.toLowerCase(java.util.Locale.ROOT).contains("artist");
                if(!verified && (sawArtistInColumn||i>1))continue;
                if(!credited.contains(value))credited.add(value);
                sawArtistInColumn=true;
            }
        }
        return android.text.TextUtils.join(" · ",credited);
    }

    private static String firstAlbum(JSONArray columns) {
        if(columns.length()<=1)return "";
        JSONObject col=columns.optJSONObject(1);
        JSONObject flex=col==null?null:
            col.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        JSONObject text=flex==null?null:flex.optJSONObject("text");
        JSONArray runs=text==null?null:text.optJSONArray("runs");
        if(runs==null)return "";
        boolean artistSeen=false;
        for(int i=0;i<runs.length();i++) {
            JSONObject run=runs.optJSONObject(i);
            if(run==null)continue;
            String label=run.optString("text","").trim();
            if(!isLikelyArtist(label))continue;
            if(!artistSeen){
                artistSeen=true;
                continue;
            }
            // The next meaningful run in the official song search result
            // is its album. Penalize live albums when selecting studio tracks.
            return label;
        }
        return "";
    }

    private static boolean isLikelyArtist(String text) {
        if(text==null||text.isEmpty()||text.equals("•")||text.equals("·")||
           text.equals("|")||text.matches("\\d{4}")||
           text.matches("\\d+:\\d+(?::\\d+)?")) return false;
        for(String category:new String[]{
            "Song","Songs","노래","동영상","Video","Videos",
            "Album","앨범","Single","싱글","EP","Music video",
            "Episode","Episodes","에피소드","팟캐스트","Podcast","Podcast episode"}) {
            if(text.equalsIgnoreCase(category))return false;
        }
        if(text.contains("조회수")||text.contains("회 재생")||
           text.contains("views"))return false;
        return true;
    }

    // ytmusicapi 1.12.3 also relies on this nested watchEndpoint type
    // to distinguish music audio (ATV) from podcasts and ordinary videos.
    private static String findMusicVideoType(JSONObject row) {
        JSONObject overlay=row.optJSONObject("overlay");
        JSONObject thumb=overlay==null?null:
            overlay.optJSONObject("musicItemThumbnailOverlayRenderer");
        JSONObject content=thumb==null?null:thumb.optJSONObject("content");
        JSONObject play=content==null?null:
            content.optJSONObject("musicPlayButtonRenderer");
        JSONObject endpoint=play==null?null:
            play.optJSONObject("playNavigationEndpoint");
        if(endpoint==null) endpoint=row.optJSONObject("navigationEndpoint");
        JSONObject watch=endpoint==null?null:endpoint.optJSONObject("watchEndpoint");
        JSONObject supported=watch==null?null:
            watch.optJSONObject("watchEndpointMusicSupportedConfigs");
        JSONObject config=supported==null?null:
            supported.optJSONObject("watchEndpointMusicConfig");
        return config==null?"":config.optString("musicVideoType","");
    }

    private static String findVideoId(JSONObject row) {
        JSONObject playlistData = row.optJSONObject("playlistItemData");
        String id = playlistData == null ? "" :
            playlistData.optString("videoId", "");
        if(isVideoId(id)) return id;

        JSONObject navigation = row.optJSONObject("navigationEndpoint");
        id = nestedVideoId(navigation);
        if(isVideoId(id)) return id;

        JSONObject overlay = row.optJSONObject("overlay");
        JSONObject thumbnail = overlay == null ? null :
            overlay.optJSONObject("musicItemThumbnailOverlayRenderer");
        JSONObject content = thumbnail == null ? null :
            thumbnail.optJSONObject("content");
        JSONObject playButton = content == null ? null :
            content.optJSONObject("musicPlayButtonRenderer");
        JSONObject endpoint = playButton == null ? null :
            playButton.optJSONObject("playNavigationEndpoint");
        return nestedVideoId(endpoint);
    }

    private static String nestedVideoId(JSONObject endpoint) {
        JSONObject watch = endpoint == null ? null :
            endpoint.optJSONObject("watchEndpoint");
        return watch == null ? "" : watch.optString("videoId", "");
    }

    private static boolean isVideoId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{11}");
    }

    private static String firstText(JSONObject column) {
        JSONObject flex = column == null ? null :
            column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        JSONObject text = flex == null ? null : flex.optJSONObject("text");
        if(text == null) return "";
        JSONArray runs = text.optJSONArray("runs");
        if(runs == null) return text.optString("simpleText","");
        StringBuilder title = new StringBuilder();
        for(int i=0;i<runs.length();i++) {
            JSONObject part=runs.optJSONObject(i);
            if(part!=null)title.append(part.optString("text",""));
        }
        return title.toString().trim();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

}
