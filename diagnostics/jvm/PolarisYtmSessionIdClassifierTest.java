package com.polarisunbound.app;

/** 
 * A media ID is NOT automatically a playlist ID.
 * Accept only a verifiable original YTM playlist URI from a live session.
 */
public final class PolarisYtmSessionIdClassifierTest {
    private static int assertions=0;
    private static void playlist(String label,String value,String expected){
        String actual=PolarisYtmSessionIdClassifier.explicitPlaylistId(value);
        if(expected==null?actual!=null:!expected.equals(actual))
            throw new AssertionError(label+" expected "+expected+" but got "+actual);
        assertions++;
    }
    private static void mediaId(String label,String value,boolean expected){
        boolean actual=PolarisYtmSessionIdClassifier.opaqueMediaIdPresent(value);
        if(actual!=expected)
            throw new AssertionError(label+" presence incorrect");
        assertions++;
    }
    public static void main(String[] args){
        playlist("user-confirmed playlist URI",
            "https://music.youtube.com/playlist?list=PLabc123","PLabc123");
        playlist("standard YouTube playlist URI",
            "https://www.youtube.com/playlist?list=PLxyz789&si=abc","PLxyz789");
        playlist("liked music URI",
            "https://music.youtube.com/playlist?list=LM","LM");
        playlist("unrelated opaque track ID","abcDefGHIJ_012",null);
        playlist("not an official playlist ID","PLabc123",null);
        playlist("watch with list parameter",
            "https://music.youtube.com/watch?v=abcdef12345&list=PLabc123",null);
        playlist("playback CDN URL",
            "https://googlevideo.com/videoplayback?id=abc",null);
        playlist("spoofed official host",
            "https://music.youtube.com.attacker.invalid/playlist?list=PLabc123",null);
        playlist("null metadata",null,null);
        playlist("invalid percent",
            "https://music.youtube.com/playlist?list=abc%ZZ",null);
        mediaId("track media ID is present","3h5V1",true);
        mediaId("missing raw ID","",false);
        mediaId("whitespace media ID","  ",false);
        mediaId("null media ID",null,false);
        System.out.println("PASS "+assertions+" read-only YTM MediaSession ID classifier fixtures");
    }
}
