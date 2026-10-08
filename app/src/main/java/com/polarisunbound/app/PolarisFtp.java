package com.polarisunbound.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class PolarisFtp {
    private static final String PREFS="polaris_ftp";
    private static final String KEY_ALIAS="polaris_ftp_password_v1";
    private static final String K_HOST="host";
    private static final String K_PORT="port";
    private static final String K_USER="user";
    private static final String K_PASS="password_cipher";
    private static final String K_IV="password_iv";
    private static final String K_MP3_ROOT="mp3_root";
    private static final String K_CAN_ROOT="can_root";
    private static final String DEFAULT_MP3_ROOT="/repusy/MP3";
    private static final String DEFAULT_CAN_ROOT="/repusy/CAN";

    public static final class Config {
        public final String host;
        public final int port;
        public final String user;
        public final String password;
        public final String mp3Root;
        public final String canRoot;

        Config(String host,int port,String user,String password,String mp3Root,String canRoot){
            this.host=host==null?"":host.trim();
            this.port=port<=0?21:port;
            this.user=user==null?"":user.trim();
            this.password=password==null?"":password;
            this.mp3Root=normalizeRoot(mp3Root,DEFAULT_MP3_ROOT);
            this.canRoot=normalizeRoot(canRoot,DEFAULT_CAN_ROOT);
        }

        public boolean isConfigured(){
            return !host.isEmpty()&&!user.isEmpty();
        }

        public String rootFor(String scope){
            return "MP3".equalsIgnoreCase(scope)?mp3Root:canRoot;
        }
    }

    public static final class Entry {
        public final String name;
        public final String relativePath;
        public final boolean directory;
        public final long size;

        Entry(String name,String relativePath,boolean directory,long size){
            this.name=name;
            this.relativePath=relativePath;
            this.directory=directory;
            this.size=Math.max(0L,size);
        }
    }

    public static final class DirectoryPage {
        public final List<Entry> entries;
        public final int total;
        public final int offset;
        public final int limit;

        DirectoryPage(List<Entry> entries,int total,int offset,int limit){
            this.entries=entries;
            this.total=Math.max(0,total);
            this.offset=Math.max(0,offset);
            this.limit=Math.max(1,limit);
        }

        public boolean hasMore(){
            return offset+entries.size()<total;
        }
    }

    private PolarisFtp(){}

    public static Config load(Context context){
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String mp3Root=p.getString(K_MP3_ROOT,DEFAULT_MP3_ROOT);
        String canRoot=p.getString(K_CAN_ROOT,DEFAULT_CAN_ROOT);

        boolean migrate=false;
        if("/Polaris/MP3".equals(mp3Root)){
            mp3Root=DEFAULT_MP3_ROOT;
            migrate=true;
        }
        if("/Polaris/CAN".equals(canRoot)){
            canRoot=DEFAULT_CAN_ROOT;
            migrate=true;
        }
        if(migrate){
            p.edit()
                .putString(K_MP3_ROOT,mp3Root)
                .putString(K_CAN_ROOT,canRoot)
                .apply();
        }

        return new Config(
            p.getString(K_HOST,""),
            p.getInt(K_PORT,21),
            p.getString(K_USER,""),
            decryptPassword(p),
            mp3Root,
            canRoot
        );
    }

    public static void save(Context context,String host,int port,String user,String password,
                            String mp3Root,String canRoot) throws Exception {
        SharedPreferences.Editor e=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putString(K_HOST,host==null?"":host.trim())
            .putInt(K_PORT,port<=0?21:port)
            .putString(K_USER,user==null?"":user.trim())
            .putString(K_MP3_ROOT,normalizeRoot(mp3Root,DEFAULT_MP3_ROOT))
            .putString(K_CAN_ROOT,normalizeRoot(canRoot,DEFAULT_CAN_ROOT));

        if(password!=null){
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,getOrCreateKey());
            byte[] encrypted=cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            e.putString(K_PASS,Base64.encodeToString(encrypted,Base64.NO_WRAP));
            e.putString(K_IV,Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP));
        }
        e.apply();
    }

    public static String uploadText(Context context,String scope,String fileName,String text) throws Exception {
        Config cfg=load(context);
        if(!cfg.isConfigured()) throw new IllegalStateException("FTP 설정이 필요합니다");
        String root=cfg.rootFor(scope);
        if(fileName==null||fileName.trim().isEmpty()) throw new IllegalArgumentException("파일명이 비었습니다");

        FTPClient ftp=open(cfg);
        try{
            ensureRemoteRoot(ftp,root);

            String safeName=fileName.replace('/','_').replace('\\','_');
            String tempName=safeName+".part";
            byte[] bytes=(text==null?"":text).getBytes(StandardCharsets.UTF_8);

            try(ByteArrayInputStream in=new ByteArrayInputStream(bytes)){
                if(!ftp.storeFile(tempName,in))
                    throw new IllegalStateException("FTP 업로드 실패: "+ftp.getReplyString().trim());
            }

            if(!ftp.rename(tempName,safeName)){
                ftp.deleteFile(tempName);
                throw new IllegalStateException("FTP 최종 파일명 변경 실패");
            }

            return joinRemote(root,safeName);
        } finally {
            close(ftp);
        }
    }

    public static DirectoryPage listMp3(Context context,String relativePath,int offset,int limit) throws Exception {
        Config cfg=load(context);
        if(!cfg.isConfigured()) throw new IllegalStateException("FTP 설정이 필요합니다");

        String rel=normalizeRelative(relativePath);
        int safeOffset=Math.max(0,offset);
        int safeLimit=Math.max(1,Math.min(limit,500));

        FTPClient ftp=open(cfg);
        try{
            if(!changeToExistingRoot(ftp,cfg.mp3Root))
                throw new IllegalStateException("MP3 FTP Root가 없습니다: "+cfg.mp3Root);
            if(!rel.isEmpty() && !changeRelativeDirectory(ftp,rel))
                throw new IllegalStateException("FTP 폴더를 찾을 수 없습니다: "+rel);

            FTPFile[] raw=ftp.listFiles();
            List<Entry> all=new ArrayList<>();
            if(raw!=null){
                for(FTPFile file:raw){
                    if(file==null) continue;
                    String name=file.getName()==null?"":file.getName().trim();
                    if(name.isEmpty()||".".equals(name)||"..".equals(name)||name.startsWith(".")) continue;

                    if(file.isDirectory()){
                        all.add(new Entry(name,joinRelative(rel,name),true,0L));
                    }else if(file.isFile() && isAudioFile(name)){
                        all.add(new Entry(name,joinRelative(rel,name),false,file.getSize()));
                    }
                }
            }

            Collections.sort(all,new Comparator<Entry>(){
                @Override public int compare(Entry a,Entry b){
                    if(a.directory!=b.directory) return a.directory?-1:1;
                    return a.name.compareToIgnoreCase(b.name);
                }
            });

            int from=Math.min(safeOffset,all.size());
            int to=Math.min(from+safeLimit,all.size());
            return new DirectoryPage(new ArrayList<>(all.subList(from,to)),all.size(),from,safeLimit);
        } finally {
            close(ftp);
        }
    }

    public static List<String> listMp3AudioPaths(Context context,String relativeDirectory) throws Exception {
        Config cfg=load(context);
        if(!cfg.isConfigured()) throw new IllegalStateException("FTP 설정이 필요합니다");

        String rel=normalizeRelative(relativeDirectory);
        FTPClient ftp=open(cfg);
        try{
            if(!changeToExistingRoot(ftp,cfg.mp3Root))
                throw new IllegalStateException("MP3 FTP Root가 없습니다: "+cfg.mp3Root);
            if(!rel.isEmpty() && !changeRelativeDirectory(ftp,rel))
                throw new IllegalStateException("FTP 폴더를 찾을 수 없습니다: "+rel);

            FTPFile[] raw=ftp.listFiles();
            List<String> out=new ArrayList<>();
            if(raw!=null){
                for(FTPFile file:raw){
                    if(file==null||!file.isFile()) continue;
                    String name=file.getName()==null?"":file.getName().trim();
                    if(name.isEmpty()||name.startsWith(".")||!isAudioFile(name)) continue;
                    out.add(joinRelative(rel,name));
                }
            }
            Collections.sort(out,String.CASE_INSENSITIVE_ORDER);
            return out;
        } finally {
            close(ftp);
        }
    }

    public static File downloadMp3ToCache(Context context,String relativePath) throws Exception {
        Config cfg=load(context);
        if(!cfg.isConfigured()) throw new IllegalStateException("FTP 설정이 필요합니다");

        String rel=normalizeRelative(relativePath);
        if(rel.isEmpty()||!isAudioFile(rel))
            throw new IllegalArgumentException("재생할 FTP 음악 파일이 아닙니다");

        File dir=new File(context.getCacheDir(),"ftp_mp3");
        if(!dir.exists()&&!dir.mkdirs())
            throw new IllegalStateException("FTP 캐시 폴더를 만들 수 없습니다");

        String extension="";
        int dot=rel.lastIndexOf('.');
        if(dot>=0 && dot>rel.lastIndexOf('/')) extension=rel.substring(dot).toLowerCase(Locale.US);
        String cacheName=sha256(rel)+extension;
        File target=new File(dir,cacheName);
        File part=new File(dir,cacheName+".part");

        FTPClient ftp=open(cfg);
        try{
            if(!changeToExistingRoot(ftp,cfg.mp3Root))
                throw new IllegalStateException("MP3 FTP Root가 없습니다: "+cfg.mp3Root);

            // Walk the directory first, then RETR the exact LIST filename. This avoids
            // re-parsing a full slash-separated UTF-8 path on Synology for Korean names.
            String parent=parentRelative(rel);
            if(!parent.isEmpty() && !changeRelativeDirectory(ftp,parent))
                throw new IllegalStateException("FTP 폴더 접근 실패: "+parent);
            String fileName=rel.substring(rel.lastIndexOf('/')+1);

            long expectedSize=remoteSize(ftp,fileName);
            if(target.isFile() && target.length()>0L &&
               (expectedSize<=0L || target.length()==expectedSize))
                return target;

            if(part.exists()) part.delete();
            try(FileOutputStream out=new FileOutputStream(part)){
                if(!ftp.retrieveFile(fileName,out))
                    throw new IllegalStateException("FTP 다운로드 실패: "+ftp.getReplyString().trim());
            }

            if(expectedSize>0L && part.length()!=expectedSize){
                long got=part.length();
                part.delete();
                throw new IllegalStateException("FTP 다운로드 크기 불일치: "+got+"/"+expectedSize);
            }

            if(target.exists()&&!target.delete()){
                part.delete();
                throw new IllegalStateException("기존 FTP 캐시를 교체할 수 없습니다");
            }
            if(!part.renameTo(target)){
                part.delete();
                throw new IllegalStateException("FTP 캐시 완료 처리 실패");
            }
            return target;
        } finally {
            close(ftp);
        }
    }

    public static String displayTitle(String fileName){
        if(fileName==null) return "";
        String name=fileName;
        int slash=Math.max(name.lastIndexOf('/'),name.lastIndexOf('\\'));
        if(slash>=0) name=name.substring(slash+1);
        int dot=name.lastIndexOf('.');
        if(dot>0) name=name.substring(0,dot);
        return name;
    }

    public static String parentRelative(String relativePath){
        String rel=normalizeRelative(relativePath);
        int slash=rel.lastIndexOf('/');
        return slash<0?"":rel.substring(0,slash);
    }

    public static boolean isAudioFile(String name){
        if(name==null) return false;
        String lower=name.toLowerCase(Locale.US);
        return lower.endsWith(".mp3")||lower.endsWith(".m4a")||
            lower.endsWith(".flac")||lower.endsWith(".wav")||
            lower.endsWith(".aac")||lower.endsWith(".ogg")||
            lower.endsWith(".opus")||lower.endsWith(".wma");
    }

    private static FTPClient open(Config cfg) throws Exception {
        FTPClient ftp=new FTPClient();
        ftp.setConnectTimeout(10000);
        ftp.setDefaultTimeout(10000);
        ftp.setDataTimeout(30000);
        ftp.setAutodetectUTF8(true);
        ftp.setControlEncoding(StandardCharsets.UTF_8.name());
        ftp.setBufferSize(64*1024);

        ftp.connect(cfg.host,cfg.port);
        int reply=ftp.getReplyCode();
        if(!FTPReply.isPositiveCompletion(reply)){
            close(ftp);
            throw new IllegalStateException("FTP 연결 실패: "+reply);
        }
        if(!ftp.login(cfg.user,cfg.password)){
            close(ftp);
            throw new IllegalStateException("FTP 로그인 실패");
        }

        // Synology advertises UTF-8 on modern FTP servers. Explicitly request it so
        // the exact name returned by LIST/MLSD is also usable by RETR for Korean names.
        try{ ftp.sendCommand("OPTS","UTF8 ON"); }catch(Exception ignored){}

        ftp.enterLocalPassiveMode();
        if(!ftp.setFileType(FTP.BINARY_FILE_TYPE)){
            close(ftp);
            throw new IllegalStateException("FTP binary mode 설정 실패");
        }
        return ftp;
    }

    private static void close(FTPClient ftp){
        if(ftp!=null&&ftp.isConnected()){
            try{ ftp.logout(); }catch(Exception ignored){}
            try{ ftp.disconnect(); }catch(Exception ignored){}
        }
    }

    private static void ensureRemoteRoot(FTPClient ftp,String root) throws Exception {
        String normalized=normalizeRoot(root,"/");
        if("/".equals(normalized)){
            if(!ftp.changeWorkingDirectory("/"))
                throw new IllegalStateException("FTP 루트 접근 실패");
            return;
        }

        boolean absolute=normalized.startsWith("/");
        if(absolute && !ftp.changeWorkingDirectory("/"))
            throw new IllegalStateException("FTP 루트 접근 실패");

        String[] parts=normalized.split("/");
        for(String raw:parts){
            String part=raw.trim();
            if(part.isEmpty()) continue;
            if(ftp.changeWorkingDirectory(part)) continue;
            if(!ftp.makeDirectory(part) || !ftp.changeWorkingDirectory(part))
                throw new IllegalStateException("FTP 폴더 생성/접근 실패: "+part);
        }
    }

    private static boolean changeToExistingRoot(FTPClient ftp,String root) throws Exception {
        String normalized=normalizeRoot(root,"/");
        if(normalized.startsWith("/")&&!ftp.changeWorkingDirectory("/")) return false;
        if("/".equals(normalized)) return true;
        for(String raw:normalized.split("/")){
            String part=raw.trim();
            if(part.isEmpty()) continue;
            if(!ftp.changeWorkingDirectory(part)) return false;
        }
        return true;
    }

    private static boolean changeRelativeDirectory(FTPClient ftp,String relative) throws Exception {
        String rel=normalizeRelative(relative);
        if(rel.isEmpty()) return true;
        for(String part:rel.split("/")){
            if(!ftp.changeWorkingDirectory(part)) return false;
        }
        return true;
    }

    private static long remoteSize(FTPClient ftp,String rel){
        try{
            FTPFile[] files=ftp.listFiles(rel);
            if(files!=null){
                for(FTPFile f:files){
                    if(f!=null&&f.isFile()) return Math.max(0L,f.getSize());
                }
            }
        }catch(Exception ignored){}
        return -1L;
    }

    private static String normalizeRelative(String value){
        String rel=value==null?"":value.trim().replace('\\','/');
        while(rel.startsWith("/")) rel=rel.substring(1);
        while(rel.endsWith("/")&&!rel.isEmpty()) rel=rel.substring(0,rel.length()-1);
        while(rel.contains("//")) rel=rel.replace("//","/");
        if(rel.isEmpty()) return "";
        for(String part:rel.split("/")){
            if(part.isEmpty()||".".equals(part)||"..".equals(part))
                throw new IllegalArgumentException("허용되지 않는 FTP 경로입니다");
        }
        return rel;
    }

    private static String joinRelative(String base,String name){
        String b=normalizeRelative(base);
        String n=normalizeRelative(name);
        return b.isEmpty()?n:b+"/"+n;
    }

    private static String normalizeRoot(String root,String fallback){
        String value=root==null?"":root.trim().replace('\\','/');
        if(value.isEmpty()) value=fallback;
        while(value.contains("//")) value=value.replace("//","/");
        if(value.length()>1 && value.endsWith("/")) value=value.substring(0,value.length()-1);
        return value;
    }

    private static String joinRemote(String root,String name){
        String r=normalizeRoot(root,"/");
        if("/".equals(r)) return "/"+name;
        return r+"/"+name;
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] bytes=digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();
        for(byte b:bytes) out.append(String.format(Locale.US,"%02x",b&0xff));
        return out.toString();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if(store.containsAlias(KEY_ALIAS)){
            KeyStore.SecretKeyEntry entry=(KeyStore.SecretKeyEntry)store.getEntry(KEY_ALIAS,null);
            return entry.getSecretKey();
        }

        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return generator.generateKey();
    }

    private static String decryptPassword(SharedPreferences p){
        String encoded=p.getString(K_PASS,"");
        String encodedIv=p.getString(K_IV,"");
        if(encoded.isEmpty()||encodedIv.isEmpty()) return "";
        try{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv=Base64.decode(encodedIv,Base64.NO_WRAP);
            cipher.init(Cipher.DECRYPT_MODE,getOrCreateKey(),new GCMParameterSpec(128,iv));
            byte[] plain=cipher.doFinal(Base64.decode(encoded,Base64.NO_WRAP));
            return new String(plain,StandardCharsets.UTF_8);
        }catch(Exception e){
            return "";
        }
    }
}
