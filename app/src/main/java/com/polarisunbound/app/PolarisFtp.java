package com.polarisunbound.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
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

    private PolarisFtp(){}

    public static Config load(Context context){
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String mp3Root=p.getString(K_MP3_ROOT,DEFAULT_MP3_ROOT);
        String canRoot=p.getString(K_CAN_ROOT,DEFAULT_CAN_ROOT);

        // v0.41 shipped with /Polaris/... defaults. Migrate only those exact
        // old defaults; preserve any path the user entered manually.
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

        FTPClient ftp=new FTPClient();
        ftp.setConnectTimeout(10000);
        ftp.setDefaultTimeout(10000);
        ftp.setDataTimeout(15000);
        ftp.setControlEncoding("UTF-8");

        try{
            ftp.connect(cfg.host,cfg.port);
            int reply=ftp.getReplyCode();
            if(!FTPReply.isPositiveCompletion(reply))
                throw new IllegalStateException("FTP 연결 실패: "+reply);

            if(!ftp.login(cfg.user,cfg.password))
                throw new IllegalStateException("FTP 로그인 실패");

            ftp.enterLocalPassiveMode();
            if(!ftp.setFileType(FTP.BINARY_FILE_TYPE))
                throw new IllegalStateException("FTP binary mode 설정 실패");

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
            if(ftp.isConnected()){
                try{ ftp.logout(); }catch(Exception ignored){}
                try{ ftp.disconnect(); }catch(Exception ignored){}
            }
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
