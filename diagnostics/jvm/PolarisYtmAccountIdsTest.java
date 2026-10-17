package com.polarisunbound.app;

public final class PolarisYtmAccountIdsTest {
    private static int n=0;
    private static void check(String name,String actual,String expected){
        if(expected==null?actual!=null:!expected.equals(actual))
            throw new AssertionError(name+" expected="+expected+" actual="+actual);
        n++;
    }
    public static void main(String[] args){
        check("valid playlist","PLabcdef012345",PolarisYtmAccountIds.playlist("VLPLabcdef012345"));
        check("bare playlist","PLabcdef012345",PolarisYtmAccountIds.playlist("PLabcdef012345"));
        check("invalid playlist",null,PolarisYtmAccountIds.playlist("VL/../"));
        check("missing playlist",null,PolarisYtmAccountIds.playlist(null));
        check("empty",null,PolarisYtmAccountIds.playlist("VL"));
        check("real 11-char", "abcdEf123_-",PolarisYtmAccountIds.video("abcdEf123_-"));
        check("short",null,PolarisYtmAccountIds.video("abcd"));
        check("overlong",null,PolarisYtmAccountIds.video("abcdEf123_--"));
        check("URL not ID",null,PolarisYtmAccountIds.video("http://x/y"));
        check("no fake title",null,PolarisYtmAccountIds.video("Title Song"));
        System.out.println("PASS "+n+" account-library ID fixtures");
    }
}
