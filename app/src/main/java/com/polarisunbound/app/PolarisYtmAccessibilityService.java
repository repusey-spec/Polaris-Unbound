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

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        // Enabling the service while YTM is already in front may not trigger
        // an immediate content-changed event. Schedule one safe initial scan.
        handler.postDelayed(scan,500L);
    }

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
        int[] nodes={0};
        collect(root,labels,0,nodes);
        List<PolarisYtmSnapshot.Entry> entries=
            PolarisYtmSnapshot.fromVisibleLabels(labels);
        int candidates=PolarisYtmSnapshot.metadataCandidateCount(labels);
        PolarisYtmSnapshot.markAttempt(this,
            entries.isEmpty()?"no matching visible title/metadata":
                "saved "+entries.size()+" visible entries",
            nodes[0],labels.size(),candidates);
        if(entries.isEmpty()) {
            Log.i(TAG,"scan had "+nodes[0]+" nodes, "+labels.size()+
                " strings, "+candidates+" candidates, 0 valid entries");
            return;
        }
        PolarisYtmSnapshot.save(this,entries);
        Log.i(TAG,"captured "+entries.size()+" genuine visible entries");
        try {
            sendBroadcast(new android.content.Intent(
                "com.polarisunbound.app.YTM_SNAPSHOT_UPDATED")
                .setPackage(getPackageName()));
        }catch(Exception ignored){}
    }
    private static void collect(AccessibilityNodeInfo node,
            List<String> labels,int depth,int[] nodes) {
        if(node==null||depth>24||labels.size()>=450)return;
        nodes[0]++;
        addLabel(labels,node.getText());
        // Some YTM versions expose the playlist type and number of songs only
        // through contentDescription, alongside a separate text title.
        CharSequence description=node.getContentDescription();
        if(description!=null &&
           (node.getText()==null || !description.toString().contentEquals(node.getText())))
            addLabel(labels,description);
        for(int i=0;i<node.getChildCount() && labels.size()<450;i++)
            collect(node.getChild(i),labels,depth+1,nodes);
    }

    private static void addLabel(List<String> labels,CharSequence text){
        if(TextUtils.isEmpty(text))return;
        String value=text.toString().trim();
        if(value.isEmpty()||value.length()>300)return;
        if(labels.isEmpty()||!value.equals(labels.get(labels.size()-1)))
            labels.add(value);
    }

    @Override public void onInterrupt() {
        handler.removeCallbacks(scan);
    }
    @Override public void onDestroy() {
        handler.removeCallbacks(scan);
        super.onDestroy();
    }
}
