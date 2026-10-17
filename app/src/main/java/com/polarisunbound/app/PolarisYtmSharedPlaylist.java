package com.polarisunbound.app;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an explicit YouTube Music / YouTube playlist SHARE link.
 *
 * This is a user-supplied link, not a guess from a visible playlist name.
 * It never reads installed YTM login cookies and never dereferences links.
 * Only the playlist ID is retained; query tracking parameters are discarded.
 */
final class PolarisYtmSharedPlaylist {
    private static final Pattern URL=Pattern.compile(
        "https://[^\\s<>\\\"']+",Pattern.CASE_INSENSITIVE);
    private static final Pattern ID=Pattern.compile("[A-Za-z0-9_=-]{2,180}");
    private PolarisYtmSharedPlaylist(){}

    static String extractId(String shareText){
        if(shareText==null||shareText.length()>12000)return null;
        Matcher urls=URL.matcher(shareText);
        while(urls.find()){
            String candidate=urls.group();
            while(!candidate.isEmpty() &&
                ".,;!)]}、。".indexOf(candidate.charAt(candidate.length()-1))>=0)
                candidate=candidate.substring(0,candidate.length()-1);
            String id=parsePlaylistUrl(candidate);
            if(id!=null)return id;
        }
        return null;
    }
    static String parsePlaylistUrl(String text){
        if(text==null || text.length()>2500)return null;
        try{
            URI uri=new URI(text.trim());
            if(!"https".equalsIgnoreCase(uri.getScheme()))return null;
            String host=uri.getHost();
            if(host==null)return null;
            String domain=host.toLowerCase(java.util.Locale.ROOT);
            if(!("music.youtube.com".equals(domain)||
                "youtube.com".equals(domain)||
                "www.youtube.com".equals(domain)||
                "m.youtube.com".equals(domain)))return null;
            if(!"/playlist".equals(uri.getPath()))return null;
            String query=uri.getRawQuery();
            if(query==null)return null;
            for(String param:query.split("&")){
                int equal=param.indexOf('=');
                if(equal<0||!"list".equals(param.substring(0,equal)))continue;
                String id=URLDecoder.decode(param.substring(equal+1),"UTF-8");
                return validId(id)?id:null;
            }
        }catch(Exception ignored){}
        return null;
    }
    static boolean validId(String id){
        return id!=null && ID.matcher(id).matches();
    }
}
