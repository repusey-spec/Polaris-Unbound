package com.polarisunbound.app;

/**
 * Pure-Java ID validation for account-library navigation. The ID is always
 * obtained from a server browse/watch endpoint, never inferred from a label.
 */
final class PolarisYtmAccountIds {
    private PolarisYtmAccountIds(){}

    static String playlist(String browseId){
        if(browseId==null)return null;
        String id=browseId.startsWith("VL")?browseId.substring(2):browseId;
        return id.matches("[A-Za-z0-9_=-]{2,180}")?id:null;
    }

    static String video(String id){
        return id!=null && id.matches("[A-Za-z0-9_-]{11}")?id:null;
    }
}
