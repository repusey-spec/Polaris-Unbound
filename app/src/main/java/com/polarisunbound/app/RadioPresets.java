package com.polarisunbound.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared physical 4x4 radio layout for the phone and compact playable order
 * for Android Auto. 13 unique stations and three movable empty slots.
 *
 * On first load v0.55's 13 logical slots are migrated, without losing existing
 * swaps, to physical grid positions:
 *   KR:  0  1  2  3 / 4  5  -  -
 *   US:  8  9 10 11 / 12 13 14 -
 */
public final class RadioPresets {
    public static final int SLOT_COUNT=16;
    private static final int EMPTY=-1;
    private static final String STORE="radio_presets";
    private static final String NEW_SLOT_KEY="layout_slot";
    private static final String SCHEMA_KEY="layout_schema";
    private static final int[] MIGRATION_POSITION={
        0,1,2,3,4,5,8,9,10,11,12,13,14
    };
    public static final String[] IDS={
        "kr1","kr2","kr3","kr4","kr5","kr6",
        "gallery","wild949","z100","power1051",
        "kiis","alt987","myfm1043"
    };
    public static final String[] NAMES={
        "89.1 KBS CoolFM","91.9 MBC FM4U","93.9 CBS MusicFM",
        "95.9 MBC 표준FM","102.7 AFN EagleFM","107.7 SBS PowerFM",
        "Jazz from Gallery 41","Wild 94.9","Z100 New York",
        "Power 105.1","102.7 KIIS-FM","ALT 98.7","104.3 MYfm"
    };

    private RadioPresets(){}

    private static SharedPreferences preferences(Context c){
        return c.getSharedPreferences(STORE,Context.MODE_PRIVATE);
    }

    private static int indexOf(String id){
        if(id==null) return EMPTY;
        for(int i=0;i<IDS.length;i++) if(IDS[i].equals(id)) return i;
        return EMPTY;
    }

    private static synchronized void ensureLayout(Context c){
        SharedPreferences p=preferences(c);
        if(p.getInt(SCHEMA_KEY,0)==1) return;

        // The original v0.55 layout stored 13 indices in keys slot0..slot12.
        // Read the original order first, then position its items into 16 cells.
        boolean[] used=new boolean[IDS.length];
        int[] old=new int[IDS.length];
        for(int slot=0;slot<IDS.length;slot++){
            int idx=p.getInt("slot"+slot,slot);
            if(idx<0||idx>=IDS.length||used[idx]){
                idx=EMPTY;
                if(!used[slot]) idx=slot;
                else for(int n=0;n<IDS.length;n++)
                    if(!used[n]){ idx=n; break; }
            }
            old[slot]=idx;
            used[idx]=true;
        }
        int[] layout=new int[SLOT_COUNT];
        Arrays.fill(layout,EMPTY);
        for(int oldSlot=0;oldSlot<old.length;oldSlot++)
            layout[MIGRATION_POSITION[oldSlot]]=old[oldSlot];

        SharedPreferences.Editor e=p.edit();
        for(int slot=0;slot<SLOT_COUNT;slot++)
            e.putInt(NEW_SLOT_KEY+slot,layout[slot]);
        e.putInt(SCHEMA_KEY,1);
        e.apply();
    }

    public static String[] layoutIds(Context c){
        ensureLayout(c);
        SharedPreferences p=preferences(c);
        String[] ids=new String[SLOT_COUNT];
        boolean[] used=new boolean[IDS.length];
        for(int slot=0;slot<SLOT_COUNT;slot++){
            int idx=p.getInt(NEW_SLOT_KEY+slot,EMPTY);
            if(idx>=0&&idx<IDS.length&&!used[idx]){
                ids[slot]=IDS[idx];
                used[idx]=true;
            }
        }
        // A corrupt or interrupted save must not lose radio stations.
        for(int station=0;station<IDS.length;station++){
            if(used[station]) continue;
            for(int slot=0;slot<SLOT_COUNT;slot++){
                if(ids[slot]==null){
                    ids[slot]=IDS[station];
                    break;
                }
            }
        }
        return ids;
    }

    /** AA lists only playable items, retaining the order of physical cells. */
    public static String[] orderedIds(Context c){
        String[] grid=layoutIds(c);
        List<String> ordered=new ArrayList<>(IDS.length);
        for(String id:grid) if(id!=null) ordered.add(id);
        return ordered.toArray(new String[0]);
    }

    public static String stationName(String id){
        int idx=indexOf(id);
        return idx==EMPTY?(id==null?"":id):NAMES[idx];
    }

    public static String region(String id){
        if(id==null) return "";
        if(id.startsWith("kr")) return "국내";
        if("gallery".equals(id)||"wild949".equals(id)) return "SF";
        if("z100".equals(id)||"power1051".equals(id)) return "NY";
        if("kiis".equals(id)||"alt987".equals(id)||"myfm1043".equals(id)) return "LA";
        return "";
    }

    /**
     * Choosing a station in a blank cell moves it there and leaves its previous
     * slot blank. Choosing in an occupied cell swaps the two stations instead.
     */
    public static void swap(Context c,int slot,int stationIndex){
        if(slot<0||slot>=SLOT_COUNT||stationIndex<0||stationIndex>=IDS.length) return;
        String[] grid=layoutIds(c);
        String chosen=IDS[stationIndex];
        int oldSlot=EMPTY;
        for(int i=0;i<grid.length;i++)
            if(chosen.equals(grid[i])){oldSlot=i;break;}
        if(oldSlot==slot) return;
        String replaced=grid[slot];
        grid[slot]=chosen;
        if(oldSlot>=0) grid[oldSlot]=replaced;

        SharedPreferences.Editor e=preferences(c).edit();
        for(int i=0;i<grid.length;i++)
            e.putInt(NEW_SLOT_KEY+i,indexOf(grid[i]));
        e.apply();
    }
}
