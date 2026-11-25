#!/usr/bin/env python3
"""Apply the radio-media physical-network transport to the reconstructed 6.16 app.
This is a source integration component, NOT a complete VPN application.
Apply after verify616.py. Does not change the music engine or the UI.
"""
from pathlib import Path
import sys
root = Path(sys.argv[1])
pkg = root / "app/src/main/java/com/dd3boh/outertune/polaris"
p = pkg / "PolarisMediaSources.kt"
s = p.read_text()
old = '''    private val direct = DefaultDataSource.Factory(context,
        DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 Polaris/6.0")
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000).setAllowCrossProtocolRedirects(true))'''
assert s.count(old) == 1, "Unexpected radio transport source; inspect before changing"
s = s.replace(old, '''    private val direct = DefaultDataSource.Factory(context,
        androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(PolarisDirectRadioCalls(context))
            .setUserAgent("Mozilla/5.0 Polaris/6.0"))''', 1)
s = s.replace("import androidx.media3.datasource.DefaultHttpDataSource\n", "")
p.write_text(s)
p = pkg / "PolarisDirectRadioCalls.kt"
assert not p.exists()
p.write_text(r'''package com.dd3boh.outertune.polaris

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Select the physical interface for each radio media request, including HLS
 * segments and redirects. DNS and TCP must use the same Network.
 * Never use the process-wide network binding or fall back to a VPN route.
 */
internal class PolarisDirectRadioCalls(context: Context) : Call.Factory {
    private val cm = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var selected: Network? = null
    private var client: OkHttpClient? = null

    private fun physical(n: Network): NetworkCapabilities? =
        cm.getNetworkCapabilities(n)?.takeIf {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
             it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
             it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }

    private fun directNetwork(): Network {
        cm.activeNetwork?.let { if (physical(it) != null) return it }
        return cm.allNetworks.mapNotNull { n -> physical(n)?.let { n to it } }
            .filter { (_, caps) -> caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .sortedWith(compareByDescending<Pair<Network, NetworkCapabilities>> {
                it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }.thenByDescending {
                it.second.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            })
            .firstOrNull()?.first
            ?: throw IOException("Polaris radio: no validated physical network; no VPN fallback")
    }

    override fun newCall(request: Request): Call = synchronized(lock) {
        val n = directNetwork()
        if (selected != n || client == null) {
            client?.connectionPool?.evictAll()
            client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(n.socketFactory)
                .dns(Dns { host -> n.getAllByName(host).toList() })
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            selected = n
            android.util.Log.i("PolarisVpnRoute", "Radio media transport: physical network=" + n)
        }
        checkNotNull(client).newCall(request)
    }
}
''')
print("Applied physical DNS and sockets to the existing 6.16 radio media factory only.")
