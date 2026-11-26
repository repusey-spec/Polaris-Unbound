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
    private fun vpnMode(): Boolean = true
    private val routePrefs get() = getSharedPreferences("polaris_vpn_631", 0)
    private lateinit var account: TextView
    private lateinit var quota: ProgressBar
    private lateinit var accountRefresh: Button
    private lateinit var logout: Button
    private var loadedAccount = false
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
        account = label("계정 확인 중")
        quota = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; body.addView(this) }
        accountRefresh = button("계정 데이터 새로고침") { refreshAccountData() }
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
        logout = button("계정 로그아웃") {
            lifecycleScope.launch {
                core.vpnController.turnOff()
                core.userRepository.get().logout()
                available = emptyList()
                cities.adapter = null
                password.text.clear()
                otp.text.clear()
                token = null
                name.text.clear()
                loadedAccount = false
                updateAccount(null)
                show("로그아웃")
            }
        }
        label("자동 실행 설정")
        fun check(title: String, value: Boolean, change: (Boolean) -> Unit) {
            body.addView(CheckBox(this).apply { text = title; isChecked = value
                setOnCheckedChangeListener { _, checked -> change(checked) }
            })
        }
        check("부팅 후 폴라리스 자동 실행", routePrefs.getBoolean("app_boot", false)) {
            routePrefs.edit().putBoolean("app_boot", it).apply()
            if (it && android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 733)
            }
        }
        check("부팅 후 VPN 자동 연결", core.preference.autoStartOnBoot) {
            core.preference.autoStartOnBoot = it
            if (it && VpnService.prepare(this) != null) show("먼저 VPN을 수동으로 한 번 연결해 권한을 승인하세요.")
        }
        check("연결 끊김 시 자동 재연결", routePrefs.getBoolean("reconnect", false)) {
            routePrefs.edit().putBoolean("reconnect", it).apply()
            core.preference.autoConnect = it && core.preference.globalUserConnectionPreference
        }
        label("앱 화면 자동 실행은 Android 제한 시 알림을 표시합니다. VPN 자동 연결은 별도 작동합니다.")
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
        updateAccount(core.userRepository.get().user.value)
        lifecycleScope.launch {
            core.userRepository.get().user.collect { user ->
                val signedIn = user != null && !core.preference.sessionHash.isNullOrBlank()
                updateAccount(if (signedIn) user else null)
                if (signedIn && !loadedAccount) { loadedAccount = true; loadCities(); refreshAccountData() }
            }
        }
    }

    private fun updateAccount(user: com.windscribe.vpn.model.User?) {
        val signedIn = user != null && !core.preference.sessionHash.isNullOrBlank()
        val visible = android.view.View.VISIBLE
        val gone = android.view.View.GONE
        listOf(name, password, otp, login).forEach { it.visibility = if (signedIn) gone else visible }
        if (signedIn) {
            clearChallenge()
            password.text.clear(); otp.text.clear(); name.text.clear()
        }
        accountRefresh.visibility = if (signedIn) visible else gone
        logout.visibility = if (signedIn) visible else gone
        quota.visibility = if (signedIn && user != null && user.maxData > 0) visible else gone
        if (signedIn && user != null) {
            fun gb(n: Long) = String.format(java.util.Locale.US, "%.2f GB", n / 1_000_000_000.0)
            val unlimited = user.maxData < 0
            account.text = user.userName + " · " + (if (user.isPro) "Pro" else "무료") +
                "\n잔여 데이터: " + (if (unlimited) "무제한" else gb(user.dataLeft) + " / " + gb(user.maxData)) +
                "\n다음 초기화: " + (user.nextResetDate() ?: "해당 없음 / 미확인")
            quota.progress = if (user.maxData > 0) (user.dataLeft.toDouble() / user.maxData * 1000).toInt().coerceIn(0,1000) else 0
        } else account.text = "Windscribe 로그인 필요"
    }

    private fun refreshAccountData() {
        if (core.preference.sessionHash.isNullOrBlank()) return
        lifecycleScope.launch {
            accountRefresh.isEnabled = false
            try {
                val res = withContext(Dispatchers.IO) {
                    result<com.windscribe.vpn.api.response.UserSessionResponse> {
                        api.getSessionGeneric(null, backup = core.preference.getBackupParameter())
                    }
                }
                when (res) {
                    is CallResult.Success -> {
                        core.userRepository.get().reloadNow(res.data)
                        updateAccount(core.userRepository.get().user.value)
                    }
                    is CallResult.Error -> show("계정 데이터 갱신 실패: " + res.code)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                show("계정 데이터 갱신 실패: " + e.javaClass.simpleName)
            } finally { accountRefresh.isEnabled = true }
        }
    }

    /** Only explicitly selected launchable apps are added to the host's allow-list. */
    private fun chooseVpnApps() {
        val pm = packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = java.text.Collator.getInstance(java.util.Locale.getDefault())
        val apps = pm.queryIntentActivities(launchIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .sortedWith(Comparator { a, b ->
                val order = collator.compare(pm.getApplicationLabel(a).toString(), pm.getApplicationLabel(b).toString())
                if (order != 0) order else a.packageName.compareTo(b.packageName)
            })
        val selected = core.preference.installedApps.filter { it != packageName }.toMutableSet()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 0, 16, 0) }
        val search = EditText(this).apply { hint = "앱 이름 검색"; setSingleLine(true); body.addView(this) }
        val only = CheckBox(this).apply { text = "선택된 앱만 보기"; body.addView(this) }
        val list = ListView(this)
        body.addView(list, LinearLayout.LayoutParams(-1, (350 * resources.displayMetrics.density).toInt()))
        fun render() {
            val q = search.text.toString().trim()
            val shown = apps.filter { (!only.isChecked || it.packageName in selected) && pm.getApplicationLabel(it).toString().contains(q, true) }
            list.adapter = object : BaseAdapter() {
                override fun getCount() = shown.size
                override fun getItem(position: Int) = shown[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                    val app = getItem(position)
                    val row = LinearLayout(this@PolarisVpnActivity).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(8,4,8,4) }
                    val size = (32 * resources.displayMetrics.density).toInt()
                    row.addView(ImageView(this@PolarisVpnActivity).apply { setImageDrawable(pm.getApplicationIcon(app)) }, LinearLayout.LayoutParams(size,size))
                    row.addView(CheckBox(this@PolarisVpnActivity).apply {
                        text = pm.getApplicationLabel(app); isChecked = app.packageName in selected
                        maxLines = 1
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
                            if (only.isChecked) render()
                        }
                    }, LinearLayout.LayoutParams(0,-2,1f))
                    return row
                }
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        only.setOnCheckedChangeListener { _, _ -> render() }
        render()
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("VPN 사용할 앱 선택")
            .setView(body).setNegativeButton("취소", null)
            .setNeutralButton("모두 해제", null)
            .setPositiveButton("저장 및 적용") { _, _ -> applyVpnApps(selected) }.create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { selected.clear(); render() }
        }
        dialog.show()
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
                val last = routePrefs.getString("last_city", null)
                available.indexOfFirst { it.id.toString() == last }.takeIf { it >= 0 }?.let { cities.setSelection(it) }
                show("사용 가능 서버: " + available.size)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                show("서버 목록 조회 오류: " + e.javaClass.simpleName)
            }
        }
    }

    private fun requestConnection(city: Datacenter) {
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
        connectCity(city, protocol)
    }

    private fun connectCity(city: Datacenter, protocol: String) {
        routePrefs.edit().putString("last_city", city.id.toString()).apply()
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
