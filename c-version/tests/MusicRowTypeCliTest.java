package com.polaris.cprobe;

public final class MusicRowTypeCliTest {
    private static void expect(String in,String want){
        String actual=MusicRowType.classify(in);
        if(!want.equals(actual))
            throw new AssertionError("type="+in+" expected="+want+" got="+actual);
    }

    public static void main(String[] args){
        expect("노래","song");
        expect("Song","song");
        expect("Songs","song");
        expect("곡","song");
        expect("에피소드","episode");
        expect("Episode","episode");
        expect("Podcast","episode");
        expect("팟캐스트","episode");
        expect("동영상","video");
        expect("Video","video");
        expect("Music video","video");
        expect("성시경","unknown"); // Filtered search: artist is first run.
        expect("서태지","unknown");
        for(String denied:new String[]{"에피소드","Episode","동영상","Video"}){
            if(MusicRowType.canBeSong(denied))
                throw new AssertionError("Podcast/video must never be a song");
        }
        if(!MusicRowType.canBeSong("성시경"))
            throw new AssertionError("Filtered song with artist label is valid");
        System.out.println("C05 type classification: 13 fixtures + 5 safety checks OK");
    }
}
