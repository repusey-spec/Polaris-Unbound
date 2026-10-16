package com.polarisunbound.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v0.57 NAS downloads: permanent user-visible music, not Android cache.
 *
 * The default Android 10+ destination is Music/Polaris NAS, in MediaStore.
 * An optional persisted ACTION_OPEN_DOCUMENT_TREE permission overrides the
 * destination for subsequent downloads. A per-source-path manifest maps each
 * completed file to its exact URI, so offline playback never calls the FTP
 * server just to check file sizes. Interrupted writes are never registered.
 */
public final class NasMusicStore {
    private static final String PREF="polaris_nas_music_v1";
    private static final String ITEM="saved:";
    private static final String TREE="selected_tree_uri";
    private static final String MOBILE="allow_metered_download";
    private static final String DEFAULT_RELATIVE="Music/Polaris NAS/";
    private NasMusicStore(){}

    public static final class Song {
        public final String relativePath;
        public final Uri uri;
        Song(String relativePath,Uri uri){
            this.relativePath=relativePath;
            this.uri=uri;
        }
    }

    private static SharedPreferences prefs(Context c){
        return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }

    private static String key(String relativePath){
        return ITEM+Base64.encodeToString(
            relativePath.getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP);
    }

    private static String pathFromKey(String key){
        if(key==null||!key.startsWith(ITEM)) return null;
        try {
            return new String(Base64.decode(key.substring(ITEM.length()),
                Base64.URL_SAFE|Base64.NO_WRAP),StandardCharsets.UTF_8);
        }catch(Exception e){return null;}
    }

    private static String clean(String relativePath){
        if(relativePath==null) throw new IllegalArgumentException("음악 경로가 없습니다");
        String path=relativePath.replace('\\','/').trim();
        while(path.startsWith("/")) path=path.substring(1);
        if(path.isEmpty()) throw new IllegalArgumentException("음악 경로가 없습니다");
        for(String seg:path.split("/"))
            if(seg.isEmpty()||seg.equals(".")||seg.equals(".."))
                throw new IllegalArgumentException("올바르지 않은 음악 경로입니다");
        return path;
    }

    public static boolean hasSaved(Context c,String relativePath){
        if(relativePath==null) return false;
        return prefs(c).contains(key(relativePath));
    }

    public static Uri savedUri(Context c,String relativePath){
        if(relativePath==null) return null;
        String entry=prefs(c).getString(key(relativePath),null);
        if(entry==null) return null;
        try{
            Uri uri=Uri.parse(entry);
            try(android.os.ParcelFileDescriptor fd=c.getContentResolver().openFileDescriptor(uri,"r")){
                if(fd!=null && fd.getStatSize()!=0L) return uri;
            }
        }catch(Exception ignored){}
        // The file was deleted manually or its SAF permission was revoked.
        prefs(c).edit().remove(key(relativePath)).apply();
        return null;
    }

    public static List<Song> savedSongs(Context c){
        List<Song> songs=new ArrayList<>();
        for(Map.Entry<String,?> entry:prefs(c).getAll().entrySet()){
            String rel=pathFromKey(entry.getKey());
            Object value=entry.getValue();
            if(rel==null||!(value instanceof String)) continue;
            try{ songs.add(new Song(rel,Uri.parse((String)value))); }
            catch(Exception ignored){}
        }
        Collections.sort(songs,(a,b)->
            a.relativePath.compareToIgnoreCase(b.relativePath));
        return songs;
    }

    public static List<String> savedInDirectory(Context c,String directory){
        String normalized=directory==null?"":directory;
        List<String> result=new ArrayList<>();
        for(Song song:savedSongs(c)){
            String path=song.relativePath;
            int slash=path.lastIndexOf('/');
            String parent=slash<0?"":path.substring(0,slash);
            if(parent.equals(normalized)) result.add(path);
        }
        return result;
    }

    public static boolean allowMetered(Context c){
        return prefs(c).getBoolean(MOBILE,false);
    }
    public static void setAllowMetered(Context c,boolean value){
        prefs(c).edit().putBoolean(MOBILE,value).apply();
    }

    /** Unmetered networks such as Wi-Fi are allowed by default. */
    public static boolean mayDownload(Context c){
        if(allowMetered(c)) return true;
        ConnectivityManager manager=(ConnectivityManager)
            c.getSystemService(Context.CONNECTIVITY_SERVICE);
        return manager!=null && !manager.isActiveNetworkMetered();
    }

    public static Uri selectedTree(Context c){
        String uri=prefs(c).getString(TREE,null);
        return uri==null||uri.isEmpty()?null:Uri.parse(uri);
    }

    public static void setSelectedTree(Context c,Uri uri){
        if(uri==null) prefs(c).edit().remove(TREE).apply();
        else prefs(c).edit().putString(TREE,uri.toString()).apply();
    }

    public static String locationName(Context c){
        Uri tree=selectedTree(c);
        if(tree==null) return DEFAULT_RELATIVE;
        try{
            DocumentFile root=DocumentFile.fromTreeUri(c,tree);
            if(root!=null && root.canWrite()) return root.getName()+" (선택한 폴더)";
        }catch(Exception ignored){}
        return "선택한 폴더 접근 권한 확인 필요";
    }

