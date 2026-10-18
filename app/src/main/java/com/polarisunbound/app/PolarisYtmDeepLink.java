package com.polarisunbound.app;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * Resolves verified YouTube Music browse IDs into links for the installed
 * official YT Music app. Pure Java so incorrect links can be regression-tested
 * without an APK or fake music data.
 *
 * This does not guarantee that Android Auto can launch the activity or that
 * launching a playlist URL starts playback automatically.
 */
final class PolarisYtmDeepLink {
    private static final String PLAY="ytm:play:";
    private static final String OPEN_PLAYLIST="ytm:openplaylist:";
    private PolarisYtmDeepLink(){}

    static String urlFor(String mediaId){
        if(mediaId==null)return null;
        if(mediaId.startsWith(PLAY)){
            String details=mediaId.substring(PLAY.length());
            String[] parts=details.split("\\?list=",2);
            String video=decode(parts[0]);
            if(!validVideo(video))return null;
            String url="https://music.youtube.com/watch?v="+escape(video);
            if(parts.length>1){
                String playlist=decode(parts[1]);
                if(!validPlaylist(playlist))return null;
                url+="&list="+escape(playlist);
            }
            return url;
        }
        if(mediaId.startsWith(OPEN_PLAYLIST)){
            String playlist=decode(mediaId.substring(OPEN_PLAYLIST.length()));
            if(!validPlaylist(playlist))return null;
            return "https://music.youtube.com/playlist?list="+escape(playlist);
        }
        return null;
    }

    private static String decode(String value){
        if(value==null)return null;
        try{return URLDecoder.decode(value,"UTF-8");}
        catch(UnsupportedEncodingException|IllegalArgumentException ignored){return null;}
    }
    private static String escape(String id){
        return id.replace("=","%3D");
    }
    private static boolean validVideo(String id){
        return id!=null&&id.matches("[A-Za-z0-9_-]{6,20}");
    }
    private static boolean validPlaylist(String id){
        return id!=null&&id.matches("[A-Za-z0-9_=-]{1,180}");
    }
}
