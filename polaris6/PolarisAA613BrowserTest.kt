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
class PolarisAA613BrowserTest {
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
            assertEquals(2,get("polaris:radio:world:v613").size)
        } finally {
            instrumentation.runOnMainSync { MediaController.releaseFuture(connected) }
        }
    }
}
