package com.polarisunbound.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private LinearLayout body;
    private MediaPlayer localPlayer;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        showHome();
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.READ_MEDIA_AUDIO},7);
    }
    private TextView button(String s) {
        TextView v=new TextView(this); v.setText(s); v.setTextSize(22); v.setGravity(17); v.setPadding(18,38,18,38);
        v.setBackgroundResource(android.R.drawable.btn_default); return v;
    }
    private void base(String title) {
        ScrollView sc=new ScrollView(this); body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(24,24,24,24);
        TextView h=new TextView(this); h.setText(title); h.setTextSize(30); h.setPadding(0,10,0,24); body.addView(h);
        sc.addView(body); setContentView(sc);
    }
    private void showHome() {
        base("Polaris Unbound");
        for(String s:new String[]{"국내라디오","해외라디오","MP3"}) {
            TextView v=button(s); body.addView(v); 
            if(s.startsWith("국내")) v.setOnClickListener(x->showDomestic());
            else if(s.startsWith("해외")) v.setOnClickListener(x->showForeign());
            else v.setOnClickListener(x->showMp3());
        }
    }
    private void showDomestic() {
        base("국내라디오");
        String[] n={"KBS CoolFM","MBC FM4U","CBS MusicFM","MBC 표준FM","AFN EagleFM","SBS PowerFM"};
        LinearLayout row=null;
        for(int i=0;i<n.length;i++){
            if(i%3==0){ row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); body.addView(row,new LinearLayout.LayoutParams(-1,-2)); }
            TextView v=button((i+1)+"\n"+n[i]+"\n현재 프로그램"); v.setTextSize(15);
            row.addView(v,new LinearLayout.LayoutParams(0,-2,1));
        }
    }
    private void showForeign(){ base("해외라디오"); body.addView(button("102.7 KIIS-FM\nLos Angeles")); }
    private void showMp3(){ base("MP3"); TextView t=new TextView(this); t.setText("로컬 음악 라이브러리\n다음 단계에서 MediaStore 목록/재생 연결"); t.setTextSize(20); body.addView(t); }
    @Override public void onBackPressed(){ showHome(); }
    @Override protected void onDestroy(){ if(localPlayer!=null) localPlayer.release(); super.onDestroy(); }
}
