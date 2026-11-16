#!/usr/bin/env python3
"""Polaris 6.07: replace malformed phone mini-bar glyphs with symmetric drawable icons.

Apply only after the complete, verified 6.06 integration. No audio engine,
radio streams, playlists, database, voice routing, or AA browser code is changed.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1]).resolve()
src=root/"app/src/main/java/com/dd3boh/outertune/polaris/PolarisShell.kt"
text=src.read_text(encoding="utf-8")

def once(s, old, new, label):
    count=s.count(old)
    assert count==1, f"{label}: expected unique text, got {count}"
    return s.replace(old,new,1)

text=once(text,
    "import androidx.compose.ui.unit.dp\n",
    "import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.res.painterResource\nimport com.dd3boh.outertune.R\n",
    "Compose vector painter imports")
before='''                TextButton(onClick={connection.player.seekToPreviousMediaItem()}){Text("◀")}
                TextButton(onClick={if(playing)connection.player.pause()else{connection.player.prepare();connection.player.play()}}){Text(if(playing)"Ⅱ" else "▶")}
                TextButton(onClick={connection.player.seekToNextMediaItem()}){Text("▶▶")}
'''
after='''                // Exactly matching 40dp targets and 24dp artwork on both sides.
                // No typographic arrows, Unicode pause symbols, or font-dependent sizing.
                IconButton(
                    onClick = { connection.player.seekToPreviousMediaItem() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_previous),
                        contentDescription = "이전 라디오 채널",
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(
                    onClick = {
                        if (playing) connection.player.pause()
                        else { connection.player.prepare(); connection.player.play() }
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        painter = painterResource(if (playing) R.drawable.pause else R.drawable.play),
                        contentDescription = if (playing) "일시정지" else "재생",
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(
                    onClick = { connection.player.seekToNextMediaItem() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_next),
                        contentDescription = "다음 라디오 채널",
                        modifier = Modifier.size(24.dp),
                    )
                }
'''
text=once(text,before,after,"radio mini controls")
assert 'Text("◀")' not in text and 'Text("▶▶")' not in text and '"Ⅱ"' not in text
assert text.count("modifier = Modifier.size(40.dp)") == 3
assert text.count("modifier = Modifier.size(24.dp)") == 3
src.write_text(text,encoding="utf-8")

gradle=root/"app/build.gradle.kts"
build=gradle.read_text(encoding="utf-8")
build=once(build,"versionCode = 100606","versionCode = 100607","versionCode")
build=once(build,'versionName = "6.06"','versionName = "6.07"',"versionName")
gradle.write_text(build,encoding="utf-8")

report=root/"app/src/androidTest/java/com/dd3boh/outertune/polaris/PolarisSessionTest.kt"
body=report.read_text(encoding="utf-8")
body=once(body,"Polaris 6.06 (versionCode 100606)","Polaris 6.07 (versionCode 100607)","report version")
report.write_text(body,encoding="utf-8")

# Regression check against the actually rendered phone-mini Composable.
unit=root/"app/src/test/java/com/dd3boh/outertune/polaris/PolarisRadioMini607Test.kt"
unit.parent.mkdir(parents=True,exist_ok=True)
unit.write_text('''package com.dd3boh.outertune.polaris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PolarisRadioMini607Test {
    private val code = File("src/main/java/com/dd3boh/outertune/polaris/PolarisShell.kt")
        .readText().substringAfter("fun PolarisRadioMini(")

    @Test fun threeEquallySizedRealControlsWithMirroredSkips() {
        assertEquals(3, code.split("IconButton(").size - 1)
        assertEquals(3, code.split("Modifier.size(40.dp)").size - 1)
        assertEquals(3, code.split("Modifier.size(24.dp)").size - 1)
        assertTrue(code.contains("painterResource(R.drawable.skip_previous)"))
        assertTrue(code.contains("painterResource(R.drawable.skip_next)"))
        assertTrue(code.contains("painterResource(if (playing) R.drawable.pause else R.drawable.play)"))
    }
    @Test fun noTypographicControlsAndSamePlaybackCommands() {
        assertFalse(code.contains("◀"))
        assertFalse(code.contains("▶▶"))
        assertFalse(code.contains("Ⅱ"))
        assertTrue(code.contains("seekToPreviousMediaItem()"))
        assertTrue(code.contains("seekToNextMediaItem()"))
        assertTrue(code.contains("connection.player.pause()"))
        assertTrue(code.contains("connection.player.prepare(); connection.player.play()"))
    }
}
''',encoding="utf-8")
print("PASS: Polaris 6.07 symmetric, icon-based mini controls; 6.06 streaming/radio untouched")
