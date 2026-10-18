package com.polaris.cprobe;

import android.app.Activity;
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
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Isolated C01 experiment: one text field -> public YT Music search ->
 * official YT Music deep-link launch. No player, cookies or Polaris service.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "PolarisC01";
    private static final String YTM_PACKAGE = "com.google.android.apps.youtube.music";
    private static final String YTM_SEARCH =
        "https://music.youtube.com/youtubei/v1/search?prettyPrint=false";
    // Public WEB_REMIX key embedded in the YT Music web client, not a user secret.
    private static final String YTM_PUBLIC_KEY =
        "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";
    private static final String CLIENT_VERSION = "1.20260114.03.00";
    private static final String SONGS_FILTER =
        "EgWKAQIIAWoKEAMQBBAJEAoQEA==";
    private static final int MAX_JSON_BYTES = 3 * 1024 * 1024;

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
            Track result = null;
            String error = null;
            try {
                result = searchFirstSong(query);
                if(result == null) error = "곡 ID를 찾지 못했습니다.";
            } catch (Exception e) {
                error = "검색 오류: " + e.getClass().getSimpleName() +
                    (e.getMessage() != null ? " (" + e.getMessage() + ")" : "");
                Log.w(TAG, "C01 search request failed", e);
            }
            final Track selected = result;
            final String failure = error;
            runOnUiThread(() -> {
                if(isFinishing() || isDestroyed()) return;
                execute.setEnabled(true);
                if(selected == null) {
                    tell(failure == null ? "검색 실패" : failure);
                    return;
                }
                openOfficialYtmTrack(selected);
            });
        });
    }

    private boolean isYtmInstalled() {
        try {
            getPackageManager().getPackageInfo(YTM_PACKAGE, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openOfficialYtmTrack(Track song) {
        // The only playback action is handed to the *installed official app*.
        // startActivity succeeding is NOT evidence that YT Music actually played.
        Uri link = Uri.parse("https://music.youtube.com/watch?v=" + song.id);
        Intent request = new Intent(Intent.ACTION_VIEW, link);
        request.setPackage(YTM_PACKAGE);
        request.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(request);
            Log.i(TAG, "C01 target ID handed off to official YT Music: " + song.id);
            tell("YT Music 재생 요청: " + song.title);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Official YT Music cannot open watch URI", e);
            tell("YT Music 앱이 곡 링크를 열지 못했습니다.");
        }
    }

    private static Track searchFirstSong(String query) throws Exception {
        Exception firstError = null;
        try {
            Track song = findFirstSong(postYtmSearch(query, true));
            if(song != null) return song;
        } catch (IOException e) {
            firstError = e;
        }
        // Some anonymous clients reject the song-filter token; retry default
        // public results, still selecting only a real watchEndpoint videoId.
        try {
            Track song = findFirstSong(postYtmSearch(query, false));
            if(song != null) return song;
        } catch (IOException e) {
            if(firstError != null) e.addSuppressed(firstError);
            throw e;
        }
        if(firstError != null) throw firstError;
        return null;
    }

    private static JSONObject postYtmSearch(String query, boolean songsOnly)
        throws Exception {
        JSONObject client = new JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "ko")
            .put("gl", "KR");
        JSONObject context = new JSONObject().put("client", client);
        JSONObject payload = new JSONObject()
            .put("context", context)
            .put("query", query);
        if(songsOnly) payload.put("params", SONGS_FILTER);
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection http = (HttpURLConnection) new URL(YTM_SEARCH)
            .openConnection();
        http.setRequestMethod("POST");
        http.setConnectTimeout(10000);
        http.setReadTimeout(12000);
        http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        http.setRequestProperty("Accept", "application/json");
        http.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.6");
        http.setRequestProperty("X-YouTube-Client-Name", "67");
        http.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);
        http.setRequestProperty("X-Goog-Api-Key", YTM_PUBLIC_KEY);
        http.setRequestProperty("Origin", "https://music.youtube.com");
        http.setRequestProperty("Referer", "https://music.youtube.com/");
        http.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36");
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

    private static Track findFirstSong(JSONObject response) {
        return walk(response, new int[]{0}, 0);
    }

    private static Track walk(Object node, int[] visited, int depth) {
        if(node == null || depth > 24 || ++visited[0] > 13000) return null;
        if(node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject renderer = object.optJSONObject("musicResponsiveListItemRenderer");
            if(renderer != null) {
                String id = findVideoId(renderer);
                if(isVideoId(id)) {
                    JSONArray columns = renderer.optJSONArray("flexColumns");
                    String title = columns == null ? "" : firstText(columns.optJSONObject(0));
                    return new Track(id, title.isEmpty() ? "검색된 곡" : title);
                }
            }
            Iterator<String> keys = object.keys();
            while(keys.hasNext()) {
                Track found = walk(object.opt(keys.next()), visited, depth + 1);
                if(found != null) return found;
            }
        } else if(node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for(int i = 0; i < arr.length(); i++) {
                Track found = walk(arr.opt(i), visited, depth + 1);
                if(found != null) return found;
            }
        }
        return null;
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
        JSONObject first = runs == null ? null : runs.optJSONObject(0);
        return first == null ? text.optString("simpleText", "") :
            first.optString("text", "");
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class Track {
        final String id;
        final String title;
        Track(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }
}
