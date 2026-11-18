package com.dd3boh.outertune.polaris

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.playback.MusicService
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Runs against the actual minified release service on Android, not a mocked AA tree. */
@RunWith(AndroidJUnit4::class)
class PolarisSessionTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block:()->T):T {
        var result:Result<T>?=null
        instrumentation.runOnMainSync {result=runCatching(block)}
        return result!!.getOrThrow()
    }
    @Test fun libraryAndVoiceShareTheSingleRadioSession() {
        val context=instrumentation.targetContext
        val pending=main { MediaBrowser.Builder(context,SessionToken(context,ComponentName(context,MusicService::class.java))).buildAsync() }
        val browser=pending.get(15,TimeUnit.SECONDS)
        try {
            assertEquals(20_000L,PolarisRadioRequests.queueItem("kr1").liveConfiguration.targetOffsetMs)
            val root=main {browser.getLibraryRoot(null)}.get(10,TimeUnit.SECONDS)
            assertEquals(0,root.resultCode)
            val folders=main {browser.getChildren(root.value!!.mediaId,0,100,null)}.get(10,TimeUnit.SECONDS)
            assertEquals(0,folders.resultCode)
            assertEquals(listOf(RadioCatalog.RADIO_BROWSE_ROOT_ID, RadioCatalog.STREAMING_BROWSE_ROOT_ID),folders.value!!.map{it.mediaId})
            val channels=main {browser.getChildren(RadioCatalog.RADIO_ROOT_ID,0,100,null)}.get(10,TimeUnit.SECONDS)
            assertEquals(0,channels.resultCode)
            assertEquals(13,channels.value!!.size)
            channels.value!!.forEach {
                assertEquals(false,it.mediaMetadata.isBrowsable)
                assertEquals(true,it.mediaMetadata.isPlayable)
                assertNotNull(PolarisRadioRequests.stationId(it))
            }
            val item=main {browser.getItem("polaris:station:kr5")}.get(10,TimeUnit.SECONDS)
            assertEquals(0,item.resultCode)
            assertEquals("polaris:station:kr5",item.value!!.mediaId)
            // Resolve the spoken command and queue without prepare/play or a network stream.
            main {
                browser.setMediaItem(MediaItem.Builder().setMediaId("").setRequestMetadata(
                    MediaItem.RequestMetadata.Builder().setSearchQuery("폴라리스에서 AFN 102.7 틀어줘").build()).build())
            }
            val until=System.currentTimeMillis()+5000
            while(System.currentTimeMillis()<until && main{browser.currentMediaItem?.mediaId}!="polaris:station:kr5")Thread.sleep(100)
            assertEquals("polaris:station:kr5",main{browser.currentMediaItem?.mediaId})
            assertEquals(13,main{browser.mediaItemCount})
            assertFalse(main{browser.playWhenReady})
            val report=PolarisErrorReport.dump(context)
            assertTrue(report.startsWith("Polaris 6.09 (versionCode 100609)"))
            assertTrue(report.contains("=== PROCESS EXIT HISTORY ==="))
            assertTrue(report.contains("AA-radio-request station=kr5"))
        } finally {main{browser.release()}}
    }
}
