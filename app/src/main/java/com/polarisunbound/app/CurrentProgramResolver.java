package com.polarisunbound.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Html;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CurrentProgramResolver {
    private static final String PREFS="polaris_current_program";
    private static final long CACHE_MAX_AGE_MS=20L*60L*1000L;

    public static final class Result {
        public final String station;
        public final String program;
        public final String startLabel;
        public final int startMinutes;
        public final int nextStartMinutes;
        public final long updatedAt;

        Result(String station,String program,String startLabel,int startMinutes,int nextStartMinutes,long updatedAt){
            this.station=station;
            this.program=program;
            this.startLabel=startLabel;
            this.startMinutes=startMinutes;
            this.nextStartMinutes=nextStartMinutes;
            this.updatedAt=updatedAt;
        }

        public String aaTitle(){
            if(program==null||program.trim().isEmpty()) return station;
            return program;
        }

        public String phoneText(String id){
            if("gallery".equals(id)){
                SimpleDateFormat f=new SimpleDateFormat("HH:mm",Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));
                return station+"  |  SF "+f.format(new Date())+"\n"+program;
            }
            if(program==null||program.trim().isEmpty()||program.equals(station)) return station;
            return station+"\n"+aaTitle();
        }
    }

    private static final class Entry {
        final int start;
        final String title;
        Entry(int start,String title){ this.start=start; this.title=title; }
    }

    private CurrentProgramResolver(){}

    public static String stationName(String id){
        if("kr1".equals(id)) return "89.1 KBS CoolFM";
        if("kr2".equals(id)) return "91.9 MBC FM4U";
        if("kr3".equals(id)) return "93.9 CBS MusicFM";
        if("kr4".equals(id)) return "95.9 MBC 표준FM";
        if("kr5".equals(id)) return "102.7 AFN EagleFM";
        if("kr6".equals(id)) return "107.7 SBS PowerFM";
        if("kiis".equals(id)) return "102.7 KIIS-FM";
        if("gallery".equals(id)) return "Jazz from Gallery 41";
        return id==null?"":id;
    }

    public static String scheduleUrl(String id){
        if("kr1".equals(id)) return "https://namu.wiki/w/KBS%202FM?from=KBS%20Cool%20FM";
        if("kr2".equals(id)) return "https://namu.wiki/w/MBC%20FM4U?from=MBC%20FM";
        if("kr3".equals(id)) return "https://namu.wiki/w/CBS%20%EC%9D%8C%EC%95%85FM?from=CBS%20FM";
        if("kr4".equals(id)) return "https://namu.wiki/w/MBC%20%EB%9D%BC%EB%94%94%EC%98%A4/%ED%8E%B8%EC%84%B1%ED%91%9C";
        if("kr6".equals(id)) return "https://namu.wiki/w/SBS%20%ED%8C%8C%EC%9B%8CFM";
        if("kiis".equals(id)) return "https://kiisfm.iheart.com/schedule/";
        if("gallery".equals(id)) return "https://live365.com/station/Jazz-from-Gallery-41-a94394";
        return null;
    }

    public static TimeZone timeZoneFor(String id){
        return TimeZone.getTimeZone(("kiis".equals(id)||"gallery".equals(id))?"America/Los_Angeles":"Asia/Seoul");
    }

    public static Result resolve(String id) throws Exception {
        String station=stationName(id);
        if("kr5".equals(id)){
            return new Result(station,station,"",-1,-1,System.currentTimeMillis());
        }
        String url=scheduleUrl(id);
        if(url==null) return new Result(station,station,"",-1,-1,System.currentTimeMillis());

        HttpURLConnection con=(HttpURLConnection)new URL(url).openConnection();
        con.setConnectTimeout(9000);
        con.setReadTimeout(12000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
        con.setRequestProperty("Accept-Language","ko-KR,ko;q=0.9,en-US;q=0.7,en;q=0.6");
        StringBuilder b=new StringBuilder();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(con.getInputStream()))){
            String line;
            while((line=r.readLine())!=null) b.append(line).append('\n');
        } finally {
            con.disconnect();
        }

        if("gallery".equals(id)) return parseGallery(id,b.toString());
        if("kiis".equals(id)) return parseKiis(id,b.toString());
        return parseNamu(id,b.toString());
    }

    @SuppressWarnings("deprecation")
    private static String htmlText(String html){
        String s=Html.fromHtml(html==null?"":html).toString();
        s=s.replace('\u00a0',' ');
        s=s.replace("&nbsp;"," ").replace("&amp;","&");
        return s;
    }

    private static Result parseGallery(String id,String html){
        String station=stationName(id);
        String text=htmlText(html).replaceAll("[ \\t]+"," ").replaceAll("\\n+","\n").trim();
        String low=text.toLowerCase(Locale.US);
        int a=low.indexOf("now playing");
        int b=low.indexOf("last played",Math.max(0,a));
        String now=(a>=0&&b>a)?text.substring(a+"now playing".length(),b).trim():"";
        now=now.replaceAll("\\s+"," ").trim();
        if(now.length()>96) now=now.substring(0,96).trim();
        if(now.isEmpty()) now=station;
        return new Result(station,now,"",-1,-1,System.currentTimeMillis());
    }

    private static Result parseNamu(String id,String html){
        String station=stationName(id);
        String text=htmlText(html).replaceAll("[ \\t]+"," ").replaceAll("\\n+","\n");
        Pattern p=Pattern.compile("(\\d{1,2}):(\\d{2})\\s+(.{1,140}?)\\s*(?:\\[편집\\]|편집)(?=\\s|$)",Pattern.CASE_INSENSITIVE);
        Matcher m=p.matcher(text);
        LinkedHashMap<Integer,String> byStart=new LinkedHashMap<>();
        while(m.find()){
            int hh=parseInt(m.group(1),-1), mm=parseInt(m.group(2),-1);
            if(hh<0||hh>23||mm<0||mm>59) continue;
            String raw=cleanTitle(m.group(3));
            if(raw.isEmpty()) continue;

            if("kr3".equals(id)){
                boolean mentionsRegion=raw.contains("서울")||raw.contains("부산")||raw.contains("대구")||raw.contains("광주");
                if(mentionsRegion && !raw.contains("서울")) continue;
                raw=raw.replaceAll("\\s*\\((?=[^)]*(?:서울|부산|대구|광주))[^)]*\\)\\s*$","").trim();
            }

            if(raw.length()>90) raw=raw.substring(0,90).trim();
            int start=hh*60+mm;
            if(!byStart.containsKey(start)) byStart.put(start,raw);
        }
        return selectCurrent(id,station,byStart);
    }

    private static Result parseKiis(String id,String html){
        String station=stationName(id);
        String text=htmlText(html).replaceAll("\\r","").replace('\u00a0',' ');
        Pattern times=Pattern.compile("(\\d{1,2}:\\d{2})\\s*(AM|PM)\\s*[-–~]\\s*(\\d{1,2}:\\d{2})\\s*(AM|PM)",Pattern.CASE_INSENSITIVE);
        Matcher m=times.matcher(text);
        LinkedHashMap<Integer,String> byStart=new LinkedHashMap<>();
        while(m.find()){
            int start=to12HourMinutes(m.group(1),m.group(2));
            if(start<0) continue;
            String title=titleBefore(text,m.start());
            title=title.replaceFirst("(?i)^Image:\\s*","")
                       .replaceFirst("(?i)^On[- ]Air Now\\s*","")
                       .replaceFirst("^[•*\\-]+\\s*","")
                       .trim();
            if(title.isEmpty()) continue;
            if(title.length()>80) title=title.substring(Math.max(0,title.length()-80)).trim();
            if(!byStart.containsKey(start)) byStart.put(start,title);
        }
        return selectCurrent(id,station,byStart);
    }

    private static String titleBefore(String text,int pos){
        int from=Math.max(0,pos-180);
        String s=text.substring(from,pos).replace('\r','\n');
        String[] lines=s.split("\\n");
        for(int i=lines.length-1;i>=0;i--){
            String x=lines[i].replaceAll("\\s+"," ").trim();
            if(x.isEmpty()) continue;
            if(x.matches("(?i).*(Mo|Tu|We|Th|Fr|Sa|Su)$")) continue;
            return x;
        }
        return "";
    }

    private static Result selectCurrent(String id,String station,Map<Integer,String> source){
        if(source.isEmpty()) return new Result(station,station,"",-1,-1,System.currentTimeMillis());
        List<Entry> entries=new ArrayList<>();
        for(Map.Entry<Integer,String> e:source.entrySet()) entries.add(new Entry(e.getKey(),e.getValue()));
        Collections.sort(entries,Comparator.comparingInt(a->a.start));

        Calendar c=Calendar.getInstance(timeZoneFor(id));
        int now=c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE);
        Entry current=null;
        for(Entry e:entries) if(e.start<=now) current=e; else break;
        if(current==null) current=entries.get(entries.size()-1);

        int next=-1;
        for(Entry e:entries){
            if(e.start>current.start){ next=e.start; break; }
        }
        if(next<0) next=entries.get(0).start;

        String label=String.format(Locale.US,"%02d:%02d",current.start/60,current.start%60);
        return new Result(station,current.title,label,current.start,next,System.currentTimeMillis());
    }

    private static String cleanTitle(String s){
        if(s==null) return "";
        String x=s.replaceAll("\\s+"," ").trim();
        x=x.replaceAll("^\\d+(?:\\.\\d+)+\\.?\\s*","").trim();
        return x;
    }

    private static int to12HourMinutes(String hhmm,String ampm){
        String[] p=hhmm.split(":");
        if(p.length!=2) return -1;
        int h=parseInt(p[0],-1), m=parseInt(p[1],-1);
        if(h<1||h>12||m<0||m>59) return -1;
        boolean pm="PM".equalsIgnoreCase(ampm);
        if(h==12) h=0;
        if(pm) h+=12;
        return h*60+m;
    }

    private static int parseInt(String s,int fallback){
        try{ return Integer.parseInt(s); }catch(Exception e){ return fallback; }
    }

    public static void save(Context context,String id,Result r){
        if(context==null||id==null||r==null) return;
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putString(id+"_station",r.station)
            .putString(id+"_program",r.program)
            .putString(id+"_start_label",r.startLabel)
            .putInt(id+"_start",r.startMinutes)
            .putInt(id+"_next",r.nextStartMinutes)
            .putLong(id+"_updated",r.updatedAt)
            .apply();
    }

    public static Result cached(Context context,String id){
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long at=p.getLong(id+"_updated",0L);
        if(at<=0) return null;
        return new Result(
            p.getString(id+"_station",stationName(id)),
            p.getString(id+"_program",stationName(id)),
            p.getString(id+"_start_label",""),
            p.getInt(id+"_start",-1),
            p.getInt(id+"_next",-1),
            at
        );
    }

    public static boolean cacheFresh(Result r){
        return r!=null && System.currentTimeMillis()-r.updatedAt<CACHE_MAX_AGE_MS;
    }

    public static long nextRefreshDelay(String id,Result r){
        Calendar c=Calendar.getInstance(timeZoneFor(id));
        int minute=c.get(Calendar.MINUTE);
        int second=c.get(Calendar.SECOND);
        int milli=c.get(Calendar.MILLISECOND);
        int[] marks={5,10,30,35};

        int nextHourOffset=0;
        int target=-1;
        for(int mark:marks){
            if(mark>minute || (mark==minute && (second<1 || (second==1 && milli==0)))){
                target=mark;
                break;
            }
        }
        if(target<0){
            target=marks[0];
            nextHourOffset=1;
        }

        int deltaMinutes=(nextHourOffset*60)+target-minute;
        long delay=deltaMinutes*60L*1000L-second*1000L-milli+1500L;
        return Math.max(1000L,delay);
    }
}
