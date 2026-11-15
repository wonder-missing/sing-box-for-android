package io.nekohasekai.sfa.conductor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ConductorImageStoreTest {
    @Test
    fun savesAndLoadsImageBytesWithoutTouchingSessionJson() {
        val dir = Files.createTempDirectory("conductor-images").toFile()
        val store = ConductorImageStore(dir)
        val bytes = byteArrayOf(1, 2, 3, 4)

        val ref = store.save(bytes, "image/jpeg", "screenshot.jpg", 100, 200)

        assertEquals("screenshot.jpg", ref.name)
        assertEquals("image/jpeg", ref.mimeType)
        assertEquals(100, ref.width)
        assertEquals(200, ref.height)
        assertEquals(4L, ref.size)
        assertTrue(store.file(ref.id)!!.isFile)
        assertEquals(bytes.toList(), store.load(ref.id)!!.bytes.toList())

        store.clear()
        assertNull(store.load(ref.id))
        dir.deleteRecursively()
    }

    @Test
    fun rejectsPathLikeIds() {
        val dir = Files.createTempDirectory("conductor-images").toFile()
        val store = ConductorImageStore(dir)

        assertNull(store.file("../secret"))
        assertNull(store.file("a/b"))

        dir.deleteRecursively()
    }
}
