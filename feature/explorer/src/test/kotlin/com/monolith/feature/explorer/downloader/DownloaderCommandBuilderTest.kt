package com.monolith.feature.explorer.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderCommandBuilderTest {

    private val defaultOutputDir = "/storage/emulated/0/Download"

    @Test
    fun `defaults match bash_aliases video probe command`() {
        val defaults = DownloaderSettings()
        assertTrue(DownloaderCommandBuilder.needsVideoFormatProbe(DownloadMode.VIDEO, defaults))
        assertFalse(DownloaderCommandBuilder.needsVideoFormatProbe(DownloadMode.AUDIO, defaults))

        val probeOptions = DownloaderCommandBuilder.buildVideoProbeOptions(defaults)
        assertEquals(
            listOf(
                "--skip-download" to null,
                "--no-warnings" to null,
                "--print" to "%(ext)s",
                "--no-playlist" to null,
                "-f" to "bv[ext~='^(webm|mp4)$'][vcodec~='^(vp0?9|avc|h264)']/bv/b",
            ),
            probeOptions,
        )
    }

    @Test
    fun `parseProbedExtension trims lowercases and handles empty or NA`() {
        assertEquals("webm", DownloaderCommandBuilder.parseProbedExtension("  WEBM \n"))
        assertEquals("mp4", DownloaderCommandBuilder.parseProbedExtension("mp4\nwebm\n"))
        assertNull(DownloaderCommandBuilder.parseProbedExtension("   \n  "))
        assertNull(DownloaderCommandBuilder.parseProbedExtension("NA"))
        assertNull(DownloaderCommandBuilder.parseProbedExtension("none"))
    }

    @Test
    fun `resolveVideoFormatExpression enforces pure containers with clean fallbacks`() {
        val defaults = DownloaderSettings()
        assertEquals(
            "bv[ext=webm][vcodec~='^vp0?9']+ba[ext=webm]/bv[ext=mp4][vcodec~='^(avc|h264)']+ba[ext=m4a]/bv*+ba/b",
            DownloaderCommandBuilder.resolveVideoFormatExpression(defaults, "webm"),
        )
        assertEquals(
            "bv[ext=mp4][vcodec~='^(avc|h264)']+ba[ext=m4a]/bv[ext=webm][vcodec~='^vp0?9']+ba[ext=webm]/bv*+ba/b",
            DownloaderCommandBuilder.resolveVideoFormatExpression(defaults, "mp4"),
        )
        assertEquals(
            "bv[ext=webm][vcodec~='^vp0?9']+ba[ext=webm]/bv[ext=mp4][vcodec~='^(avc|h264)']+ba[ext=m4a]/bv*+ba/b",
            DownloaderCommandBuilder.resolveVideoFormatExpression(defaults, null),
        )
        assertEquals(
            "bv[ext=webm][vcodec~='^vp0?9']+ba[ext=webm]/bv[ext=mp4][vcodec~='^(avc|h264)']+ba[ext=m4a]/bv*+ba/b",
            DownloaderCommandBuilder.resolveVideoFormatExpression(defaults, "mkv"),
        )
    }

    @Test
    fun `default video download command matches bash_aliases without embed-thumbnail`() {
        val defaults = DownloaderSettings()
        val built = DownloaderCommandBuilder.buildDownloadCommand(
            mode = DownloadMode.VIDEO,
            settings = defaults,
            outputDirPath = "$defaultOutputDir/",
            probedExt = "webm",
        )

        assertEquals(
            listOf(
                "--newline" to null,
                "--sponsorblock-remove" to "sponsor,selfpromo",
                "--min-sleep-interval" to "3",
                "--max-sleep-interval" to "12",
                "-f" to "bv[ext=webm][vcodec~='^vp0?9']+ba[ext=webm]/bv[ext=mp4][vcodec~='^(avc|h264)']+ba[ext=m4a]/bv*+ba/b",
                "--embed-metadata" to null,
                "--embed-chapters" to null,
                "--no-playlist" to null,
                "-o" to "$defaultOutputDir/%(title)s.%(ext)s",
            ),
            built.options,
        )
        assertFalse(built.options.any { it.first == "--embed-thumbnail" })
        assertTrue(built.extraTokens.isEmpty())
    }

    @Test
    fun `default audio download command matches bash_aliases with embed-thumbnail`() {
        val defaults = DownloaderSettings()
        val built = DownloaderCommandBuilder.buildDownloadCommand(
            mode = DownloadMode.AUDIO,
            settings = defaults,
            outputDirPath = defaultOutputDir,
        )

        assertEquals(
            listOf(
                "--newline" to null,
                "--sponsorblock-remove" to "sponsor,selfpromo",
                "--min-sleep-interval" to "3",
                "--max-sleep-interval" to "12",
                "-f" to "bestaudio[ext=m4a]/bestaudio",
                "--embed-metadata" to null,
                "--embed-thumbnail" to null,
                "--embed-chapters" to null,
                "--no-playlist" to null,
                "-o" to "$defaultOutputDir/%(title)s.%(ext)s",
            ),
            built.options,
        )
    }

    @Test
    fun `height limits apply cleanly to probe and video presets`() {
        val settings1080 = DownloaderSettings(videoMaxHeight = 1080)
        val probe = DownloaderCommandBuilder.buildVideoProbeOptions(settings1080)
        assertEquals(
            "bv[height<=1080][ext~='^(webm|mp4)$'][vcodec~='^(vp0?9|avc|h264)']/bv[height<=1080]/b[height<=1080]/bv/b",
            probe.first { it.first == "-f" }.second,
        )

        assertEquals(
            "bv[height<=1080][ext=mp4][vcodec~='^(avc|h264)']+ba[ext=m4a]/bv[height<=1080][ext=webm][vcodec~='^vp0?9']+ba[ext=webm]/bv*[height<=1080]+ba/b[height<=1080]/b",
            DownloaderCommandBuilder.resolveVideoFormatExpression(settings1080, "mp4"),
        )
        assertEquals(
            "bv[height<=1080][ext=webm][vcodec~='^vp0?9']+ba[ext=webm]/bv[height<=1080][ext=mp4][vcodec~='^(avc|h264)']+ba[ext=m4a]/bv*[height<=1080]+ba/b[height<=1080]/b",
            DownloaderCommandBuilder.resolveVideoFormatExpression(settings1080, "webm"),
        )
        assertEquals(
            "bv*[height<=1080]+ba/b[height<=1080]/b",
            DownloaderCommandBuilder.resolveVideoFormatExpression(
                settings1080.copy(videoFormatPreset = VideoFormatPreset.BEST_ANY),
                null,
            ),
        )
        assertEquals(
            "bv[height<=720][ext=mp4][vcodec~='^(avc|h264)']+ba[ext=m4a]/b[height<=720][ext=mp4]/bv*[height<=720]+ba/b[height<=720]/b",
            DownloaderCommandBuilder.resolveVideoFormatExpression(
                DownloaderSettings(videoFormatPreset = VideoFormatPreset.MP4_H264, videoMaxHeight = 720),
                null,
            ),
        )
    }

    @Test
    fun `sponsorblock mark off and canonical ordering work as expected`() {
        val markSettings = DownloaderSettings(
            sponsorBlockMode = SponsorBlockMode.MARK,
            sponsorBlockCategories = setOf("outro", "sponsor", "intro", "selfpromo"),
        )
        val markCmd = DownloaderCommandBuilder.buildDownloadCommand(
            mode = DownloadMode.VIDEO,
            settings = markSettings,
            outputDirPath = defaultOutputDir,
            probedExt = "mp4",
        )
        assertEquals(
            "sponsor,selfpromo,intro,outro",
            markCmd.options.first { it.first == "--sponsorblock-mark" }.second,
        )
        assertFalse(markCmd.options.any { it.first == "--sponsorblock-remove" })

        val offSettings = DownloaderSettings(sponsorBlockMode = SponsorBlockMode.OFF)
        val offCmd = DownloaderCommandBuilder.buildDownloadCommand(
            mode = DownloadMode.VIDEO,
            settings = offSettings,
            outputDirPath = defaultOutputDir,
        )
        assertFalse(offCmd.options.any { it.first.startsWith("--sponsorblock") })
    }

    @Test
    fun `disabled sleep interval and no-playlist and thumbnail and subtitles options`() {
        val settings = DownloaderSettings(
            sleepIntervalEnabled = false,
            allowPlaylists = false,
            thumbnailEmbedMode = ThumbnailEmbedMode.ALWAYS,
            embedSubtitles = true,
            subtitleLanguages = "de,en,ja",
            embedMetadata = false,
            embedChapters = false,
        )

        val probe = DownloaderCommandBuilder.buildVideoProbeOptions(settings)
        assertTrue(probe.any { it == ("--no-playlist" to null) })
        assertFalse(probe.any { it.first == "--playlist-items" })

        val videoCmd = DownloaderCommandBuilder.buildDownloadCommand(
            mode = DownloadMode.VIDEO,
            settings = settings,
            outputDirPath = defaultOutputDir,
        )
        assertFalse(videoCmd.options.any { it.first == "--min-sleep-interval" })
        assertFalse(videoCmd.options.any { it.first == "--max-sleep-interval" })
        assertFalse(videoCmd.options.any { it.first == "--embed-metadata" })
        assertFalse(videoCmd.options.any { it.first == "--embed-chapters" })
        assertTrue(videoCmd.options.any { it == ("--embed-thumbnail" to null) })
        assertTrue(videoCmd.options.any { it == ("--embed-subs" to null) })
        assertEquals("de,en,ja", videoCmd.options.first { it.first == "--sub-langs" }.second)
        assertTrue(videoCmd.options.any { it == ("--no-playlist" to null) })

        val neverThumbCmd = DownloaderCommandBuilder.buildDownloadCommand(
            mode = DownloadMode.AUDIO,
            settings = settings.copy(thumbnailEmbedMode = ThumbnailEmbedMode.NEVER),
            outputDirPath = defaultOutputDir,
        )
        assertFalse(neverThumbCmd.options.any { it.first == "--embed-thumbnail" })
        assertFalse(neverThumbCmd.options.any { it.first == "--embed-subs" })
    }

    @Test
    fun `audio format presets convert mp3 flac opus and custom cleanly`() {
        val mp3Cmd = DownloaderCommandBuilder.buildDownloadCommand(
            mode = DownloadMode.AUDIO,
            settings = DownloaderSettings(audioFormatPreset = AudioFormatPreset.MP3_CONVERT),
            outputDirPath = defaultOutputDir,
        )
        assertTrue(mp3Cmd.options.contains("-f" to "bestaudio/best"))
        assertTrue(mp3Cmd.options.contains("-x" to null))
        assertTrue(mp3Cmd.options.contains("--audio-format" to "mp3"))
        assertTrue(mp3Cmd.options.contains("--audio-quality" to "0"))

        val flacCmd = DownloaderCommandBuilder.buildDownloadCommand(
            mode = DownloadMode.AUDIO,
            settings = DownloaderSettings(audioFormatPreset = AudioFormatPreset.FLAC_CONVERT),
            outputDirPath = defaultOutputDir,
        )
        assertTrue(flacCmd.options.contains("-f" to "bestaudio/best"))
        assertTrue(flacCmd.options.contains("-x" to null))
        assertTrue(flacCmd.options.contains("--audio-format" to "flac"))
        assertFalse(flacCmd.options.any { it.first == "--audio-quality" })

        val opusCmd = DownloaderCommandBuilder.resolveAudioFormatOptions(
            DownloaderSettings(audioFormatPreset = AudioFormatPreset.OPUS_WEBM),
        )
        assertEquals(listOf("-f" to "bestaudio[ext=webm]/bestaudio"), opusCmd)

        val customCmd = DownloaderCommandBuilder.resolveAudioFormatOptions(
            DownloaderSettings(
                audioFormatPreset = AudioFormatPreset.CUSTOM,
                customAudioFormat = "ba[abr<=160]",
            ),
        )
        assertEquals(listOf("-f" to "ba[abr<=160]"), customCmd)
    }

    @Test
    fun `tokenizeExtraArgs handles quotes escapes and whitespace`() {
        val raw = """--proxy http://127.0.0.1:8080 --postprocessor-args "ffmpeg:-c:a aac -b:a 192k" --extractor-args 'youtube:player_client=android,web' --user-agent Mozilla\ 5.0 --note "say \"hi\"" """
        val tokens = DownloaderCommandBuilder.tokenizeExtraArgs(raw)
        assertEquals(
            listOf(
                "--proxy",
                "http://127.0.0.1:8080",
                "--postprocessor-args",
                "ffmpeg:-c:a aac -b:a 192k",
                "--extractor-args",
                "youtube:player_client=android,web",
                "--user-agent",
                "Mozilla 5.0",
                "--note",
                "say \"hi\"",
            ),
            tokens,
        )
        assertTrue(DownloaderCommandBuilder.tokenizeExtraArgs("   \n\t  ").isEmpty())
    }

    @Test
    fun `parseUrls splits on whitespace filters http and deduplicates preserving order`() {
        val input = """
            https://youtu.be/abc123
            not-a-url
            http://example.com/video.mp4
            https://youtu.be/abc123
            ftp://files.example.com/a.mp4
            https://vimeo.com/999   https://
        """.trimIndent()

        assertEquals(
            listOf(
                "https://youtu.be/abc123",
                "http://example.com/video.mp4",
                "https://vimeo.com/999",
            ),
            DownloaderCommandBuilder.parseUrls(input),
        )
    }

    @Test
    fun `parseOutputFilePathFromLine and parseTitleFromPathOrLine extract paths and titles`() {
        assertEquals(
            "/storage/emulated/0/Download/Cool Video.webm",
            DownloaderCommandBuilder.parseOutputFilePathFromLine(
                """[Merger] Merging formats into "/storage/emulated/0/Download/Cool Video.webm"""",
            ),
        )
        assertEquals(
            "/storage/emulated/0/Download/Moved Clip.mp4",
            DownloaderCommandBuilder.parseOutputFilePathFromLine(
                """[MoveFiles] Moving file "/tmp/part.mp4" to "/storage/emulated/0/Download/Moved Clip.mp4"""",
            ),
        )
        assertEquals(
            "/storage/emulated/0/Download/Track 01.mp3",
            DownloaderCommandBuilder.parseOutputFilePathFromLine(
                "[ExtractAudio] Destination: /storage/emulated/0/Download/Track 01.mp3",
            ),
        )
        assertEquals(
            "/storage/emulated/0/Download/Stream.f248.webm",
            DownloaderCommandBuilder.parseOutputFilePathFromLine(
                "[download] Destination: /storage/emulated/0/Download/Stream.f248.webm",
            ),
        )
        assertEquals(
            "/storage/emulated/0/Download/Already Here.m4a",
            DownloaderCommandBuilder.parseOutputFilePathFromLine(
                "[download] /storage/emulated/0/Download/Already Here.m4a has already been downloaded",
            ),
        )
        assertNull(DownloaderCommandBuilder.parseOutputFilePathFromLine("[download]  42.0% of 10.0MiB at 1.2MiB/s ETA 00:05"))

        assertEquals(
            "Stream",
            DownloaderCommandBuilder.parseTitleFromPathOrLine("/storage/emulated/0/Download/Stream.f248.webm"),
        )
        assertEquals(
            "Cool Video",
            DownloaderCommandBuilder.parseTitleFromPathOrLine(
                """[Merger] Merging formats into "/storage/emulated/0/Download/Cool Video.webm"""",
            ),
        )
        assertNull(DownloaderCommandBuilder.parseTitleFromPathOrLine("[youtube] abc: Downloading webpage"))
    }

    @Test
    fun `parseStatusDetailFromLine maps yt-dlp phases to German labels`() {
        assertEquals(
            "SponsorBlock wird angewendet…",
            DownloaderCommandBuilder.parseStatusDetailFromLine("[SponsorBlock] Removing 2 segments from video"),
        )
        assertEquals(
            "Streams werden zusammengeführt…",
            DownloaderCommandBuilder.parseStatusDetailFromLine("""[Merger] Merging formats into "a.webm""""),
        )
        assertEquals(
            "Metadaten werden eingebettet…",
            DownloaderCommandBuilder.parseStatusDetailFromLine("[Metadata] Adding metadata to \"a.m4a\""),
        )
        assertEquals(
            "Vorschaubild wird eingebettet…",
            DownloaderCommandBuilder.parseStatusDetailFromLine("[EmbedThumbnail] ffmpeg: Adding thumbnail to \"a.m4a\""),
        )
        assertEquals(
            "Audio wird extrahiert…",
            DownloaderCommandBuilder.parseStatusDetailFromLine("[ExtractAudio] Destination: a.mp3"),
        )
        assertEquals(
            "Wartezeit (5.42 s)…",
            DownloaderCommandBuilder.parseStatusDetailFromLine("[download] Sleeping 5.42 seconds as required by the site..."),
        )
        assertNull(DownloaderCommandBuilder.parseStatusDetailFromLine("[download]  55.0% of 12.00MiB"))
    }

    @Test
    fun `parseDownloadMetricsFromLine detectStreamLabel and isSleepLine work cleanly`() {
        assertEquals(
            "2.10MiB/s · 15.23MiB",
            DownloaderCommandBuilder.parseDownloadMetricsFromLine(
                "[download]  42.3% of   15.23MiB at    2.10MiB/s ETA 00:04",
            ),
        )
        assertEquals(
            "15.23MiB",
            DownloaderCommandBuilder.parseDownloadMetricsFromLine(
                "[download]  10.0% of ~15.23MiB at Unknown B/s ETA Unknown",
            ),
        )
        assertNull(DownloaderCommandBuilder.parseDownloadMetricsFromLine("[Merger] Merging formats"))

        assertEquals(
            "Video-Stream",
            DownloaderCommandBuilder.detectStreamLabel("/dl/Clip.f248.webm", DownloadMode.VIDEO, 1),
        )
        assertEquals(
            "Audio-Stream",
            DownloaderCommandBuilder.detectStreamLabel("/dl/Clip.f251.webm", DownloadMode.VIDEO, 2),
        )
        assertEquals(
            "Audio-Stream",
            DownloaderCommandBuilder.detectStreamLabel("/dl/Clip.f140.m4a", DownloadMode.VIDEO, 1),
        )
        assertEquals(
            "Audio-Stream",
            DownloaderCommandBuilder.detectStreamLabel("/dl/Track.m4a", DownloadMode.AUDIO, 1),
        )

        assertTrue(DownloaderCommandBuilder.isSleepLine("[download] Sleeping 4.21 seconds ..."))
        assertFalse(DownloaderCommandBuilder.isSleepLine("[download] 42.0% of 10MiB"))
    }
}
