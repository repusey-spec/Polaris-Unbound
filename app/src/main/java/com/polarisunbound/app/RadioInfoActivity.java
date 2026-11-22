package com.polarisunbound.app;

import android.content.ComponentName;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Phone-only details page. The radio service stays in PolarisMediaService.
 * Uses first-party public information pages as a conservative initial bridge:
 * message and playlist APIs are not public contracts and must not be guessed.
 * The web content is not presented as an independently implemented native feed.
 */
public final class RadioInfoActivity extends AppCompatActivity {
    public static final String EXTRA_STATION_ID = "station_id";

    private WebView informationWeb;
    private String stationId;
    private String officialUrl;
    private TextView help;
    private TextView audioButton;
    private MediaBrowserCompat browser;
    private MediaControllerCompat controller;

    private int dp(float n) {
        return Math.round(getResources().getDisplayMetrics().density * n);
    }

    private TextView text(String message, int size, int color) {
        TextView view = new TextView(this);
        view.setText(message);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(16));
        return drawable;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        stationId = getIntent().getStringExtra(EXTRA_STATION_ID);
        if (stationId == null) stationId = "";
        final boolean mbc = "kr2".equals(stationId) || "kr4".equals(stationId);
        final boolean kbs = "kr1".equals(stationId);

        if (mbc) {
            officialUrl = "https://miniwebapp.imbc.com/index?channel=" +
                ("kr4".equals(stationId) ? "sfm" : "mfm");
        } else if (kbs) {
            officialUrl = "https://radio.kbs.co.kr/";
        }

        getWindow().setStatusBarColor(0xFF152235);
        getWindow().setNavigationBarColor(0xFF152235);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));
        root.setBackgroundColor(0xFF152235);
        setContentView(root);

        TextView back = text("‹  라디오 채널 홈", 17, Color.WHITE);
        back.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        back.setPadding(dp(12), dp(12), dp(8), dp(12));
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(52)));

        TextView station = text(RadioPresets.stationName(stationId), 23, Color.WHITE);
        station.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        station.setPadding(dp(12), dp(6), dp(8), dp(4));
        root.addView(station);

        audioButton = text("라디오 재생 상태 확인 중…", 15, Color.WHITE);
        audioButton.setGravity(Gravity.CENTER);
        audioButton.setBackground(rounded(0xFF28537B));
        LinearLayout.LayoutParams audioParams =
            new LinearLayout.LayoutParams(-1, dp(48));
        audioParams.setMargins(0, dp(8), 0, dp(10));
        root.addView(audioButton, audioParams);
        audioButton.setOnClickListener(v -> toggleRadio());

        if (officialUrl == null) {
            TextView noInfo = text("이 채널은 선곡표·청취자 글 연동 대상이 아닙니다.\n\n뒤로가기를 누르면 기존 라디오 채널 홈으로 돌아갑니다.", 16, 0xFFEBEFF6);
            noInfo.setPadding(dp(16), dp(25), dp(16), dp(16));
            root.addView(noInfo);
        } else {
            help = text(mbc
                ? "MBC mini 공식 웹 정보 · 선곡표 / mini 메시지 메뉴에서 열람하세요."
                : "KBS 공식 라디오 사이트 · 선곡표 / 채팅은 사이트에서 제공되는 범위만 표시됩니다.",
                13, 0xFFCFD7E5);
            help.setPadding(dp(8), dp(4), dp(8), dp(9));
            root.addView(help);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView songs = navigation("선곡표 보기");
            TextView messages = navigation("청취자 글 보기");
            LinearLayout.LayoutParams w1 = new LinearLayout.LayoutParams(0, dp(48), 1f);
            w1.setMargins(0, 0, dp(5), dp(5));
            LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
            w2.setMargins(dp(5), 0, 0, dp(5));
            row.addView(songs, w1);
            row.addView(messages, w2);
            root.addView(row);

            informationWeb = new WebView(this);
            informationWeb.setBackgroundColor(Color.WHITE);
            informationWeb.getSettings().setJavaScriptEnabled(true);
            informationWeb.getSettings().setDomStorageEnabled(true);
            informationWeb.getSettings().setMediaPlaybackRequiresUserGesture(true);
            informationWeb.getSettings().setAllowFileAccess(false);
            informationWeb.getSettings().setAllowContentAccess(false);
            informationWeb.getSettings().setBuiltInZoomControls(false);
            informationWeb.getSettings().setDisplayZoomControls(false);
            informationWeb.setWebChromeClient(new WebChromeClient());
            informationWeb.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) {
                    if (help != null) help.setText(
                        (mbc ? "MBC mini" : "KBS 라디오") +
                        " 공식 웹 화면입니다. 해당 화면의 선곡표 / 메시지 메뉴를 누르세요.");
                }
                @Override public void onReceivedError(WebView view,
                        WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame() && help != null) {
                        help.setText("공식 정보 페이지를 열 수 없습니다. 인터넷 연결 및 웹사이트 지원을 확인해 주세요.");
                    }
                }
            });
            root.addView(informationWeb, new LinearLayout.LayoutParams(-1, 0, 1f));
            songs.setOnClickListener(v -> showInformation(true));
            messages.setOnClickListener(v -> showInformation(false));
            informationWeb.loadUrl(officialUrl);
        }

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
                        } catch (Exception ignored) {
                            if (audioButton != null) audioButton.setText("라디오 연결 확인 필요");
                        }
                    }
                }, null);
        browser.connect();
    }

    private TextView navigation(String label) {
        TextView button = text(label, 15, Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(0xFF344C67));
        return button;
    }

    private void showInformation(boolean songs) {
        if (informationWeb == null) return;
        if (help != null) help.setText(songs
            ? "공식 웹 화면에서 '선곡표'를 눌러 보세요. 선곡 정보는 방송사에서 제공합니다."
            : "공식 웹 화면에서 '메시지' 또는 '채팅'을 눌러 보세요. 글쓰기는 필요하지 않습니다.");
        // Both views live in the broadcaster's first-party page.
        // Do not invent undocumented message endpoints or pretend a feed is loaded.
        informationWeb.loadUrl(officialUrl);
    }

    private void toggleRadio() {
        if (controller == null) return;
        PlaybackStateCompat state = controller.getPlaybackState();
        int code = state == null ? -1 : state.getState();
        if (code == PlaybackStateCompat.STATE_PLAYING ||
                code == PlaybackStateCompat.STATE_BUFFERING) {
            controller.getTransportControls().stop();
        } else {
            controller.getTransportControls().playFromMediaId(stationId, null);
        }
        audioButton.postDelayed(this::updateAudio, 500);
    }

    private void updateAudio() {
        if (audioButton == null || controller == null) return;
        PlaybackStateCompat s = controller.getPlaybackState();
        int code = s == null ? -1 : s.getState();
        audioButton.setText((code == PlaybackStateCompat.STATE_PLAYING ||
            code == PlaybackStateCompat.STATE_BUFFERING) ?
            "■  라디오 정지" : "▶  라디오 재생");
    }

    @Override public void onBackPressed() {
        // Always return to the 0.59 station grid. Do not traverse WebView history.
        finish();
    }

    @Override protected void onDestroy() {
        if (browser != null) {
            try { browser.disconnect(); } catch (Exception ignored) {}
        }
        if (informationWeb != null) {
            informationWeb.stopLoading();
            informationWeb.loadUrl("about:blank");
            informationWeb.destroy();
            informationWeb = null;
        }
        super.onDestroy();
    }
}
