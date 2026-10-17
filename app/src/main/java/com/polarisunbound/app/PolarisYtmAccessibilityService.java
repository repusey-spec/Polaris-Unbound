package com.polarisunbound.app;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * User-enabled, read-only snapshot of VISIBLE official YT Music screen labels.
 *
 * Does not click, scroll, type, read passwords, or extract account cookies.
 * No screen automation when Android Auto is active. YTM must be in foreground.
 */
public final class PolarisYtmAccessibilityService extends AccessibilityService {
    private static final String YTM="com.google.android.apps.youtube.music";
    private static final String TAG="PolarisYtmAccessibility";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long lastScan=0L;
    private final Runnable scan=() -> snapshot();

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(event==null||event.getPackageName()==null||
            !YTM.contentEquals(event.getPackageName()))return;
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-lastScan<900L)return;
        lastScan=now;
        handler.removeCallbacks(scan);
        handler.postDelayed(scan,350L);
    }
    private void snapshot() {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null||root.getPackageName()==null||
            !YTM.contentEquals(root.getPackageName()))return;
        ArrayList<String> labels=new ArrayList<>();
        collect(root,labels,0);
        List<PolarisYtmSnapshot.Entry> entries=
            PolarisYtmSnapshot.fromVisibleLabels(labels);
        if(entries.isEmpty())return;
        PolarisYtmSnapshot.save(this,entries);
        Log.i(TAG,"captured "+entries.size()+" genuine visible entries");
        try {
            sendBroadcast(new android.content.Intent(
                "com.polarisunbound.app.YTM_SNAPSHOT_UPDATED")
                .setPackage(getPackageName()));
        }catch(Exception ignored){}
    }
    private static void collect(AccessibilityNodeInfo node,
            List<String> labels,int depth) {
        if(node==null||depth>24||labels.size()>=450)return;
        CharSequence content=node.getText();
        if(!TextUtils.isEmpty(content)) {
            String s=content.toString().trim();
            if(!s.isEmpty() && (labels.isEmpty()||
                !s.equals(labels.get(labels.size()-1))))labels.add(s);
        } else {
            CharSequence description=node.getContentDescription();
            if(!TextUtils.isEmpty(description)) {
                String s=description.toString().trim();
                if(!s.isEmpty() && !s.equals(labels.isEmpty()?"":labels.get(labels.size()-1)))
                    labels.add(s);
            }
        }
        for(int i=0;i<node.getChildCount() && labels.size()<450;i++)
            collect(node.getChild(i),labels,depth+1);
    }
    @Override public void onInterrupt() {
        handler.removeCallbacks(scan);
    }
    @Override public void onDestroy() {
        handler.removeCallbacks(scan);
        super.onDestroy();
    }
}
