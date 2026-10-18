package com.polaris.cprobe;

import java.util.Locale;

/** Type tags from YouTube Music flexColumns; not based on videoId alone. */
final class MusicRowType {
    private MusicRowType(){}

    static String classify(String raw){
        String label=raw==null?"":raw.trim().toLowerCase(Locale.ROOT);
        switch(label){
            case "song":
            case "songs":
            case "노래":
            case "곡": return "song";
            case "video":
            case "videos":
            case "동영상":
            case "뮤직비디오":
            case "music video": return "video";
            case "episode":
            case "episodes":
            case "에피소드":
            case "팟캐스트":
            case "podcast":
            case "podcast episode": return "episode";
            default: return "unknown";
        }
    }

    static boolean canBeSong(String raw){
        String type=classify(raw);
        return !type.equals("video")&&!type.equals("episode");
    }
}
