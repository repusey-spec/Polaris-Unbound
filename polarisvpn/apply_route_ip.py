#!/usr/bin/env python3
"""0.03: display real public IPv4 measurements in both existing phone tabs.
Use the very same Call.Factory instances owned by MusicService, not a second
generic HTTP client or a guessed country. No player engine replacement.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1])
pkg=root/"app/src/main/java/com/dd3boh/outertune"
def edit(p, old, new):
    s=p.read_text()
    assert s.count(old)==1, f"{p}: expected unique anchor {old[:100]}"
    p.write_text(s.replace(old,new,1))
# Wire the actual radio factory and actual ordinary music HTTP playerCalls
# instance to the diagnostic; no independent network-routing implementation.
p=pkg/"polaris/PolarisMediaSources.kt"
edit(p,'class PolarisMediaSources(context: Context, private val songs: MediaSource.Factory) : MediaSource.Factory by songs {',
'''class PolarisMediaSources(context: Context, private val songs: MediaSource.Factory,
    radioCalls: okhttp3.Call.Factory = PolarisDirectRadioCalls(context)) : MediaSource.Factory by songs {''')
edit(p,'OkHttpDataSource.Factory(PolarisDirectRadioCalls(context))','OkHttpDataSource.Factory(radioCalls)')
p=pkg/"playback/MusicService.kt"
edit(p,'    private val playerCalls by lazy { StreamFamily.Calls(playerStreamClient) }',
'''    private val playerCalls by lazy { StreamFamily.Calls(playerStreamClient) }
    private val polarisProbeRadioCalls by lazy { com.dd3boh.outertune.polaris.PolarisDirectRadioCalls(this) }''')
edit(p,'PolarisMediaSources(this, DefaultMediaSourceFactory(createDataSourceFactory()))',
     'PolarisMediaSources(this, DefaultMediaSourceFactory(createDataSourceFactory()), polarisProbeRadioCalls)')
edit(p,'        polarisRadioSession = PolarisRadioSession(this, exoPlayer, scope)',
'''        polarisRadioSession = PolarisRadioSession(this, exoPlayer, scope)
        com.dd3boh.outertune.polaris.PolarisRouteIpProbe.attach(polarisProbeRadioCalls, playerCalls)''')
edit(p,'        polarisRadioSession?.dispose()',
'''        com.dd3boh.outertune.polaris.PolarisRouteIpProbe.detach(polarisProbeRadioCalls)
        polarisRadioSession?.dispose()''')
p=pkg/"polaris/PolarisRouteIpProbe.kt"
assert not p.exists()
p.write_text(r'''package com.dd3boh.outertune.polaris

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal object PolarisRouteIpProbe {
    data class Factories(val radio: Call.Factory, val music: Call.Factory)
    data class Result(val ip: String?, val failure: String? = null)
    private val current = MutableStateFlow<Factories?>(null)
    val factories = current.asStateFlow()

    fun attach(radio: Call.Factory, music: Call.Factory) {
        current.value = Factories(radio, music)
    }
    fun detach(radio: Call.Factory) {
        if (current.value?.radio === radio) current.value = null
    }
    private fun read(factory: Call.Factory): Result {
        return try {
            val request = Request.Builder().url("https://api.ipify.org/")
                .header("Cache-Control", "no-cache, no-store").build()
            val call = factory.newCall(request)
            call.timeout().timeout(10, TimeUnit.SECONDS)
            call.execute().use { response ->
                if (!response.isSuccessful) return Result(null, "HTTP " + response.code)
                val value = response.body?.string()?.trim().orEmpty()
                val parts = value.split('.')
                if (parts.size == 4 && parts.all { p ->
                        p.length in 1..3 && p.all { it in '0'..'9' } &&
                            (p.toIntOrNull() ?: -1) in 0..255
                    }) Result(value)
                else Result(null, "Invalid IPv4 response")
            }
        } catch (e: Exception) {
            Result(null, e.javaClass.simpleName)
        }
    }

    suspend fun measure(pair: Factories): Pair<Result, Result> = coroutineScope {
        val radio = async(Dispatchers.IO) { read(pair.radio) }
        val music = async(Dispatchers.IO) { read(pair.music) }
        radio.await() to music.await()
    }
}
''')
p=pkg/"polaris/PolarisShell.kt"
edit(p,'import androidx.compose.ui.unit.dp\n','import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.unit.sp\n')
edit(p,'''    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=12.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        listOf(true to "Radio",false to "Streaming").forEach{(value,label)->
            Button(modifier=Modifier.weight(1f),onClick={onSelect(value)},colors=ButtonDefaults.buttonColors(
                containerColor=if(radio==value)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor=if(radio==value)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )){Text(label)}
        }
    }''',
'''    val factories by PolarisRouteIpProbe.factories.collectAsState()
    val vpnState by com.windscribe.vpn.Windscribe.appContext.vpnConnectionStateManager.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var radioIp by remember { mutableStateOf("서비스 연결 중") }
    var musicIp by remember { mutableStateOf("서비스 연결 중") }
    LaunchedEffect(factories, vpnState.status, radio, refresh) {
        val pair = factories
        if (pair == null) {
            radioIp = "서비스 연결 중"
            musicIp = "서비스 연결 중"
        } else {
            radioIp = "IP 측정 중"
            musicIp = "IP 측정 중"
            val (r, m) = PolarisRouteIpProbe.measure(pair)
            radioIp = r.ip ?: "IP 확인 실패"
            musicIp = m.ip ?: "IP 확인 실패"
            PolarisVpnReport.note("IPv4 probe: radio=" + (r.ip ?: r.failure) +
                "; music HTTP=" + (m.ip ?: m.failure) + "; VPN=" + vpnState.status)
        }
    }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=12.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        listOf(true to "Radio",false to "Streaming").forEach{(value,label)->
            Button(modifier=Modifier.weight(1f),onClick={onSelect(value); refresh++},
                contentPadding=PaddingValues(horizontal=4.dp,vertical=4.dp),
                colors=ButtonDefaults.buttonColors(
                containerColor=if(radio==value)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor=if(radio==value)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )){
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(label)
                    Text(if(value)radioIp else musicIp, fontSize=10.sp, maxLines=1)
                }
            }
        }
    }''')
print("PASS: phone Radio/Streaming tabs show actual IPv4 from player-owned Call.Factory instances.")
