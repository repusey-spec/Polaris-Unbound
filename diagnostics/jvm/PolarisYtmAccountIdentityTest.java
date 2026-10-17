package com.polarisunbound.app;

public final class PolarisYtmAccountIdentityTest {
    private static int passed=0;
    private static void eq(String title,String expected,String actual){
        if(expected==null?actual!=null:!expected.equals(actual))
            throw new AssertionError(title+": "+expected+" vs "+actual);
        passed++;
    }
    public static void main(String[] args){
        eq("default","0",PolarisYtmAccountIdentity.authIndex(null));
        eq("zero","0",PolarisYtmAccountIdentity.authIndex("0"));
        eq("selected account","1",PolarisYtmAccountIdentity.authIndex(" 1 "));
        eq("max expected","19",PolarisYtmAccountIdentity.authIndex("19"));
        eq("bad 20","0",PolarisYtmAccountIdentity.authIndex("20"));
        eq("negative","0",PolarisYtmAccountIdentity.authIndex("-1"));
        eq("token rejected","0",PolarisYtmAccountIdentity.authIndex("SAPISIDHASH"));
        eq("non-numeric","0",PolarisYtmAccountIdentity.authIndex("two"));
        eq("empty page",null,PolarisYtmAccountIdentity.pageId(null,null));
        String uc="UCabcdefghijklmnopqrstuv";
        eq("channel UC",uc,PolarisYtmAccountIdentity.pageId("",uc+"||ignored"));
        eq("prefer delegated",uc,PolarisYtmAccountIdentity.pageId(uc,"12345678901234567"));
        eq("numeric page","12345678901234567",
            PolarisYtmAccountIdentity.pageId("12345678901234567",null));
        eq("short page reject",null,PolarisYtmAccountIdentity.pageId("user",""));
        eq("cookie not page",null,PolarisYtmAccountIdentity.pageId("SID=some; cookie",""));
        eq("no authority URL",null,PolarisYtmAccountIdentity.pageId(
            "https://example.com/path",""));
        System.out.println("PASS "+passed+" YTM account/channel selection fixtures");
    }
}
