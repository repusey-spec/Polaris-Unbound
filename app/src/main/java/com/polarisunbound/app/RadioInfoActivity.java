package com.polarisunbound.app;

import android.content.ComponentName;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The 0.60 embedded site was not a radio-data integration.
 * This screen contains NO WebView. Broadcast info is fetched as text from
 * the specific broadcast's existing public JSON/JSONP service.
 *
 * Currently verified from public integration examples:
 *   MBC: miniapi.imbc.com/music/somitem, Schedule/schedulelist
 *   KBS Cool FM: static.api.kbs.co.kr/mediafactory/v1/schedule/onair_now (25)
 * Audience messages and historical playlists are not implemented until their
 * actual read-only endpoints are identified; never substitute a webpage or
 * a different channel and never invent messages.
 */
public final class RadioInfoActivity extends AppCompatActivity {
    public static final String EXTRA_STATION_ID = "station_id";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private MediaBrowserCompat browser;
    private MediaControllerCompat controller;
    private String stationId = "";
    private TextView audioButton;
    private TextView programView;
    private TextView contentView;
    private TextView songTab;
    private TextView messageTab;
    private boolean resumed;
    private boolean fetching;
    private boolean showingMessages;
    private String program = "방송 프로그램 확인 중…";
    private String currentSong = "현재 선곡을 확인 중…";
    private String fetchedAt = "";
    private final List<String> sessionSongs = new ArrayList<>();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refreshInfo();
            handler.postDelayed(this, 20000L);
        }
    };

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private GradientDrawable background(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(14));
        return drawable;
    }

    private TextView label(String title, int size, int color) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private TextView action(String title) {
        TextView t = label(title, 16, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(background(0xFF305779));
        return t;
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent().getStringExtra(EXTRA_STATION_ID);
        if (id != null) stationId = id;
        getWindow().setStatusBarColor(0xFF172234);
        getWindow().setNavigationBarColor(0xFF172234);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF172234);
        root.setPadding(dp(15), dp(8), dp(15), dp(12));
        setContentView(root);

        TextView back = label("‹  라디오 채널 홈", 17, Color.WHITE);
        back.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        back.setPadding(dp(6), dp(7), dp(6), dp(7));
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(49)));

        TextView station = label(RadioPresets.stationName(stationId), 24, Color.WHITE);
        station.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        station.setPadding(dp(4), dp(9), dp(4), dp(9));
        root.addView(station);

        audioButton = action("라디오 재생 연결 중…");
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(52));
        ap.setMargins(0, dp(7), 0, dp(12));
        root.addView(audioButton, ap);
        audioButton.setOnClickListener(v -> toggleRadio());

        programView = label("방송 프로그램 확인 중…", 18, Color.WHITE);
        programView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        programView.setPadding(dp(6), dp(4), dp(6), dp(12));
        root.addView(programView);

        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        songTab = action("현재 선곡");
        messageTab = action("청취자 글");
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(48), 1f);
        left.setMargins(0, 0, dp(6), 0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(48), 1f);
        right.setMargins(dp(6), 0, 0, 0);
        choices.addView(songTab, left);
        choices.addView(messageTab, right);
        root.addView(choices);
        songTab.setOnClickListener(v -> { showingMessages = false; updateContent(); });
        messageTab.setOnClickListener(v -> { showingMessages = true; updateContent(); });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sp.setMargins(0, dp(12), 0, 0);
        root.addView(scroll, sp);
        contentView = label("", 17, 0xFFEDF2F8);
        contentView.setGravity(Gravity.TOP);
        contentView.setPadding(dp(14), dp(20), dp(14), dp(18));
        contentView.setBackground(background(0xFF253A50));
        scroll.addView(contentView, new ScrollView.LayoutParams(-1, -2));
        updateContent();

        browser = new MediaBrowserCompat(this,
            new ComponentName(this, PolarisMediaService.class),
            new MediaBrowserCompat.ConnectionCallback() {
                @Override public void onConnected() {
                    try {
                        controller = new MediaControllerCompat(RadioInfoActivity.this,
                            browser.getSessionToken());
                        controller.registerCallback(new MediaControllerCompat.Callback() {
                            @Override public void onPlaybackStateChanged(PlaybackStateCompat state) {
                                updateAudio();
                            }
                        });
                        updateAudio();
                    } catch (Exception e) {
                        audioButton.setText("라디오 상태 확인 불가");
                    }
                }
            }, null);
        browser.connect();
    }

    private void toggleRadio() {
        if (controller == null) return;
        PlaybackStateCompat state = controller.getPlaybackState();
        int mode = state == null ? -1 : state.getState();
        if (mode == PlaybackStateCompat.STATE_PLAYING || mode == PlaybackStateCompat.STATE_BUFFERING) {
            controller.getTransportControls().stop();
        } else {
            controller.getTransportControls().playFromMediaId(stationId, null);
        }
        handler.postDelayed(this::updateAudio, 400);
    }

    private void updateAudio() {
        if (controller == null || audioButton == null) return;
        PlaybackStateCompat state = controller.getPlaybackState();
        int mode = state == null ? -1 : state.getState();
        audioButton.setText(mode == PlaybackStateCompat.STATE_PLAYING ||
            mode == PlaybackStateCompat.STATE_BUFFERING ? "■  라디오 정지" : "▶  라디오 재생");
    }

    private void updateContent() {
        if (songTab == null || messageTab == null || contentView == null) return;
        songTab.setBackground(background(showingMessages ? 0xFF305779 : 0xFF447CA8));
        messageTab.setBackground(background(showingMessages ? 0xFF447CA8 : 0xFF305779));
        if (showingMessages) {
            contentView.setText("청취자 메시지\n\n실시간 메시지 수신 API를 아직 검증하지 못했습니다.\n\n폴라리스에서 방송사 페이지를 띄우거나 다른 프로그램 글을 대신 표시하지 않습니다.\n\n읽기 전용 수신 경로가 확인되면 이 화면에 해당 프로그램의 메시지만 표시합니다.");
        } else if ("kr2".equals(stationId) || "kr4".equals(stationId)) {
            StringBuilder s = new StringBuilder("MBC 실시간 선곡 정보\n\n").append(currentSong);
            if (!sessionSongs.isEmpty()) {
                s.append("\n\n화면을 연 뒤 확인된 곡\n");
                for (String song : sessionSongs) s.append("\n• ").append(song);
            }
            s.append("\n\n").append(fetchedAt);
            s.append("\n\n참고: 최근 곡은 폴라리스에서 이 화면을 연 후 관측한 기록이며, MBC 전체 선곡표와는 다릅니다.");
            contentView.setText(s.toString());
        } else if ("kr1".equals(stationId)) {
            contentView.setText("KBS CoolFM 현재 방송\n\n" + program +
                "\n\nKBS 실시간 곡 목록 API가 아직 확인되지 않았습니다.\n\n" + fetchedAt);
        } else {
            contentView.setText("이 채널에 연결된 선곡 정보 API는 없습니다.\n기존 라디오 재생은 계속 이용할 수 있습니다.");
        }
    }

    private static String response(String url, String referer) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Polaris/0.61)");
        if (referer != null) connection.setRequestProperty("Referer", referer);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
            try (InputStream stream = connection.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = stream.read(buffer)) >= 0) {
                    bytes.write(buffer, 0, n);
                    if (bytes.size() > 400000) throw new java.io.IOException("Response too large");
                }
                return bytes.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static JSONArray jsonArrayOrJsonp(String raw) throws Exception {
        raw = raw.trim();
        if (!raw.startsWith("[")) {
            int open = raw.indexOf('('), close = raw.lastIndexOf(')');
            if (open < 0 || close <= open) throw new java.io.IOException("JSONP expected");
            raw = raw.substring(open + 1, close).trim();
        }
        return new JSONArray(raw);
    }

    private void refreshInfo() {
        if (fetching) return;
        if (!"kr1".equals(stationId) && !"kr2".equals(stationId) && !"kr4".equals(stationId))
            return;
        fetching = true;
        final String channelId = stationId;
        network.execute(() -> {
            String newProgram = null;
            String newSong = null;
            String error = null;
            try {
                if ("kr1".equals(channelId)) {
                    String json = response("https://static.api.kbs.co.kr/mediafactory/v1/schedule/onair_now?local_station_code=00&channel_code=25",
                        "https://onair.kbs.co.kr/");
                    JSONObject object;
                    if (json.trim().startsWith("[")) {
                        JSONArray list = new JSONArray(json);
                        object = list.length() > 0 ? list.getJSONObject(0) : new JSONObject();
                    } else object = new JSONObject(json);
                    JSONArray programs = object.optJSONArray("schedules");
                    if (programs != null && programs.length() > 0) {
                        JSONObject current = programs.getJSONObject(0);
                        newProgram = current.optString("program_title",
                            current.optString("programming_table_title", ""));
                    }
                } else {
                    String channel = "kr2".equals(channelId) ? "FM4U" : "STFM";
                    String channelUrl = "kr2".equals(channelId) ? "mfm" : "sfm";
                    String referer = "https://miniwebapp.imbc.com/index?channel=" + channelUrl;
                    JSONArray songs = jsonArrayOrJsonp(response(
                        "https://miniapi.imbc.com/music/somitem?rtype=jsonp&callback=__somitem", referer));
                    for (int i = 0; i < songs.length(); i++) {
                        JSONObject item = songs.optJSONObject(i);
                        if (item != null && channel.equalsIgnoreCase(item.optString("Channel"))) {
                            String title = item.optString("SomItem", "").replace("♬", "").trim();
                            if (!title.isEmpty()) newSong = title;
                            break;
                        }
                    }
                    JSONArray schedules = jsonArrayOrJsonp(response(
                        "https://miniapi.imbc.com/Schedule/schedulelist?callback=__schedulelist", referer));
                    java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("HHmm", java.util.Locale.KOREA);
                    format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));
                    String now = format.format(new java.util.Date());
                    for (int i = 0; i < schedules.length(); i++) {
                        JSONObject item = schedules.optJSONObject(i);
                        if (item == null || !channel.equalsIgnoreCase(item.optString("Channel"))) continue;
                        String begin = item.optString("StartTime", "").replaceAll("[^0-9]", "");
                        String end = item.optString("EndTime", "").replaceAll("[^0-9]", "");
                        if (begin.length() < 4 || end.length() < 4) continue;
                        begin = begin.substring(0, 4);
                        end = end.substring(0, 4);
                        boolean active = begin.compareTo(end) < 0
                            ? now.compareTo(begin) >= 0 && now.compareTo(end) < 0
                            : now.compareTo(begin) >= 0 || now.compareTo(end) < 0;
                        if (active) {
                            newProgram = item.optString("ProgramTitle", "");
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                error = "조회 실패: " + e.getClass().getSimpleName();
            }
            final String p = newProgram, s = newSong, err = error;
            handler.post(() -> {
                fetching = false;
                if (isFinishing() || isDestroyed()) return;
                if (p != null && !p.trim().isEmpty()) program = p;
                if (s != null && !s.isEmpty()) {
                    currentSong = s;
                    if (sessionSongs.isEmpty() || !sessionSongs.get(0).equals(s)) {
                        sessionSongs.add(0, s);
                        if (sessionSongs.size() > 15) sessionSongs.remove(15);
                    }
                } else if ("kr2".equals(stationId) || "kr4".equals(stationId)) {
                    currentSong = "현재 곡 정보가 제공되지 않습니다.";
                }
                fetchedAt = err == null ? "방송사 데이터 조회 완료" : err;
                if (programView != null) programView.setText(program);
                updateContent();
            });
        });
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override public void onBackPressed() { finish(); }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (browser != null) try { browser.disconnect(); } catch (Exception ignored) {}
        network.shutdownNow();
        super.onDestroy();
    }
}
