package app.hush.music.polaris;

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

        // US stations use the same droplet/text template as the Korean presets.
        if("kiis".equals(id)) return new Spec(0xFFD4007F,Color.WHITE,"102.7","KIIS FM","LOS ANGELES",66,102,44);
        if("gallery".equals(id)) return new Spec(0xFF151515,0xFFFF4B4B,"JAZZ FROM","GALLERY 41","SAN FRANCISCO",48,78,40);
        if("wild949".equals(id)) return new Spec(0xFF7A1FA2,Color.WHITE,"94.9","WILD","SAN FRANCISCO",72,112,42);
        if("alt987".equals(id)) return new Spec(0xFF202020,0xFF66E0FF,"98.7","ALT","LOS ANGELES",72,118,42);
        if("myfm1043".equals(id)) return new Spec(0xFFEA5B2A,Color.WHITE,"104.3","MYFM","LOS ANGELES",66,106,42);
        if("z100".equals(id)) return new Spec(0xFFE21F26,Color.WHITE,"100.3","Z100","NEW YORK",68,112,46);
        if("power1051".equals(id)) return new Spec(0xFF1B1B1B,0xFFFFC400,"105.1","POWER","NEW YORK",68,102,46);

        return new Spec(0xFF304050,Color.WHITE,"RADIO","POLARIS","LIVE",60,92,48);
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
