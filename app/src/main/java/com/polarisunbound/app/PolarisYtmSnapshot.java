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
    static List<Entry> fromVisibleLabels(List<String> labels) {
        ArrayList<Entry> entries=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        // uiautomator on YTM showed the real sequence:
        // "jazz" -> "재생목록 • repusy • 트랙 16개".
        for(int i=1;i<labels.size() && entries.size()<MAX_ITEMS;i++){
            String subtitle=labels.get(i);
            String lower=subtitle.toLowerCase(Locale.US);
            String kind=null;
            if(lower.startsWith("재생목록 •")||lower.startsWith("playlist •"))
                kind="playlist";
            else if(lower.startsWith("앨범 •")||lower.startsWith("album •"))
                kind="album";
            else if(lower.startsWith("아티스트 •")||lower.startsWith("artist •"))
                kind="artist";
            else if(lower.startsWith("노래 •")||lower.startsWith("song •"))
                kind="song";
            if(kind==null)continue;
            String title=labels.get(i-1).trim();
            if(title.isEmpty() || title.length()>150 || title.contains("•") ||
                title.equals(subtitle) || title.startsWith("android."))continue;
            String key=kind+"|"+title;
            if(seen.add(key))
                entries.add(new Entry(title,subtitle,kind));
        }
        return entries;
    }
}
