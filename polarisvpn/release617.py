#!/usr/bin/env python3
"""6.32 production candidate. Preserve original Polaris production application ID and its existing local data."""
from pathlib import Path
import sys, zipfile, tempfile, subprocess, re, shutil
mode= sys.argv[1]
root=Path(sys.argv[2])
def edit(p,a,b,once=True):
    s=p.read_text()
    assert s.count(a)==1 if once else s.count(a)>0, (str(p),a[:100],s.count(a))
    p.write_text(s.replace(a,b,1 if once else -1))
if mode=="core":
    base=root/"base/src/main/java/com/windscribe/vpn"
    p=base/"Windscribe.kt"
    edit(p,"        reloadAfterMigration()","""        reloadAfterMigration()
        // 6.32: only Polaris plus explicitly chosen external apps use this VPN.
        preference.installedApps = (listOf(packageName) + preference.installedApps).distinct()
        preference.splitRoutingMode = com.windscribe.vpn.apppreference.PreferencesKeyConstants.INCLUSIVE_MODE
        preference.splitTunnelToggle = true
        preference.autoConnect = getSharedPreferences("polaris_vpn_631", 0).getBoolean("reconnect", false) && preference.globalUserConnectionPreference && !getSharedPreferences("polaris_vpn_631", 0).getBoolean("proxy_only", false)
        preference.savedProtocol = com.windscribe.vpn.apppreference.PreferencesKeyConstants.PROTO_WIRE_GUARD
        preference.wireGuardPort = "443"
        if (getSharedPreferences("polaris_vpn_631", 0).getBoolean("proxy_only", false)) { preference.autoStartOnBoot = false; preference.globalUserConnectionPreference = false }
""")
    # Keep upstream selected-app lists and hold/replacement policies. No allowBypass.
    p=base/"backend/tunnel/WindscribeVpnService.kt"
    edit(p, '    override fun requestTun(config: TunConfig): ParcelFileDescriptor {', '''    override fun requestTun(config: TunConfig): ParcelFileDescriptor {
        logger.info("Polaris 6.32 D1 sdk=" + android.os.Build.VERSION.SDK_INT)''')
    edit(p, '    companion object {', '''    companion object {
        @Volatile private var polarisService: WindscribeVpnService? = null
        @JvmStatic fun protectRadio(socket: java.net.Socket): Boolean =
            polarisService?.let { it.protect(socket) } ?: false''')
    edit(p, '        super.onCreate()', '        super.onCreate()\n        polarisService = this')
    edit(p, '        super.onDestroy()', '        if (polarisService === this) polarisService = null\n        super.onDestroy()')
    # Record only safe tunnel lifecycle metadata, never credentials or endpoint keys.
    edit(p, 'override fun protect(socketFd: Int): Boolean = super<VpnService>.protect(socketFd)', '''override fun protect(socketFd: Int): Boolean {
        val ok = super<VpnService>.protect(socketFd)
        android.util.Log.i("PolarisVpnCore", "protect result=" + ok)
        return ok
    }''')
    edit(p, 'logger.info("Engine \'$engineId\' stopped: $reason${detail?.let { " ($it)" } ?: ""}")', '''logger.info("Engine '$engineId' stopped: $reason${detail?.let { " ($it)" } ?: ""}")
        android.util.Log.i("PolarisVpnCore", "engine stopped: " + reason)''')
    edit(p, 'liveTun = established', '''liveTun = established
            android.util.Log.i("PolarisVpnCore", "TUN established")''')
    # Wait for actual WireGuard readiness; preserve upstream OpenVPN readiness.
    p=base/"backend/utils/WindVpnController.kt"
    edit(p, 'logger.info("WireGuard key limit reached with no screen to ask on; ending the attempt.")', '''logger.info("WireGuard key limit reached with no screen to ask on; ending the attempt.")
                        preferencesHelper.globalUserConnectionPreference = false''')
    # Native verdict remains authoritative for VPN; app route checks are separate.
    p=base/"backend/VpnBackend.kt"
    edit(p, 'withTimeout(15.seconds) {', 'withTimeout(25.seconds) {')
    edit(p, 'Connectivity test timeout: exceeded 15 seconds', 'Connectivity test timeout: exceeded 25 seconds')
    edit(p,'                        val testResult = wsNetWrapper.runTunnelTest(isBridgeAllowed)','''                        if (!polarisAwaitHandshake()) {
                            vpnLogger.error("Polaris: WireGuard handshake not ready")
                            failedConnectivityTest()
                            return@withTimeout
                        }
                        val diagnosticId = android.os.SystemClock.elapsedRealtime()
                        polarisDiagnosticLog(diagnosticId, "WSNet start bridgeAllowed=$isBridgeAllowed " + polarisDiagnosticStats())
                        val testResult = try {
                            wsNetWrapper.runTunnelTest(isBridgeAllowed)
                        } finally {
                            polarisDiagnosticLog(diagnosticId, "WSNet finished " + polarisDiagnosticStats())
                        }
                        polarisDiagnosticLog(diagnosticId, "WSNet result success=${testResult.success} ipv4Present=${testResult.ipv4.isNotBlank()} ipv6Present=${testResult.ipv6.isNotBlank()}")''')
    edit(p,'    fun updateState(vpnState: VPNState) {','''    protected open fun polarisDiagnosticStats(): String = "wgStats=unavailable"
    protected open suspend fun polarisAwaitHandshake(): Boolean = true

    private fun polarisDiagnosticLog(id: Long, msg: String) {
        val line = "6.32 probe=$id $msg"
        android.util.Log.i("PolarisVpnDiag", line)
        vpnLogger.info(line)
    }

    fun updateState(vpnState: VPNState) {''')
    p=base/"backend/wireguard/WireguardBackend.kt"
    edit(p,'        /** Our profiles carry exactly one peer. */','''        override suspend fun polarisAwaitHandshake(): Boolean {
            return kotlinx.coroutines.withTimeoutOrNull(8000L) {
                while (true) {
                    val stats = polarisDiagnosticStats()
                    if ((stats.substringAfter("handshakeEpochMs=", "0").toLongOrNull() ?: 0L) > 0L) break
                    kotlinx.coroutines.delay(200L)
                }
                true
            } ?: false
        }

        override fun polarisDiagnosticStats(): String =
            runCatching {
                val stats = engineBackendLazy.get().currentStatistics
                val peer = stats?.let { value -> value.peers().firstOrNull()?.let { value.peer(it) } }
                if (peer == null) "wgStats=unavailable" else
                    "wgRx=" + peer.rxBytes + " wgTx=" + peer.txBytes +
                    " handshakeEpochMs=" + peer.latestHandshakeEpochMillis
            }.getOrDefault("wgStats=error")

        /** Our profiles carry exactly one peer. */''')
    # Keep original Windscribe reconnect and boot service; boot and reconnect toggles are independent.
    p=base/"state/ConnectionPolicy.kt"
    edit(p, '            input.autoConnect && input.isAutoSecureOn != false -> true\n            else -> input.autoStartOnBoot', '            else -> input.autoStartOnBoot')
