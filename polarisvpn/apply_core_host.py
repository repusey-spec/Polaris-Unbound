#!/usr/bin/env python3
"""Integrate the compiled Windscribe core into the reconstructed Polaris 6.16.
Experimental build only: requires compile and runtime checks before delivery.
"""
from pathlib import Path
import sys, shutil, re
root=Path(sys.argv[1])
ws=Path(sys.argv[2])
pkg=root/"app/src/main/java/com/dd3boh/outertune"
def replace(p, old, new):
    s=p.read_text()
    assert s.count(old)==1, f"{p}: expected one match: {old[:80]}"
    p.write_text(s.replace(old,new,1))
appgrad=root/"app/build.gradle.kts"
assert 'applicationId = "app.hush.music.polarisradio.debug"' in appgrad.read_text(), "Original Polaris application ID required"
replace(appgrad,'versionCode = 100616','versionCode = 3')
replace(appgrad,'versionName = "6.16"','versionName = "0.03"')
# Flip7 test: only ARM64; compress native libraries in the delivered APK.
replace(appgrad,'    defaultConfig {','    defaultConfig {\n        ndk { abiFilters.clear(); abiFilters.addAll(listOf("POLARIS_ABI")) }')
appgrad.write_text(appgrad.read_text().replace('POLARIS_ABI', sys.argv[3].replace(',', '", "')))
with appgrad.open("a") as f:
    f.write("\nandroid { packaging { jniLibs { useLegacyPackaging = true } } }\n")
lib=root/"app/libs"
lib.mkdir(exist_ok=True)
for f in ("windscribe-core.aar","windscribe-core-dependencies.gradle"):
    shutil.copy2(ws/"windscribe-core/build/outputs/aar"/f, lib/f)
with appgrad.open("a") as f:
    f.write('\n dependencies { implementation(files("libs/windscribe-core.aar")) }\napply(from = "libs/windscribe-core-dependencies.gradle")\n')
# The original list already accepts LGPL-2.1 under its long-form name.
# JNA's POM uses the shorter alias. JUnit's POM names EPL-1.0.
# Preserve strict mode, dependency notices and license metadata for this private test.
a=appgrad.read_text()
assert a.count('allowedLicenses.addAll(')==1
a=a.replace('allowedLicenses.addAll(', 'allowedLicenses.addAll("LGPL, version 2.1", "EPL-1.0", "Eclipse Public License 1.0", ', 1)
appgrad.write_text(a)
toml=root/"gradle/libs.versions.toml"
for old,new in [('kotlin = "2.2.21"','kotlin = "2.3.21"'),('hilt = "2.57.2"','hilt = "2.59.2"'),('ksp = "2.3.1"','ksp = "2.3.9"')]:
    replace(toml,old,new)
# Hilt 2.59 requires AGP 9.0+, per the upstream 2.59 release notes.
# Keep the existing project's legacy Kotlin and variant DSL during this test.
replace(toml,'androidGradlePlugin = "8.11.1"','androidGradlePlugin = "9.0.0"')
props=root/"gradle.properties"
with props.open("a") as f:
    f.write("\nandroid.builtInKotlin=false\nandroid.newDsl=false\nandroid.r8.optimizedResourceShrinking=false\n")
