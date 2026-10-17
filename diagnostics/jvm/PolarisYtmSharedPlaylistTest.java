package com.polarisunbound.app;

public final class PolarisYtmSharedPlaylistTest {
    static int count=0;
    static void check(String label,String url,String expected){
        String actual=PolarisYtmSharedPlaylist.extractId(url);
        if(expected==null?actual!=null:!expected.equals(actual))
            throw new AssertionError(label+": expected="+expected+", actual="+actual);
        count++;
    }
    static void parseCheck(String label,String url,String expected){
        String actual=PolarisYtmSharedPlaylist.parsePlaylistUrl(url);
        if(expected==null?actual!=null:!expected.equals(actual))
            throw new AssertionError(label+": expected="+expected+", actual="+actual);
        count++;
    }
    public static void main(String[] args){
        check("original YT Music share",
            "https://music.youtube.com/playlist?list=PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i&feature=share",
            "PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i");
        check("normal youtube playlist",
            "https://www.youtube.com/playlist?list=PLabc123&si=tracking","PLabc123");
        check("share message",
            "내 YT Music 리스트: https://music.youtube.com/playlist?list=RDCLAK123_xyz&si=xx",
            "RDCLAK123_xyz");
        check("share message with punctuation",
            "이거 들어봐 (https://music.youtube.com/playlist?list=PLabc123).",
            "PLabc123");
        check("encoded ID",
            "https://music.youtube.com/playlist?list=PLabc%3D","PLabc=");
        check("liked playlist compact ID",
            "https://music.youtube.com/playlist?list=LM","LM");
        check("no playlist share in song",
            "https://music.youtube.com/watch?v=abcdef12345&list=PLabc123",null);
        check("reject spoofed host",
            "https://music.youtube.com.evil.org/playlist?list=PLabc123",null);
        check("reject non-HTTPS",
            "http://music.youtube.com/playlist?list=PLabc123",null);
        check("reject javascript text",
            "javascript:alert(1)",null);
        check("reject shortlink without resolving",
            "https://youtu.be/abcdef12345",null);
        check("reject tracking with no list",
            "https://music.youtube.com/playlist?si=abc",null);
        check("reject invalid ID",
            "https://music.youtube.com/playlist?list=PLabc%26evil",null);
        check("reject massive sharing text",
            new String(new char[12010]).replace('\0','a')+
                " https://music.youtube.com/playlist?list=PLabc123",null);
        parseCheck("id-only parser ignores fragment",
            "https://music.youtube.com/playlist?list=PLabc123#comment","PLabc123");
        System.out.println("PASS "+count+" shared YTM playlist link parser fixtures");
    }
}
