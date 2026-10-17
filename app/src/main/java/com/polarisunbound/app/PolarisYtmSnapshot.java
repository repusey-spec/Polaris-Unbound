package com.polarisunbound.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Only saves visible labels obtained WITH opt-in Android accessibility access
 * from the installed official YouTube Music app.
 *
 * These entries are not playlists with verified media IDs. Deliberately mark
 * them browsable instead of playable. Never manufacture a video/playlist ID.
 */
final class PolarisYtmSnapshot {
    private static final String PREF="ytm_visible_library";
    private static final String KEY="entries";
    private static final String TIME="captured_at";
    private static final String LAST_SCAN="last_scan";
    private static final String LAST_REASON="last_reason";
    private static final String LAST_LABELS="last_labels";
    private static final String LAST_MATCHES="last_matches";
    private static final String LAST_NODES="last_nodes";
    private static final int MAX_ITEMS=60;
    private PolarisYtmSnapshot(){}

    static final class Entry {
        final String title;
        final String description;
        final String kind;
        Entry(String title,String description,String kind){
            this.title=title;this.description=description;this.kind=kind;
        }
    }
    static void save(Context ctx,List<Entry> entries) {
        if(entries==null||entries.isEmpty())return;
        JSONArray arr=new JSONArray();
        for(Entry e:entries){
            if(arr.length()>=MAX_ITEMS)break;
            try {
                arr.put(new JSONObject().put("title",e.title)
                    .put("subtitle",e.description).put("kind",e.kind));
            }catch(Exception ignored){}
        }
        ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
            .putString(KEY,arr.toString())
            .putLong(TIME,System.currentTimeMillis()).apply();
    }
    static void markAttempt(Context ctx,String reason,int nodes,int labels,int matches) {
        ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
            .putLong(LAST_SCAN,System.currentTimeMillis())
            .putString(LAST_REASON,reason)
            .putInt(LAST_NODES,nodes)
            .putInt(LAST_LABELS,labels)
            .putInt(LAST_MATCHES,matches)
            .apply();
    }

    static String diagnostics(Context ctx) {
        SharedPreferences prefs=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        long last=prefs.getLong(LAST_SCAN,0L);
        if(last==0L) return "접근성 서비스의 YT Music 화면 검사 기록 없음. "+
            "공식 YT Music 앱에서 보관함 화면을 실제로 열었는지 확인 필요.";
        long delta=Math.max(0,(System.currentTimeMillis()-last)/1000L);
        long saved=prefs.getLong(TIME,0L);
        return "마지막 YT Music 화면 검사 "+delta+"초 전; "+
            "노드="+prefs.getInt(LAST_NODES,0)+
            ", 문자열="+prefs.getInt(LAST_LABELS,0)+
            ", 분류된 항목="+prefs.getInt(LAST_MATCHES,0)+
            "; 결과="+prefs.getString(LAST_REASON,"(없음)")+
            "; 최근 정상 저장="+(saved==0L?"없음":
                ((System.currentTimeMillis()-saved)/1000L)+"초 전");
    }

    static List<Entry> load(Context ctx) {
        List<Entry> entries=new ArrayList<>();
        String json=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .getString(KEY,"[]");
        try {
            JSONArray arr=new JSONArray(json);
            for(int i=0;i<arr.length() && entries.size()<MAX_ITEMS;i++){
                JSONObject row=arr.optJSONObject(i);
                if(row==null)continue;
                String title=row.optString("title","");
                if(title.trim().isEmpty())continue;
                entries.add(new Entry(title,row.optString("subtitle",""),
                    row.optString("kind","")));
            }
        }catch(Exception ignored){}
        return entries;
    }
    static long timestamp(Context ctx){
        return ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).getLong(TIME,0);
    }
    static List<MediaBrowserCompat.MediaItem> items(Context ctx) {
        List<MediaBrowserCompat.MediaItem> items=new ArrayList<>();
        for(Entry entry:load(ctx)) {
            String key=entry.kind+"|"+entry.title;
            String mediaId=PolarisYtmCatalog.CAPTURED+Uri.encode(key);
            MediaDescriptionCompat d=new MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(entry.title)
                .setSubtitle(entry.description+" · 화면에서 읽음 / 재생 ID 미확인")
                .build();
            items.add(new MediaBrowserCompat.MediaItem(d,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        }
        return items;
    }
    /**
     * Different builds/locales use '•', '·' or full-width middle dots.
     * Text order is not a stable Android accessibility contract, therefore
     * keep the scan strictly read-only and do not invent media IDs.
     */
    static List<Entry> fromVisibleLabels(List<String> labels) {
        ArrayList<Entry> entries=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(int i=1;i<labels.size() && entries.size()<MAX_ITEMS;i++){
            String description=labels.get(i).replace('\u00a0',' ').trim();
            String lower=description.toLowerCase(Locale.US);
            String kind=kindFromDescription(lower);
            if(kind==null)continue;
            for(int delta=1;delta<=3 && delta<=i;delta++){
                String title=labels.get(i-delta).trim();
                if(!validTitle(title,description))continue;
                String key=kind+"|"+title;
                if(seen.add(key))
                    entries.add(new Entry(title,description,kind));
                break;
            }
        }
        return entries;
    }

    static int metadataCandidateCount(List<String> labels){
        int candidates=0;
        for(String label:labels)
            if(kindFromDescription(label.toLowerCase(Locale.US).trim())!=null)
                candidates++;
        return candidates;
    }

    private static String kindFromDescription(String s){
        if(s==null)return null;
        if(startsWithType(s,"재생목록") || startsWithType(s,"playlist") ||
            startsWithType(s,"자동 재생목록")) return "playlist";
        if(startsWithType(s,"앨범")||startsWithType(s,"album"))
            return "album";
        if(startsWithType(s,"아티스트")||startsWithType(s,"artist"))
            return "artist";
        if(startsWithType(s,"노래")||startsWithType(s,"song"))
            return "song";
        return null;
    }

    private static boolean startsWithType(String value,String type){
        if(!value.startsWith(type)||value.length()==type.length())return false;
        char next=value.charAt(type.length());
        return Character.isWhitespace(next)||next=='•'||next=='·'||
            next=='ㆍ'||next==':'||next=='|';
    }

    private static boolean validTitle(String title,String metadata){
        if(title.isEmpty() || title.length()>150 ||
            title.equals(metadata)||title.startsWith("android."))return false;
        String lower=title.toLowerCase(Locale.US);
        if(lower.equals("보관함")||lower.equals("재생목록")||
           lower.equals("플레이리스트")||lower.equals("playlist")||
           lower.equals("최근 활동")||lower.equals("최근 추가한 항목")||
           lower.equals("오프라인 저장")||lower.equals("검색"))return false;
        if(kindFromDescription(lower)!=null)return false;
        return !(title.contains("•")||title.contains("·"));
    }
}
