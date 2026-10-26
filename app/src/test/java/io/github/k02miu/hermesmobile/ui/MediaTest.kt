package io.github.k02miu.hermesmobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {
    @Test fun `MEDIA lines become files between the prose`() {
        val parts = splitMedia("Here it is:\nMEDIA:/tmp/report.html\nOpen it on the phone.")
        assertEquals(
            listOf(TextPart.Prose("Here it is:\n"), TextPart.Media("/tmp/report.html"), TextPart.Prose("\nOpen it on the phone.")),
            parts,
        )
    }

    @Test fun `markdown around the tag is ignored`() {
        assertEquals(listOf(TextPart.Media("/tmp/a b.png")), splitMedia("  **MEDIA: `/tmp/a b.png`**  "))
    }

    @Test fun `MEDIA in the middle of a sentence is prose`() {
        val text = "the MEDIA: tag delivers files"
        assertEquals(listOf(TextPart.Prose(text)), splitMedia(text))
    }

    @Test fun `previewable kinds`() {
        assertTrue(canPreview("/tmp/a.HTML"))
        assertTrue(canPreview("/tmp/a.png"))
        assertTrue(canPreview("/tmp/notes.md"))
        assertFalse(canPreview("/tmp/archive.zip"))
    }
}
