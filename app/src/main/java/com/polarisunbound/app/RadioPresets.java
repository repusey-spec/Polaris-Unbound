package com.polarisunbound.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * One radio preset order shared by the phone UI and Android Auto browse tree.
 * Existing v0.54 slot0..slot5 values are 0..5 (Korean station indices), so
 * keeping these indices migrates domestic presets without resetting them.
 */
public final class RadioPresets {
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
        return c.getSharedPreferences("radio_presets",Context.MODE_PRIVATE);
    }

    public static String[] orderedIds(Context c){
        SharedPreferences p=preferences(c);
        boolean[] used=new boolean[IDS.length];
        int[] order=new int[IDS.length];
        for(int slot=0;slot<IDS.length;slot++){
            int idx=p.getInt("slot"+slot,slot);
            if(idx<0||idx>=IDS.length||used[idx]){
                idx=-1;
                if(!used[slot]) idx=slot;
                else for(int n=0;n<IDS.length;n++) if(!used[n]){idx=n;break;}
            }
            order[slot]=idx;
            used[idx]=true;
        }
        String[] result=new String[IDS.length];
        for(int i=0;i<IDS.length;i++) result[i]=IDS[order[i]];
        return result;
    }

    public static String stationName(String id){
        for(int i=0;i<IDS.length;i++) if(IDS[i].equals(id)) return NAMES[i];
        return id==null?"":id;
    }

    public static String region(String id){
        if(id!=null && id.startsWith("kr")) return "국내";
        if("gallery".equals(id)||"wild949".equals(id)) return "SF";
        if("z100".equals(id)||"power1051".equals(id)) return "NY";
        return "LA";
    }

    public static void swap(Context c,int slot,int stationIndex){
        if(slot<0||slot>=IDS.length||stationIndex<0||stationIndex>=IDS.length) return;
        String[] ids=orderedIds(c);
        String replacing=ids[slot];
        String chosen=IDS[stationIndex];
        int other=-1;
        for(int i=0;i<ids.length;i++) if(chosen.equals(ids[i])){other=i;break;}
        ids[slot]=chosen;
        if(other>=0 && other!=slot) ids[other]=replacing;
        SharedPreferences.Editor edit=preferences(c).edit();
        for(int i=0;i<ids.length;i++){
            int idx=0;
            for(int j=0;j<IDS.length;j++) if(IDS[j].equals(ids[i])){idx=j;break;}
            edit.putInt("slot"+i,idx);
        }
        edit.apply();
    }
}