# Preserve all existing App initialization, but use Windscribe's application base
# so the inherited core fields are initialized by the single host Hilt graph.
app=pkg/"App.kt"
replace(app,'class App : Application(), SingletonImageLoader.Factory {','''class App : com.windscribe.vpn.Windscribe(), SingletonImageLoader.Factory,
    com.windscribe.vpn.Windscribe.ApplicationInterface, androidx.work.Configuration.Provider {
    @javax.inject.Inject lateinit var vpnWorkerFactory: androidx.hilt.work.HiltWorkerFactory
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setWorkerFactory(vpnWorkerFactory).build()
    override val homeIntent: android.content.Intent
        get() = android.content.Intent(this, MainActivity::class.java)
    override val splashIntent: android.content.Intent get() = homeIntent
    override val welcomeIntent: android.content.Intent
        get() = android.content.Intent(this, com.dd3boh.outertune.polaris.PolarisVpnActivity::class.java)
    override val upgradeIntent: android.content.Intent get() = welcomeIntent
    override val isTV: Boolean get() = false
    override fun setTheme() {}
    override fun launchFragment(
        protocolInformationList: List<com.windscribe.vpn.autoconnection.ProtocolInformation>,
        fragmentType: com.windscribe.vpn.autoconnection.FragmentType,
        autoConnectionModeCallback: com.windscribe.vpn.autoconnection.AutoConnectionModeCallback,
        protocolInformation: com.windscribe.vpn.autoconnection.ProtocolInformation?
    ): Boolean {
        android.util.Log.w("PolarisVpnRoute", "Windscribe connection event: " + fragmentType)
        return false
    }''')
replace(app,'        super.onCreate()','''        com.windscribe.vpn.Windscribe.appContext = this
        applicationInterface = this
        super.onCreate()
        com.dd3boh.outertune.polaris.PolarisVpnReport.observe(this)''')
# Preserve the original proxy initializer in Proxy mode only; record effective settings.
replace(app,'ProxyAtStart.setting = ProxyAtStart.of(dataStore[ProxyEnabledKey], dataStore[ProxyTypeKey], dataStore[ProxyUrlKey])','''val polarisProxyMode = getSharedPreferences("polaris_vpn_631", 0).getBoolean("proxy_only", false)
        ProxyAtStart.setting = ProxyAtStart.of(polarisProxyMode && dataStore[ProxyEnabledKey] == true, dataStore[ProxyTypeKey], dataStore[ProxyUrlKey])
        android.util.Log.i("PolarisVpnRoute", "Proxy configured=" + (dataStore[ProxyEnabledKey] == true) + " modeAllows=" + polarisProxyMode)''')
replace(app,'if (dataStore[ProxyEnabledKey] == true) {','if (polarisProxyMode && dataStore[ProxyEnabledKey] == true) {')
replace(app,'ProxySelector.setDefault(AppProxySelector(proxy))','ProxySelector.setDefault(AppProxySelector(proxy))\n                android.util.Log.i("PolarisVpnRoute", "InterTune proxy applied=true")')
manifest=root/"app/src/main/AndroidManifest.xml"
s=manifest.read_text()
# The test profile contains VPN login secrets; do not allow Android backup.
s=s.replace('android:allowBackup="true"','android:allowBackup="false"')
s=re.sub(r'\s+android:dataExtractionRules="[^"]+"','',s,count=1)
s=s.replace('android:fullBackupContent="@xml/backup_rules"','android:fullBackupContent="false"')
s=s.replace('android:supportsRtl="true"','android:supportsRtl="true" tools:replace="android:supportsRtl"')
assert '        <activity\n' in s
s=s.replace('        <activity\n','''        <activity android:name=".polaris.PolarisVpnActivity"
            android:exported="false" android:label="Polaris VPN"
            android:theme="@style/Theme.AppCompat.Light.NoActionBar" />
        <provider android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false" tools:node="merge">
            <meta-data android:name="androidx.work.WorkManagerInitializer"
                tools:node="remove" />
        </provider>
        <activity
'''.replace('\\$','$'),1)
s=s.replace('android:label="@string/app_name"','android:label="Polaris VPN 0.03"')
# Force the launcher activity label too, independently of resource overrides.
s,n=re.subn(r'(<activity\b[^>]*android:name="\.MainActivity")',r'\1 android:label="Polaris VPN 0.03"',s,count=1)
assert n==1, "Launcher activity missing"
# App-screen boot is separate from the upstream VPN BootWorker.
s=s.replace('<application','<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n    <application',1) if 'android.permission.RECEIVE_BOOT_COMPLETED' not in s else s
s=s.replace('<application','<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n    <application',1) if 'android.permission.POST_NOTIFICATIONS' not in s else s
s=s.replace('        <activity android:name=".polaris.PolarisVpnActivity"', '''        <receiver android:name=".polaris.PolarisBootReceiver" android:exported="false">
            <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>
        </receiver>
        <activity android:name=".polaris.PolarisVpnActivity"''',1)
