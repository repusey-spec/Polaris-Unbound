package com.polarisunbound.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host-JVM-testable parser for labels visible in the official YouTube Music app.
 *
 * No Android APIs, credentials, networking, playback requests, or fabricated IDs.
 * A label is not a media item unless the same text or a nearby label explicitly
 * contains the item's type. This cannot establish the original playlist ID.
 */
final class PolarisYtmLabelParser {
    static final class Entry {
        final String title, description, kind;
        Entry(String title,String description,String kind) {
            this.title=title;
            this.description=description;
            this.kind=kind;
        }
    }

    private static final int MAX=60;
    private static final String TYPES=
        "(자동\\s*재생목록|재생목록|플레이리스트|playlist|앨범|album|아티스트|artist|노래|song)";
    private static final Pattern COMBINED=Pattern.compile(
        "^(.{1,150}?)[,，•·]\\s*"+TYPES+"(?=\\s|[•·ㆍ,:|，]|$).*",
        Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private static final Pattern TYPE_ONLY=Pattern.compile(
        "^\\s*"+TYPES+"\\s*$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private static final Pattern METADATA=Pattern.compile(
        "^\\s*"+TYPES+"(?=\\s|[•·ㆍ,:|，]|$).*",
        Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);

    static List<Entry> parse(List<String> labels) {
        List<Entry> rows=new ArrayList<>();
        if(labels==null)return rows;
        Set<String> seen=new HashSet<>();
        for(int i=0;i<labels.size() && rows.size()<MAX;i++){
            String raw=normalize(labels.get(i));
            if(raw.isEmpty())continue;
            Matcher combined=COMBINED.matcher(raw);
            if(combined.matches() &&
               !TYPE_ONLY.matcher(combined.group(1).trim()).matches()) {
                String title=combined.group(1).trim();
                String kind=kind(combined.group(2));
                add(rows,seen,title,raw,kind);
                continue;
            }
            Matcher meta=METADATA.matcher(raw);
            if(!meta.matches() || i==0)continue;
            String type=kind(meta.group(1));
            // Android's accessibility tree can interleave UI controls. Only
            // look back across two labels rather than binding distant rows.
            for(int offset=1;offset<=2 && offset<=i;offset++){
                String candidate=normalize(labels.get(i-offset));
                if(!validTitle(candidate,raw))continue;
                add(rows,seen,candidate,raw,type);
                break;
            }
        }
        return rows;
    }

    static int metadataCount(List<String> labels){
        if(labels==null)return 0;
        int count=0;
        for(String label:labels){
            String str=normalize(label);
            if(METADATA.matcher(str).matches()||COMBINED.matcher(str).matches())
                count++;
        }
        return count;
    }

    private static String kind(String type){
        if(type==null)return null;
        String value=type.toLowerCase(Locale.ROOT);
        if(value.contains("재생목록")||value.equals("플레이리스트")||
            value.equals("playlist"))return "playlist";
        if(value.equals("앨범")||value.equals("album"))return "album";
        if(value.equals("아티스트")||value.equals("artist"))return "artist";
        if(value.equals("노래")||value.equals("song"))return "song";
        return null;
    }

    private static String normalize(String text){
        return text==null?"":text.replace('\u00a0',' ').replace('\n',' ').replace('\r',' ').trim();
    }

    private static boolean validTitle(String title,String metadata){
        if(title.isEmpty()||title.length()>150||title.equals(metadata))return false;
        if(title.contains("•")||title.contains("·")||
            title.startsWith("android."))return false;
        String lower=title.toLowerCase(Locale.ROOT);
        // Test05 showed accessibility UI labels masquerading as titles.
        // Prefer dropping ambiguous controls to inventing a playlist.
        if(lower.equals("고정됨")||lower.equals("고정")||
            lower.equals("pinned")||lower.equals("pin")||
            lower.equals("스마트 오프라인 저장 완료")||
            lower.startsWith("오프라인 저장한 재생목록 표시")||
            lower.startsWith("오프라인 저장한 곡 표시")||
            lower.equals("show downloaded playlists")||
            lower.equals("show downloaded songs"))return false;
        for(String nav:new String[]{"홈","보관함","플레이리스트","재생목록",
            "검색","최근 활동","최근 추가한 항목","오프라인 저장",
            "home","library","search","playlists","playlist"}){
            if(lower.equals(nav))return false;
        }
        if(METADATA.matcher(title).matches())return false;
        return true;
    }

    private static void add(List<Entry> out,Set<String> seen,
            String title,String description,String type){
        if(type==null||!validTitle(title,description))return;
        String key=type+"|"+title;
        if(seen.add(key))out.add(new Entry(title,description,type));
    }
}