elif mode=="prune":
    aar=root/"windscribe-core/build/outputs/aar/windscribe-core.aar"
    # Only known non-WireGuard protocol native binaries; preserve shared WSNet.
    candidates={"libcharon.so","libimcv.so","libipsec.so","libopenvpn.so","libovpnexec.so","libovpnutil.so","libstrongswan.so","libtnccs.so","libtncif.so","libtpmtss.so"}
    with tempfile.TemporaryDirectory() as tmp:
        tmp=Path(tmp)
        with zipfile.ZipFile(aar) as z:
            libs={x.filename:x for x in z.infolist() if x.filename.endswith(".so") and x.filename.startswith("jni/")}
            removed={n for n in libs if Path(n).name in candidates}
            # Transitive DT_NEEDED and conservative direct filename string checks.
            changed=True
            while changed:
                changed=False
                for n in set(libs)-removed:
                    data=z.read(n)
                    f=tmp/Path(n).name
                    f.write_bytes(data)
                    needed=subprocess.run(["readelf","-d",str(f)],capture_output=True,text=True,check=True).stdout
                    for d in list(removed):
                        if Path(d).parent==Path(n).parent and (Path(d).name in needed or Path(d).name.encode() in data):
                            removed.remove(d); changed=True
            out=aar.with_suffix(".trimmed.aar")
            with zipfile.ZipFile(out,"w") as w:
                for e in z.infolist():
                    if e.filename not in removed:
                        w.writestr(e,z.read(e))
            print("Removed unused VPN binaries:", sorted(removed))
            print("Retained candidate dependencies:",sorted(n for n in libs if Path(n).name in candidates and n not in removed))
        out.replace(aar)
