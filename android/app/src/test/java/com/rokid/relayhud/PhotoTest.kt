package com.rokid.relayhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoTest {
    @Test fun `photo voice command matches per language`() {
        assertTrue(matchesPhoto("take photo", "en"))
        assertTrue(matchesPhoto("Take a picture.", "en"))
        assertTrue(matchesPhoto("look at this", "en"))
        assertTrue(matchesPhoto("拍照", "zh"))
        assertTrue(matchesPhoto("拍个照。", "zh"))
        assertFalse(matchesPhoto("take photo of my code and refactor it", "en"))
        assertFalse(matchesPhoto("photograph", "en"))
        assertFalse(matchesPhoto("take photo", "zh"))   // 语言隔离,同现有口令的行为
    }

    @Test fun `photoAck parses into PhotoAck message`() {
        val ok = parseServerMessage("""{"type":"photoAck","file":"./photos/photo-1.jpg"}""")
        assertTrue(ok is ServerMessage.PhotoAck)
        assertEquals("./photos/photo-1.jpg", (ok as ServerMessage.PhotoAck).file)

        val fail = parseServerMessage("""{"type":"photoAck","file":""}""")
        assertEquals("", (fail as ServerMessage.PhotoAck).file)
    }
}
