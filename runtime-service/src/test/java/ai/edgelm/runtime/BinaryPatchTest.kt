package ai.edgelm.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File

/** Pure-JVM round-trip test for the EDLT1 delta applier. Run: ./gradlew :runtime-service:testDebugUnitTest */
class BinaryPatchTest {

    private fun tmp(bytes: ByteArray): File =
        File.createTempFile("edgelm-patch", ".bin").apply { writeBytes(bytes); deleteOnExit() }

    @Test fun `apply reconstructs NEW from OLD via copy + add`() {
        val old = tmp("HELLO WORLD".toByteArray())
        val delta = File.createTempFile("edgelm", ".delta").apply { deleteOnExit() }
        DataOutputStream(delta.outputStream()).use {
            it.write("EDLT1\n".toByteArray())
            it.writeByte('C'.code); it.writeLong(0); it.writeLong(5)          // copy "HELLO" from old
            val add = " THERE".toByteArray()
            it.writeByte('A'.code); it.writeLong(add.size.toLong()); it.write(add)  // append " THERE"
        }
        val out = File.createTempFile("edgelm", ".out").apply { deleteOnExit() }
        BinaryPatch.apply(old, delta, out)
        assertEquals("HELLO THERE", out.readText())
    }

    @Test fun `copy from the middle of old works`() {
        val old = tmp("0123456789".toByteArray())
        val delta = File.createTempFile("edgelm", ".delta").apply { deleteOnExit() }
        DataOutputStream(delta.outputStream()).use {
            it.write("EDLT1\n".toByteArray())
            it.writeByte('C'.code); it.writeLong(3); it.writeLong(4)          // "3456"
        }
        val out = File.createTempFile("edgelm", ".out").apply { deleteOnExit() }
        BinaryPatch.apply(old, delta, out)
        assertEquals("3456", out.readText())
    }

    @Test fun `isDelta detects the magic header`() {
        val d = File.createTempFile("edgelm", ".delta").apply { writeBytes("EDLT1\n....".toByteArray()); deleteOnExit() }
        val notD = tmp("just a gguf".toByteArray())
        assertTrue(BinaryPatch.isDelta(d))
        assertFalse(BinaryPatch.isDelta(notD))
    }
}
