package com.polarisunbound.app;

public final class PolarisYtmSearchQueryTest {
    private static int assertions=0;
    private static void expect(String label,String given,String expected){
        String actual=PolarisYtmSearchQuery.normalize(given);
        if(expected==null?actual!=null:!expected.equals(actual))
            throw new AssertionError(label+": "+String.valueOf(actual));
        assertions++;
    }
    public static void main(String[] args){
        expect("null",null,null);
        expect("empty","",null);
        expect("whitespace"," \t\n ",null);
        expect("basic Korean","정인 장마","정인 장마");
        expect("trim","   citypop   ","citypop");
        expect("collapse spaces","A  B","A B");
        expect("collapse tabs","A\tB","A B");
        expect("collapse line breaks","A\nB","A B");
        expect("multi-space", "  A \t  B \n C ","A B C");
        expect("non-English Unicode","클래지콰이 Think About You","클래지콰이 Think About You");
        expect("short 11 char video ID","abcdefghijk","abcdefghijk");
        expect("max 120","x".repeat(120),"x".repeat(120));
        expect("reject 121","x".repeat(121),null);
        expect("reject embedded control","foo\u0001bar",null);
        expect("leading whitespace"," \n Hello","Hello");
        expect("trailing newline","Hello\n","Hello");
        System.out.println("PASS "+assertions+" YTM explicit search input fixtures");
    }
}
