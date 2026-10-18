package com.polarisunbound.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class PolarisYtmLabelParserTest {
    private static int assertions=0;
    private static void expect(String name,List<String> input,
            int count,String firstTitle,String firstKind) {
        List<PolarisYtmLabelParser.Entry> found=
            PolarisYtmLabelParser.parse(input);
        if(found.size()!=count)
            throw new AssertionError(name+": count="+found.size()+
                " expected="+count+" "+found);
        if(count>0){
            if(!firstTitle.equals(found.get(0).title))
                throw new AssertionError(name+": title="+found.get(0).title);
            if(!firstKind.equals(found.get(0).kind))
                throw new AssertionError(name+": kind="+found.get(0).kind);
        }
        assertions++;
    }
    public static void main(String[] args) {
        expect("separate Korean playlist",
            Arrays.asList("citypop","재생목록 • repusy • 트랙 49개"),
            1,"citypop","playlist");
        expect("automatic playlist",
            Arrays.asList("좋아요 표시한 음악","자동 재생목록 · 159곡"),
            1,"좋아요 표시한 음악","playlist");
        expect("comma-delimited inline label",
            Arrays.asList("citypop, 재생목록 • repusy • 트랙 49개"),
            1,"citypop","playlist");
        expect("bullet-delimited inline label",
            Arrays.asList("citypop • 재생목록 • repusy • 트랙 49개"),
            1,"citypop","playlist");
        expect("multiline inline label",
            Arrays.asList("citypop, 재생목록\nrepusy · 49곡"),
            1,"citypop","playlist");
        expect("English playlist",
            Arrays.asList("jazz","Playlist • repusy • 16 tracks"),
            1,"jazz","playlist");
        expect("album label",
            Arrays.asList("The Album","앨범 · artist"),
            1,"The Album","album");
        expect("mixed two rows",
            Arrays.asList("citypop","재생목록 • repusy • 트랙 49개",
                "NEON","노래 • A20 및 A20 LITE"),
            2,"citypop","playlist");
        expect("duplicate entries",
            Arrays.asList("citypop","재생목록 • repusy",
                "citypop","재생목록 • repusy"),
            1,"citypop","playlist");
        expect("offline saved automatic playlist",
            Arrays.asList("오프라인 저장한 노래","자동 재생목록 · 469곡"),
            1,"오프라인 저장한 노래","playlist");
        expect("fullwidth middle dot metadata",
            Arrays.asList("mood","재생목록ㆍrepusyㆍ트랙 7개"),
            1,"mood","playlist");
        expect("no fabricated title from navigation",
            Arrays.asList("보관함","앨범 · artist"),
            0,null,null);
        expect("Test05 false positive: downloaded playlist toggle",
            Arrays.asList("오프라인 저장한 재생목록 표시","재생목록 · 화면에서 읽음"),
            0,null,null);
        expect("Test05 false positive: downloaded songs toggle",
            Arrays.asList("오프라인 저장한 곡 표시","노래 · 화면에서 읽음"),
            0,null,null);
        expect("Test05 false positive: pinned",
            Arrays.asList("고정됨","자동 재생목록 • 159곡"),
            0,null,null);
        expect("Test05 false positive: download status",
            Arrays.asList("스마트 오프라인 저장 완료","재생목록 · YouTube Music · 100곡"),
            0,null,null);
        expect("real Likes playlist still kept",
            Arrays.asList("좋아요 표시한 음악","자동 재생목록 · 159곡"),
            1,"좋아요 표시한 음악","playlist");
        expect("navigation only",
            Arrays.asList("홈","보관함","검색","최근 활동"),
            0,null,null);
        expect("metadata but no title",
            Arrays.asList("보관함","재생목록 • repusy"),
            0,null,null);
        System.out.println("PASS: "+assertions+" YTM label-parser fixture tests");
    }
}
