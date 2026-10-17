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
        // The original implementation replaced the entire cache on each
        // accessibility event. Scrolling the real library therefore discarded
        // all entries that were no longer visible. Accumulate verified names.
        java.util.LinkedHashMap<String,Entry> merged=new java.util.LinkedHashMap<>();
        for(Entry e:entries){
            if(e==null||e.title==null||e.title.trim().isEmpty())continue;
            merged.put(e.kind+"|"+e.title,e);
        }
        for(Entry e:load(ctx)){
            if(e!=null && e.title!=null){
                String key=e.kind+"|"+e.title;
                if(!merged.containsKey(key))merged.put(key,e);
            }
        }
        JSONArray arr=new JSONArray();
        for(Entry e:merged.values()){
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
            ", 유형설명 후보="+prefs.getInt(LAST_MATCHES,0)+
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
                // Old Test05 snapshots can contain UI controls. Never retain
                // them just because they were cached before the parser fix.
                if(!PolarisYtmLabelParser.validCapturedTitle(title))continue;
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
        Set<String> displayed=new HashSet<>();
        for(Entry entry:load(ctx)) {
            String key=entry.kind+"|"+entry.title;
            String mediaId=PolarisYtmCatalog.CAPTURED+Uri.encode(key);
            String boundId="playlist".equals(entry.kind)?
                PolarisYtmPlaylistBindings.get(ctx,entry.title):null;
            String subtitle=boundId!=null?
                entry.description+" · 공식 YT Music 공유 링크 연결됨":
                entry.description+" · 화면에서 읽음 / 재생 ID 미확인";
            MediaDescriptionCompat d=new MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(entry.title)
                .setSubtitle(subtitle)
                .build();
            items.add(new MediaBrowserCompat.MediaItem(d,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            if("playlist".equals(entry.kind))displayed.add(entry.title);
        }
        // User-provided name, backed by a genuine shared playlist link, is
        // legitimate even when that item was not visible to Accessibility.
        // Identify it honestly as a manual share import rather than "captured".
        for(java.util.Map.Entry<String,String> bound:
                PolarisYtmPlaylistBindings.all(ctx).entrySet()){
            if(displayed.contains(bound.getKey()))continue;
            String id=PolarisYtmCatalog.CAPTURED+
                Uri.encode("playlist|"+bound.getKey());
            MediaDescriptionCompat d=new MediaDescriptionCompat.Builder()
                .setMediaId(id).setTitle(bound.getKey())
                .setSubtitle("사용자가 공유 링크로 등록한 재생목록 · 원본에서 열기 가능")
                .build();
            items.add(new MediaBrowserCompat.MediaItem(d,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
        }
        return items;
    }
    static List<Entry> fromVisibleLabels(List<String> labels) {
        List<Entry> items=new ArrayList<>();
        for(PolarisYtmLabelParser.Entry match:PolarisYtmLabelParser.parse(labels)){
            items.add(new Entry(match.title,match.description,match.kind));
        }
        return items;
    }

    static int metadataCandidateCount(List<String> labels) {
        return PolarisYtmLabelParser.metadataCount(labels);
    }

}
