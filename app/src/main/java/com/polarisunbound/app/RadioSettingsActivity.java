package com.polarisunbound.app;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.Context;

public final class RadioSettingsActivity extends Activity {
    public static final String PREFS="polaris_radio_settings";
    public static final String REVERSE_CHAT="reverse_chat_order";
    public static final String BACK_EXITS="back_exits_app";

    public static boolean chatReversed(Context context){
        return context.getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean(REVERSE_CHAT,false);
    }
    public static boolean backExits(Context context){
        return context.getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean(BACK_EXITS,false);
    }
    private int dp(int value){
        return Math.round(value*getResources().getDisplayMetrics().density);
    }
    private TextView heading(String text,int size){
        TextView view=new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(30,30,30));
        view.setPadding(0,dp(12),0,dp(12));
        return view;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ScrollView scroll=new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(22),dp(20),dp(20));
        TextView back=heading("‹   폴라리스 라디오 설정",22);
        back.setTypeface(null,Typeface.BOLD);
        back.setOnClickListener(v->finish());
        root.addView(back);
        CheckBox chat=new CheckBox(this);
        chat.setText("청취자 메시지 표시 순서 반전");
        chat.setTextSize(17);
        chat.setChecked(chatReversed(this));
        chat.setOnCheckedChangeListener((v,checked)->
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean(REVERSE_CHAT,checked).apply());
        root.addView(chat);
        TextView chatHelp=heading("체크 시 메시지 표시 순서를 반대로 바꾸고, 최신 위치부터 표시합니다.",13);
        chatHelp.setTextColor(0xff666666);
        root.addView(chatHelp);
        CheckBox exit=new CheckBox(this);
        exit.setText("뒤로가기 버튼으로 앱 종료");
        exit.setTextSize(17);
        exit.setChecked(backExits(this));
        exit.setOnCheckedChangeListener((v,checked)->
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean(BACK_EXITS,checked).apply());
        root.addView(exit);
        TextView exitHelp=heading(
            "체크: 메인 화면에서 뒤로가기 시 재생 중지 및 앱 종료\n"+
            "해제: 화면만 백그라운드로 보내고 라디오 재생 유지",13);
        exitHelp.setTextColor(0xff666666);
        root.addView(exitHelp);
        scroll.addView(root);
        setContentView(scroll);
    }
}
