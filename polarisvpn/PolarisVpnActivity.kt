package com.dd3boh.outertune.polaris

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.activity.ComponentActivity
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
class PolarisVpnActivity : ComponentActivity() {
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
        connect = button("선택 서버 WireGuard 연결") {
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
        label("VPN 적용 대상: 스트리밍 + 선택한 앱 / 라디오 직접 통신")
        button("VPN 적용 앱 선택") { chooseVpnApps() }
        button("통신 상태 확인") { show(PolarisVpnReport.snapshot(this)) }
        status = label("")
        show(PolarisVpnReport.snapshot(this))
        lifecycleScope.launch {
            core.vpnConnectionStateManager.state.collect {
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
        prefs.splitTunnelToggle = true
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
        pendingCity = city
        val permission = VpnService.prepare(this)
        if (permission == null) startConnection() else startActivityForResult(permission, 732)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 732 && resultCode == Activity.RESULT_OK) startConnection()
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
        prefs.savedProtocol = PreferencesKeyConstants.PROTO_WIRE_GUARD
        prefs.selectedProtocol = PreferencesKeyConstants.PROTO_WIRE_GUARD
        prefs.globalUserConnectionPreference = true
        core.locationRepository.get().setSelectedCity(city.id)
        core.autoConnectionManager.reset()
        show("WireGuard 연결 요청: " + city.nodeName)
        Windscribe.applicationScope.launch {
            core.autoConnectionManager.connectInForeground(ConnectSource.User)
        }
    }
}
