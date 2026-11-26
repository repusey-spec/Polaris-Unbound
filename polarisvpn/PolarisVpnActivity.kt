package com.dd3boh.outertune.polaris

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.windscribe.vpn.Windscribe
import com.windscribe.vpn.api.IApiCallManager
import com.windscribe.vpn.api.response.AuthToken
import com.windscribe.vpn.api.response.UserLoginResponse
import com.windscribe.vpn.apppreference.PreferencesKeyConstants
import com.windscribe.vpn.backend.utils.ConnectSource
import com.windscribe.vpn.commonutils.Ext.result
import com.windscribe.vpn.repository.CallResult
import com.windscribe.vpn.repository.UserDataState
import com.windscribe.vpn.serverlist.entity.Datacenter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Phone-only settings page; the car browser and both playback paths stay in the host. */
@AndroidEntryPoint
class PolarisVpnActivity : AppCompatActivity() {
    @Inject lateinit var api: IApiCallManager
    private val core: Windscribe get() = application as Windscribe
    private lateinit var name: EditText
    private lateinit var password: EditText
    private lateinit var otp: EditText
    private lateinit var answer: EditText
    private lateinit var challenge: ImageView
    private lateinit var challengeRefresh: Button
    private lateinit var status: TextView
    private lateinit var cities: Spinner
    private lateinit var login: Button
    private lateinit var connect: Button
    private lateinit var refresh: Button
    private var token: AuthToken? = null
    private val diagnostic = java.util.ArrayDeque<String>()
    private fun trace(message: String) {
        val line = android.os.SystemClock.elapsedRealtime().toString() + " " + message
        if (diagnostic.size >= 80) diagnostic.removeFirst()
        diagnostic.addLast(line)
        PolarisVpnReport.note(line)
    }
    private fun comparisonMode(): String = getSharedPreferences("polaris_vpn_630", 0).getString("mode", "A") ?: "A"
    private fun vpnMode(): Boolean = comparisonMode() == "C" || comparisonMode().startsWith("D")
    private val routePrefs get() = getSharedPreferences("polaris_vpn_630", 0)
    private var pendingCity: Datacenter? = null
    private var available: List<Datacenter> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
        }
        scroll.addView(body)
        setContentView(scroll)
        fun label(s: String) = TextView(this).apply { text = s; textSize = 16f; body.addView(this) }
        fun edit(hintText: String, secret: Boolean = false) = EditText(this).apply {
            hint = hintText
            setSingleLine(true)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
            body.addView(this)
        }
        fun button(title: String, click: () -> Unit) = Button(this).apply {
            text = title
            setOnClickListener { click() }
            body.addView(this)
        }
        label("Polaris VPN 0.03 · Windscribe / WireGuard")
        label("실험용 통합 설정. 음악 재생 전에 VPN 연결 상태를 확인하세요.")
        name = edit("Windscribe 계정")
        password = edit("비밀번호", true)
        otp = edit("2단계 인증 코드 (사용 시)", true)
        challenge = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "보안 문자 이미지"
            visibility = android.view.View.GONE
            body.addView(this, LinearLayout.LayoutParams(-1, (110 * resources.displayMetrics.density).toInt()))
        }
        answer = edit("보안 문자 답변")
        answer.visibility = android.view.View.GONE
        login = button("로그인") { authenticate() }
        challengeRefresh = button("보안 문자 새로고침") {
            clearChallenge()
            authenticate()
        }
        challengeRefresh.visibility = android.view.View.GONE
        refresh = button("서버 목록 새로고침") { loadCities() }
        cities = Spinner(this)
        body.addView(cities)
        label("VPN 프로토콜: WireGuard 443")
        connect = button("선택 서버 VPN 연결") {
            available.getOrNull(cities.selectedItemPosition)?.let { requestConnection(it) }
                ?: show("서버 목록에서 연결할 서버를 선택하세요.")
        }
        button("VPN 연결 끊기") {
            lifecycleScope.launch { core.vpnController.turnOff(); show("VPN 연결 해제 요청") }
        }
        button("계정 로그아웃") {
            lifecycleScope.launch {
                core.vpnController.turnOff()
                core.userRepository.get().logout()
                available = emptyList()
                cities.adapter = null
                password.text.clear()
                otp.text.clear()
                token = null
                show("로그아웃")
            }
        }
        label("비교 모드 (연결 끊은 뒤 변경)")
        val modeButton = button("현재 모드: " + comparisonMode()) {}
        modeButton.setOnClickListener {
            if (core.vpnConnectionStateManager.state.value.status != com.windscribe.vpn.backend.VPNState.Status.Disconnected) {
                show("VPN 연결을 먼저 끊으세요.")
            } else {
                val modes = arrayOf("A", "B", "C", "D1", "D2", "D3", "D4")
                val descriptions = arrayOf("A · 일반망 / 프록시 OFF", "B · 일반망 / InterTune 프록시", "C · 선택 앱 VPN / 라디오도 VPN", "D1 · FD 초기화 + protect(Socket)", "D2 · FD 초기화 + protect + 물리망 bind", "D3 · FD 복제 + protect(fd)", "D4 · 목적지 IPv4 제외 (Android 13+)")
                android.app.AlertDialog.Builder(this).setTitle("VPN 비교 모드")
                    .setSingleChoiceItems(descriptions, modes.indexOf(comparisonMode())) { dialog, index ->
                        val chosen = modes[index]
                        if (chosen == "D4" && android.os.Build.VERSION.SDK_INT < 33) {
                            dialog.dismiss()
                            show("D4는 Android 13 이상에서 지원합니다.")
                            return@setSingleChoiceItems
                        }
                        if (getSharedPreferences("polaris_vpn_630", 0).edit().putString("mode", chosen).commit()) {
                            core.preference.splitTunnelToggle = chosen == "C" || chosen.startsWith("D")
                            core.preference.globalUserConnectionPreference = false
                            modeButton.text = "현재 모드: " + chosen
                            trace("Comparison mode=" + chosen)
                            android.app.AlertDialog.Builder(this@PolarisVpnActivity)
                                .setTitle("통신 모드 변경").setMessage("앱을 재시작하여 프록시와 통신 클라이언트를 새로 초기화합니다. B 모드는 기존 음악 설정에서 프록시 주소를 지정하세요.")
                                .setCancelable(false).setPositiveButton("재시작") { _, _ ->
                                    com.dd3boh.outertune.utils.ProxyAtStart.restart(this@PolarisVpnActivity)
                                }.show()
                        }
                        dialog.dismiss()
                    }.setNegativeButton("취소", null).show()
            }
        }
        label("A/B: VPN 연결 금지. C: VPN. D1–D4: 라디오 우회 비교. D4는 지정 목적지만 제외합니다.")
        button("D4 제외할 라디오 호스트 설정") {
            val field = EditText(this).apply {
                hint = "radio.example.com, stream.example.com"
                setText(routePrefs.getString("d4_hosts", ""))
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("D4 목적지 제외 · 실험용")
                .setMessage("지정된 호스트의 IPv4 전체가 VPN 밖으로 나갈 수 있습니다. 공유 CDN은 피하세요. IP 확인용 checkip.amazonaws.com은 자동 포함됩니다. 연결 해제 상태에서 설정하세요.")
                .setView(field).setNegativeButton("취소", null)
                .setPositiveButton("저장") { _, _ ->
                    if (core.vpnConnectionStateManager.state.value.status != com.windscribe.vpn.backend.VPNState.Status.Disconnected) {
                        show("VPN 연결을 먼저 끊으세요.")
                    } else {
                        val hosts = field.text.toString().split(Regex("[,\\s]+")).filter { it.matches(Regex("[A-Za-z0-9.-]{1,253}")) }.distinct().take(24)
                        routePrefs.edit().putString("d4_hosts", hosts.joinToString(",")).commit()
                        show("D4 호스트 저장: " + hosts.size)
                    }
                }.show()
        }
        button("VPN 적용 앱 선택") { chooseVpnApps() }
        button("통신 상태 확인") { show(PolarisVpnReport.snapshot(this)) }
        button("VPN 진단 복사") {
            lifecycleScope.launch {
            val coreLog = withContext(Dispatchers.IO) {
                    runCatching { java.lang.Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1000", "-s", "PolarisVpnCore:I")).inputStream.bufferedReader().use { it.readText() } }.getOrDefault("Core log unavailable")
                }
                val originalLog = withContext(Dispatchers.IO) { readWindscribeLog() }
                val report = diagnostic.joinToString("\n") + "\n" + PolarisVpnReport.snapshot(this@PolarisVpnActivity) + "\n" + coreLog + "\n" + originalLog
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("VPN diagnostic", report))
            show("VPN 진단 복사 완료")
            }
        }
        status = label("")
        trace("SDK=" + android.os.Build.VERSION.SDK_INT + " ABI=" + android.os.Build.SUPPORTED_ABIS.joinToString())
        show(PolarisVpnReport.snapshot(this))
        lifecycleScope.launch {
            core.vpnConnectionStateManager.state.collect {
                trace("state=" + it.status + " " + PolarisVpnReport.snapshot(this@PolarisVpnActivity))
                show("Windscribe: " + it.status + "\n" + PolarisVpnReport.snapshot(this@PolarisVpnActivity))
            }
        }
        if (core.userRepository.get().loggedIn()) loadCities()
    }

    /** Only explicitly selected launchable apps are added to the host's allow-list. */
    private fun chooseVpnApps() {
        val pm = packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launchIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedWith(compareBy({ it.second.lowercase() }, { it.first }))
        val previous = core.preference.installedApps.filter { it != packageName }.toSet()
        val selected = previous.toMutableSet()
        val names = apps.map { it.second + "\n" + it.first }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].first in selected }
        android.app.AlertDialog.Builder(this)
            .setTitle("VPN 사용할 앱 선택")
            .setMultiChoiceItems(names, checked) { _, index, enabled ->
                val id = apps[index].first
                if (enabled) selected.add(id) else selected.remove(id)
            }
            .setNegativeButton("취소", null)
            .setNeutralButton("모두 해제") { _, _ -> applyVpnApps(emptySet()) }
            .setPositiveButton("저장 및 적용") { _, _ -> applyVpnApps(selected) }
            .show()
    }

    private fun applyVpnApps(selected: Set<String>) {
        val prefs = core.preference
        val updated = (listOf(packageName) + selected.filter { it != packageName }.sorted()).distinct()
        val changed = prefs.installedApps.toSet() != updated.toSet() ||
            !prefs.splitTunnelToggle || prefs.splitRoutingMode != PreferencesKeyConstants.INCLUSIVE_MODE
        prefs.installedApps = updated
        prefs.splitRoutingMode = PreferencesKeyConstants.INCLUSIVE_MODE
        prefs.splitTunnelToggle = vpnMode()
        PolarisVpnReport.note("VPN external apps selected=" + (updated.size - 1))
        val active = core.vpnConnectionStateManager.state.value.status ==
            com.windscribe.vpn.backend.VPNState.Status.Connected
        if (changed && active) {
            show("선택 앱 저장: " + (updated.size - 1) + "개 · VPN 재연결 중")
            core.autoConnectionManager.reset()
            Windscribe.applicationScope.launch {
                core.autoConnectionManager.connectInForeground(ConnectSource.User)
            }
        } else {
            show("선택 앱 저장: " + (updated.size - 1) + "개 · 다음 연결부터 적용")
        }
    }

    private fun readWindscribeLog(): String {
        val modules = Regex("""(?i)\b(vpn|vpn_backend|auto-connect-watcher|connection-policy|tunnel-health-check|network-info-manager|device-state-manager|wsnet-wrapper|wsnet)\b""")
        val sensitive = Regex("""(?i)password|private.?key|preshared.?key|session.?hash|auth.?token|authorization|cookie|bearer|credential|secret|username|email|https?://|wg.?config""")
        val safe = java.util.ArrayDeque<String>()
        fun collect(reader: java.io.BufferedReader) {
            reader.lineSequence().forEach { line ->
                if (modules.containsMatchIn(line) && !sensitive.containsMatchIn(line)) {
                    if (safe.size >= 450) safe.removeFirst()
                    safe.addLast(line.take(1500))
                }
            }
        }
        val current = java.io.File(filesDir, "applog.txt")
        val rolled = java.io.File(filesDir, "applog.1.log.zip")
        val errors = mutableListOf<String>()
        if (rolled.isFile) runCatching {
            java.util.zip.ZipInputStream(rolled.inputStream()).use { zip ->
                if (zip.nextEntry != null) collect(zip.bufferedReader())
            }
        }.onFailure { errors.add("rolled: " + it.javaClass.simpleName) }
        if (current.isFile) runCatching {
            current.bufferedReader().use { collect(it) }
        }.onFailure { errors.add("current: " + it.javaClass.simpleName) }
        return "=== Windscribe original diagnostic ===\n" +
            "applog.txt exists=" + current.isFile + " bytes=" + current.length() +
            "; rolled=" + rolled.isFile + "; selected lines=" + safe.size +
            "; errors=" + errors.joinToString() + "\n" +
            safe.joinToString("\n")
    }

    private fun show(value: String) { status.text = value }
    private fun busy(b: Boolean) {
        login.isEnabled = !b
        refresh.isEnabled = !b
        connect.isEnabled = !b
    }
    private fun clearChallenge() {
        token = null
        answer.text.clear()
        challenge.setImageDrawable(null)
        challenge.visibility = android.view.View.GONE
        answer.visibility = android.view.View.GONE
        challengeRefresh.visibility = android.view.View.GONE
    }
    // Follow Windscribe AccessibleCaptchaDialog: decode the ASCII art and draw square cells.
    // No recognition or automatic submission of the CAPTCHA.
    private fun renderChallenge(encoded: String): Boolean {
        val art = try {
            String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) { return false }
        val rows = art.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        val columns = rows.maxOfOrNull { it.length } ?: 0
        if (rows.isEmpty() || columns == 0 || rows.size > 256 || columns > 256) return false
        val cell = 4
        val bitmap = android.graphics.Bitmap.createBitmap(columns * cell, rows.size * cell,
            android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val ink = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; isAntiAlias = false }
        rows.forEachIndexed { y, line ->
            line.forEachIndexed { x, ch ->
                if (!ch.isWhitespace()) canvas.drawRect((x*cell).toFloat(), (y*cell).toFloat(),
                    ((x+1)*cell).toFloat(), ((y+1)*cell).toFloat(), ink)
            }
        }
        challenge.setImageBitmap(bitmap)
        challenge.visibility = android.view.View.VISIBLE
        challengeRefresh.visibility = android.view.View.VISIBLE
        answer.visibility = android.view.View.VISIBLE
        return true
    }
    private fun authenticate() {
        val u = name.text.toString().trim()
        val p = password.text.toString()
        val two = otp.text.toString().trim()
        val supplied = answer.text.toString().trim()
        if (u.isEmpty() || p.isEmpty()) { show("계정과 비밀번호를 입력하세요."); return }
        busy(true)
        lifecycleScope.launch {
            try {
                val t = token ?: when (val res = withContext(Dispatchers.IO) {
                    result<AuthToken> { api.authTokenLogin(u, true) }
                }) {
                    is CallResult.Error -> { show("인증 요청 실패: " + res.code); return@launch }
                    is CallResult.Success -> res.data
                }
                token = t
                val captcha = t.captcha
                if (captcha != null && supplied.isBlank()) {
                    val ascii = captcha.asciiArt
                    if (ascii.isNullOrBlank()) {
                        token = null
                        show("서버가 그림 CAPTCHA를 요청했습니다. 이 빌드는 공식 텍스트 CAPTCHA만 지원합니다.")
                        return@launch
                    }
                    if (!renderChallenge(ascii)) {
                        clearChallenge()
                        show("보안 문자 표시 오류: 다시 로그인하세요.")
                        return@launch
                    }
                    show("보안 문자 답변을 입력한 후 로그인하세요.")
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    result<UserLoginResponse> {
                        api.logUserIn(u, p, two, t.token,
                            if (captcha != null) supplied else null, floatArrayOf(), floatArrayOf())
                    }
                }
                clearChallenge()
                when (result) {
                    is CallResult.Error -> show("로그인 실패: " + result.code + " (계정/2FA/보안 문자 확인)")
                    is CallResult.Success -> {
                        val hash = result.data.sessionAuthHash
                        if (hash.isNullOrBlank()) { show("로그인 세션을 받지 못했습니다."); return@launch }
                        core.preference.sessionHash = hash
                        password.text.clear()
                        otp.text.clear()
                        withContext(Dispatchers.IO) {
                            core.userRepository.get().prepareDashboard(null).collect { state ->
                                withContext(Dispatchers.Main) {
                                    when (state) {
                                        is UserDataState.Loading -> show(state.status)
                                        is UserDataState.Error -> {
                                            core.preference.sessionHash = null
                                            show("계정/서버 목록 초기화 실패")
                                        }
                                        UserDataState.Success -> { show("로그인 완료"); loadCities() }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                show("VPN 인증 처리 오류: " + e.javaClass.simpleName)
            } finally { busy(false) }
        }
    }

    private fun loadCities() {
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val user = core.userRepository.get().user.value
                    core.localDbInterface.getAllLocationsAsync().flatMap { group ->
                        group.datacenters.filter { it.status == 1 && !it.pubKey.isNullOrBlank() &&
                            (it.pro == 0 || user?.isPro == true) }.map {
                            it to (group.location?.name.orEmpty() + " / " + it.nodeName.orEmpty() + " " + it.nickName.orEmpty())
                        }
                    }
                }
                available = data.map { it.first }
                cities.adapter = ArrayAdapter(this@PolarisVpnActivity, android.R.layout.simple_spinner_dropdown_item,
                    data.map { it.second })
                show("사용 가능 서버: " + available.size)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                show("서버 목록 조회 오류: " + e.javaClass.simpleName)
            }
        }
    }

    private fun requestConnection(city: Datacenter) {
        if (!vpnMode()) { show("C 또는 D 모드로 변경 후 재시작하세요."); return }
        if (core.vpnConnectionStateManager.state.value.status == com.windscribe.vpn.backend.VPNState.Status.Connecting) {
            show("VPN 연결 진행 중입니다.")
            return
        }
        pendingCity = city
        val permission = VpnService.prepare(this)
        trace("VPN permission already granted=" + (permission == null))
        if (permission == null) startConnection() else startActivityForResult(permission, 732)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 732) {
            trace("VPN permission result=" + resultCode)
            if (resultCode == Activity.RESULT_OK) startConnection() else show("VPN 권한 승인 실패")
        }
    }

    private fun startConnection() {
        val city = pendingCity ?: return
        pendingCity = null
        if (!core.userRepository.get().accountStatusOkay()) { show("Windscribe 계정 상태를 확인하세요."); return }
        val prefs = core.preference
        prefs.splitTunnelToggle = false
        prefs.isConnectingToStaticIp = false
        prefs.isConnectingToConfigured = false
        prefs.connectionMode = PreferencesKeyConstants.CONNECTION_MODE_MANUAL
        val protocol = PreferencesKeyConstants.PROTO_WIRE_GUARD
        prefs.savedProtocol = protocol
        prefs.selectedProtocol = protocol
        prefs.selectedPort = "443"
        prefs.wireGuardPort = "443"
        prefs.globalUserConnectionPreference = true
        if (comparisonMode() == "D4") {
            busy(true)
            lifecycleScope.launch {
                try {
                    val mapping = withContext(Dispatchers.IO) {
                        val n = PolarisRadioNetwork.physical(this@PolarisVpnActivity)
                        val hosts = (listOf("checkip.amazonaws.com") + routePrefs.getString("d4_hosts", "").orEmpty().split(",").filter { it.isNotBlank() }).distinct()
                        hosts.associateWith { host ->
                            n.getAllByName(host).filterIsInstance<java.net.Inet4Address>().map { it.hostAddress.orEmpty() }.distinct().also {
                                if (it.isEmpty()) throw java.io.IOException("No IPv4 for " + host)
                            }
                        }
                    }
                    val json = org.json.JSONObject()
                    mapping.forEach { (host, ips) -> json.put(host.lowercase(), org.json.JSONArray(ips)) }
                    routePrefs.edit().putString("d4_map", json.toString()).commit()
                    trace("D4 excluded hosts=" + mapping.size + " IPv4=" + mapping.values.flatten().distinct().size)
                    connectCity(city, protocol)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    show("D4 목적지 조회 실패: " + e.javaClass.simpleName)
                } finally { busy(false) }
            }
        } else connectCity(city, protocol)
    }

    private fun connectCity(city: Datacenter, protocol: String) {
        core.locationRepository.get().setSelectedCity(city.id)
        core.autoConnectionManager.reset()
        trace("VPN request; protocol=" + protocol + "/443; mode=" + comparisonMode() + "; city=" + city.id)
        show(protocol + "/443 연결 요청: " + city.nodeName)
        Windscribe.applicationScope.launch {
            try {
                core.autoConnectionManager.connectInForeground(ConnectSource.User)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(Dispatchers.Main) { trace("connect exception=" + e.javaClass.name); show("VPN 연결 오류: " + e.javaClass.simpleName) }
            }
        }
    }
}
