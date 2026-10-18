package com.polaris.cprobe;

import java.util.Arrays;
import java.util.List;

/** Compiles with plain javac; no Android runtime or external test library. */
public final class SongSelectorCliTest {
    private static SongSelector.Candidate song(String id,String title,String artist,int rank) {
        return new SongSelector.Candidate(id,title,artist,rank);
    }

    private static void wants(String query, String expectedId,
                              SongSelector.Candidate... candidates) {
        List<SongSelector.Candidate> options=Arrays.asList(candidates);
        SongSelector.Selection pick=SongSelector.select(query,options);
        String actual=pick==null?null:pick.song.id;
        if(!java.util.Objects.equals(expectedId,actual))
            throw new AssertionError("query="+query+" expected="+expectedId+
                " actual="+actual+" score="+(pick==null?"none":pick.score));
    }

    public static void main(String[] args) {
        SongSelector.Candidate wrongSung=song("AAAAAAAAAAA","너의 모든 순간","성시경",0);
        SongSelector.Candidate rightSung=song("BBBBBBBBBBB","너에게","성시경",4);
        SongSelector.Candidate wrongCover=song("CCCCCCCCCCC","너에게","아이유",0);
        SongSelector.Candidate original=song("DDDDDDDDDDD","너에게","서태지와 아이들",8);
        SongSelector.Candidate otherCover=song("EEEEEEEEEEE","너에게","경서",1);
        SongSelector.Candidate live=song("FFFFFFFFFFF","너에게 (LIVE)","성시경",2);

        // Reproductions of the two real wrong-play complaints.
        wants("성시경 너에게는","BBBBBBBBBBB",wrongSung,rightSung,wrongCover);
        wants("서태지 너에게","DDDDDDDDDDD",wrongCover,otherCover,original);
        // Safety: no candidate with the requested original performer.
        wants("서태지 너에게",null,wrongCover,otherCover);
        wants("성시경 너에게",null,wrongCover,otherCover);
        // Prefer official track over live/remix when the query says neither.
        wants("성시경 너에게","BBBBBBBBBBB",live,rightSung,wrongCover);
        wants("성시경 너에게","BBBBBBBBBBB",wrongCover,rightSung);
        // An artist-less request for a multiply covered title is ambiguous.
        wants("너에게",null,rightSung,wrongCover,original);
        wants("없는 노래",null,rightSung,original);

        SongSelector.Candidate james=song("GGGGGGGGGGG",
            "You Give Me Something","James Morrison",10);
        SongSelector.Candidate wrongJames=song("HHHHHHHHHHH",
            "You Give Me Something","Other Singer",0);
        wants("James Morrison You Give Me Something","GGGGGGGGGGG",
            wrongJames,james);
        wants("서태지와 아이들 너에게","DDDDDDDDDDD",wrongCover,original);

        SongSelector.Candidate exact=song("IIIIIIIIIII","너에게는","성시경",3);
        wants("성시경 너에게는","IIIIIIIIIII",rightSung,exact);

        System.out.println("C02 singer/title matching: 11 fixtures OK");
    }
}
