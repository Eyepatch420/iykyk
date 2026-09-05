package com.example.ikyky.people

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.StandardDispatcherProvider
import com.example.ikyky.core.storage.impl.CacheRepresentativeImageStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 7 — [CacheRepresentativeImageStorage] lifecycle: writes land in the
 * app-private cache dir (never MediaStore/gallery), the returned Uri is
 * immediately readable, `clear()` actually removes files, and different
 * sessions/persons never collide.
 */
@RunWith(AndroidJUnit4::class)
class RepresentativeImageTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun storage() = CacheRepresentativeImageStorage(context, StandardDispatcherProvider())

    private fun bitmap(color: Int = android.graphics.Color.RED): Bitmap {
        val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        b.eraseColor(color)
        return b
    }

    @Test
    fun save_writesUnderAppCacheDir_notMediaStore() = runBlocking {
        val result = storage().save("session_1", "person_0", bitmap())
        assertTrue(result is AppResult.Success)
        val uri = (result as AppResult.Success).value
        assertEquals("file", uri.scheme)
        assertTrue(
            "must live under the app cache dir, was ${uri.path}",
            uri.path.orEmpty().startsWith(context.cacheDir.path),
        )
    }

    @Test
    fun save_producesAReadableFile() = runBlocking {
        val result = storage().save("session_1", "person_0", bitmap()) as AppResult.Success
        val file = File(result.value.path!!)
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
    }

    @Test
    fun differentPersonsInTheSameSession_getDistinctFiles() = runBlocking {
        val s = storage()
        val a = (s.save("session_1", "person_0", bitmap()) as AppResult.Success).value
        val b = (s.save("session_1", "person_1", bitmap()) as AppResult.Success).value
        assertFalse(a == b)
    }

    @Test
    fun sameSessionAndPerson_overwritesRatherThanAccumulating() = runBlocking {
        val s = storage()
        val first = (s.save("session_x", "person_0", bitmap(android.graphics.Color.RED)) as AppResult.Success).value
        val second = (s.save("session_x", "person_0", bitmap(android.graphics.Color.BLUE)) as AppResult.Success).value
        assertEquals(first, second)
    }

    @Test
    fun clear_removesEveryFileForThatSession() = runBlocking {
        val s = storage()
        val saved = (s.save("session_clear", "person_0", bitmap()) as AppResult.Success).value
        val file = File(saved.path!!)
        assertTrue(file.exists())
        s.clear("session_clear")
        assertFalse(file.exists())
    }

    @Test
    fun clear_doesNotAffectOtherSessions() = runBlocking {
        val s = storage()
        val keep = (s.save("session_keep", "person_0", bitmap()) as AppResult.Success).value
        s.save("session_gone", "person_0", bitmap())
        s.clear("session_gone")
        assertTrue(File(keep.path!!).exists())
    }
}
