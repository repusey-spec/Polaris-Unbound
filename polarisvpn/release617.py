#!/usr/bin/env python3
"""6.17 production candidate. Preserve the tested 0.03 application ID and account."""
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
        // Migrate the tested installation's last explicit connection choice.
        if (preference.sessionHash != null && preference.globalUserConnectionPreference) {
            preference.autoConnect = true
            preference.autoStartOnBoot = true
        }""")
    p=base/"state/ConnectionPolicy.kt"
    s=p.read_text()
    start=s.index("    fun blockingApplies(input: PolicyInput): Boolean =")
    end=s.index("    /**",start)
    s=s[:start]+"""    // Polaris radio must remain on the physical network even during recovery.
    // Android system lockdown remains a separate user setting and must be off.
    fun blockingApplies(input: PolicyInput): Boolean = false

"""+s[end:]
    s=s.replace("input.isAutoSecureOn ?: !input.canReadNetworkName","input.isAutoSecureOn ?: true")
    p.write_text(s)
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
    edit(g,"versionCode = 3","versionCode = 100617")
    edit(g,'versionName = "0.03"','versionName = "6.17"')
    for p in [root/"app/src/main/AndroidManifest.xml",*list((root/"app/src").glob("*/res/values*/*.xml"))]:
        s=p.read_text()
        if "Polaris VPN 0.03" in s: p.write_text(s.replace("Polaris VPN 0.03","Polaris 6.17"))
    a=pkg/"polaris/PolarisVpnActivity.kt"
    edit(a,"Polaris VPN 0.03 · Windscribe / WireGuard","Polaris 6.17 · Windscribe / WireGuard")
    edit(a,"실험용 통합 설정. 음악 재생 전에 VPN 연결 상태를 확인하세요.","최초 연결 후 자동 재연결 · 라디오는 직접 통신")
    edit(a,"        prefs.globalUserConnectionPreference = true","""        prefs.autoConnect = true
        prefs.autoStartOnBoot = true
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
    p=pkg/"polaris/PolarisShell.kt"
    s=p.read_text()
    start=s.index("    val factories by PolarisRouteIpProbe.factories.collectAsState()")
    end=s.index("\n    }",s.index("                    Text(if(value)radioIp else musicIp",start))+6
    s=s[:start]+'''    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=12.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        listOf(true to "Radio",false to "Streaming").forEach{(value,label)->
            Button(modifier=Modifier.weight(1f),onClick={onSelect(value)},colors=ButtonDefaults.buttonColors(
                containerColor=if(radio==value)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor=if(radio==value)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )){Text(label)}
        }
    }'''+s[end:]
    p.write_text(s)
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
                    verified.value = before && activeVpn(app) && r.ip != null && m.ip != null && r.ip != m.ip
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
    print("6.17: same package, session retained, auto-connect/boot, route indicator and phone UI restored")
else:
    raise ValueError(mode)
