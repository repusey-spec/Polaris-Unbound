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

        // Verified against actual anonymous YT Music API response fields
        // printed by the CI diagnostic for the user's two failing searches.
        SongSelector.Candidate realSung=song("JJJJJJJJJJJ",
            "너에게 To You","성시경",0);
        SongSelector.Candidate realWrong=song("KKKKKKKKKKK",
            "연연","성시경",1);
        wants("성시경 너에게는","JJJJJJJJJJJ",
            realWrong,realSung,wrongCover);

        SongSelector.Candidate seotaijiOriginal=new SongSelector.Candidate(
            "LLLLLLLLLLL","To You","Seo Taiji and Boys",
            "Seotaiji and Boys II",0);
        SongSelector.Candidate seotaijiLive=new SongSelector.Candidate(
            "MMMMMMMMMMM","너에게","서태지",
            "Seotaiji Live Tour Zero 04",1);
        wants("서태지 너에게","LLLLLLLLLLL",
            seotaijiLive,seotaijiOriginal,wrongCover);
        wants("서태지 너에게","LLLLLLLLLLL",
            seotaijiOriginal,seotaijiLive,wrongCover);
        wants("성시경 너에게는",null,realWrong,wrongCover);
        // Never match an unrelated Korean extension of "너에게".
        SongSelector.Candidate unrelated=song("NNNNNNNNNNN",
            "너에게로 가는 길","성시경",1);
        wants("성시경 너에게",null,unrelated,wrongCover);

        // Exact user input from C03 phone failure with real YTM song ID.
        SongSelector.Candidate returnedFirst=new SongSelector.Candidate(
            "YE9YnrFgib8","너에게 To You","성시경",
            "응답하라 1994 OST Part 2",0);
        wants("성시경 너에게","YE9YnrFgib8",
            realWrong,wrongCover,returnedFirst);
        if(SongSelector.inspectScore("성시경 너에게",returnedFirst)<205)
            throw new AssertionError("Expected real OST track to pass threshold");
        if(SongSelector.inspectScore("성시경 너에게",wrongCover)>=205)
            throw new AssertionError("An unrelated cover must not pass threshold");

        System.out.println("C04 artist/title/score: 17 match fixtures + 2 score checks OK");
    }
}
