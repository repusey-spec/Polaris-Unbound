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

    /**
     * Use YouTube Music's musicVideoType when available (as in ytmusicapi
     * parse_search_result), not the presence of any 11-char video ID.
     * In an explicitly songs-filtered response, a first credit run is often
     * the artist rather than the translated word "Song".
     */
    static String resolve(String label,String videoType,boolean songsOnly){
        if("MUSIC_VIDEO_TYPE_PODCAST_EPISODE".equals(videoType))
            return "episode";
        if("MUSIC_VIDEO_TYPE_ATV".equals(videoType))
            return "song";
        if("MUSIC_VIDEO_TYPE_OMV".equals(videoType)||
           "MUSIC_VIDEO_TYPE_UGC".equals(videoType))
            return "video";
        String tagged=classify(label);
        if(!"unknown".equals(tagged))return tagged;
        return songsOnly?"song":"unknown";
    }

    static boolean canBeSong(String raw){
        String type=classify(raw);
        return !type.equals("video")&&!type.equals("episode");
    }
}
