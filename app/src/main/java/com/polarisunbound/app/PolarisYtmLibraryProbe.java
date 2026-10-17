package com.polarisunbound.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Consent-based, read-only YT Music browse capability diagnostic.
 * Never attempts private/permission-protected services or fabricates a library.
 * This only tests publicly advertised MediaBrowser services on the phone.
 */
final class PolarisYtmLibraryProbe {
    interface Listener { void onFinished(String report); }
    private static final String PACKAGE="com.google.android.apps.youtube.music";
    private static final String MEDIA_BROWSER="android.media.browse.MediaBrowserService";
    private static final int MAX_SERVICES=4;
    private static final int MAX_ITEMS=18;
    private final Context context;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Listener callback;
    private final StringBuilder report=new StringBuilder();
    private final List<ComponentName> candidates=new ArrayList<>();
    private MediaBrowserCompat browser;
    private int nextIndex=0;
    private int tested=0;
    private int accessible=0;
    private final List<String> pendingFolders=new ArrayList<>();
    private int nextFolder=0;
    private boolean finished=false;
    private int generation=0;
    private Runnable timeout;

    PolarisYtmLibraryProbe(Context context,Listener callback){
        this.context=context.getApplicationContext();
        this.callback=callback;
    }

    void start(){
        report.append("Polaris YTM library browse diagnostic 02\n");
        report.append("Package: ").append(PACKAGE).append("\n");
        report.append("MediaBrowser action: ").append(MEDIA_BROWSER).append("\n\n");
        try{
            PackageManager pm=context.getPackageManager();
            PackageInfo pkg=pm.getPackageInfo(PACKAGE,PackageManager.GET_SERVICES);
            report.append("YT Music installed: yes\n");
            if(pkg.services!=null){
                int shown=0;
                for(ServiceInfo info:pkg.services){
                    if(info==null)continue;
                    if(info.name.toLowerCase().contains("media")||
                        info.name.toLowerCase().contains("browser")||
                        info.name.toLowerCase().contains("car")){
                        if(shown<16){
                            report.append("Declared service: ")
                                .append(info.name).append(" / exported=")
                                .append(info.exported).append(" / permission=")
                                .append(info.permission==null?"none":info.permission)
                                .append("\n");
                        }
                        shown++;
                    }
                }
                if(shown>16)report.append("Additional candidates not shown: ")
                    .append(shown-16).append("\n");
            }
            Intent intent=new Intent(MEDIA_BROWSER).setPackage(PACKAGE);
            List<ResolveInfo> resolved=pm.queryIntentServices(intent,0);
            Set<String> seen=new HashSet<>();
            if(resolved!=null){
                for(ResolveInfo item:resolved){
                    if(item==null||item.serviceInfo==null)continue;
                    ServiceInfo info=item.serviceInfo;
                    if(!PACKAGE.equals(info.packageName))continue;
                    report.append("Advertised browser: ").append(info.name)
                        .append(" / exported=").append(info.exported)
                        .append(" / permission=")
                        .append(info.permission==null?"none":info.permission)
                        .append("\n");
                    if(!info.exported){
                        report.append("  skip: nonexported\n");
                    }else if(info.permission!=null){
                        report.append("  skip: requires permission (not bypassed)\n");
                    }else if(seen.add(info.name)){
                        candidates.add(new ComponentName(PACKAGE,info.name));
                    }
                }
            }
            report.append("Public unprotected browser candidates: ")
                .append(candidates.size()).append("\n");
        }catch(PackageManager.NameNotFoundException e){
            report.append("YT Music installed: NO (or hidden from package query)\n");
            finish();
            return;
        }catch(Exception e){
            report.append("Package inspection failed: ")
                .append(e.getClass().getSimpleName()).append(" ")
                .append(e.getMessage()).append("\n");
            finish();
            return;
        }
        if(candidates.isEmpty()){
            report.append("\nNo public MediaBrowser entry discovered.\n");
            report.append("This does not prove playlists are inaccessible by every method.\n");
            finish();
            return;
        }
        startNext();
    }

