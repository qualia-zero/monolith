package com.monolith.core.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbPathParserTest {

    @Test
    fun parseServerRootShareAndNestedFile() {
        val root = SmbPathParser.parse("smb://srv1/")
        assertEquals(ParsedSmbPath(serverId = "srv1", share = null, relativePath = ""), root)

        val shareRoot = SmbPathParser.parse("smb://srv1/Media/")
        assertEquals(ParsedSmbPath(serverId = "srv1", share = "Media", relativePath = ""), shareRoot)

        val nested = SmbPathParser.parse("smb://srv1/Media/Movies/Test Film.mkv")
        assertEquals(
            ParsedSmbPath(serverId = "srv1", share = "Media", relativePath = "Movies/Test Film.mkv"),
            nested,
        )

        assertNull(SmbPathParser.parse("/storage/emulated/0"))
        assertNull(SmbPathParser.parse("smb://"))
    }

    @Test
    fun buildAndParentPathRoundTrip() {
        val serverRoot = SmbPathParser.buildPath("srv1", isDirectory = true)
        assertEquals("smb://srv1/", serverRoot)
        assertNull(SmbPathParser.parentPath(serverRoot))

        val sharePath = SmbPathParser.buildPath("srv1", share = "Share", isDirectory = true)
        assertEquals("smb://srv1/Share/", sharePath)
        assertEquals("smb://srv1/", SmbPathParser.parentPath(sharePath))

        val filePath = SmbPathParser.buildPath("srv1", share = "Share", relativePath = "Folder/a.txt", isDirectory = false)
        assertEquals("smb://srv1/Share/Folder/a.txt", filePath)
        assertEquals("smb://srv1/Share/Folder/", SmbPathParser.parentPath(filePath))
        assertEquals("smb://srv1/Share/", SmbPathParser.parentPath("smb://srv1/Share/Folder/"))
    }

    @Test
    fun encodeSmbUrlPreservesRawSegmentsAndPort() {
        val defaultPortRoot = SmbPathParser.encodeSmbUrl(
            host = "192.168.1.20",
            port = 445,
            share = null,
            relativePath = "",
            isDirectory = true,
        )
        assertEquals("smb://192.168.1.20/", defaultPortRoot)

        val customPortFile = SmbPathParser.encodeSmbUrl(
            host = "nas.local",
            port = 1445,
            share = "My Share",
            relativePath = "100% #1/Clip [HD] äöü.mp4",
            isDirectory = false,
        )
        assertEquals(
            "smb://nas.local:1445/My Share/100% #1/Clip [HD] äöü.mp4",
            customPortFile,
        )

        val dirUrl = SmbPathParser.encodeSmbUrl(
            host = "192.168.1.20",
            port = 445,
            share = "Docs",
            relativePath = "Sub Folder",
            isDirectory = true,
        )
        assertEquals("smb://192.168.1.20/Docs/Sub Folder/", dirUrl)
    }
}
