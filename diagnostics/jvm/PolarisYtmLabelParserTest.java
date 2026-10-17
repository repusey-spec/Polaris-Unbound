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
            Arrays.asList("citypop, 재생목록\\nrepusy · 49곡"),
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
        expect("navigation only",
            Arrays.asList("홈","보관함","검색","최근 활동"),
            0,null,null);
        expect("metadata but no title",
            Arrays.asList("보관함","재생목록 • repusy"),
            0,null,null);
        System.out.println("PASS: "+assertions+" YTM label-parser fixture tests");
    }
}