elif mode=="host":
    pkg=root/"app/src/main/java/com/dd3boh/outertune"
    g=root/"app/build.gradle.kts"
    edit(g,"versionCode = 3","versionCode = 100632")
    edit(g,'versionName = "0.03"','versionName = "6.32"')
    for p in [root/"app/src/main/AndroidManifest.xml",*list((root/"app/src").glob("*/res/values*/*.xml"))]:
        s=p.read_text()
        if "Polaris VPN 0.03" in s: p.write_text(s.replace("Polaris VPN 0.03","Polaris 6.32"))
    a=pkg/"polaris/PolarisVpnActivity.kt"
    edit(a,"Polaris VPN 0.03 · Windscribe / WireGuard","Polaris 6.32 · WireGuard 443")
    edit(a,"실험용 통합 설정. 음악 재생 전에 VPN 연결 상태를 확인하세요.","라디오 일반망 · 스트리밍 VPN")
    edit(a,"        prefs.globalUserConnectionPreference = true","""        prefs.autoConnect = routePrefs.getBoolean("reconnect", false)
        prefs.globalUserConnectionPreference = true""")
    edit(a,'lifecycleScope.launch { core.vpnController.turnOff(); show("VPN 연결 해제 요청") }','''lifecycleScope.launch {
                core.preference.autoConnect = false
                core.vpnController.turnOff()
                show("VPN 연결 해제")
            }''')
    edit(a,"                core.vpnController.turnOff()\n                core.userRepository.get().logout()","""                core.preference.autoConnect = false
                core.preference.autoStartOnBoot = false
                core.vpnController.turnOff()
                core.userRepository.get().logout()""")
    # Discover launchable installed apps without blanket QUERY_ALL_PACKAGES access.
    manifest=root/"app/src/main/AndroidManifest.xml"
    ms=manifest.read_text()
    query='''<intent><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent>'''
    if '<queries>' in ms:
        if 'android.intent.category.LAUNCHER' not in ms.split('<queries>',1)[1].split('</queries>',1)[0]:
            ms=ms.replace('<queries>','<queries>'+query,1)
    else:
        assert '</manifest>' in ms
        ms=ms.replace('</manifest>','<queries>'+query+'</queries>\n</manifest>',1)
    manifest.write_text(ms)
    edit(a,'        prefs.splitTunnelToggle = false','''        prefs.installedApps = (listOf(packageName) + prefs.installedApps).distinct()
        prefs.splitRoutingMode = PreferencesKeyConstants.INCLUSIVE_MODE
        prefs.splitTunnelToggle = vpnMode()''')
    p=pkg/"polaris/PolarisRouteIpProbe.kt"
    s=p.read_text()
    s=s.replace("import kotlinx.coroutines.Dispatchers",'''import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.windscribe.vpn.Windscribe
import com.windscribe.vpn.backend.VPNState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged''',1)
    anchor="    val factories = current.asStateFlow()"
    assert s.count(anchor)==1
    s=s.replace(anchor,anchor+'''

    private val verified = MutableStateFlow(false)
    val healthy = verified.asStateFlow()
    private val ticks = MutableStateFlow(0L)
    val refreshes = ticks.asStateFlow()
    private val networkChanges = MutableStateFlow(0L)
    private fun activeVpn(app: Windscribe): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
            app.vpnConnectionStateManager.state.value.status == VPNState.Status.Connected
    }

    fun observe(app: Windscribe) {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        runCatching { cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { networkChanges.value += 1 }
            override fun onLost(network: android.net.Network) { networkChanges.value += 1 }
        }) }
        // Refresh AA only when the verified icon changes. One delayed retry covers a host
        // that coalesces or caches the first invalidate; never refresh continuously at steady state.
        Windscribe.applicationScope.launch {
            healthy.collectLatest {
                ticks.value += 1
                delay(30_000)
                ticks.value += 1
            }
        }
        Windscribe.applicationScope.launch {
            combine(factories, app.vpnConnectionStateManager.state.map { it.status to it.connectionId }.distinctUntilChanged(), networkChanges) { f, state, _ ->
                f to state
            }.collectLatest { (f, state) ->
                verified.value = false
                if (f == null || state.first != VPNState.Status.Connected) return@collectLatest
                while (true) {
                    val started = android.os.SystemClock.elapsedRealtime()
                    val before = activeVpn(app)
                    val (r,m) = measure(f ?: return@collectLatest)
                    verified.value = before && activeVpn(app) && r.ip != null && m.ip != null && r.ip != m.ip
                    PolarisVpnReport.note("Streaming route check: " + verified.value +
                        "; radio=" + (r.ip ?: r.failure) + "; music=" + (m.ip ?: m.failure))
                    delay((30_000L - (android.os.SystemClock.elapsedRealtime() - started)).coerceAtLeast(0L))
                }
            }
        }
    }

    fun icon(): Int = if (healthy.value)
        com.dd3boh.outertune.R.drawable.polaris614_nav_streaming
    else com.dd3boh.outertune.R.drawable.polaris617_nav_streaming_off
''',1)
    p.write_text(s)
    p=pkg/"App.kt"
    edit(p,"        com.dd3boh.outertune.polaris.PolarisVpnReport.observe(this)","""        com.dd3boh.outertune.polaris.PolarisVpnReport.observe(this)
        com.dd3boh.outertune.polaris.PolarisRouteIpProbe.observe(this)
        com.dd3boh.outertune.polaris.PolarisResourceDiagnostics.install()""")
    (pkg/"polaris/PolarisResourceDiagnostics.kt").write_text(r'''package com.dd3boh.outertune.polaris

import android.os.Build
import android.os.StrictMode
import android.util.Log
import java.util.concurrent.Executors

/** Report leaked closeable/SQLite allocations, never terminate the process. */
internal object PolarisResourceDiagnostics {
    fun install() {
        if (Build.VERSION.SDK_INT < 28) return
        runCatching {
            val executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "PolarisResourceDiag").apply { isDaemon = true }
            }
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyListener(executor) { violation ->
                    // Keep only class/method/line information, not provider data or secrets.
                    val where = generateSequence<Throwable>(violation) { it.cause }
                        .take(3).joinToString(" | ") { error ->
                            error.javaClass.simpleName + ": " +
                                error.stackTrace.take(18).joinToString(" <- ") { frame ->
                                    frame.className + "." + frame.methodName + ":" + frame.lineNumber
                                }
                        }
                    Log.w("PolarisResource", where)
                    PolarisErrorReport.record("RESOURCE LEAK " + where)
                }.build())
        }.onFailure { Log.w("PolarisResource", "Resource diagnostics unavailable: " + it.javaClass.simpleName) }
    }
}
''')
    # Only CBS Music FM gets a new HTTPS endpoint. Preserve D1 DNS and socket factory.
    radio=pkg/"polaris/PolarisDirectRadioCalls.kt"
    edit(radio,"            client = OkHttpClient.Builder()",'''            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val url = request.url
                    val cbs939 = url.host in setOf("m-aac.cbs.co.kr", "aac.cbs.co.kr") &&
                        url.encodedPath.contains("cbs939.stream/playlist.m3u8") &&
                        request.method == "GET"
                    if (!cbs939) {
                        chain.proceed(request)
                    } else {
                        val primary = request.newBuilder()
                            .url("https://m-aac.cbs.co.kr/mweb_cbs939/_definst_/cbs939.stream/playlist.m3u8").build()
                        val backup = request.newBuilder()
                            .url("https://aac.cbs.co.kr/cbs939/_definst_/cbs939.stream/playlist.m3u8").build()
                        val first = try {
                            chain.proceed(primary)
                        } catch (e: IOException) {
                            if (chain.call().isCanceled()) throw e
                            PolarisVpnReport.note("CBS 939 HTTPS primary failed: " + e.javaClass.simpleName)
                            null
                        }
                        if (first != null && first.isSuccessful) {
                            first
                        } else {
                            if (first != null) {
                                PolarisVpnReport.note("CBS 939 HTTPS primary HTTP " + first.code)
                                first.close()
                            }
                            PolarisVpnReport.note("CBS 939 HTTPS backup requested")
                            chain.proceed(backup)
                        }
                    }
                }''')
    # Update literal legacy endpoints too so the media item/resolver uses HTTPS from the start.
    cbs_re = re.compile(r'https?://(?:m-aac|aac)\.cbs\.co\.kr/[^"\s<>]*cbs939\.stream/playlist\.m3u8')
    for source in (pkg/"polaris").rglob("*"):
        if source == radio or source.suffix not in (".kt", ".java"): continue
        old=source.read_text()
        new=cbs_re.sub("https://m-aac.cbs.co.kr/mweb_cbs939/_definst_/cbs939.stream/playlist.m3u8",old)
        if old!=new:
            source.write_text(new)
            print("CBS HTTPS source:",source.name)
    p=pkg/"playback/AutoCar.kt"
    edit(p,"Polaris613Nav.STREAM -> carContext.icon(R.drawable.polaris614_nav_streaming)","Polaris613Nav.STREAM -> carContext.icon(com.dd3boh.outertune.polaris.PolarisRouteIpProbe.icon())")
    edit(p,"""    private var tabs: List<MediaItem>? = null""","""    private var tabs: List<MediaItem>? = null

    init {
        lifecycleScope.launch {
            com.dd3boh.outertune.polaris.PolarisRouteIpProbe.refreshes.collect { invalidate() }
        }
    }""")
    p=pkg/"playback/MediaLibrarySessionCallback.kt"
    edit(p,"drawableUri(R.drawable.polaris614_nav_streaming)","drawableUri(com.dd3boh.outertune.polaris.PolarisRouteIpProbe.icon())",False)
    p=pkg/"playback/MusicService.kt"
    edit(p,"        com.dd3boh.outertune.polaris.PolarisRouteIpProbe.attach(polarisProbeRadioCalls, playerCalls)","""        com.dd3boh.outertune.polaris.PolarisRouteIpProbe.attach(polarisProbeRadioCalls, playerCalls)
        scope.launch {
            com.dd3boh.outertune.polaris.PolarisRouteIpProbe.refreshes.collect {
                runCatching { mediaSession.notifyChildrenChanged(ROOT, Int.MAX_VALUE, null) }
            }
        }""")
    draw=root/"app/src/main/res/drawable/polaris617_nav_streaming_off.xml"
    draw.write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#00000000" android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.8" android:strokeLineCap="round"
        android:strokeLineJoin="round" android:pathData="M8,17 L8,5 L19,2 L19,14 M8,17 C6,15 2,16 2,19 C2,22 8,21 8,18 M19,14 C17,12 13,13 13,16 C13,19 19,18 19,15"/>
    <path android:fillColor="#00000000" android:strokeColor="#FF17191E" android:strokeWidth="3.8" android:pathData="M3,3 L21,21 M21,3 L3,21"/>
    <path android:fillColor="#00000000" android:strokeColor="#FFFFFFFF" android:strokeWidth="1.8" android:strokeLineCap="round" android:pathData="M3,3 L21,21 M21,3 L3,21"/>
</vector>
''')
    print("6.32: original production package, local data retained, auto-connect/boot, route indicator and phone UI restored")
else:
    raise ValueError(mode)
