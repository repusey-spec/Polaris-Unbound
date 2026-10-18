package com.polarisunbound.app;

public final class PolarisYtmDeepLinkTest {
    private static int count=0;
    private static void expect(String label,String input,String expected){
        String actual=PolarisYtmDeepLink.urlFor(input);
        if(expected==null ? actual!=null : !expected.equals(actual))
            throw new AssertionError(label+": expected="+expected+" actual="+actual);
        count++;
    }
    public static void main(String[] args){
        expect("valid song","ytm:play:abcdEFGH12",
            "https://music.youtube.com/watch?v=abcdEFGH12");
        expect("song with playlist","ytm:play:abcdEFGH12?list=RDmix123",
            "https://music.youtube.com/watch?v=abcdEFGH12&list=RDmix123");
        expect("percent-encoded playlist",
            "ytm:play:abcdEFGH12?list=PLabc%3D",
            "https://music.youtube.com/watch?v=abcdEFGH12&list=PLabc%3D");
        expect("playlist folder",
            "ytm:openplaylist:PL123abc",
            "https://music.youtube.com/playlist?list=PL123abc");
        expect("playlist equal-sign",
            "ytm:openplaylist:PLab%3D",
            "https://music.youtube.com/playlist?list=PLab%3D");
        expect("missing video","ytm:play:",null);
        expect("too-short song","ytm:play:ab",null);
        expect("script injection","ytm:play:abc123%26evil%3D1",null);
        expect("empty linked playlist","ytm:play:abcdEFGH12?list=",null);
        expect("bad percent sequence","ytm:openplaylist:PL%GG",null);
        expect("unrelated item","ytm:captured:jazz",null);
        expect("unrelated media id","mp3:415",null);
        expect("null item",null,null);
        System.out.println("PASS "+count+" Polaris YTM official-app deeplink fixture tests");
    }
}
