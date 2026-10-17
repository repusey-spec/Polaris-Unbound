package com.polarisunbound.app;

/**
 * Sanitizes non-secret account selection metadata obtained from the user's
 * own authenticated Polaris WebView. Never accepts a cookie or auth token.
 *
 * When a signed-in YouTube profile has a brand channel, choosing the account
 * index alone is insufficient; the selected profile may also have a Page ID.
 */
final class PolarisYtmAccountIdentity {
    private PolarisYtmAccountIdentity(){}

    static String authIndex(String source){
        if(source==null)return "0";
        String index=source.trim();
        if(!index.matches("[0-9]{1,2}"))return "0";
        int value=Integer.parseInt(index);
        return value<=19?Integer.toString(value):"0";
    }

    static String pageId(String delegated,String dataSync){
        String valid=validPageId(delegated);
        if(valid!=null)return valid;
        if(dataSync==null)return null;
        return validPageId(dataSync.split("\\|\\|",2)[0]);
    }

    private static String validPageId(String input){
        if(input==null)return null;
        String p=input.trim();
        // Common channel and brand page ID shapes, not raw session cookies.
        if(p.length()<12||p.length()>128 ||
            !p.matches("[A-Za-z0-9_-]+"))return null;
        return p;
    }
}
