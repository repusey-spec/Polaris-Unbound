#!/usr/bin/env python3
"""6.30 production candidate. Preserve original Polaris production application ID and its existing local data."""
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
        // 6.30: only Polaris plus explicitly chosen external apps use this VPN.
        val polarisMode = getSharedPreferences("polaris_vpn_630", 0).getString("mode", "A") ?: "A"
        preference.installedApps = (listOf(packageName) + preference.installedApps).distinct()
        preference.splitRoutingMode = com.windscribe.vpn.apppreference.PreferencesKeyConstants.INCLUSIVE_MODE
        preference.splitTunnelToggle = (polarisMode == "C" || polarisMode.startsWith("D"))
        if (!(polarisMode == "C" || polarisMode?.startsWith("D") == true)) preference.globalUserConnectionPreference = false
        preference.autoConnect = false
        preference.autoStartOnBoot = false""")
    # Keep upstream selected-app lists and hold/replacement policies. No allowBypass.
    p=base/"backend/tunnel/WindscribeVpnService.kt"
    edit(p, '    override fun requestTun(config: TunConfig): ParcelFileDescriptor {', '''    override fun requestTun(config: TunConfig): ParcelFileDescriptor {
        val polarisMode = getSharedPreferences("polaris_vpn_630", 0).getString("mode", "A")
        if (!(polarisMode == "C" || polarisMode?.startsWith("D") == true)) throw TunEstablishException("VPN disabled in this Polaris mode")
        logger.info("Polaris 6.30 mode=" + polarisMode + " sdk=" + android.os.Build.VERSION.SDK_INT)''')
    edit(p, '    companion object {', '''    companion object {
        @Volatile private var polarisService: WindscribeVpnService? = null
        @JvmStatic fun protectRadio(socket: java.net.Socket): Boolean =
            polarisService?.let { it.protect(socket) } ?: false
        @JvmStatic fun protectRadioFd(fd: Int): Boolean =
            polarisService?.let { it.protect(fd) } ?: false''')
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
    edit(p, '            excludedIpHolder.applyExcludedRoutes(builder)', '''            excludedIpHolder.applyExcludedRoutes(builder)
            if (polarisMode == "D4") {
                if (android.os.Build.VERSION.SDK_INT < 33) throw TunEstablishException("D4 requires Android 13")
                val map = org.json.JSONObject(getSharedPreferences("polaris_vpn_630", 0).getString("d4_map", "{}") ?: "{}")
                val ips = mutableSetOf<String>()
                val keys = map.keys()
                while (keys.hasNext()) {
                    val arr = map.getJSONArray(keys.next())
                    for (i in 0 until arr.length()) ips.add(arr.getString(i))
                }
                if (ips.isEmpty()) throw TunEstablishException("D4 has no resolved destinations")
                for (ip in ips) {
                    val addr = android.net.InetAddresses.parseNumericAddress(ip)
                    if (addr !is java.net.Inet4Address) throw TunEstablishException("D4 requires IPv4")
                    builder.excludeRoute(android.net.IpPrefix(addr, 32))
                }
                logger.info("Polaris D4 excluded IPv4 count=" + ips.size)
            }''')
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
                        startPolarisDiagnostics(diagnosticId)
                        val testResult = try {
                            wsNetWrapper.runTunnelTest(isBridgeAllowed)
                        } finally {
                            polarisDiagnosticLog(diagnosticId, "WSNet finished " + polarisDiagnosticStats())
                        }
                        polarisDiagnosticLog(diagnosticId, "WSNet result success=${testResult.success} ipv4Present=${testResult.ipv4.isNotBlank()} ipv6Present=${testResult.ipv6.isNotBlank()}")''')
    edit(p,'    fun updateState(vpnState: VPNState) {','''    protected open fun polarisDiagnosticStats(): String = "wgStats=unavailable"
    protected open suspend fun polarisAwaitHandshake(): Boolean = true

    private fun polarisDiagnosticLog(id: Long, msg: String) {
        val line = "6.30 probe=$id $msg"
        android.util.Log.i("PolarisVpnDiag", line)
        vpnLogger.info(line)
    }

    // One bounded, read-only diagnostic worker per native test. No process-wide network binding,
    // no protect() on probe sockets, no success override, no retry of the actual connection.
    private fun startPolarisDiagnostics(id: Long) {
        Thread {
            try {
                val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
                val active = cm.activeNetwork
                val bound = cm.boundNetworkForProcess
                val vpn = cm.allNetworks.firstOrNull { n ->
                    cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                } ?: active?.takeIf { n ->
                    cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                }
                val lp = vpn?.let { cm.getLinkProperties(it) }
                val endpoint = java.net.InetAddress.getByName("10.255.255.1")
                polarisDiagnosticLog(id, "sdk=" + android.os.Build.VERSION.SDK_INT + " active=" + active +
                    " bound=" + bound + " vpn=" + vpn + " iface=" + lp?.interfaceName +
                    " dns=" + lp?.dnsServers?.joinToString { it.hostAddress.orEmpty() } +
                    " defaultRoute=" + lp?.routes?.any { it.isDefaultRoute })
                fun tcp(label: String, network: android.net.Network?) {
                    val started = android.os.SystemClock.elapsedRealtime()
                    try {
                        val socket = network?.socketFactory?.createSocket() ?: java.net.Socket()
                        socket.use { it.connect(java.net.InetSocketAddress(endpoint, 19800), 1800) }
                        polarisDiagnosticLog(id, "$label TCP19800 OK ms=" + (android.os.SystemClock.elapsedRealtime()-started))
                    } catch (e: Exception) {
                        polarisDiagnosticLog(id, "$label TCP19800 FAIL " + e.javaClass.simpleName +
                            " ms=" + (android.os.SystemClock.elapsedRealtime()-started))
                    }
                }
                fun dns(label: String, network: android.net.Network?) {
                    val started = android.os.SystemClock.elapsedRealtime()
                    try {
                        // DNS A example.com, with a fixed per-socket ID. No user or account data.
                        val query = byteArrayOf(0x62,0x26,1,0,0,1,0,0,0,0,0,0,
                            7,101,120,97,109,112,108,101,3,99,111,109,0,0,1,0,1)
                        java.net.DatagramSocket(null as java.net.SocketAddress?).use { sock ->
                            network?.bindSocket(sock)
                            sock.bind(java.net.InetSocketAddress(0))
                            sock.soTimeout = 1800
                            sock.connect(endpoint, 53)
                            sock.send(java.net.DatagramPacket(query, query.size))
                            val reply = ByteArray(512)
                            val packet = java.net.DatagramPacket(reply, reply.size)
                            sock.receive(packet)
                            val valid = packet.length >= 12 && reply[0] == query[0] && reply[1] == query[1] &&
                                (reply[2].toInt() and 128) != 0
                            val rcode = if (valid) reply[3].toInt() and 15 else -1
                            polarisDiagnosticLog(id, "$label UDP53 reply=$valid rcode=$rcode bytes=" + packet.length +
                                " ms=" + (android.os.SystemClock.elapsedRealtime()-started))
                        }
                    } catch (e: Exception) {
                        polarisDiagnosticLog(id, "$label UDP53 FAIL " + e.javaClass.simpleName +
                            " ms=" + (android.os.SystemClock.elapsedRealtime()-started))
                    }
                }
                if (vpn == null) {
                    polarisDiagnosticLog(id, "No visible VPN Network; explicit probes skipped")
                } else {
                    tcp("VPN_BOUND", vpn)
                    tcp("DEFAULT", null)
                    dns("VPN_BOUND", vpn)
                    dns("DEFAULT", null)
                    // Independent HTTPS check, only on the explicit VPN network.
                    try {
                        val c = vpn.openConnection(java.net.URL("https://api.ipify.org/")) as javax.net.ssl.HttpsURLConnection
                        try {
                            c.connectTimeout = 2000
                            c.readTimeout = 2000
                            c.instanceFollowRedirects = false
                            val code = c.responseCode
                            val ip = if (code == 200) c.inputStream.bufferedReader().use { it.readLine()?.take(64).orEmpty() } else ""
                            polarisDiagnosticLog(id, "VPN_BOUND HTTPS code=$code validIp=" + Util.validIpAddress(ip) + " exit=" + (if (Util.validIpAddress(ip)) ip else "invalid"))
                        } finally {
                            c.disconnect()
                        }
                    } catch (e: Exception) {
                        polarisDiagnosticLog(id, "VPN_BOUND HTTPS FAIL " + e.javaClass.simpleName)
                    }
                }
                polarisDiagnosticLog(id, "probes complete " + polarisDiagnosticStats())
            } catch (e: Exception) {
                polarisDiagnosticLog(id, "probe worker FAIL " + e.javaClass.simpleName)
            }
        }.apply {
            name = "PolarisVpnDiag"
            isDaemon = true
        }.start()
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
    p=base/"wsnet/WSNetWrapper.kt"
    edit(p,'        if (isWsNetNoise(message)) {','''        // Categorize native phases without echoing tokens, URLs, or credentials.
        val phase = when {
            message.contains("DNS never resolved", ignoreCase = true) -> "dns-never-resolved"
            message.contains("dns: failed", ignoreCase = true) -> "dns-failed"
            message.contains("BridgeTokenRequest", ignoreCase = true) -> "bridge-token"
            message.contains("Tunnel test", ignoreCase = true) -> "tunnel-test"
            message.contains("BridgeAPI_impl", ignoreCase = true) -> "bridge-api"
            else -> null
        }
        if (phase != null) android.util.Log.i("PolarisVpnDiag", "WSNet native phase=$phase level=$level")
        if (isWsNetNoise(message)) {''')
    # Keep upstream ConnectionPolicy unchanged.
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
    edit(g,"versionCode = 3","versionCode = 100630")
    edit(g,'versionName = "0.03"','versionName = "6.30"')
    for p in [root/"app/src/main/AndroidManifest.xml",*list((root/"app/src").glob("*/res/values*/*.xml"))]:
        s=p.read_text()
        if "Polaris VPN 0.03" in s: p.write_text(s.replace("Polaris VPN 0.03","Polaris 6.30"))
    a=pkg/"polaris/PolarisVpnActivity.kt"
    edit(a,"Polaris VPN 0.03 · Windscribe / WireGuard","Polaris 6.30 · WireGuard 443")
    edit(a,"실험용 통합 설정. 음악 재생 전에 VPN 연결 상태를 확인하세요.","A–D 비교 진단 · 모드 변경은 VPN 연결 해제 후")
    edit(a,"        prefs.globalUserConnectionPreference = true","""        prefs.autoConnect = false
        prefs.autoStartOnBoot = false
        prefs.globalUserConnectionPreference = true""")
    edit(a,'lifecycleScope.launch { core.vpnController.turnOff(); show("VPN 연결 해제 요청") }','''lifecycleScope.launch {
                core.preference.autoConnect = false
                core.preference.autoStartOnBoot = false
                core.vpnController.turnOff()
                show("VPN 연결 및 자동 연결 해제")
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
    # Use a separate IP probe hostname for destination exclusion; the music probe is never excluded.
    p=pkg/"polaris/PolarisRouteIpProbe.kt"
    edit(p, 'private fun read(factory: Call.Factory): Result', 'private fun read(factory: Call.Factory, radio: Boolean = false): Result')
    edit(p, 'val request = Request.Builder().url("https://api.ipify.org/")', '''val host = if (radio && PolarisRadioNetwork.mode() == "D4") "https://checkip.amazonaws.com/" else "https://api.ipify.org/"
            val request = Request.Builder().url(host)''')
    edit(p, 'read(pair.radio)', 'read(pair.radio, true)')
    # 6.30: keep original 0.03 phone IP text from apply_route_ip.py.
    # Do not change AA icons, VPN policy, or media transport.
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
    private fun activeVpn(app: Windscribe): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
            app.vpnConnectionStateManager.state.value.status == VPNState.Status.Connected
    }

    fun observe(app: Windscribe) {
        Windscribe.applicationScope.launch {
            combine(factories, app.vpnConnectionStateManager.state.map { it.status to it.connectionId }.distinctUntilChanged()) { f, state ->
                f to state
            }.collectLatest { (f, state) ->
                verified.value = false
                if (f == null || state.first != VPNState.Status.Connected) return@collectLatest
                while (true) {
                    val before = activeVpn(app)
                    val (r,m) = measure(f)
                    val mode = app.getSharedPreferences("polaris_vpn_630", 0).getString("mode", "A")
                    verified.value = before && activeVpn(app) && r.ip != null && m.ip != null &&
                        (if (mode?.startsWith("D") == true) r.ip != m.ip else r.ip == m.ip)
                    PolarisVpnReport.note("Streaming route check: " + verified.value +
                        "; radio=" + (r.ip ?: r.failure) + "; music=" + (m.ip ?: m.failure))
                    delay(30_000)
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
        com.dd3boh.outertune.polaris.PolarisRouteIpProbe.observe(this)""")
    p=pkg/"playback/AutoCar.kt"
    edit(p,"Polaris613Nav.STREAM -> carContext.icon(R.drawable.polaris614_nav_streaming)","Polaris613Nav.STREAM -> carContext.icon(com.dd3boh.outertune.polaris.PolarisRouteIpProbe.icon())")
    edit(p,"""    private var tabs: List<MediaItem>? = null""","""    private var tabs: List<MediaItem>? = null

    init {
        lifecycleScope.launch {
            com.dd3boh.outertune.polaris.PolarisRouteIpProbe.healthy.collect { invalidate() }
        }
    }""")
    p=pkg/"playback/MediaLibrarySessionCallback.kt"
    edit(p,"drawableUri(R.drawable.polaris614_nav_streaming)","drawableUri(com.dd3boh.outertune.polaris.PolarisRouteIpProbe.icon())",False)
    p=pkg/"playback/MusicService.kt"
    edit(p,"        com.dd3boh.outertune.polaris.PolarisRouteIpProbe.attach(polarisProbeRadioCalls, playerCalls)","""        com.dd3boh.outertune.polaris.PolarisRouteIpProbe.attach(polarisProbeRadioCalls, playerCalls)
        scope.launch {
            com.dd3boh.outertune.polaris.PolarisRouteIpProbe.healthy.collect {
                runCatching { mediaSession.notifyChildrenChanged(ROOT, Int.MAX_VALUE, null) }
            }
        }""")
    draw=root/"app/src/main/res/drawable/polaris617_nav_streaming_off.xml"
    draw.write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#00000000" android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.8" android:strokeLineCap="round"
        android:strokeLineJoin="round" android:pathData="M2,9 L7,14 M7,9 L2,14 M15,4 L15,18 M15,18 C13,16 9,17 9,20 C9,22 15,22 15,19 M15,4 L22,8"/>
</vector>
''')
    print("6.30: original production package, local data retained, auto-connect/boot, route indicator and phone UI restored")
else:
    raise ValueError(mode)
