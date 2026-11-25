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
replace(appgrad,'applicationId = "app.hush.music.polarisradio.debug"','applicationId = "com.polaris.vpn"')
replace(appgrad,'versionCode = 100616','versionCode = 1')
replace(appgrad,'versionName = "6.16"','versionName = "0.01"')
lib=root/"app/libs"
lib.mkdir(exist_ok=True)
for f in ("windscribe-core.aar","windscribe-core-dependencies.gradle"):
    shutil.copy2(ws/"windscribe-core/build/outputs/aar"/f, lib/f)
with appgrad.open("a") as f:
    f.write('\n dependencies { implementation(files("libs/windscribe-core.aar")) }\napply(from = "libs/windscribe-core-dependencies.gradle")\n')
toml=root/"gradle/libs.versions.toml"
for old,new in [('kotlin = "2.2.21"','kotlin = "2.3.21"'),('hilt = "2.57.2"','hilt = "2.59.2"'),('ksp = "2.3.1"','ksp = "2.3.9"')]:
    replace(toml,old,new)
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
for p in (root/"app/src").glob("*/res/values*/strings.xml"):
    s=p.read_text()
    s2=re.sub(r'(<string name="app_name"[^>]*>)[^<]*(</string>)',r'\g<1>Polaris VPN 0.01\g<2>',s)
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
            ", DNS servers=" + dns + ", music=default network, radio media=physical network"
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
print("Host integration applied; compilation and runtime verification still required.")
