package com.polarisunbound.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Html;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

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
    private static final String PREFS="polaris_current_program_v3";
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

        public String aaTitle(String id){
            String title=aaTitle();
            if("kiis".equals(id)||"gallery".equals(id))
                return title+"  |  "+localClockLabel(id);
            return title;
        }

        public String phoneText(String id){
            if("kiis".equals(id)||"gallery".equals(id)){
                String clock=localClockLabel(id);
                if(program==null||program.trim().isEmpty()||program.equals(station))
                    return station+"  |  "+clock;
                return station+"\n"+program+"  |  "+clock;
            }
            if(program==null||program.trim().isEmpty()||program.equals(station)) return station;
            String prefix=(startLabel==null||startLabel.isEmpty())?"":startLabel+" ";
            return station+"\n"+prefix+program;
        }
    }

    public static String localClockLabel(String id){
        if(!"kiis".equals(id)&&!"gallery".equals(id)) return "";
        SimpleDateFormat f=new SimpleDateFormat("HH:mm",Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));
        String city="kiis".equals(id)?"LA":"SF";
        return city+" "+f.format(new Date());
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
        if("kr4".equals(id)) return "https://namu.wiki/edit/MBC%20%EB%9D%BC%EB%94%94%EC%98%A4/%ED%8E%B8%EC%84%B1%ED%91%9C?section=2";
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

        if(isNaverDomestic(id)){
            try{
                return resolveNaver(id);
            }catch(Exception e){
                Log.w("PolarisUnbound","Naver schedule failed for "+id+": "+e.getMessage());
                // Keep the previous parser only as a fallback. Naver is the primary source.
                return resolveLegacy(id);
            }
        }
        return resolveLegacy(id);
    }

    private static boolean isNaverDomestic(String id){
        return "kr1".equals(id)||"kr2".equals(id)||"kr3".equals(id)||
               "kr4".equals(id)||"kr6".equals(id);
    }

    private static String naverServiceId(String id){
        if("kr1".equals(id)) return "815457"; // KBS CoolFM
        if("kr2".equals(id)) return "815463"; // MBC FM4U
        if("kr3".equals(id)) return "815449"; // CBS MusicFM
        if("kr4".equals(id)) return "815464"; // MBC Standard FM
        if("kr6".equals(id)) return "815467"; // SBS PowerFM
        return null;
    }

    private static Result resolveNaver(String id) throws Exception {
        String serviceId=naverServiceId(id);
        if(serviceId==null) throw new IllegalArgumentException("No Naver service id for "+id);

        SimpleDateFormat dayFormat=new SimpleDateFormat("yyyyMMdd",Locale.US);
        dayFormat.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        String day=dayFormat.format(new Date());

        String endpoint="https://m.search.naver.com/p/csearch/content/nqapirender.nhn"
            +"?key=SingleChannelDailySchedule"
            +"&where=m"
            +"&pkid=66"
            +"&u1="+serviceId
            +"&u2="+day;

        HttpURLConnection con=(HttpURLConnection)new URL(endpoint).openConnection();
        con.setConnectTimeout(9000);
        con.setReadTimeout(12000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
        con.setRequestProperty("Referer","https://m.search.naver.com/search.naver?where=m&query=%ED%8E%B8%EC%84%B1%ED%91%9C");
        con.setRequestProperty("Accept","application/json,text/plain,*/*");
        con.setRequestProperty("Accept-Language","ko-KR,ko;q=0.9,en-US;q=0.7");

        int code=con.getResponseCode();
        if(code<200||code>=300){
            con.disconnect();
            throw new IllegalStateException("Naver HTTP "+code);
        }

        StringBuilder body=new StringBuilder();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(con.getInputStream()))){
            String line;
            while((line=r.readLine())!=null) body.append(line);
        } finally {
            con.disconnect();
        }

        JSONObject root=new JSONObject(body.toString());
        if(!"success".equalsIgnoreCase(root.optString("statusCode"))){
            throw new IllegalStateException("Naver status "+root.optString("statusCode"));
        }

        Object dataHtml=root.opt("dataHtml");
        StringBuilder html=new StringBuilder();
        if(dataHtml instanceof JSONArray){
            JSONArray arr=(JSONArray)dataHtml;
            for(int i=0;i<arr.length();i++) html.append(arr.optString(i));
        }else if(dataHtml!=null){
            html.append(String.valueOf(dataHtml));
        }

        if(html.length()==0) throw new IllegalStateException("Naver dataHtml empty");
        return parseNaverSchedule(id,html.toString());
    }

    private static Result parseNaverSchedule(String id,String html){
        String station=stationName(id);
        LinkedHashMap<Integer,String> byStart=new LinkedHashMap<>();
        Pattern timePattern=Pattern.compile("^(\\d{1,2}):(\\d{2})$");

        Document doc=Jsoup.parseBodyFragment(html);
        Elements rows=doc.select("li.list");
        if(rows.isEmpty()) rows=doc.select("li");

        for(Element row:rows){
            Element timeElement=row.selectFirst("div.time");
            Element titleElement=row.selectFirst("div.pr_title");

            String timeText=timeElement==null?"":timeElement.text().replaceAll("\\s+"," ").trim();
            String title=titleElement==null?"":titleElement.text().replaceAll("\\s+"," ").trim();

            // Fallback to the historical Naver div positions if class names change.
            Elements cells=row.select("div");
            if(timeText.isEmpty()&&cells.size()>1)
                timeText=cells.get(1).text().replaceAll("\\s+"," ").trim();
            if(title.isEmpty()&&cells.size()>4)
                title=cells.get(4).text().replaceAll("\\s+"," ").trim();

            Matcher tm=timePattern.matcher(timeText);
            if(!tm.matches()) continue;
            int hh=parseInt(tm.group(1),-1);
            int mm=parseInt(tm.group(2),-1);
            if(hh<0||hh>23||mm<0||mm>59) continue;

            // Last-resort title fallback: choose a text-bearing descendant after the time.
            if(title.isEmpty()||!containsProgramText(title)||timePattern.matcher(title).matches()){
                for(Element cell:cells){
                    String candidate=cell.text().replaceAll("\\s+"," ").trim();
                    if(candidate.isEmpty()||candidate.equals(timeText)) continue;
                    if(timePattern.matcher(candidate).matches()) continue;
                    if(candidate.matches("(?i)^(재|재방송|본|본방송|[0-9]+세|전체)$")) continue;
                    if(!containsProgramText(candidate)) continue;
                    if(candidate.length()>title.length()) title=candidate;
                }
            }

            title=cleanTitle(title);
            if(title.isEmpty()||!containsProgramText(title)) continue;
            if(title.length()>90) title=title.substring(0,90).trim();

            int start=hh*60+mm;
            if(!byStart.containsKey(start)) byStart.put(start,title);
        }

        if(byStart.isEmpty()) throw new IllegalStateException("No Naver schedule entries for "+id);
        return selectCurrent(id,station,byStart);
    }

    private static Result resolveLegacy(String id) throws Exception {
        String station=stationName(id);
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
        if("kr4".equals(id)) return parseMbcStandardEdit(id,b.toString());
        return parseNamu(id,b.toString());
    }

    @SuppressWarnings("deprecation")
    private static String htmlText(String html){
        String safe=html==null?"":html;
        safe=safe.replaceAll("(?is)<script\\b[^>]*>.*?</script>"," ")
                 .replaceAll("(?is)<style\\b[^>]*>.*?</style>"," ");
        String out=Html.fromHtml(safe).toString();
        out=out.replace('\u00a0',' ');
        out=out.replace("&nbsp;"," ").replace("&amp;","&");
        return out;
    }

    private static Result parseGallery(String id,String html){
        String station=stationName(id);
        String text=htmlText(html).replaceAll("[ \\t]+"," ").replaceAll("\\n+","\n").trim();
        String low=text.toLowerCase(Locale.US);
        int a=low.indexOf("now playing");
        int b=low.indexOf("last played",Math.max(0,a));
        String now=(a>=0&&b>a)?text.substring(a+"now playing".length(),b).trim():"";
        now=now.replaceAll("(?i)^image\\s*:?\\s*","")
               .replaceAll("\\s+"," ").trim();
        if(now.length()>120) now=now.substring(0,120).trim();
        if(now.isEmpty()) throw new IllegalStateException("No Gallery Now Playing");
        return new Result(station,now,"",-1,-1,System.currentTimeMillis());
    }

    private static Result parseNamu(String id,String html){
        String station=stationName(id);
        String text=htmlText(html).replaceAll("\\r","");
        LinkedHashMap<Integer,String> byStart=new LinkedHashMap<>();

        // NamuWiki renders schedule headings as one line ending in "편집".
        Pattern heading=Pattern.compile(
            "(?m)^\\s*(?:\\d+(?:\\.\\d+)*\\.?\\s*)?(\\d{1,2}):(\\d{2})\\s+([^\\n]{1,140}?)\\s*(?:\\[?편집\\]?)\\s*$",
            Pattern.CASE_INSENSITIVE);
        Matcher hm=heading.matcher(text);
        while(hm.find()){
            addNamuEntry(id,byStart,hm.group(1),hm.group(2),hm.group(3));
        }

        // Fallback for pages where the heading is flattened into surrounding text.
        if(byStart.size()<3){
            Pattern fallback=Pattern.compile(
                "(\\d{1,2}):(\\d{2})\\s+(.{1,120}?)\\s*(?:\\[편집\\]|편집)(?=\\s|$)",
                Pattern.CASE_INSENSITIVE);
            Matcher m=fallback.matcher(text);
            while(m.find()){
                addNamuEntry(id,byStart,m.group(1),m.group(2),m.group(3));
            }
        }

        if(byStart.isEmpty()) throw new IllegalStateException("No schedule entries for "+id);
        return selectCurrent(id,station,byStart);
    }

    private static void addNamuEntry(String id,Map<Integer,String> byStart,String h,String min,String title){
        int hh=parseInt(h,-1), mm=parseInt(min,-1);
        if(hh<0||hh>23||mm<0||mm>59) return;
        String raw=cleanTitle(title);
        if(raw.isEmpty()||looksLikeTimeRange(raw)||!containsProgramText(raw)) return;

        if("kr3".equals(id)){
            boolean mentionsRegion=raw.contains("서울")||raw.contains("부산")||raw.contains("대구")||raw.contains("광주");
            if(mentionsRegion && !raw.contains("서울")) return;
            raw=raw.replaceAll("\\s*\\((?=[^)]*(?:서울|부산|대구|광주))[^)]*\\)\\s*$","").trim();
        }

        if(raw.length()>90) raw=raw.substring(0,90).trim();
        int start=hh*60+mm;
        if(!byStart.containsKey(start)) byStart.put(start,raw);
    }

    private static boolean looksLikeTimeRange(String s){
        if(s==null) return true;
        String x=s.trim();
        if(x.matches("^[~\\-–—]?\\s*\\d{1,2}:\\d{2}.*")) return true;
        if(x.matches("^.*\\d{1,2}:\\d{2}\\s*[~\\-–—]\\s*\\d{1,2}:\\d{2}.*") && !containsProgramText(x.replaceAll("\\d{1,2}:\\d{2}",""))) return true;
        return false;
    }

    private static boolean containsProgramText(String s){
        return s!=null && Pattern.compile("[가-힣A-Za-z]").matcher(s).find();
    }

    private static Result parseKiis(String id,String html){
        String station=stationName(id);
        String text=htmlText(html).replaceAll("\\r","");
        LinkedHashMap<Integer,String> byStart=new LinkedHashMap<>();
        Pattern time=Pattern.compile("(\\d{1,2}:\\d{2})\\s*(AM|PM)\\s*[-–—]\\s*(\\d{1,2}:\\d{2})\\s*(AM|PM)",Pattern.CASE_INSENSITIVE);
        String previous="";
        for(String rawLine:text.split("\\n")){
            String line=rawLine.replaceAll("\\s+"," ").trim();
            if(line.isEmpty()) continue;
            Matcher tm=time.matcher(line);
            if(tm.find()){
                int start=to12HourMinutes(tm.group(1),tm.group(2));
                if(start>=0){
                    String title=line.substring(0,tm.start()).trim();
                    if(title.isEmpty()) title=previous;
                    title=cleanKiisTitle(title);
                    if(!title.isEmpty()&&!looksLikeTimeRange(title)&&containsProgramText(title))
                        byStart.put(start,title);
                }
            }
            if(!time.matcher(line).matches() && !line.matches("(?i)^(Mo|Tu|We|Th|Fr|Sa|Su|On-Air Now)$"))
                previous=line;
        }

        if(byStart.isEmpty()) throw new IllegalStateException("No KIIS schedule entries");
        return selectCurrent(id,station,byStart);
    }

    private static String cleanKiisTitle(String title){
        String x=title==null?"":title;
        x=x.replaceFirst("(?i)^Image:\\s*","")
           .replaceFirst("(?i)^On[- ]Air Now\\s*","")
           .replaceFirst("^[•*\\-]+\\s*","")
           .replaceAll("\\s+"," ").trim();
        if(x.length()>80) x=x.substring(0,80).trim();
        return x;
    }

    private static Result parseMbcStandardEdit(String id,String html){
        String station=stationName(id);
        String source=extractEditSource(html);
        LinkedHashMap<Integer,String> byStart=new LinkedHashMap<>();

        String[] spanText=new String[9];
        int[] spanRows=new int[9];

        for(String rawLine:source.split("\\r?\\n")){
            String line=rawLine.trim();
            if(!line.contains("||")) continue;

            String[] row=new String[9];
            boolean[] occupied=new boolean[9];
            for(int c=0;c<9;c++){
                if(spanRows[c]>0){
                    row[c]=spanText[c];
                    occupied[c]=true;
                    spanRows[c]--;
                    if(spanRows[c]==0) spanText[c]=null;
                }
            }

            String[] cells=line.split("\\|\\|",-1);
            int col=0;
            for(String cell:cells){
                if(cell==null||cell.trim().isEmpty()) continue;
                while(col<9&&occupied[col]) col++;
                if(col>=9) break;

                int colspan=directiveNumber(cell,"<-",1);
                int rowspan=directiveNumber(cell,"<|",1);
                String value=cleanWikiCell(cell);
                for(int n=0;n<colspan&&col+n<9;n++){
                    int at=col+n;
                    while(at<9&&occupied[at]) at++;
                    if(at>=9) break;
                    row[at]=value;
                    occupied[at]=true;
                    if(rowspan>1){
                        spanText[at]=value;
                        spanRows[at]=rowspan-1;
                    }
                }
                col++;
            }

            int hh=parseInt(row[0],-1);
            int mm=parseInt(row[1],-1);
            if(hh<0||hh>23||mm<0||mm>59) continue;

            int scheduleCol=mbcScheduleColumn();
            String title=displayProgram(row[scheduleCol]);
            if(title.isEmpty()){
                for(int c=3;c<=6;c++){
                    title=displayProgram(row[c]);
                    if(!title.isEmpty()) break;
                }
            }
            if(title.isEmpty()||!containsProgramText(title)) continue;

            int start=hh*60+mm;
            byStart.put(start,title);
        }

        if(byStart.isEmpty()) throw new IllegalStateException("No MBC Standard FM table entries");
        return selectCurrent(id,station,byStart);
    }

    private static int mbcScheduleColumn(){
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"));
        int day=c.get(Calendar.DAY_OF_WEEK);
        if(day==Calendar.SATURDAY) return 5;
        if(day==Calendar.SUNDAY) return 6;
        return 3;
    }

    private static int directiveNumber(String cell,String prefix,int fallback){
        Pattern p=Pattern.compile(Pattern.quote(prefix)+"(\\d+)>");
        Matcher m=p.matcher(cell);
        if(m.find()) return Math.max(1,parseInt(m.group(1),fallback));
        return fallback;
    }

    private static String extractEditSource(String html){
        if(html==null) return "";
        Matcher ta=Pattern.compile("(?is)<textarea[^>]*>(.*?)</textarea>").matcher(html);
        if(ta.find()) return htmlText(ta.group(1));
        String text=htmlText(html);
        int at=text.indexOf("== 타임테이블 ==");
        return at>=0?text.substring(at):text;
    }

    private static String cleanWikiCell(String cell){
        if(cell==null) return "";
        String x=cell;
        x=x.replaceAll("<[^>]*>"," ")
           .replace("[br]"," ")
           .replace("'''","")
           .replace("**","")
           .replaceAll("\\{\\{\\{#[0-9A-Fa-f]+\\s*","")
           .replace("}}}","");
        return x.replaceAll("\\s+"," ").trim();
    }

    private static String displayProgram(String cell){
        if(cell==null) return "";
        String x=cell.trim();
        if(x.isEmpty()) return "";

        Matcher link=Pattern.compile("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]").matcher(x);
        String title="";
        int end=-1;
        if(link.find()){
            title=(link.group(2)!=null?link.group(2):link.group(1)).trim();
            end=link.end();
        } else {
            title=x.replaceAll("\\[\\*.*"," ")
                   .replaceAll("\\[[^]]*]"," ")
                   .replaceAll("\\s+"," ").trim();
        }

        if(end>=0&&end<x.length()){
            String tail=x.substring(end)
                .replaceAll("\\[\\*.*"," ")
                .replaceAll("\\[[^]]*]"," ")
                .replaceAll("\\s+"," ").trim();
            if(tail.matches("(?i)^[0-9].*부.*")||tail.matches("(?i)^(1|2|3|4)[, ]+.*부.*"))
                title=(title+" "+tail).trim();
        }

        if(title.matches("(?i)^(서울|춘천|원주|강원영동|충북|대전|전주|광주|목포|여수|대구|안동|포항|부산|울산|경남|제주)$"))
            return "";
        if(title.length()>90) title=title.substring(0,90).trim();
        return title;
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
        int[] marks={0,5,10,30,35};

        int nextHourOffset=0;
        int target=-1;
        for(int mark:marks){
            if(mark>minute){
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
