package com.polarisunbound.app;

/**
 * Pure-Java validation for an explicit on-device YT Music search request.
 * This is not a playlist ID parser, remote assistant invocation or cookie API.
 */
final class PolarisYtmSearchQuery {
    private PolarisYtmSearchQuery(){}

    static String normalize(String raw){
        if(raw==null)return null;
        StringBuilder out=new StringBuilder();
        boolean pendingSpace=false;
        for(int i=0;i<raw.length();i++){
            char ch=raw.charAt(i);
            if(Character.isISOControl(ch) && !Character.isWhitespace(ch))
                return null;
            if(Character.isWhitespace(ch)){
                pendingSpace=out.length()>0;
                continue;
            }
            if(pendingSpace){
                out.append(' ');
                pendingSpace=false;
            }
            if(out.length()>=120)return null;
            out.append(ch);
        }
        return out.length()==0?null:out.toString();
    }
}