    private static String mime(String name){
        String lower=name.toLowerCase(Locale.US);
        if(lower.endsWith(".mp3")) return "audio/mpeg";
        if(lower.endsWith(".m4a")) return "audio/mp4";
        if(lower.endsWith(".flac")) return "audio/flac";
        if(lower.endsWith(".wav")) return "audio/wav";
        if(lower.endsWith(".aac")) return "audio/aac";
        if(lower.endsWith(".ogg")) return "audio/ogg";
        if(lower.endsWith(".opus")) return "audio/opus";
        if(lower.endsWith(".wma")) return "audio/x-ms-wma";
        return "application/octet-stream";
    }

    private static void transfer(File source,OutputStream out) throws Exception{
        long copied=0L;
        try(InputStream in=new FileInputStream(source)){
            byte[] buf=new byte[65536];
            for(int n;(n=in.read(buf))>=0;){
                if(n==0) continue;
                out.write(buf,0,n);
                copied+=n;
            }
            out.flush();
        }
        if(copied!=source.length() || copied==0L)
            throw new java.io.IOException("NAS 저장 크기 불일치");
    }

    /** The already-downloaded source is copied to the permanent destination. */
    public static Uri store(Context c,String relativePath,File cached) throws Exception{
        String rel=clean(relativePath);
        Uri old=savedUri(c,rel);
        if(old!=null) return old;
        if(cached==null||!cached.isFile()||cached.length()<=0)
            throw new java.io.IOException("다운로드 원본 파일이 없습니다");
        int slash=rel.lastIndexOf('/');
        String name=slash<0?rel:rel.substring(slash+1);
        String directory=slash<0?"":rel.substring(0,slash);
        ContentResolver resolver=c.getContentResolver();
        Uri result=null;
        boolean committed=false;

        try{
            Uri tree=selectedTree(c);
            if(tree!=null){
                DocumentFile root=DocumentFile.fromTreeUri(c,tree);
                if(root==null||!root.canWrite())
                    throw new java.io.IOException("선택한 NAS 저장 폴더에 쓸 수 없습니다");
                if(!directory.isEmpty()){
                    for(String segment:directory.split("/")){
                        DocumentFile child=root.findFile(segment);
                        if(child==null) child=root.createDirectory(segment);
                        if(child==null||!child.isDirectory())
                            throw new java.io.IOException("NAS 폴더 생성 실패: "+segment);
                        root=child;
                    }
                }
                // Never overwrite a user-managed file with the same name.
                if(root.findFile(name)!=null)
                    throw new java.io.IOException("저장 위치에 동명 파일이 있습니다: "+name);
                DocumentFile doc=root.createFile(mime(name),name);
                if(doc==null) throw new java.io.IOException("NAS 저장 파일 생성 실패");
                result=doc.getUri();
                try(OutputStream out=resolver.openOutputStream(result,"w")){
                    if(out==null) throw new java.io.IOException("NAS 폴더 쓰기 오류");
                    transfer(cached,out);
                }
            }else if(Build.VERSION.SDK_INT>=29){
                ContentValues values=new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
                values.put(MediaStore.MediaColumns.MIME_TYPE,mime(name));
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    DEFAULT_RELATIVE+(directory.isEmpty()?"":directory+"/"));
                values.put(MediaStore.MediaColumns.IS_PENDING,1);
                result=resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values);
                if(result==null) throw new java.io.IOException("NAS Music 저장 항목 생성 실패");
                try(OutputStream out=resolver.openOutputStream(result,"w")){
                    if(out==null) throw new java.io.IOException("NAS 저장 파일 접근 오류");
                    transfer(cached,out);
                }
                ContentValues done=new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING,0);
                if(resolver.update(result,done,null,null)<1)
                    throw new java.io.IOException("NAS 저장 파일 완료 처리 실패");
            }else{
                File folder=c.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
                if(folder==null) folder=c.getFilesDir();
                File destDir=new File(folder,"Polaris NAS"+(directory.isEmpty()?"":"/"+directory));
                if(!destDir.isDirectory()&&!destDir.mkdirs())
                    throw new java.io.IOException("NAS 저장 폴더를 만들지 못했습니다");
                File target=new File(destDir,name);
                if(target.exists())
                    throw new java.io.IOException("저장 위치에 동명 파일이 있습니다: "+name);
                File pending=new File(destDir,name+".part");
                try(OutputStream out=new FileOutputStream(pending)){transfer(cached,out);}
                if(!pending.renameTo(target)){
                    pending.delete();
                    throw new java.io.IOException("NAS 저장 완료 처리 실패");
                }
                result=Uri.fromFile(target);
            }
            prefs(c).edit().putString(key(rel),result.toString()).commit();
            committed=true;
            return result;
        }finally{
            if(!committed&&result!=null){
                try{deleteUri(c,result);}catch(Exception ignored){}
            }
        }
    }

    private static void deleteUri(Context c,Uri uri)throws Exception{
        if("file".equals(uri.getScheme())){
            File file=new File(uri.getPath());
            if(file.exists()&&!file.delete()) throw new java.io.IOException("파일 삭제 실패");
        }else{
            if(android.provider.DocumentsContract.isDocumentUri(c,uri)){
                android.provider.DocumentsContract.deleteDocument(c.getContentResolver(),uri);
            }else c.getContentResolver().delete(uri,null,null);
        }
    }

    public static boolean deleteSaved(Context c,String relativePath){
        String entry=prefs(c).getString(key(relativePath),null);
        if(entry==null) return true;
        try{
            deleteUri(c,Uri.parse(entry));
            prefs(c).edit().remove(key(relativePath)).apply();
            return true;
        }catch(Exception e){return false;}
    }
}
