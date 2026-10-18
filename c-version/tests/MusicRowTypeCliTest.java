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

        // The musicVideoType field is the source of truth if supplied.
        if(!"song".equals(MusicRowType.resolve("성시경",
                "MUSIC_VIDEO_TYPE_ATV",false)))
            throw new AssertionError("Unfiltered YTM ATV must be accepted");
        if(!"episode".equals(MusicRowType.resolve("성시경",
                "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",true)))
            throw new AssertionError("Podcast must be rejected even in songs filter");
        if(!"video".equals(MusicRowType.resolve("성시경",
                "MUSIC_VIDEO_TYPE_OMV",true)))
            throw new AssertionError("Music video is not a filtered song");
        if(!"song".equals(MusicRowType.resolve("성시경","",true)))
            throw new AssertionError("Filtered song with untyped singer first run");
        if(!"unknown".equals(MusicRowType.resolve("성시경","",false)))
            throw new AssertionError("Untyped general result should not be played");
        System.out.println("C06 row type: 13 label fixtures + 5 safety + 5 videoType OK");
    }
}
