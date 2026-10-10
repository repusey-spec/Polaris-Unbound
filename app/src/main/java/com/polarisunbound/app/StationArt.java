package com.polarisunbound.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.HashMap;
import java.util.Map;

public final class StationArt {
    private static final Map<String,Bitmap> CACHE=new HashMap<>();
    private StationArt(){}

    public static Bitmap bitmap(Context context,String id){ return bitmap(context,id,512); }

    public static Bitmap bitmap(Context context,String id,int size){
        String key=String.valueOf(id)+"@"+size;
        synchronized(CACHE){
            Bitmap cached=CACHE.get(key);
            if(cached!=null && !cached.isRecycled()) return cached;
        }

        Bitmap out=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(out);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Spec s=spec(id);

        p.setColor(s.bg);
        c.drawRoundRect(new RectF(0,0,512,512),42f,42f,p);

        p.setColor(0x22FFFFFF);
        c.drawCircle(430,82,145,p);
        c.drawCircle(70,470,125,p);

        p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));
        p.setColor(s.fg);
        p.setTextAlign(Paint.Align.CENTER);

        p.setTextSize(s.topSize);
        c.drawText(s.top,256,150,p);

        p.setTextSize(s.midSize);
        c.drawText(s.mid,256,285,p);

        p.setTextSize(s.bottomSize);
        c.drawText(s.bottom,256,390,p);

        p.setStrokeWidth(4f);
        p.setColor(0x55FFFFFF);
        c.drawLine(100,430,412,430,p);

        Bitmap result=out;
        if(size!=512){
            result=Bitmap.createScaledBitmap(out,size,size,true);
            out.recycle();
        }

        synchronized(CACHE){
            Bitmap existing=CACHE.get(key);
            if(existing!=null && !existing.isRecycled()){
                if(result!=existing && !result.isRecycled()) result.recycle();
                return existing;
            }
            CACHE.put(key,result);
        }
        return result;
    }

    private static Spec spec(String id){
        if("kr1".equals(id)) return new Spec(0xFF169BD5,Color.WHITE,"89.1","KBS","COOL FM",72,118,58);
        if("kr2".equals(id)) return new Spec(0xFFFF7A00,Color.WHITE,"91.9","MBC","FM4U",72,112,68);
        if("kr3".equals(id)) return new Spec(0xFF0054A6,Color.WHITE,"93.9","CBS","MUSIC FM",72,118,54);
        if("kr4".equals(id)) return new Spec(0xFF1476D4,Color.WHITE,"95.9","MBC","STANDARD FM",72,112,46);
        if("kr5".equals(id)) return new Spec(0xFF173B63,Color.WHITE,"102.7","AFN","THE EAGLE",68,112,50);
        if("kr6".equals(id)) return new Spec(0xFF123A7A,0xFF4DE7F0,"107.7","SBS","POWER FM",68,112,56);
        if("kiis".equals(id)) return new Spec(0xFFF7F7F7,0xFFD4007F,"102.7","KIIS FM","LOS ANGELES",66,102,44);
        return new Spec(0xFF050505,0xFFB41414,"JAZZ FROM","GALLERY 41","SAN FRANCISCO",48,78,40);
    }

    private static final class Spec{
        final int bg,fg;
        final String top,mid,bottom;
        final float topSize,midSize,bottomSize;
        Spec(int bg,int fg,String top,String mid,String bottom,float topSize,float midSize,float bottomSize){
            this.bg=bg; this.fg=fg; this.top=top; this.mid=mid; this.bottom=bottom;
            this.topSize=topSize; this.midSize=midSize; this.bottomSize=bottomSize;
        }
    }
}
