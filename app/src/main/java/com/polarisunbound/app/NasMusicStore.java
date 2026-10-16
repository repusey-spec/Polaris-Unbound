package com.polarisunbound.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

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
 * v0.58 NAS downloads.
 *
 * No user-selected folder, no new Polaris folder and no Wi-Fi-only restriction.
 * On Android 10+, save directly into the already-existing public Music folder;
 * if Music does not exist, use the public Download folder through MediaStore.
 * These MediaStore inserts don't require a storage-folder picker or broad
 * write-storage permission. The manifest preserves completed v0.57 URIs too.
 */
public final class NasMusicStore {
    private static final String PREF="polaris_nas_music_v1";
    private static final String ITEM="saved:";
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
            relativePath.getBytes(StandardCharsets.UTF_8),
            Base64.URL_SAFE|Base64.NO_WRAP);
    }

    private static String pathFromKey(String key){
        if(key==null||!key.startsWith(ITEM)) return null;
        try{
            return new String(Base64.decode(key.substring(ITEM.length()),
                Base64.URL_SAFE|Base64.NO_WRAP),StandardCharsets.UTF_8);
        }catch(Exception ignored){return null;}
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

    public static Uri savedUri(Context c,String relativePath){
        if(relativePath==null) return null;
        String stored=prefs(c).getString(key(relativePath),null);
        if(stored==null) return null;
        try{
            Uri uri=Uri.parse(stored);
            try(android.os.ParcelFileDescriptor fd=
                    c.getContentResolver().openFileDescriptor(uri,"r")){
                if(fd!=null && fd.getStatSize()!=0L) return uri;
            }
        }catch(Exception ignored){}
        // Do not delete the file; only clear a stale manifest entry.
        prefs(c).edit().remove(key(relativePath)).apply();
        return null;
    }

    public static List<Song> savedSongs(Context c){
        List<Song> songs=new ArrayList<>();
        for(Map.Entry<String,?> entry:prefs(c).getAll().entrySet()){
            String rel=pathFromKey(entry.getKey());
            Object value=entry.getValue();
            if(rel==null||!(value instanceof String)) continue;
            try{songs.add(new Song(rel,Uri.parse((String)value)));}
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
            int slash=song.relativePath.lastIndexOf('/');
            String parent=slash<0?"":song.relativePath.substring(0,slash);
            if(parent.equals(normalized)) result.add(song.relativePath);
        }
        return result;
    }

    private static boolean musicFolderExists(){
        try{
            File music=Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC);
            return music!=null && music.isDirectory();
        }catch(Exception ignored){
            return false;
        }
    }

    public static String locationName(Context c){
        if(Build.VERSION.SDK_INT<29) return "앱 저장소 / Music (Android 9 이하)";
        return musicFolderExists()?"Music (공용)":"Download (공용)";
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

    private static void copy(File source,OutputStream out) throws Exception{
        long copied=0;
        try(InputStream in=new FileInputStream(source)){
            byte[] buffer=new byte[65536];
            int count;
            while((count=in.read(buffer))!=-1){
                if(count>0){out.write(buffer,0,count);copied+=count;}
            }
            out.flush();
        }
        if(copied<=0 || copied!=source.length())
            throw new java.io.IOException("저장된 파일 크기가 원본과 다릅니다");
    }

    /**
     * FTP's downloaded/cached File is copied into a user-visible destination.
     * Existing tracked songs keep their old location (including v0.57 SAF).
     * Android MediaStore resolves same-name collisions without overwriting.
     */
    public static Uri store(Context c,String relativePath,File cached)throws Exception{
        String rel=clean(relativePath);
        Uri existing=savedUri(c,rel);
        if(existing!=null) return existing;
        if(cached==null||!cached.isFile()||cached.length()<=0)
            throw new java.io.IOException("다운로드된 파일이 없습니다");

        String fileName=rel.substring(rel.lastIndexOf('/')+1);
        Uri result=null;
        boolean committed=false;
        ContentResolver resolver=c.getContentResolver();

        try{
            if(Build.VERSION.SDK_INT>=29){
                boolean music=musicFolderExists();
                ContentValues values=new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME,fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE,mime(fileName));
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    music?Environment.DIRECTORY_MUSIC+"/":Environment.DIRECTORY_DOWNLOADS+"/");
                values.put(MediaStore.MediaColumns.IS_PENDING,1);

                // Download is a different MediaStore collection than Audio:
                // Audio.Media does not permit relative path Download/ on all OSes.
                Uri collection=music ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                result=resolver.insert(collection,values);
                if(result==null) throw new java.io.IOException("공용 음악 저장소 접근 실패");
                try(OutputStream out=resolver.openOutputStream(result,"w")){
                    if(out==null) throw new java.io.IOException("공용 파일 쓰기 실패");
                    copy(cached,out);
                }
                ContentValues done=new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING,0);
                if(resolver.update(result,done,null,null)<1)
                    throw new java.io.IOException("공용 음악 파일 완료 처리 실패");
            }else{
                // Android 9 and older: preserve permission-free legacy fallback.
                File directory=c.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
                if(directory==null) directory=c.getFilesDir();
                if(!directory.isDirectory()&&!directory.mkdirs())
                    throw new java.io.IOException("앱 음악 폴더를 사용할 수 없습니다");
                File target=new File(directory,fileName);
                if(target.exists())
                    throw new java.io.IOException("동일한 이름의 저장 파일이 있습니다");
                File partial=new File(directory,fileName+".part");
                try(OutputStream out=new FileOutputStream(partial)){copy(cached,out);}
                if(!partial.renameTo(target)){
                    partial.delete();
                    throw new java.io.IOException("음악 파일 저장 완료 실패");
                }
                result=Uri.fromFile(target);
            }

            if(!prefs(c).edit().putString(key(rel),result.toString()).commit())
                throw new java.io.IOException("저장곡 목록 기록 실패");
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
            if(file.exists()&&!file.delete())
                throw new java.io.IOException("저장 파일 삭제 실패");
        }else if(android.provider.DocumentsContract.isDocumentUri(c,uri)){
            android.provider.DocumentsContract.deleteDocument(
                c.getContentResolver(),uri);
        }else{
            c.getContentResolver().delete(uri,null,null);
        }
    }

    public static boolean deleteSaved(Context c,String relativePath){
        String stored=prefs(c).getString(key(relativePath),null);
        if(stored==null) return true;
        try{
            deleteUri(c,Uri.parse(stored));
            prefs(c).edit().remove(key(relativePath)).apply();
            return true;
        }catch(Exception ignored){return false;}
    }
}
