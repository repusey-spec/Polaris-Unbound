package com.polarisunbound.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * User-confirmed binding between a displayed title and a REAL YTM playlist
 * share ID. This is not extracted from cookies, names, or a guessed URL.
 * Existing bindings remain opt-in and persist separately in test-app storage.
 */
final class PolarisYtmPlaylistBindings {
    private static final String PREF="ytm_user_shared_playlist_links";
    private static final String KEY="links";
    private static final int LIMIT=80;

    private PolarisYtmPlaylistBindings(){}

    static boolean bind(Context ctx,String title,String playlistId){
        if(ctx==null||!validTitle(title)||
            !PolarisYtmSharedPlaylist.validId(playlistId))return false;
        LinkedHashMap<String,String> all=all(ctx);
        if(!all.containsKey(title) && all.size()>=LIMIT)return false;
        all.put(title,playlistId);
        save(ctx,all);
        return true;
    }

    static String get(Context ctx,String title){
        return title==null?null:all(ctx).get(title);
    }

    static void remove(Context ctx,String title){
        if(title==null)return;
        LinkedHashMap<String,String> all=all(ctx);
        if(all.remove(title)!=null)save(ctx,all);
    }

    static LinkedHashMap<String,String> all(Context ctx){
        LinkedHashMap<String,String> map=new LinkedHashMap<>();
        if(ctx==null)return map;
        SharedPreferences p=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        String json=p.getString(KEY,"{}");
        try{
            JSONObject object=new JSONObject(json);
            Iterator<String> names=object.keys();
            while(names.hasNext() && map.size()<LIMIT){
                String title=names.next();
                String id=object.optString(title,"");
                if(validTitle(title)&&PolarisYtmSharedPlaylist.validId(id))
                    map.put(title,id);
            }
        }catch(Exception ignored){}
        return map;
    }

    private static void save(Context ctx,LinkedHashMap<String,String> map){
        JSONObject json=new JSONObject();
        for(Map.Entry<String,String> entry:map.entrySet()){
            try{json.put(entry.getKey(),entry.getValue());}
            catch(Exception ignored){}
        }
        ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .edit().putString(KEY,json.toString()).apply();
    }

    private static boolean validTitle(String title){
        return title!=null&&!title.trim().isEmpty()&&title.length()<=150;
    }
}
