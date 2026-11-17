package dev.polaris.aauiprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.browse.MediaBrowser;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

/** Phone preview: exact proposed arrangement. Car rendering is host-controlled. */
public final class MainActivity extends Activity {
    private static final int BG=Color.rgb(17,22,31);
    private static final int PANEL=Color.rgb(30,38,51);
    private static final int EDGE=Color.rgb(55,67,87);
    private static final int WHITE=Color.rgb(242,245,250);
    private static final int MUTED=Color.rgb(177,190,207);
    private static final int ACCENT=Color.rgb(114,184,247);
    private String tab="home";
    private String selection="";
    private LinearLayout stage;
    private TextView diagnostic;
    private MediaBrowser browser;
    private boolean rootOk,canOk,radioOk,streamOk;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        render();
    }
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable surface(int color,int stroke,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if(stroke!=0)d.setStroke(dp(1),stroke);
        return d;
    }
    private TextView text(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private void space(int px){View v=new View(this);stage.addView(v,new LinearLayout.LayoutParams(1,dp(px)));}
    private void header(String s){TextView t=text(s,13,MUTED,true);stage.addView(t);space(9);}
    private void render(){
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        stage=new LinearLayout(this);stage.setOrientation(LinearLayout.VERTICAL);stage.setPadding(dp(15),dp(23),dp(15),dp(24));
        scroll.addView(stage);
        setContentView(scroll);

        TextView title=text("POLARIS   •   AA UI PROBE",20,WHITE,true);stage.addView(title);space(5);
        TextView subtitle=text("실제 차량 신호 / 라디오 / 음원 연결 없음",12,MUTED,false);stage.addView(subtitle);space(17);
        header("① Android Auto 최상위 탐색 탭 — 4개");
        LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);
        stage.addView(tabs);
        tabButton(tabs,"H","Home","home","#35B7AB");
        tabButton(tabs,"C","CAN","can","#699DF1");
        tabButton(tabs,"R","Radio","radio","#F0AF65");
        tabButton(tabs,"S","Streaming","streaming","#B89BF8");
        space(18);

        if(tab.equals("home")){
            header("② Home — 하위 아이콘 없이 두 종류의 혼합 요약");
            summary("CAN + Radio","SOC 62% · 평균 전비 18.5 kWh/100km","국내 89.1 FM · 가상 방송 표시");
            space(9);
            summary("CAN + Streaming","SOC 62% · 주행 기록 대기","Demo Track A · 가상 재생 정보");
        }else{
            header("② "+label()+" — 세부 메뉴 "+(tab.equals("radio")?"2":"4")+"개 (그리드 시도)");
            LinearLayout grid=new LinearLayout(this);grid.setOrientation(LinearLayout.HORIZONTAL);
            stage.addView(grid);
            if(tab.equals("can")){
                menuButton(grid,"V","서플라이","#6389EB");
                menuButton(grid,"EV","전비기록","#6389EB");
                menuButton(grid,"ST","상태","#6389EB");
                menuButton(grid,"LOG","로깅","#6389EB");
            }else if(tab.equals("radio")){
                menuButton(grid,"KR","국내","#E4A156");
                menuButton(grid,"INT","해외","#E4A156");
                addBlank(grid);addBlank(grid);
            }else{
                menuButton(grid,"♥","좋아요","#A58AEB");
                menuButton(grid,"↓","저장","#A58AEB");
                menuButton(grid,"★","추천","#A58AEB");
                menuButton(grid,"↺","최근재생","#A58AEB");
            }
            space(17);
            header("③ "+label()+" 목록 미리보기"+(selection.isEmpty()?"":" · "+selection));
            for(String[] row:preview()){
                listRow(row[0],row[1]);
                space(7);
            }
        }
        space(18);
        TextView divider=text("────────────────────────",12,EDGE,false);divider.setGravity(Gravity.CENTER);stage.addView(divider);space(12);
        TextView diagnosticTitle=text("Android Auto 서비스 데이터 점검",15,WHITE,true);stage.addView(diagnosticTitle);space(7);
        TextView button=text("4개 탭 및 하위 메뉴 트리 확인",14,WHITE,true);
        button.setGravity(Gravity.CENTER);button.setBackground(surface(Color.rgb(44,74,101),ACCENT,11));
        button.setPadding(dp(10),dp(12),dp(10),dp(12));stage.addView(button);
        diagnostic=text("실행 전 · 휴대폰에서 이 버튼을 누르면 실제 MediaBrowserService를 조회합니다.",12,MUTED,false);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);stage.addView(diagnostic,p);
        button.setOnClickListener(v->selfTest());
        space(13);
        TextView note=text("휴대폰은 설계 미리보기이며 Android Auto 화면은 차량 호스트가 배치합니다. 오디오·CAN·Bluetooth 기능은 전혀 실행하지 않습니다.",12,MUTED,false);
        stage.addView(note);
    }
    private String label(){
        switch(tab){case "can":return "CAN";case "radio":return "Radio";case "streaming":return "Streaming";default:return "Home";}
    }
    private void tabButton(LinearLayout parent,String glyph,String name,String id,String hex){
        final boolean active=tab.equals(id);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER);
        card.setPadding(dp(2),dp(9),dp(2),dp(9));
        card.setBackground(surface(active?Color.rgb(48,64,82):PANEL,active?ACCENT:EDGE,10));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(77),1);
        lp.leftMargin=dp(3);lp.rightMargin=dp(3);parent.addView(card,lp);
        TextView symbol=text(glyph,25,Color.parseColor(hex),true);symbol.setGravity(Gravity.CENTER);card.addView(symbol);
        TextView label=text(name,11,WHITE,active);label.setGravity(Gravity.CENTER);card.addView(label);
        card.setOnClickListener(v->{tab=id;selection="";render();});
    }
    private void menuButton(LinearLayout parent,String glyph,String name,String hex){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER);
        card.setPadding(dp(1),dp(10),dp(1),dp(10));
        card.setBackground(surface(PANEL,selection.equals(name)?ACCENT:EDGE,10));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(93),1);
        lp.leftMargin=dp(3);lp.rightMargin=dp(3);parent.addView(card,lp);
        TextView symbol=text(glyph,glyph.length()>2?18:25,Color.parseColor(hex),true);symbol.setGravity(Gravity.CENTER);card.addView(symbol);
        TextView label=text(name,11,WHITE,selection.equals(name));label.setGravity(Gravity.CENTER);card.addView(label);
        card.setOnClickListener(v->{selection=name;render();});
    }
    private void addBlank(LinearLayout parent){
        View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(0,dp(92),1));
    }
    private void summary(String heading,String line1,String line2){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15),dp(15),dp(15),dp(15));card.setBackground(surface(PANEL,EDGE,13));
        TextView t=text(heading,16,WHITE,true);card.addView(t);
        TextView one=text(line1+"  [가상]",13,MUTED,false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(7);card.addView(one,p);
        TextView two=text(line2,13,MUTED,false);p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(4);card.addView(two,p);
        stage.addView(card);
    }
    private void listRow(String title,String detail){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12),dp(13),dp(12),dp(13));card.setBackground(surface(PANEL,0,11));
        TextView glyph=text("▣",22,ACCENT,true);card.addView(glyph);
        LinearLayout values=new LinearLayout(this);values.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=dp(12);card.addView(values,lp);
        values.addView(text(title,15,WHITE,true));
        values.addView(text(detail,12,MUTED,false));
        stage.addView(card);
        card.setOnClickListener(v->{
            android.widget.Toast.makeText(this,"UI 테스트: 실제 기능은 연결되지 않았습니다.",android.widget.Toast.LENGTH_SHORT).show();
        });
    }
    private String[][] preview(){
        if(tab.equals("can")){
            if(selection.equals("서플라이"))return new String[][]{{"13.8 V · 모의 공급 전원","실제 OBD/CAN 연결 없음"},{"모의 전류 0 A","연동 기능 미구현"}};
            if(selection.equals("전비기록"))return new String[][]{{"18.5 kWh/100km · 가상 전비","측정 기록 아님"},{"300.0 km · 가상 주행거리","기록 기능 미구현"}};
            if(selection.equals("상태"))return new String[][]{{"배터리 SOC 62% · 가상","실제 차량 상태 아님"},{"연결 상태: 비연결","통신 기능 없음"}};
            if(selection.equals("로깅"))return new String[][]{{"로깅 대기 · 0 프레임","실제 패킷 수집 없음"},{"모의 로그 목록","테스트 항목"}};
            return new String[][]{{"SOC 62% · 전비 18.5","가상 데이터"},{"전원 13.8 V · 로깅 대기","가상 데이터"}};
        }
        if(tab.equals("radio")){
            if(selection.equals("국내"))return new String[][]{{"89.1 KBS Cool FM","가상 국내 방송"},{"107.7 SBS PowerFM","가상 국내 방송"}};
            if(selection.equals("해외"))return new String[][]{{"KIIS-FM LA","가상 해외 방송"},{"Gallery 41","가상 해외 방송"}};
            return new String[][]{{"89.1 KBS Cool FM","국내 · 가상 표시"},{"KIIS-FM LA","해외 · 가상 표시"}};
        }
        String n=selection.isEmpty()?"전체":selection;
        return new String[][]{{"Demo Track A",n+" · 음악 재생 없음"},{"Demo Track B",n+" · 음악 재생 없음"},{"Demo Track C",n+" · 음악 재생 없음"}};
    }
    private void selfTest(){
        rootOk=false;canOk=false;radioOk=false;streamOk=false;
        if(browser!=null){browser.disconnect();browser=null;}
        diagnostic.setText("MediaBrowserService에 연결 중…");
        final MediaBrowser[] holder=new MediaBrowser[1];
        holder[0]=new MediaBrowser(this,new ComponentName(this,DemoMediaService.class),
            new MediaBrowser.ConnectionCallback(){
                @Override public void onConnected(){
                    MediaBrowser b=holder[0];
                    b.subscribe(DemoMediaService.ROOT,new MediaBrowser.SubscriptionCallback(){
                        @Override public void onChildrenLoaded(String id,List<MediaBrowser.MediaItem> children){
                            rootOk=children.size()==4
                                && children.get(0).getMediaId().equals("home")
                                && children.get(1).getMediaId().equals("can")
                                && children.get(2).getMediaId().equals("radio")
                                && children.get(3).getMediaId().equals("streaming");
                            b.subscribe("can", new MediaBrowser.SubscriptionCallback(){
                                @Override public void onChildrenLoaded(String p,List<MediaBrowser.MediaItem> cs){canOk=cs.size()>=4;showResult();}
                            });
                            b.subscribe("radio",new MediaBrowser.SubscriptionCallback(){
                                @Override public void onChildrenLoaded(String p,List<MediaBrowser.MediaItem> cs){radioOk=cs.size()>=2;showResult();}
                            });
                            b.subscribe("streaming",new MediaBrowser.SubscriptionCallback(){
                                @Override public void onChildrenLoaded(String p,List<MediaBrowser.MediaItem> cs){streamOk=cs.size()>=4;showResult();}
                            });
                            showResult();
                        }
                    });
                }
                @Override public void onConnectionFailed(){diagnostic.setText("연결 실패: 미디어 서비스 등록 문제");}
                @Override public void onConnectionSuspended(){diagnostic.setText("서비스 연결이 중단되었습니다.");}
            },null);
        browser=holder[0];
        browser.connect();
    }
    private void showResult(){
        String s="루트 4탭="+(rootOk?"OK":"...")+" · CAN 메뉴="+(canOk?"OK":"...")
            +" · Radio 메뉴="+(radioOk?"OK":"...")+" · Streaming 메뉴="+(streamOk?"OK":"...");
        boolean pass=rootOk&&canOk&&radioOk&&streamOk;
        diagnostic.setText((pass?"PASS · ":"검사 중 · ")+s);
        Log.i("PolarisUIProbe","SELFTEST "+(pass?"PASS ":"INCOMPLETE ")+s);
    }
    @Override protected void onDestroy(){
        if(browser!=null){browser.disconnect();browser=null;}
        super.onDestroy();
    }
}
