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
    private volatile int generation=0;
    private String id;
    private TextView program,song,comments,chatStatus;
    private ScrollView chatScroll;
    private String lastKbsSong="";
    private long lastKbsSongStart=-1,lastKbsSongUntil=-1;
    private String currentRoom="",currentBid="",currentGid="";
    private long scheduleAt=0,seq=-1,nextKbsSchedule=0,keyRetryAt=0;
    private String webKey="",etag="";
    private final LinkedHashMap<String,String> messages=new LinkedHashMap<>();
    private final TimeZone korea=TimeZone.getTimeZone("Asia/Seoul");

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String s,int size){
        TextView v=new TextView(this); v.setText(s);v.setTextSize(size);v.setTextColor(0xff171717);
        v.setPadding(dp(12),dp(8),dp(12),dp(8));return v;
    }
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        id=getIntent().getStringExtra("station");
        if(!"kr1".equals(id)&&!"kr2".equals(id)){finish();return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(Color.WHITE);
        root.setPadding(0,dp(8),0,dp(8));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop()+dp(8),
                insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()+dp(8));
            return insets;
        });
        TextView back=label("‹   "+CurrentProgramResolver.stationName(id),21);back.setTypeface(null,Typeface.BOLD);
        back.setOnClickListener(v->finish());root.addView(back);
        program=label("현재 방송 확인 중",17);root.addView(program);
        song=label("현재 재생곡 확인 중",22);song.setTypeface(null,Typeface.BOLD);root.addView(song);
        TextView heading=label("청취자 메시지",18);heading.setTypeface(null,Typeface.BOLD);root.addView(heading);
        chatStatus=label("연결 확인 중",13);chatStatus.setTextColor(0xff666666);root.addView(chatStatus);
        chatScroll=new ScrollView(this);comments=label("",14);comments.setGravity(Gravity.TOP);
        comments.setTextIsSelectable(false);
        chatScroll.addView(comments);root.addView(chatScroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);root.requestApplyInsets();
    }
    @Override protected void onResume(){
        super.onResume();if(id==null||isFinishing())return;
        final int g=++generation;
        jobs=Executors.newScheduledThreadPool(2);
        jobs.scheduleWithFixedDelay(()->pollSong(g),0,10,TimeUnit.SECONDS);
        jobs.scheduleWithFixedDelay(()->pollChat(g),0,"kr1".equals(id)?5:10,TimeUnit.SECONDS);
    }
    @Override protected void onPause(){
        generation++;if(jobs!=null){jobs.shutdownNow();jobs=null;}super.onPause();
    }
    private void show(int g,TextView view,String value){
        ui.post(()->{if(g==generation&&!isFinishing())view.setText(value);});
    }
    private void showChat(int g,List<String> lines){
        ui.post(()->{
            if(g!=generation||isFinishing()||chatScroll==null)return;
            boolean follow=comments.getHeight()==0||comments.getText().length()==0||
                comments.getBottom()-chatScroll.getScrollY()-chatScroll.getHeight()<=dp(48);
            int oldY=chatScroll.getScrollY();
            android.text.SpannableStringBuilder content=new android.text.SpannableStringBuilder();
            for(String line:lines){
                if(content.length()>0)content.append("\n\n");
                int start=content.length();content.append(line);
                int header=line.indexOf('\n');
                if(header>0){
                    content.setSpan(new android.text.style.ForegroundColorSpan(0xff666666),start,start+header,33);
                    content.setSpan(new android.text.style.RelativeSizeSpan(0.85f),start,start+header,33);
                }
            }
            if(lines.isEmpty())content.append("오늘 수신된 메시지 없음");
            if(android.text.TextUtils.equals(comments.getText(),content))return;
            comments.setText(content);
            chatScroll.post(()->{
                if(g!=generation||isFinishing())return;
                if(follow)chatScroll.fullScroll(android.view.View.FOCUS_DOWN);
                else chatScroll.scrollTo(0,oldY);
            });
        });
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
    // Exact text mapping from the KBS PC liveChat.js client.
    private static final String[] KBS_EMOTICONS={
        "(뽀뽀)", "(식사)", "(비웃음)", "(설렘)", "(댄스)", "(NO)", "(OK)", "(좌절)", "(큰웃음)", "(애정)",
        "(응원)", "(졸림)", "(기대)", "(분노)", "(인사)", "(공포)", "(잠)", "(눈물)", "(대성통곡)", "(슬픔)",
        "(굿)", "(난감)", "(결심)", "(귀찮음)", "(심심)", "(놀람)", "(사과)", "(당황)", "(부끄러움)", "(어질어질)",
        "(배고픔)", "(신나)", "(화남)", "(기쁨)", "(황당)", "(궁금)", "(음악감상)", "(반함)", "(짜증)", "(삐짐)",
        "(생일축하)", "(식사)", "(일하는모습)", "(행복)", "(신남)", "(출근중)", "(운동)", "(기쁨)", "(신남)", "(더움)",
        "(휴식)", "(열남)", "(잠)", "(기대)", "(공포)", "(태풍)", "(간식)", "(어이없음)", "(즐거움)", "(즐거움)",
        "(놀람)", "(의심)", "(영화관람)", "(열심)", "(부듯)", "(가는중)", "(꽃구경)", "(인사)", "(공격)", "(웃음)",
        "(질주)", "(아픔)", "(즐거움)", "(외로움)", "(추움)", "(취미생활)", "(행복)", "(선물)", "(티타임)", "(뒤뚱뒤뚱)"
    };
    private static String kbsChatBody(JSONObject o){
        String msg=field(o,"msg");
        String code=field(o,"emoticon").trim();
        String label="";
        if(!code.isEmpty()&&!"null".equalsIgnoreCase(code)){
            if(code.matches("[a-h][0-9]")){
                label=KBS_EMOTICONS[(code.charAt(0)-'a')*10+code.charAt(1)-'0'];
            }else{
                label="(이모티콘: "+code+")";
            }
        }
        if(msg.trim().isEmpty())return label.isEmpty()?"(내용 없음)":label;
        return label.isEmpty()?msg:label+" "+msg;
    }
    private void pollSong(int g){
        if(g!=generation)return;
        try{
            String value="";
            if("kr1".equals(id)){
                JSONObject r=new JSONObject(get(KBS+"/api/mobile/melon_song_list?channel_code=25","KBS song"));
                JSONArray a=r.getJSONArray("items");Log.i(TAG,"KBS song items="+a.length());
                long newest=-1,now=System.currentTimeMillis(),validUntil=-1;
                for(int i=0;i<a.length();i++){
                    JSONObject o=a.optJSONObject(i);if(o==null)continue;
                    String title=field(o,"song_title").trim(),artist=field(o,"artist").trim();
                    String stamp=field(o,"timestamp");
                    long ts=parseSongTime(stamp);
                    if(ts<=0)ts=parseSongTime(field(o,"lastupdatetime"));
                    long duration=o.optLong("duration",0);
                    long playtime=o.optLong("playtime",0);
                    long seconds=duration>0?duration:playtime;
                    if(seconds>3600&&seconds<=3600000)seconds/=1000;
                    if(seconds<=0||seconds>3600)seconds=240;
                    long end=ts+seconds*1000L;
                    boolean valid=ts>0&&ts<=now+60000&&now<=end+60000&&!title.isEmpty();
                    Log.i(TAG,"KBS song time="+stamp+" duration="+duration+" playtime="+playtime+" ageSec="+((now-ts)/1000)+" accepted="+valid);
                    if(valid&&ts>=newest){
                        newest=ts;validUntil=end+60000;
                        value=artist.isEmpty()?title:title+" – "+artist;
                    }
                }
                if(g!=generation)return;
                if(!value.isEmpty()){
                    lastKbsSong=value;lastKbsSongStart=newest;lastKbsSongUntil=validUntil;
                }else if(now<=lastKbsSongUntil&&!lastKbsSong.isEmpty()){
                    value=lastKbsSong;
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
    private long parseSongTime(String value){
        String s=value==null?"":value.trim();
        if(s.matches("\\d{14}"))return parseDate(s,"yyyyMMddHHmmss");
        if(s.matches("\\d{8} \\d{6}"))return parseDate(s,"yyyyMMdd HHmmss");
        if(s.matches("\\d{13}"))try{return Long.parseLong(s);}catch(Exception ignored){}
        if(s.matches("\\d{10}"))try{return Long.parseLong(s)*1000L;}catch(Exception ignored){}
        if(s.length()>=19){
            String t=s.substring(0,19).replace('T',' ');
            return parseDate(t,"yyyy-MM-dd HH:mm:ss");
        }
        return -1;
    }
    private long scheduleTime(String day,String time){
        if(day.length()!=8||time.length()<6)return -1;
        try{
            int h=Integer.parseInt(time.substring(0,2));
            int m=Integer.parseInt(time.substring(2,4)),s=Integer.parseInt(time.substring(4,6));
            long base=parseDate(day,"yyyyMMdd");
            if(base<0||h<0||h>47||m<0||m>59||s<0||s>59)return -1;
            return base+1000L*(h*3600+m*60+s);
        }catch(Exception e){return -1;}
    }
    private String shortTime(String s){
        if(s.matches("\\d{8} \\d{6}"))return s.substring(9,11)+":"+s.substring(11,13)+":"+s.substring(13,15);
        if(s.length()>=19&&s.charAt(10)==' ')return s.substring(11,19);
        if(s.length()>=19&&s.charAt(10)=='T')return s.substring(11,19);
        return s;
    }
    private String fetchText(String url)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(6000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","*/*");
        try{
            if(c.getResponseCode()!=200)throw new IOException("KBS 웹 설정 조회 실패");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>4000000)throw new IOException("웹 설정 크기 초과");}
                return out.toString("UTF-8");
            }
        }finally{c.disconnect();}
    }
    // The public browser client publishes its app identifier in its JavaScript.
    // Resolve it at runtime rather than storing a third-party key in the repository.
    private String getWebKey()throws Exception{
        if(!webKey.isEmpty())return webKey;
        long now=System.currentTimeMillis();
        if(now<keyRetryAt)throw new IOException("KBS 웹 설정 재시도 대기");
        keyRetryAt=now+60000;
        String js=null;
        try{
            String page="https://onair.kbs.co.kr/?ch_code=25&ch_type=radioList&chat=on";
            org.jsoup.nodes.Document doc=org.jsoup.Jsoup.parse(fetchText(page),page);
            for(org.jsoup.nodes.Element script:doc.select("script[src]")){
                String u=script.absUrl("src");
                URL url=new URL(u);
                String host=url.getHost();
                if("https".equals(url.getProtocol())&&("static.kbs.co.kr".equals(host)||"onair.kbs.co.kr".equals(host))&&url.getPath().endsWith("/liveChat.js")){
                    js=fetchText(u);break;
                }
            }
        }catch(Exception ignored){}
        if(js==null)js=fetchText("https://static.kbs.co.kr/contents/20261118094618/resources/onair/js/liveChat.js");
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("API_KEY\\s*:\\s*['\"]([A-Fa-f0-9]{64})['\"]").matcher(js);
        if(!matcher.find())throw new IOException("KBS 공개 웹 설정 형식 변경");
        webKey=matcher.group(1);return webKey;
    }
    private JSONObject fetchKbsChat()throws Exception{
        String key=getWebKey();
        HttpURLConnection c=(HttpURLConnection)new URL("https://interact.static.kbs.co.kr/chatweb?room_code="+enc(currentRoom)+"&seq="+seq+"&count=30").openConnection();
        c.setConnectTimeout(6000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("webauthkey",key);
        if(!etag.isEmpty())c.setRequestProperty("If-None-Match",etag);
        try{
            int code=c.getResponseCode();Log.i(TAG,"KBS chat HTTP "+code);
            if(code==304)return null;
            if(code!=200)throw new IOException("HTTP "+code);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>4000000)throw new IOException("response too large");}
                JSONObject r=new JSONObject(out.toString("UTF-8"));
                if("0".equals(field(r,"result"))){
                    String tag=c.getHeaderField("etag");etag=tag==null?"":tag;
                }
                return r;
            }
        }finally{c.disconnect();}
    }
    private int minutes(String s){
        if(s==null||s.length()<4)return -1;
        try{return Integer.parseInt(s.substring(0,2))*60+Integer.parseInt(s.substring(2,4));}catch(Exception e){return -1;}
    }
    private boolean within(int n,int a,int b){return a>=0&&b>=0&&a!=b&&(a<b?n>=a&&n<b:n>=a||n<b);}
    private void refreshSchedule(int g)throws Exception{
        if("kr1".equals(id)?System.currentTimeMillis()<nextKbsSchedule:System.currentTimeMillis()-scheduleAt<45000)return;
        Date now=new Date();Calendar cal=Calendar.getInstance(korea);
        int minute=cal.get(Calendar.HOUR_OF_DAY)*60+cal.get(Calendar.MINUTE);
        JSONObject chosen=null;
        if("kr1".equals(id)){
            nextKbsSchedule=System.currentTimeMillis()+15000;
            JSONArray groups=new JSONArray(get("https://static.api.kbs.co.kr/mediafactory/v1/schedule/onair_now?local_station_code=00&channel_code=25","KBS schedule"));
            long nowMs=now.getTime(), chosenEnd=0;
            for(int j=0;j<groups.length();j++){
                JSONObject group=groups.optJSONObject(j);
                if(group==null||!"25".equals(field(group,"channel_code")))continue;
                JSONArray a=group.optJSONArray("schedules");if(a==null)continue;
                for(int i=0;i<a.length();i++){
                    JSONObject o=a.optJSONObject(i);if(o==null)continue;
                    String date=field(o,"service_date");
                    if(date.isEmpty())date=field(o,"program_planned_date");
                    JSONObject running=o.optJSONObject("running");
                    String st=running==null?field(o,"service_start_time"):field(running,"start_time");
                    String en=running==null?field(o,"service_end_time"):field(running,"end_time");
                    if(st.isEmpty())st=field(o,"service_start_time");
                    if(en.isEmpty())en=field(o,"service_end_time");
                    long begin=scheduleTime(date,st), finish=scheduleTime(date,en);
                    if(begin>0&&finish>0&&finish<=begin)finish+=86400000L;
                    if(begin>0&&begin<=nowMs&&finish>nowMs){chosen=o;chosenEnd=finish;break;}
                }
                if(chosen!=null)break;
            }
            if(g!=generation)return;
            String room=chosen==null?"":field(chosen,"parent_code");
            if(chosen!=null&&room.isEmpty())room="25_etc";
            if(!room.equals(currentRoom)){messages.clear();seq=-1;etag="";currentRoom=room;show(g,comments,"");}
            nextKbsSchedule=chosen==null?nowMs+15000:Math.min(nowMs+45000,chosenEnd);
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
    private synchronized void pollChat(int g){
        if(g!=generation)return;
        try{
            refreshSchedule(g);
            if("kr1".equals(id)){
                if(currentRoom.isEmpty())throw new IOException("채팅방 없음");
                JSONObject r=fetchKbsChat();
                if(g!=generation)return;
                if(r!=null){
                    if(!"0".equals(field(r,"result"))){
                        webKey="";etag="";throw new IOException("KBS 채팅 인증 또는 조회 실패");
                    }
                    if(!currentRoom.equals(field(r,"room_code")))throw new IOException("채팅방 불일치");
                    JSONArray a=r.getJSONArray("list");
                    for(int i=0;i<a.length();i++){
                        JSONObject o=a.getJSONObject(i);
                        String name="0".equals(field(o,"s_flag"))?field(o,"user_id"):field(o,"user_name");
                        messages.put(field(o,"seq"),name+"  "+shortTime(field(o,"date_created"))+"\n"+kbsChatBody(o));
                    }
                    seq=r.optLong("nextSeq",seq);Log.i(TAG,"KBS chat count="+a.length());
                }
            }else{
                if(currentBid.isEmpty()||currentGid.isEmpty())throw new IOException("프로그램 ID 없음");
                JSONObject r=new JSONObject(get(MBC+"/minimsg/msgList?bid="+enc(currentBid)+"&gid="+enc(currentGid)+"&page=1&pagesize=20","MBC chat"));
                JSONArray a=r.getJSONArray("MsgList");
                String today=fmt("yyyy-MM-dd",new Date());
                for(int i=a.length()-1;i>=0;i--){
                    JSONObject o=a.getJSONObject(i);
                    if(!field(o,"RegDate").startsWith(today))continue;
                    messages.put(field(o,"SeqID"),field(o,"UserNm")+"  "+shortTime(field(o,"RegDate"))+"\n"+field(o,"Comment"));
                }
                Log.i(TAG,"MBC chat received="+a.length()+" today="+messages.size());
            }
            while(messages.size()>80)messages.remove(messages.keySet().iterator().next());
            ArrayList<String> lines=new ArrayList<>(messages.values());
            showChat(g,lines);
            show(g,chatStatus,"갱신 "+fmt("HH:mm:ss",new Date())+" · "+messages.size()+"개");
        }catch(Exception e){Log.w(TAG,id+" chat "+e.getClass().getSimpleName()+": "+e.getMessage());show(g,chatStatus,"메시지 조회 실패: "+e.getMessage());}
    }
}
