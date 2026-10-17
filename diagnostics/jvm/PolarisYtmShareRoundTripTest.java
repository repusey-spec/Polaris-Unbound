package com.polarisunbound.app;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Source-only end-to-end contract:
 * official YTM playlist share -> real user-confirmed ID -> Polaris media ID
 * -> original official YouTube Music ACTION_VIEW URI.
 *
 * This checks ID preservation, not activity launch or automatic playback.
 */
public final class PolarisYtmShareRoundTripTest {
    private static int tests=0;
    private static void check(String label,String shareText,String expectedId){
        String parsed=PolarisYtmSharedPlaylist.extractId(shareText);
        if(parsed==null || !parsed.equals(expectedId))
            throw new AssertionError(label+" share ID mismatch: "+parsed);
        String mediaId="ytm:openplaylist:"+encode(parsed);
        String url=PolarisYtmDeepLink.urlFor(mediaId);
        String expected="https://music.youtube.com/playlist?list="+encode(parsed);
        if(!expected.equals(url))
            throw new AssertionError(label+" source URI: "+url+" (wanted "+expected+")");
        String reopened=PolarisYtmSharedPlaylist.parsePlaylistUrl(url);
        if(!parsed.equals(reopened))
            throw new AssertionError(label+" reopened ID mismatch: "+reopened);
        tests++;
    }

    private static String encode(String id){
        try{return URLEncoder.encode(id,"UTF-8");}
        catch(java.io.UnsupportedEncodingException ignored){
            throw new AssertionError("UTF-8 not supported");
        }
    }

    private static void invalid(String label,String input){
        String id=PolarisYtmSharedPlaylist.extractId(input);
        if(id!=null)
            throw new AssertionError(label+" unexpectedly accepted: "+id);
        tests++;
    }

    public static void main(String[] args) {
        check("standard real playlist",
            "https://music.youtube.com/playlist?list=PLabc123&si=extra",
            "PLabc123");
        check("playlist with =",
            "이 리스트 공유: https://music.youtube.com/playlist?list=PLabc%3D",
            "PLabc=");
        check("Liked Music ID",
            "https://music.youtube.com/playlist?list=LM","LM");
        check("YouTube shared playlist",
            "https://www.youtube.com/playlist?list=RDCLAK5uy_other&si=x",
            "RDCLAK5uy_other");
        invalid("reject video watch URL",
            "https://music.youtube.com/watch?v=abcdefghijk&list=PLabc123");
        invalid("reject attacker domain",
            "https://music.youtube.com.evil.test/playlist?list=PLabc123");
        invalid("reject malformed list",
            "https://music.youtube.com/playlist?list=PLabc%26track=7");
        System.out.println("PASS "+tests+" YTM share-to-original-app ID round trips");
    }
}