    private void startNext(){
        closeBrowser();
        pendingFolders.clear();
        nextFolder=0;
        if(nextIndex>=candidates.size()||tested>=MAX_SERVICES){
            report.append("\nSUMMARY: connected=").append(accessible)
                .append(" / probed=").append(tested).append("\n");
            finish();
            return;
        }
        final ComponentName component=candidates.get(nextIndex++);
        final int id=++generation;
        tested++;
        report.append("\nCONNECT ").append(component.flattenToShortString()).append("\n");
        try{
            browser=new MediaBrowserCompat(context,component,
                new MediaBrowserCompat.ConnectionCallback(){
                    @Override public void onConnected(){
                        if(finished||id!=generation)return;
                        accessible++;
                        String root;
                        try{root=browser.getRoot();}
                        catch(Exception e){
                            report.append("root error ").append(e).append("\n");
                            startNext();return;
                        }
                        report.append("CONNECTED root=").append(root).append("\n");
                        if(TextUtils.isEmpty(root)){startNext();return;}
                        try{
                            browser.subscribe(root,new MediaBrowserCompat.SubscriptionCallback(){
                                @Override public void onChildrenLoaded(String parent,
                                        List<MediaBrowserCompat.MediaItem> children){
                                    if(finished||id!=generation)return;
                                    report.append("ROOT children=")
                                        .append(children==null?"null":children.size()).append("\n");
                                    if(children!=null){
                                        int shown=0;
                                        for(MediaBrowserCompat.MediaItem child:children){
                                            if(shown++>=MAX_ITEMS)break;
                                            MediaDescriptionCompat d=child.getDescription();
                                            report.append("  ").append(child.isBrowsable()?"[FOLDER] ":"[PLAY] ")
                                                .append(clean(d.getTitle()))
                                                .append(" | id=").append(clean(d.getMediaId()))
                                                .append("\n");
                                            if(child.isBrowsable()&&d.getMediaId()!=null&&
                                                pendingFolders.size()<3)
                                                pendingFolders.add(d.getMediaId());
                                        }
                                        if(children.size()>MAX_ITEMS)
                                            report.append("  +").append(children.size()-MAX_ITEMS)
                                                .append(" more not printed\n");
                                    }
                                    probeNextFolder(id);
                                }
                                @Override public void onError(String parent){
                                    if(finished||id!=generation)return;
                                    report.append("ROOT LOAD ERROR: ").append(clean(parent))
                                        .append("\n");
                                    startNext();
                                }
                            });
                        }catch(Exception e){
                            report.append("subscribe exception: ")
                                .append(e.getClass().getSimpleName()).append("\n");
                            startNext();
                        }
                    }
                    @Override public void onConnectionFailed(){
                        if(finished||id!=generation)return;
                        report.append("CONNECTION FAILED (service refused or unsupported)\n");
                        startNext();
                    }
                    @Override public void onConnectionSuspended(){
                        if(finished||id!=generation)return;
                        report.append("CONNECTION SUSPENDED\n");
                        startNext();
                    }
                },null);
            browser.connect();
            timeout=()->{
                if(finished||id!=generation)return;
                report.append("TIMEOUT: browse incomplete after 12 seconds\n");
                startNext();
            };
            handler.postDelayed(timeout,12000L);
        }catch(Exception e){
            report.append("CONNECT EXCEPTION ").append(e.getClass().getSimpleName())
                .append(" ").append(e.getMessage()).append("\n");
            startNext();
        }
    }

    private void probeNextFolder(int id){
        if(finished||id!=generation)return;
        if(nextFolder>=pendingFolders.size()){
            startNext();
            return;
        }
        final String folderId=pendingFolders.get(nextFolder++);
        report.append("SUBSCRIBE child=").append(clean(folderId)).append("\n");
        try{
            browser.subscribe(folderId,new MediaBrowserCompat.SubscriptionCallback(){
                @Override public void onChildrenLoaded(String parent,
                        List<MediaBrowserCompat.MediaItem> children){
                    if(finished||id!=generation)return;
                    report.append("  child count=")
                        .append(children==null?"null":children.size()).append("\n");
                    if(children!=null){
                        int shown=0;
                        for(MediaBrowserCompat.MediaItem child:children){
                            if(shown++>=8)break;
                            MediaDescriptionCompat desc=child.getDescription();
                            report.append("    ")
                                .append(child.isBrowsable()?"[FOLDER] ":"[PLAY] ")
                                .append(clean(desc.getTitle()))
                                .append(" | id=").append(clean(desc.getMediaId())).append("\n");
                        }
                    }
                    probeNextFolder(id);
                }
                @Override public void onError(String parent){
                    if(finished||id!=generation)return;
                    report.append("  child LOAD ERROR\n");
                    probeNextFolder(id);
                }
            });
        }catch(Exception e){
            report.append("  subscribe exception ")
                .append(e.getClass().getSimpleName()).append("\n");
            probeNextFolder(id);
        }
    }

    private static String clean(CharSequence source){
        if(source==null)return "(none)";
        String value=source.toString().replace("\n"," ").replace("\r"," ");
        return value.length()>140?value.substring(0,140)+"…":value;
    }
    private void closeBrowser(){
        if(timeout!=null)handler.removeCallbacks(timeout);
        timeout=null;
        if(browser!=null){
            try{browser.disconnect();}catch(Exception ignored){}
        }
        browser=null;
    }
    private void finish(){
        if(finished)return;
        finished=true;
        closeBrowser();
        callback.onFinished(report.toString());
    }
    void cancel(){
        finished=true;
        closeBrowser();
    }
}