manifest.write_text(s)
(pkg/"polaris/PolarisBootReceiver.kt").write_text(r'''package com.dd3boh.outertune.polaris

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.windscribe.vpn.Windscribe

class PolarisBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!context.getSharedPreferences("polaris_vpn_631", 0).getBoolean("app_boot", false)) return
        val launch = (context.applicationContext as Windscribe).applicationInterface.homeIntent
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Android 10+ may silently reject background activity launches; use a regular
        // notification as fallback rather than a full-screen or overlay workaround.
        runCatching { context.startActivity(launch) }
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("polaris_boot", "Polaris 자동 실행", NotificationManager.IMPORTANCE_LOW))
            val pending = PendingIntent.getActivity(context, 631, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(631, Notification.Builder(context, "polaris_boot")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Polaris")
                .setContentText("탭하여 폴라리스 실행")
                .setContentIntent(pending).setAutoCancel(true).build())
        }
    }
}
''')
# Preserve the existing UI, add one settings entry.
settings=pkg/"ui/screens/settings/SettingsScreen.kt"
s=settings.read_text()
anchor='    val focus = LocalFocusManager.current'
assert anchor in s
s=s.replace(anchor,'    val vpnContext = androidx.compose.ui.platform.LocalContext.current\n'+anchor,1)
anchor='    val you: @Composable () -> Unit = {'
assert anchor in s
s=s.replace(anchor,anchor+'''
        androidx.compose.material3.TextButton(onClick = {
            vpnContext.startActivity(android.content.Intent(vpnContext,
                com.dd3boh.outertune.polaris.PolarisVpnActivity::class.java))
        }) { Text("Windscribe VPN") }
''',1)
settings.write_text(s)
# Rename visible app_name resources without changing unrelated strings.
for p in (root/"app/src").glob("*/res/values*/*.xml"):
    s=p.read_text()
    s2=re.sub(r'(<string name="app_name"[^>]*>)[^<]*(</string>)',r'\g<1>Polaris VPN 0.03\g<2>',s)
    if s!=s2:p.write_text(s2)
# Hilt core's shared network routing must never leak radio via the VPN.
# Only radio socket calls are explicitly bound; other app requests use Android's default network.
report=pkg/"polaris/PolarisVpnReport.kt"
report.write_text(r'''package com.dd3boh.outertune.polaris

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.dd3boh.outertune.utils.ReportTrail
import com.windscribe.vpn.Windscribe
import kotlinx.coroutines.launch

internal object PolarisVpnReport {
    fun note(value: String) {
        android.util.Log.i("PolarisVpnRoute", value)
        ReportTrail.thisRun.setting("VPN routing", value)
    }
    fun snapshot(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val dns = active?.let { cm.getLinkProperties(it) }?.dnsServers?.size ?: 0
        return "Default VPN=" + (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) +
            ", DNS servers=" + dns + ", music=default network, radio=protected physical"
    }
    fun observe(app: Windscribe) {
        Windscribe.applicationScope.launch {
            app.vpnConnectionStateManager.state.collect {
                note("Windscribe state=" + it.status + "; " + snapshot(app))
            }
        }
    }
}
''')
radio=pkg/"polaris/PolarisDirectRadioCalls.kt"
s=radio.read_text()
s=s.replace('android.util.Log.i("PolarisVpnRoute", "Radio media transport: physical network=" + n)',
'''PolarisVpnReport.note("Radio media: physical network=" + n +
                ", DNS servers=" + (cm.getLinkProperties(n)?.dnsServers?.size ?: 0))''')
