package com.polarisunbound.app;

/**
 * Important: an app's own MediaSession media ID may be an opaque track key.
 * Never treat it as an official playlist ID. Only a validated official
 * /playlist?list=... URL can establish an actual playlist identifier here.
 *
 * No network, Google login, or access to original YTM private storage.
 */
final class PolarisYtmSessionIdClassifier {
    private PolarisYtmSessionIdClassifier(){}

    static String explicitPlaylistId(String input){
        if(input==null||input.length()>2600)return null;
        return PolarisYtmSharedPlaylist.parsePlaylistUrl(input.trim());
    }

    static boolean opaqueMediaIdPresent(String value){
        return value!=null&&!value.trim().isEmpty();
    }
}
