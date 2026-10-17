package com.polarisunbound.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Selected (non-secret) account/channel metadata. Cookies remain in WebView. */
final class PolarisYtmAccountSelection {
    private static final String PREF="ytm_account_selection_v11";
    private static final String KEY_INDEX="auth_index";
    private static final String KEY_PAGE="page_id";

    private PolarisYtmAccountSelection(){}

    static void save(Context ctx,String authUser,String delegated,String dataSync){
        if(ctx==null)return;
        String index=PolarisYtmAccountIdentity.authIndex(authUser);
        String page=PolarisYtmAccountIdentity.pageId(delegated,dataSync);
        SharedPreferences.Editor e=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .edit().putString(KEY_INDEX,index);
        if(page==null)e.remove(KEY_PAGE);
        else e.putString(KEY_PAGE,page);
        e.apply();
    }

    static String authIndex(Context ctx){
        if(ctx==null)return "0";
        return PolarisYtmAccountIdentity.authIndex(
            ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY_INDEX,"0"));
    }

    static String pageId(Context ctx){
        if(ctx==null)return null;
        return PolarisYtmAccountIdentity.pageId(
            ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY_PAGE,null),null);
    }

    static boolean hasSavedSelection(Context ctx){
        return ctx!=null&&ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .contains(KEY_INDEX);
    }

    static void clear(Context ctx){
        if(ctx!=null)ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().clear().apply();
    }
}
