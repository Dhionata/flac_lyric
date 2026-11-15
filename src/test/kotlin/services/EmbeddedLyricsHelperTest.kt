package services

import comparison.infrastructure.audio.AudioMetadataExtractorImpl
import java.io.ByteArrayOutputStream
import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EmbeddedLyricsHelperTest {

    @TempDir
    lateinit var tempDir: File

    companion object {
        /**
         * Creates a minimal valid FLAC file in [target] that jaudiotagger can read and tag.
         */
        fun createMinimalFlac(target: File): File {
            val out = ByteArrayOutputStream()
            // Magic "fLaC"
            out.write("fLaC".toByteArray(Charsets.US_ASCII))
            // Metadata block header: last block (0x80) + STREAMINFO (0x00), length = 34 (0x000022)
            out.write(0x80)
            out.write(0x00)
            out.write(0x00)
            out.write(0x22)
            // STREAMINFO (34 bytes):
            // min blocksize = 4096 (0x1000), max blocksize = 4096 (0x1000)
            out.write(byteArrayOf(0x10, 0x00, 0x10, 0x00))
            // min framesize (3) + max framesize (3) = 0
            out.write(ByteArray(6))
            // sample rate = 44100 (0x0AC44), channels - 1 = 1, bps - 1 = 15, total samples = 44100 * 180 (3min)
            // 20 bits sampleRate: 0x0AC44 -> bytes: 0x0A, 0xC4, (0x4 << 4) | (1 << 1) | 0 = 0x42, (0x0F << 4) | 0 = 0xF0
            val totalSamples = 44100L * 180L
            out.write(0x0A)
            out.write(0xC4)
            out.write(0x42)
            out.write(0xF0 or ((totalSamples ushr 32).toInt() and 0x0F))
            out.write(((totalSamples ushr 24) and 0xFF).toInt())
            out.write(((totalSamples ushr 16) and 0xFF).toInt())
            out.write(((totalSamples ushr 8) and 0xFF).toInt())
            out.write((totalSamples and 0xFF).toInt())
            // MD5 (16 bytes)
            out.write(ByteArray(16))
            // Audio section bytes (> 100 bytes so jaudiotagger accepts the minimal FLAC file)
            out.write(byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x69, 0x00))
            out.write(ByteArray(2048))

            target.writeBytes(out.toByteArray())
            return target
        }
    }

    @Test
    fun `test detects and removes embedded timestamped LYRICS while preserving other tags`() {
        val flac = createMinimalFlac(File(tempDir, "Roberto Carlos - Detalhes.flac"))
        val audioObj = AudioFileIO.read(flac)
        val tag = audioObj.tagOrCreateAndSetDefault
        tag.setField(FieldKey.ARTIST, "Roberto Carlos")
        tag.setField(FieldKey.TITLE, "Detalhes")
        tag.setField(FieldKey.ALBUM, "Roberto Carlos")
        tag.setField(
            FieldKey.LYRICS,
            "[00:00.00] ...\n[00:12.72] Não adianta nem tentar\n[00:17.61] Me esquecer"
        )
        audioObj.commit()

        val extractor = AudioMetadataExtractorImpl()
        val beforeMeta = extractor.extractMetadata(flac)
        assertTrue(beforeMeta.hasEmbeddedSyncedLyrics)
        assertTrue(beforeMeta.embeddedLyricTags.contains("LYRICS"))

        val detected = EmbeddedLyricsHelper.inspectEmbeddedSyncedLyrics(flac)
        assertNotNull(detected)
        assertEquals(listOf("LYRICS"), detected!!.tagKeys)
        assertTrue(detected.content.contains("[00:12.72]"))

        val removed = EmbeddedLyricsHelper.removeEmbeddedSyncedLyrics(flac)
        assertNotNull(removed)
        assertEquals(listOf("LYRICS"), removed!!.tagKeys)

        // Confirm timestamped LYRICS are gone while ARTIST/TITLE/ALBUM stay intact
        assertNull(EmbeddedLyricsHelper.inspectEmbeddedSyncedLyrics(flac))
        assertNull(EmbeddedLyricsHelper.removeEmbeddedSyncedLyrics(flac))

        val afterMeta = extractor.extractMetadata(flac)
        assertFalse(afterMeta.hasEmbeddedSyncedLyrics)
        assertEquals("Detalhes", afterMeta.title)
        assertEquals("Roberto Carlos", afterMeta.artist)
        assertEquals("Roberto Carlos", afterMeta.album)
    }

    @Test
    fun `test ignores plain embedded LYRICS without timestamps`() {
        val flac = createMinimalFlac(File(tempDir, "Artist - Plain.flac"))
        val audioObj = AudioFileIO.read(flac)
        val tag = audioObj.tagOrCreateAndSetDefault
        tag.setField(FieldKey.ARTIST, "Artist")
        tag.setField(FieldKey.TITLE, "Plain")
        tag.setField(FieldKey.LYRICS, "First verse without timestamps\nSecond verse without timestamps")
        audioObj.commit()

        assertNull(EmbeddedLyricsHelper.inspectEmbeddedSyncedLyrics(flac))
        assertNull(EmbeddedLyricsHelper.removeEmbeddedSyncedLyrics(flac))

        val reread = AudioFileIO.read(flac)
        assertTrue(reread.tag.getFirst(FieldKey.LYRICS).contains("First verse without timestamps"))
    }

    @Test
    fun `test opus file is cleanly skipped as SkippedUnsupported without throwing`() {
        val opus = File(tempDir, "Track Without Tag Support.opus").apply {
            writeBytes(byteArrayOf(0x4F, 0x67, 0x67, 0x53, 0x00))
        }

        val outcome = EmbeddedLyricsHelper.removeEmbeddedSyncedLyricsWithOutcome(opus)
        assertTrue(outcome is EmbeddedCleanupOutcome.SkippedUnsupported)
        assertEquals("opus", (outcome as EmbeddedCleanupOutcome.SkippedUnsupported).extension)
        assertNull(EmbeddedLyricsHelper.inspectEmbeddedSyncedLyrics(opus))
        assertFalse(EmbeddedLyricsHelper.isTagEditingSupported(opus))
    }

    @Test
    fun `test corrupt ogg file throws exception attributed with file name and full path`() {
        val corruptOgg = File(tempDir, "Broken Track.ogg").apply {
            writeText("NOT_A_VALID_OGG_IDENTIFICATION_HEADER", Charsets.UTF_8)
        }

        val ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) {
            EmbeddedLyricsHelper.removeEmbeddedSyncedLyricsWithOutcome(corruptOgg)
        }
        val msg = ex.message.orEmpty()
        assertTrue(msg.contains(corruptOgg.name), "Expected file name in exception message: $msg")
        assertTrue(msg.contains(corruptOgg.absolutePath), "Expected full path in exception message: $msg")
    }

    @Test
    fun `test ogg file containing OpusHead stream is cleanly skipped as ogg-opus without throwing`() {
        val oggOpusHeader = ByteArray(48)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(oggOpusHeader, 0)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(oggOpusHeader, 28)

        val disguisedOpus = File(tempDir, "Song Disguised.ogg").apply {
            writeBytes(oggOpusHeader)
        }

        assertTrue(EmbeddedLyricsHelper.isOggOpusContainer(disguisedOpus))
        assertFalse(EmbeddedLyricsHelper.isTagEditingSupported(disguisedOpus))
        val outcome = EmbeddedLyricsHelper.removeEmbeddedSyncedLyricsWithOutcome(disguisedOpus)
        assertTrue(outcome is EmbeddedCleanupOutcome.SkippedUnsupported)
        val skipped = outcome as EmbeddedCleanupOutcome.SkippedUnsupported
        assertEquals("ogg-opus", skipped.extension)
        assertEquals("audit.reason.ogg_opus_container", skipped.reasonKey)
    }
}
