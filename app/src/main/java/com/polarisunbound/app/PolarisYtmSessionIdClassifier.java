package com.polarisunbound.app;

import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;

/**
 * A MediaSession media ID may be an app-private track key, never an
 * automatically valid playlist ID. Accept an ID only if it is explicitly
 * included in a trustworthy original YT Music or YouTube URL:
 *
 *   /playlist?list=<playlistId>
 *   /watch?v=<11-character-videoId>&list=<playlistId>
 *
 * The watch URL is an active playback CONTEXT, not proof the playlist is
 * owned by the user, listed in their library, or freely browsable.
 */
final class PolarisYtmSessionIdClassifier {
    private PolarisYtmSessionIdClassifier(){}

    static String explicitPlaylistId(String input){
        if(input==null||input.length()>2600)return null;
        String trimmed=input.trim();
        String direct=PolarisYtmSharedPlaylist.parsePlaylistUrl(trimmed);
        if(direct!=null)return direct;
        try{
            URI uri=new URI(trimmed);
            if(!"https".equalsIgnoreCase(uri.getScheme())||
                uri.getHost()==null||uri.getRawUserInfo()!=null||
                !"/watch".equals(uri.getPath()))return null;
            String host=uri.getHost().toLowerCase(Locale.ROOT);
            if(!("music.youtube.com".equals(host)||
                "youtube.com".equals(host)||
                "www.youtube.com".equals(host)||
                "m.youtube.com".equals(host)))return null;
            String query=uri.getRawQuery();
            if(query==null)return null;
            String video=null,playlist=null;
            for(String entry:query.split("&",-1)){
                int eq=entry.indexOf('=');
                if(eq<=0)continue;
                String key=entry.substring(0,eq);
                if(!"v".equals(key)&&!"list".equals(key))continue;
                String value=URLDecoder.decode(entry.substring(eq+1),"UTF-8");
                if("v".equals(key)){
                    if(video!=null)return null; // ambiguous duplicate parameters
                    video=value;
                }else{
                    if(playlist!=null)return null;
                    playlist=value;
                }
            }
            if(video==null||!video.matches("[A-Za-z0-9_-]{11}"))
                return null;
            return PolarisYtmSharedPlaylist.validId(playlist)?playlist:null;
        }catch(Exception ignored){
            return null;
        }
    }

    static boolean opaqueMediaIdPresent(String value){
        return value!=null&&!value.trim().isEmpty();
    }
}
