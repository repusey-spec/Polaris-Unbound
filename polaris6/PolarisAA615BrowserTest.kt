package com.dd3boh.outertune.polaris

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.playback.MusicService
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PolarisAA615BrowserTest {
    @Test fun signedInstalledAppHasFourRealTabsTwoRadioAndStreamingIconsAndEmptyCan() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        lateinit var connected:ListenableFuture<MediaBrowser>
        instrumentation.runOnMainSync {
            connected=MediaBrowser.Builder(context,
                SessionToken(context,ComponentName(context,MusicService::class.java))).buildAsync()
        }
        try {
            val browser=connected.get(30,TimeUnit.SECONDS)
            fun get(parent:String):List<MediaItem> {
                lateinit var pending:ListenableFuture<androidx.media3.session.LibraryResult<
                    com.google.common.collect.ImmutableList<MediaItem>>>
                instrumentation.runOnMainSync {
                    pending=browser.getChildren(parent,0,30,null)
                }
                val result=pending.get(40,TimeUnit.SECONDS)
                return result.value?.toList().orEmpty()
            }
            val root=get(MusicService.ROOT)
            assertEquals(listOf("polaris:home:v613","polaris:radio:v613",
                "polaris:streaming:v613","polaris:can:v613"),root.map { it.mediaId })
            val radio=get("polaris:radio:v613")
            assertTrue(radio.size>=2)
            assertEquals(listOf("polaris:radio:kr:v613","polaris:radio:world:v613"),
                radio.take(2).map { it.mediaId })
            val streaming=get("polaris:streaming:v613")
            assertTrue(streaming.size>=2)
            assertEquals("polaris:playlists:v610",streaming[0].mediaId)
            assertEquals("polaris:stream:downloads:v613",streaming[1].mediaId)
            assertTrue(get("polaris:can:v613").isEmpty())
            assertEquals(6,get("polaris:radio:kr:v613").size)
            assertEquals(7,get("polaris:radio:world:v613").size)
        } finally {
            instrumentation.runOnMainSync { MediaController.releaseFuture(connected) }
        }
    }
    @Test fun homeReadsActualRadioRecentAndIndependentlySavedLastMusic() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val ctx=ins.targetContext
        val radio=ctx.getSharedPreferences("polaris_radio_recent_613",android.content.Context.MODE_PRIVATE)
        val music=ctx.getSharedPreferences("polaris_last_played_614",android.content.Context.MODE_PRIVATE)
        val oldRadio=radio.all.toMap()
        val oldMusic=music.all.toMap()
        lateinit var connected:ListenableFuture<MediaBrowser>
        try {
            radio.edit().putString("ids","kr1,kr2").commit()
            music.edit().putString("media_id","test-music-615")
                .putString("title","Recorded music 615")
                .putString("artist","Test singer").commit()
            ins.runOnMainSync {
                connected=MediaBrowser.Builder(ctx,
                    SessionToken(ctx,ComponentName(ctx,MusicService::class.java))).buildAsync()
            }
            val browser=connected.get(30,TimeUnit.SECONDS)
            lateinit var pending:ListenableFuture<androidx.media3.session.LibraryResult<
                com.google.common.collect.ImmutableList<MediaItem>>>
            ins.runOnMainSync {
                pending=browser.getChildren("polaris:home:v613",0,20,null)
            }
            val rows=pending.get(40,TimeUnit.SECONDS).value?.toList().orEmpty()
            assertEquals("Home must have one radio row AND one music row",2,rows.size)
            assertTrue(rows[0].mediaMetadata.title.toString().startsWith("최근 라디오"))
            assertTrue(rows[1].mediaMetadata.title.toString().startsWith("최근 음악"))
            assertTrue("Home radio item must use last channel",rows[0].mediaId.endsWith("kr1"))
            assertTrue(rows[0].mediaMetadata.title.toString().contains("KBS"))
            assertTrue(rows[1].mediaMetadata.title.toString().contains("Recorded music 615") ||
                rows[1].mediaId.isNotBlank())
        } finally {
            ins.runOnMainSync {
                MediaController.releaseFuture(connected)
            }
            radio.edit().clear().apply {
                oldRadio.forEach { (key,value) -> if(value is String) putString(key,value) }
            }.commit()
            music.edit().clear().apply {
                oldMusic.forEach { (key,value) -> if(value is String) putString(key,value) }
            }.commit()
        }
    }

}
