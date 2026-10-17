package com.polarisunbound.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Explicit separate login for Polaris only. This WebView cannot access the
 * installed official YTM app's cookies. No passwords, cookies, or auth tokens
 * are copied into Polaris SharedPreferences or remote logs.
 *
 * Google can reject embedded browser sign-in; report that limitation rather
 * than imitating a successful login or requesting pasted credentials.
 */
public final class PolarisYtmLoginActivity extends Activity {
    private WebView web;
    private TextView status;
    private Button confirm;
    private static final String URL="https://music.youtube.com/";

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF141414);
        root.setPadding(18,18,18,14);

        TextView heading=new TextView(this);
        heading.setText("YT Music 계정 연결");
        heading.setTextSize(19);
        heading.setTextColor(Color.WHITE);
        root.addView(heading);

        TextView disclosure=new TextView(this);
        disclosure.setText("Google/YouTube 공식 웹 페이지에서 직접 로그인합니다. "+
            "공식 YT Music 앱의 로그인과 별개입니다. "+
            "비공식 InnerTube 읽기 방식이므로 로그인 제한이나 서비스 변경으로 작동하지 않을 수 있습니다. "+
            "Polaris에는 비밀번호를 입력받거나 저장하는 별도 입력창이 없습니다.");
        disclosure.setTextColor(0xFFCCCCCC);
        disclosure.setTextSize(12);
        disclosure.setPadding(0,7,0,10);
        root.addView(disclosure);

        status=new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(13);
        status.setText("https://music.youtube.com 접속 준비");
        status.setPadding(0,4,0,8);
        root.addView(status);

        confirm=new Button(this);
        confirm.setText("내 플레이리스트로 이동");
        root.addView(confirm);
        confirm.setOnClickListener(v->{
            if(PolarisYtmAccountCatalog.hasLoginCookie()){
                captureSelectedAccount(()->{
                    CookieManager.getInstance().flush();
                    setResult(RESULT_OK);
                    finish();
                });
            }else{
                status.setText("로그인 인증 쿠키를 확인하지 못했습니다. "+
                    "music.youtube.com 로그인 완료를 확인하세요. "+
                    "Google에서 WebView 로그인을 차단했다면 이 경로는 사용할 수 없습니다.");
            }
        });

        Button logout=new Button(this);
        logout.setText("Polaris 웹 로그인 데이터 삭제");
        root.addView(logout);
        logout.setOnClickListener(v->
            new android.app.AlertDialog.Builder(this)
                .setTitle("Polaris 웹 로그인 삭제")
                .setMessage("Polaris WebView의 쿠키를 삭제합니다. "+
                    "공식 YouTube Music 앱의 로그인 상태는 변경하지 않습니다.")
                .setPositiveButton("삭제",(d,w)->{
                    CookieManager.getInstance().removeAllCookies(success->{
                        PolarisYtmAccountSelection.clear(getApplicationContext());
                        CookieManager.getInstance().flush();
                        status.setText("Polaris 로그인 쿠키 삭제"+
                            (success?" 완료":" 요청 완료"));
                        if(web!=null)web.loadUrl(URL);
                    });
                })
                .setNegativeButton("취소",null).show());

        web=new WebView(this);
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(
                WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();
                if(!"https".equalsIgnoreCase(uri.getScheme())){
                    status.setText("HTTPS가 아닌 링크는 열지 않습니다.");
                    return true;
                }
                return false;
            }
            @Override public void onPageFinished(WebView view,String url){
                String host;
                try{host=Uri.parse(url).getHost();}
                catch(Exception e){host="";}
                if(host==null)host="";
                // Do not show query strings or redirect tokens on screen.
                status.setText("현재 사이트: "+host+
                    (PolarisYtmAccountCatalog.hasLoginCookie()?
                    " · 인증 쿠키 확인됨":" · 로그인 대기 중"));
            }
            @Override public void onReceivedError(
                WebView view,WebResourceRequest request,
                android.webkit.WebResourceError error){
                if(request.isForMainFrame())
                    status.setText("페이지 연결 오류. 네트워크나 Google 로그인 정책을 확인하세요.");
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,0,1f));
        setContentView(root);
        web.loadUrl(PolarisYtmAccountCatalog.hasLoginCookie()?
            URL:"https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com");
    }

    /**
     * Read only numeric SESSION_INDEX and current brand-channel page
     * identifier from Google's real logged-in WebView. Do not read,
     * expose, save or report password/cookie/authorization tokens.
     */
    private void captureSelectedAccount(Runnable done){
        if(web==null){
            status.setText("웹 화면이 준비되지 않았습니다.");
            return;
        }
        String current=web.getUrl();
        Uri uri=current==null?null:Uri.parse(current);
        if(uri==null || !"https".equalsIgnoreCase(uri.getScheme()) ||
            !"music.youtube.com".equalsIgnoreCase(uri.getHost())){
            status.setText("music.youtube.com 화면에서만 계정 정보를 확인할 수 있습니다.");
            return;
        }
        String javascript="(function(){"+
            "var c=(window.yt&&window.yt.config_)||null;"+
            "if(!c)return null;"+
            "return JSON.stringify({idx:String(c.SESSION_INDEX==null?0:c.SESSION_INDEX),"+
            "ds:String(c.DATASYNC_ID||''),"+
            "pg:String(c.DELEGATED_SESSION_ID||'')});"+
            "})()";
        web.evaluateJavascript(javascript,value->{
            try{
                if(value==null||"null".equals(value))throw new IllegalStateException();
                String data=new org.json.JSONArray("["+value+"]").getString(0);
                org.json.JSONObject account=new org.json.JSONObject(data);
                PolarisYtmAccountSelection.save(getApplicationContext(),
                    account.optString("idx","0"),
                    account.optString("pg",""),
                    account.optString("ds",""));
                done.run();
            }catch(Exception e){
                status.setText("웹 페이지에서 계정·채널 정보를 확인하지 못했습니다. "+
                    "YT Music 홈을 새로고침한 뒤 다시 눌러 주세요.");
            }
        });
    }

    @Override public void onBackPressed(){
        if(web!=null&&web.canGoBack())web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy(){
        if(web!=null){
            ((LinearLayout)web.getParent()).removeView(web);
            web.stopLoading();
            web.destroy();
            web=null;
        }
        super.onDestroy();
    }
}
