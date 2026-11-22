#!/usr/bin/env python3
"""Source-only AA startup gate, to be applied after aa614.py, BEFORE next requested APK build.

Restore original Polaris 0.50 2,500-ms wait AFTER AA projection connected.
Only background automatic queue/radio startup is delayed. An explicit play is not.
"""
from pathlib import Path
import sys

sfile=Path(sys.argv[1])/"app/src/main/java/com/dd3boh/outertune/playback/MusicService.kt"
s=sfile.read_text(encoding="utf-8")

def once(old,new,reason):
    global s
    n=s.count(old)
    assert n==1,reason+" expected once, found "+str(n)
    s=s.replace(old,new,1)

if "import kotlin.coroutines.resume" not in s:
    once("package com.dd3boh.outertune.playback\n",
         "package com.dd3boh.outertune.playback\n\nimport kotlin.coroutines.resume\n",
         "continuation import")

once("    private suspend fun resumeOnLaunchIfAsked() {\n",
"""    /** Polaris 0.50: begin playing 2500 ms AFTER the AA connection signal. */
    private val polarisAaStartDelayMs = 2500L
    private var polarisAaStartupUserIntervened = false

    /**
     * A parked phone can start MusicService before the car audio route is ready.
     * Suspend without playing; observe AA PROJECTION instead of timing from boot.
     */
    private suspend fun polarisWaitForAaProjection(): Boolean =
        withContext(Dispatchers.Main) {
            val projection = androidx.car.app.connection.CarConnection.CONNECTION_TYPE_PROJECTION
            if (carType.value == projection) return@withContext true
            kotlinx.coroutines.withTimeoutOrNull(90_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { gate ->
                    val live = carType
                    val watcher = object : androidx.lifecycle.Observer<Int> {
                        override fun onChanged(type: Int) {
                            if (type == projection && gate.isActive) {
                                live.removeObserver(this)
                                gate.resume(Unit)
                            }
                        }
                    }
                    gate.invokeOnCancellation {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            live.removeObserver(watcher)
                        }
                    }
                    live.observeForever(watcher)
                }
                true
            } ?: false
        }

    private suspend fun polarisAaStartReady(): Boolean {
        if (!polarisWaitForAaProjection()) {
            Log.i(TAG, "Polaris AA auto resume withheld: no projection")
            return false
        }
        delay(polarisAaStartDelayMs)
        return withContext(Dispatchers.Main) {
            val projection = androidx.car.app.connection.CarConnection.CONNECTION_TYPE_PROJECTION
            when {
                carType.value != projection -> {
                    Log.i(TAG, "Polaris AA startup aborted: disconnected during delay")
                    false
                }
                polarisAaStartupUserIntervened || activePlayer.playWhenReady ||
                    activePlayer.isPlaying -> {
                    Log.i(TAG, "Polaris AA startup skipped: user or player already active")
                    false
                }
                else -> {
                    Log.i(TAG, "Polaris AA startup ready after 2500ms projection delay")
                    true
                }
            }
        }
    }

    private suspend fun resumeOnLaunchIfAsked() {
""","AA startup gate methods")

once(
"""    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        super.onPlayWhenReadyChanged(playWhenReady, reason)""",
"""    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
            polarisAaStartupUserIntervened = true
        }
        super.onPlayWhenReadyChanged(playWhenReady, reason)""","manual play/pause does not get overridden")

old="        if (!dataStore.get(ResumePlaybackOnLaunchKey, false) && PolarisLastSource.radioToResume(this) == null) return"
once(old,old+"""
        // Automatic only. Avoid speaker playback before AA's actual connection.
        if (!polarisAaStartReady()) return""","gate the auto resume of both radio and streaming")

assert s.count("if (!polarisAaStartReady()) return")==1
assert s.count("delay(polarisAaStartDelayMs)")==1
assert "Resume on launch: last source radio=" in s
assert "Resuming playback on launch" in s
sfile.write_text(s,encoding="utf-8")
print("PASS: AA projection then the original 2500 ms before automatic play")
print("PASS: automatic radio AND streaming path delayed; manual play untouched")
print("PASS: disconnect and user intervention rechecked before play")
print("SOURCE ONLY: no APK or workflow created")
