package com.polarisunbound.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.*;
import android.util.Log;
import org.json.*;
import java.net.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

public class RadioLiveActivity extends Activity {
    private static final String TAG="PolarisRadioAPI";
    private static final String KBS="https://kong2017.kbs.co.kr";
    private static final String MBC="https://miniapi.imbc.com";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private ScheduledExecutorService jobs;
    private int generation=0;
    private String id;
    private TextView program,song,comments,chatStatus;
    private String currentRoom="",currentBid="",currentGid="";
    private long scheduleAt=0,seq=-1;
    private final LinkedHashMap<String,String> messages=new LinkedHashMap<>();
    private final TimeZone korea=TimeZone.getTimeZone("Asia/Seoul");

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String s,int size){
        TextView v=new TextView(this); v.setText(s);v.setTextSize(size);v.setTextColor(Color.WHITE);
        v.setPadding(dp(12),dp(8),dp(12),dp(8));return v;
    }
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        id=getIntent().getStringExtra("station");
        if(!"kr1".equals(id)&&!"kr2".equals(id)&&!"kr4".equals(id)){finish();return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xff151922);
        root.setPadding(0,dp(18),0,dp(12));
        TextView back=label("‹   "+CurrentProgramResolver.stationName(id),21);back.setTypeface(null,Typeface.BOLD);
        back.setOnClickListener(v->finish());root.addView(back);
        program=label("현재 방송 확인 중",17);root.addView(program);
        song=label("현재 재생곡 확인 중",22);song.setTypeface(null,Typeface.BOLD);root.addView(song);
        TextView heading=label("청취자 메시지",18);heading.setTypeface(null,Typeface.BOLD);root.addView(heading);
        chatStatus=label("연결 확인 중",13);chatStatus.setTextColor(0xffb6bfd0);root.addView(chatStatus);
        ScrollView scroll=new ScrollView(this);comments=label("",17);comments.setGravity(Gravity.TOP);
        scroll.addView(comments);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }
    @Override protected void onResume(){
        super.onResume();if(id==null)return;
        final int g=++generation;
        jobs=Executors.newScheduledThreadPool(2);
        jobs.scheduleWithFixedDelay(()->pollSong(g),0,"kr1".equals(id)?20:10,TimeUnit.SECONDS);
        jobs.scheduleWithFixedDelay(()->pollChat(g),0,"kr1".equals(id)?2:10,TimeUnit.SECONDS);
    }
    @Override protected void onPause(){
        generation++;if(jobs!=null){jobs.shutdownNow();jobs=null;}super.onPause();
    }
    private void show(int g,TextView view,String value){
        ui.post(()->{if(g==generation&&!isFinishing())view.setText(value);});
    }
    private String get(String url,String name)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(6000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","application/json");c.setUseCaches(false);
        try{
            int code=c.getResponseCode();
            Log.i(TAG,name+" HTTP "+code);
            if(code!=200)throw new IOException("HTTP "+code);
            try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>4000000)throw new IOException("response too large");}
                String s=out.toString("UTF-8").trim();
                if(s.startsWith("[" )||s.startsWith("{"))return s;
                throw new IOException("JSON 응답 아님");
            }
        }finally{c.disconnect();}
    }
    private String fmt(String pattern,Date d){
        SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.US);f.setTimeZone(korea);return f.format(d);
    }
    private long parseDate(String s,String pattern){
        try{SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.US);f.setTimeZone(korea);f.setLenient(false);return f.parse(s).getTime();}
        catch(Exception e){return -1;}
    }
    private String channel(){return "kr2".equals(id)?"FM4U":"STFM";}
    private static String enc(String s)throws Exception{return URLEncoder.encode(s,"UTF-8");}
    private static String field(JSONObject o,String k){return o.isNull(k)?"":o.optString(k,"");}
    private void pollSong(int g){
        if(g!=generation)return;
        try{
            String value="";
            if("kr1".equals(id)){
                JSONObject r=new JSONObject(get(KBS+"/api/mobile/melon_song_list?channel_code=25","KBS song"));
                JSONArray a=r.getJSONArray("items");Log.i(TAG,"KBS song items="+a.length());
                long newest=-1,now=System.currentTimeMillis();
                for(int i=0;i<a.length();i++){
                    JSONObject o=a.getJSONObject(i);long ts=parseDate(field(o,"timestamp"),"yyyyMMddHHmmss");
                    long dur=o.optLong("duration",0)*1000;
                    if(ts>=newest&&ts>0&&ts<=now+30000&&dur>0&&now<ts+dur+30000){
                        newest=ts;value=field(o,"song_title")+" – "+field(o,"artist");
                    }
                }
            }else{
                JSONArray a=new JSONArray(get(MBC+"/music/somItem","MBC song"));
                Log.i(TAG,"MBC song items="+a.length());
                for(int i=0;i<a.length();i++){
                    JSONObject o=a.optJSONObject(i);if(o!=null&&channel().equalsIgnoreCase(field(o,"Channel"))){value=field(o,"SomItem");break;}
                }
            }
            show(g,song,value.trim().isEmpty()?"현재 곡 정보 없음":value);
        }catch(Exception e){Log.w(TAG,id+" song "+e.getClass().getSimpleName()+": "+e.getMessage());show(g,song,"곡 정보 조회 실패");}
    }
    private int minutes(String s){
        if(s==null||s.length()<4)return -1;
        try{return Integer.parseInt(s.substring(0,2))*60+Integer.parseInt(s.substring(2,4));}catch(Exception e){return -1;}
    }
    private boolean within(int n,int a,int b){return a>=0&&b>=0&&a!=b&&(a<b?n>=a&&n<b:n>=a||n<b);}
    private void refreshSchedule(int g)throws Exception{
        if(System.currentTimeMillis()-scheduleAt<45000)return;
        Date now=new Date();Calendar cal=Calendar.getInstance(korea);
        int minute=cal.get(Calendar.HOUR_OF_DAY)*60+cal.get(Calendar.MINUTE);
        JSONObject chosen=null;
        if("kr1".equals(id)){
            String day=fmt("yyyyMMdd",now);
            JSONObject r=new JSONObject(get(KBS+"/api/mobile/kong_broadcast_schedule_now_list_new3?channel_code=25&planned_day="+day,"KBS schedule"));
            JSONArray a=r.getJSONArray("schedule_items");
            for(int i=0;i<a.length();i++){
                JSONObject o=a.getJSONObject(i);
                JSONObject ch=o.optJSONObject("channel_code");
                if(ch!=null&&!"25".equals(field(ch,"value")))continue;
                String date=field(o,"programming_date");
                long start=parseDate(date+field(o,"start_time").substring(0,6),"yyyyMMddHHmmss");
                long end=parseDate(date+field(o,"end_time").substring(0,6),"yyyyMMddHHmmss");
                if(start>0&&end>0&&end<=start)end+=86400000L;
                if(start<=now.getTime()&&end>now.getTime()){chosen=o;break;}
            }
            String room=chosen==null?"":field(chosen,"room_code");
            if(!room.equals(currentRoom)){messages.clear();seq=-1;currentRoom=room;show(g,comments,"");}
            show(g,program,chosen==null?"현재 프로그램 확인 불가":field(chosen,"program_title"));
        }else{
            JSONArray a=new JSONArray(get(MBC+"/schedule/scheduleList","MBC schedule"));
            String[] days={"일","월","화","수","목","금","토"};
            int today=cal.get(Calendar.DAY_OF_WEEK)-1;
            for(int i=0;i<a.length();i++){
                JSONObject o=a.optJSONObject(i);if(o==null||!channel().equalsIgnoreCase(field(o,"Channel")))continue;
                int start=minutes(field(o,"StartTime")),end=minutes(field(o,"EndTime"));
                if(!within(minute,start,end))continue;
                int d=(start>end&&minute<end)?(today+6)%7:today;
                if(!field(o,"LiveDays").contains(days[d]))continue;
                chosen=o;break;
            }
            String bid=chosen==null?"":field(chosen,"BroadCastID"),gid=chosen==null?"":field(chosen,"ProgramGroupID");
            if(!bid.equals(currentBid)||!gid.equals(currentGid)){messages.clear();show(g,comments,"");currentBid=bid;currentGid=gid;}
            show(g,program,chosen==null?"현재 프로그램 확인 불가":field(chosen,"ProgramTitle"));
        }
        scheduleAt=System.currentTimeMillis();
    }
    private void pollChat(int g){
        if(g!=generation)return;
        try{
            refreshSchedule(g);
            if("kr1".equals(id)){
                if(currentRoom.isEmpty()||currentRoom.endsWith("_etc"))throw new IOException("채팅방 없음");
                JSONObject r=new JSONObject(get("https://interact.static.kbs.co.kr/Chat?room_code="+enc(currentRoom)+"&seq="+seq+"&count=20","KBS chat"));
                JSONArray a=r.getJSONArray("list");
                if(r.optInt("result",-1)!=0)throw new IOException("채팅 요청 거절");
                for(int i=0;i<a.length();i++){
                    JSONObject o=a.getJSONObject(i);messages.put(field(o,"seq"),field(o,"user_name")+"  "+field(o,"date_created")+"\n"+field(o,"msg"));
                }
                seq=r.optLong("nextSeq",seq);Log.i(TAG,"KBS chat count="+a.length());
            }else{
                if(currentBid.isEmpty()||currentGid.isEmpty())throw new IOException("프로그램 ID 없음");
                JSONObject r=new JSONObject(get(MBC+"/minimsg/msgList?bid="+enc(currentBid)+"&gid="+enc(currentGid)+"&page=1&pagesize=20","MBC chat"));
                JSONArray a=r.getJSONArray("MsgList");messages.clear();
                String today=fmt("yyyy-MM-dd",new Date());
                for(int i=a.length()-1;i>=0;i--){
                    JSONObject o=a.getJSONObject(i);
                    if(!field(o,"RegDate").startsWith(today))continue;
                    messages.put(field(o,"SeqID"),field(o,"UserNm")+"  "+field(o,"RegDate")+"\n"+field(o,"Comment"));
                }
                Log.i(TAG,"MBC chat received="+a.length()+" today="+messages.size());
            }
            while(messages.size()>80)messages.remove(messages.keySet().iterator().next());
            ArrayList<String> lines=new ArrayList<>(messages.values());Collections.reverse(lines);
            show(g,comments,lines.isEmpty()?"오늘 수신된 메시지 없음":android.text.TextUtils.join("\n\n",lines));
            show(g,chatStatus,"갱신 "+fmt("HH:mm:ss",new Date())+" · "+messages.size()+"개");
        }catch(Exception e){Log.w(TAG,id+" chat "+e.getClass().getSimpleName()+": "+e.getMessage());show(g,chatStatus,"메시지 조회 실패: "+e.getMessage());}
    }
}
