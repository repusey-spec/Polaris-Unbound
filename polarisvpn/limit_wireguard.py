#!/usr/bin/env python3
"""Limit the runtime engine registry and connection selection to WireGuard.
Keep shared Windscribe modules/resources until the ARM64 build is measured.
Do not remove native binaries blindly; shared JNI dependencies need a separate check.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1])
base=root/"base/src/main/java/com/windscribe/vpn"
p=base/"di/BaseApplicationModule.kt"
s=p.read_text()
start=s.index("    fun provideTunnelManager(")
end=s.index("    @Provides",start)
s=s[:start]+'''    fun provideTunnelManager(
        goBackend: Lazy<GoBackend>,
        wireguardBackend: Lazy<WireguardBackend>,
    ): TunnelManager {
        val wireGuardEngine =
            WireGuardEngine(goBackend::get) { state ->
                wireguardBackend.get().onEngineState(state)
            }
        return TunnelManager(mapOf(WireGuardEngineProfile.PROTOCOL to wireGuardEngine))
    }

'''+s[end:]
# Remove eager initialization of unused backend instances.
start=s.index("    fun provideVpnBackendHolder(")
end=s.index("    @Provides",start)
s=s[:start]+'''    fun provideVpnBackendHolder(
        coroutineScope: CoroutineScope,
        preferenceHelper: PreferencesHelper,
        wireguardBackend: WireguardBackend,
    ): VpnBackendHolder =
        VpnBackendHolder(coroutineScope, preferenceHelper, wireguardBackend)

'''+s[end:]
p.write_text(s)
p=base/"backend/VpnBackendHolder.kt"
s=p.read_text()
for line in [
    "    private val iKev2VpnBackend: IKev2VpnBackend,\n",
    "    private val openVPNBackend: OpenVPNBackend,\n",
    "            PROTO_UDP, PROTO_TCP, PROTO_STEALTH, PROTO_WS_TUNNEL -> openVPNBackend\n",
    "            PROTO_IKev2 -> iKev2VpnBackend\n",
]:
    assert s.count(line)==1, line
    s=s.replace(line,"")
p.write_text(s)
p=base/"backend/Util.kt"
s=p.read_text()
start=s.index("    fun getAppSupportedProtocolList(")
end=s.index("    fun buildProtocolInformation(", start)
s=s[:start]+'''    fun getAppSupportedProtocolList(suggestedProtocol: Pair<String, String>? = null): ThreadSafeList<ProtocolInformation> {
        val port = suggestedProtocol?.takeIf { it.first == PROTO_WIRE_GUARD }?.second
            ?: PreferencesKeyConstants.DEFAULT_WIRE_GUARD_PORT
        return ThreadSafeList<ProtocolInformation>().apply {
            add(ProtocolInformation(
                PROTO_WIRE_GUARD,
                port,
                appContext.localized.getString(R.string.Wireguard_description),
                ProtocolConnectionStatus.Disconnected,
            ))
        }
    }

'''+s[end:]
p.write_text(s)
# Reject a non-WireGuard explicit protocol before profile creation.
p=base/"backend/utils/WindVpnController.kt"
s=p.read_text()
old="                val protocolInformation =\n                    selectedProtocol?.let {"
new="                val protocolInformation =\n                    selectedProtocol?.takeIf { it.protocol == PROTO_WIRE_GUARD }?.let {"
assert s.count(old)==1
s=s.replace(old,new,1)
p.write_text(s)
print("WireGuard-only runtime registry and supported protocol list configured; reconnect code preserved.")