radio.write_text(s)
# Existing station-address resolution and program-guide fetches must also bypass VPN.
for file, pairs in {
    "PolarisRadioResolver.kt":[
        ("URL(endpoint).openConnection()", "PolarisRadioNetwork.openConnection(URL(endpoint))")
    ],
    "CurrentProgramResolver.java":[
        ("new URL(endpoint).openConnection()", "PolarisRadioNetwork.openConnection(new URL(endpoint))"),
        ("new URL(url).openConnection()", "PolarisRadioNetwork.openConnection(new URL(url))")
    ]
}.items():
    p=pkg/"polaris"/file
    for a,b in pairs:
        replace(p,a,b)
network=pkg/"polaris/PolarisRadioNetwork.kt"
network.write_text(r'''package com.dd3boh.outertune.polaris

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.windscribe.vpn.Windscribe
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

object PolarisRadioNetwork {
    fun vpnActive(): Boolean {
        val cm = Windscribe.appContext.getSystemService(ConnectivityManager::class.java)
        return cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
    }

    private val radioCalls by lazy { PolarisDirectRadioCalls(Windscribe.appContext) }

    private fun caps(cm: ConnectivityManager, n: Network): NetworkCapabilities? =
        cm.getNetworkCapabilities(n)?.takeIf {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
             it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
             it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }

    fun physical(context: Context): Network {
        val cm=context.getSystemService(ConnectivityManager::class.java)
        cm.activeNetwork?.let { if (caps(cm,it)!=null) return it }
        return cm.allNetworks.mapNotNull { n -> caps(cm,n)?.let { n to it } }
            .filter { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .sortedWith(compareByDescending<Pair<Network,NetworkCapabilities>> {
                it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }.thenByDescending {
                it.second.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            })
            .firstOrNull()?.first ?: throw IOException("No physical radio network")
    }

    @JvmStatic
    @Throws(IOException::class)
    fun openConnection(url: URL): HttpURLConnection {
        val context=Windscribe.appContext
        val n=physical(context)
        val cm=context.getSystemService(ConnectivityManager::class.java)
        PolarisVpnReport.note("Radio address/guide: physical network="+n+
            ", DNS servers="+(cm.getLinkProperties(n)?.dnsServers?.size ?: 0))
        return PolarisRadioHttpConnection(url, radioCalls)
    }
}
''')
# Bind every radio path via the same physical-network selector.
s=radio.read_text()
s=re.sub(r'    private fun physical\(n: Network\).*?(?=    override fun newCall)', 
    '''    private fun directNetwork(): Network = PolarisRadioNetwork.physical(Windscribe.appContext)

''',s,flags=re.S)
assert "PolarisRadioNetwork.physical" in s
s=s.replace("import okhttp3.Call","import com.windscribe.vpn.Windscribe\nimport okhttp3.Call")
s=s.replace("    private var selected: Network? = null", "    private var selected: Network? = null\n    private var selectedVpn: Boolean? = null")
s=s.replace("if (selected != n || client == null)", "if (selected != n || client == null || selectedVpn != PolarisRadioNetwork.vpnActive())")
s=s.replace("            selected = n", "            selected = n\n            selectedVpn = PolarisRadioNetwork.vpnActive()")
s=s.replace(".socketFactory(n.socketFactory)", ".socketFactory(PolarisProtectedSocketFactory(n))")
radio.write_text(s)
(pkg/"polaris/PolarisProtectedSocketFactory.kt").write_text(r'''package com.dd3boh.outertune.polaris
import android.net.Network
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.windscribe.vpn.Windscribe
import com.windscribe.vpn.backend.tunnel.WindscribeVpnService
import java.net.Socket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.io.IOException
import javax.net.SocketFactory

internal class PolarisProtectedSocketFactory(private val network: Network): SocketFactory() {
    override fun createSocket(): Socket {
        val s = Socket()
        try {
            // setTcpNoDelay forces the underlying socket to be created before protect.
            s.tcpNoDelay = true
            val vpn = PolarisRadioNetwork.vpnActive()
            if (!vpn) {
                network.bindSocket(s)
            } else {
                val valid = android.os.ParcelFileDescriptor.fromSocket(s).use { it.fileDescriptor.valid() }
                val ok = valid && WindscribeVpnService.protectRadio(s)
                if (!ok) {
                    PolarisVpnReport.note("Radio socket FD valid=" + valid + " protect=false")
                    throw IOException("Radio socket protection failed")
                }
            }
            return s
        } catch (e: Exception) {
            PolarisVpnReport.note("Radio socket FAIL " + e.javaClass.simpleName)
            s.close()
            throw e
        }
    }
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(network.getAllByName(host).first(), port)) }
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(network.getAllByName(host).first(), port)) }
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
}
''')
# Read-only HttpURLConnection compatibility adapter for the radio address/program APIs.
# Keep the existing normal-mode implementation, route D requests through the same socket/DNS policy as media.
(pkg/"polaris/PolarisRadioHttpConnection.kt").write_text(r'''package com.dd3boh.outertune.polaris

import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStream
import java.io.IOException
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

internal class PolarisRadioHttpConnection(url: URL, private val factory: Call.Factory) : HttpURLConnection(url) {
    private var response: Response? = null
    private var call: Call? = null
    @Synchronized
    override fun connect() {
        if (response != null) return
        if (requestMethod != "GET" && requestMethod != "HEAD") throw IOException("Unsupported radio HTTP method: " + requestMethod)
        val b = Request.Builder().url(url.toString()).method(requestMethod, null)
        for ((name, values) in requestProperties) {
            for (value in values) b.addHeader(name, value)
        }
        val requestCall = factory.newCall(b.build())
        val timeout = listOf(connectTimeout, readTimeout).filter { it > 0 }.maxOrNull() ?: 15000
        requestCall.timeout().timeout(timeout.toLong(), TimeUnit.MILLISECONDS)
        call = requestCall
        response = requestCall.execute()
        connected = true
    }
    private fun result(): Response { connect(); return checkNotNull(response) }
    override fun getResponseCode(): Int = result().code
    override fun getResponseMessage(): String = result().message
    override fun getInputStream(): InputStream {
        val r = result()
        if (!r.isSuccessful) throw IOException("Radio HTTP " + r.code)
        return r.body?.byteStream() ?: throw IOException("Empty radio response")
    }
    override fun getErrorStream(): InputStream? = response?.takeUnless { it.isSuccessful }?.body?.byteStream()
    override fun getHeaderField(name: String?): String? = if (name == null) null else result().header(name)
    override fun getHeaderFields(): Map<String, List<String>> = result().headers.toMultimap()
    override fun usingProxy(): Boolean = false
    override fun disconnect() { call?.cancel(); response?.close(); response = null; connected = false }
}
''')
# Also feed the original Polaris TXT diagnostics, not just InterTune's trail.
s=report.read_text()
s=s.replace('android.util.Log.i("PolarisVpnRoute", value)','PolarisErrorReport.record("VPN " + value)')
report.write_text(s)
# Keep a phone-only entry in the original radio screen as well as music settings.
p=pkg/"polaris/PolarisRadioScreen.kt"
s=p.read_text()
a="TextButton(onClick={PolarisErrorReport.open(context)})"
if a in s:
    s=s.replace(a, '''TextButton(onClick={context.startActivity(android.content.Intent(context, PolarisVpnActivity::class.java))}){Text("VPN 설정")}
        '''+a,1)
    p.write_text(s)
# Assert the existing report activity and player were preserved.
assert (pkg/"polaris/PolarisReportActivity.kt").is_file()
assert (pkg/"playback/MusicService.kt").is_file()
print("Host integration applied; compilation and runtime verification still required.")
