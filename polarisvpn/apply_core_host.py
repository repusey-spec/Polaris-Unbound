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
replace(appgrad,'    defaultConfig {','    defaultConfig {\n        ndk { abiFilters.clear(); abiFilters.add("POLARIS_ABI") }')
appgrad.write_text(appgrad.read_text().replace('POLARIS_ABI', sys.argv[3]))
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
# Disable the legacy global proxy when the experimental VPN is in use.
replace(app,'if (dataStore[ProxyEnabledKey] == true) {','if (false && dataStore[ProxyEnabledKey] == true) {')
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
            android:theme="@android:style/Theme.Material.Light.NoActionBar" />
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
manifest.write_text(s)
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
            ", DNS servers=" + dns + ", mode=" + context.getSharedPreferences("polaris_vpn_compare", 0).getString("mode", "D") + ", music=default network, radio=" + (if (PolarisRadioNetwork.directEnabled()) "physical" else "default")
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
    @JvmStatic
    fun directEnabled(): Boolean = Windscribe.appContext.getSharedPreferences("polaris_vpn_compare", 0)
        .getString("mode", "D") == "D"

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
        if (!directEnabled()) return url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        val context=Windscribe.appContext
        val n=physical(context)
        val cm=context.getSystemService(ConnectivityManager::class.java)
        PolarisVpnReport.note("Radio address/guide: physical network="+n+
            ", DNS servers="+(cm.getLinkProperties(n)?.dnsServers?.size ?: 0))
        return n.openConnection(url,Proxy.NO_PROXY) as HttpURLConnection
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
s=s.replace('    override fun newCall(request: Request): Call = synchronized(lock) {',
    '''    private val normalClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun newCall(request: Request): Call = synchronized(lock) {
        if (!PolarisRadioNetwork.directEnabled()) {
            client?.connectionPool?.evictAll()
            client = null
            selected = null
            return@synchronized normalClient.newCall(request)
        }
        normalClient.connectionPool.evictAll()''')
radio.write_text(s)
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
