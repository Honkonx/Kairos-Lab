package com.termux.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ArchiveManagerTest {
    @Test
    fun safeRelativePathRejectsAbsoluteAndTraversalEntries() {
        listOf("../outside.txt", "folder/../../outside.txt", "/absolute.txt", "C:\\outside.txt", "folder\\..\\outside.txt")
            .forEach { raw ->
                try {
                    ArchiveManager.safeRelativePath(raw)
                    throw AssertionError("Expected unsafe path to be rejected: $raw")
                } catch (_: java.io.IOException) {
                    // Expected: archive paths must remain relative to the extraction root.
                }
            }
    }

    @Test
    fun zipAndSevenZCompressionCanBeListedAndExtracted() {
        val root = Files.createTempDirectory("kairos-archive-test").toFile()
        try {
            val sourceDir = File(root, "source").apply { mkdirs() }
            File(sourceDir, "hello.txt").writeText("Kairos archive test")
            val outputDir = File(root, "out").apply { mkdirs() }

            listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z).forEach { format ->
                val archive = File(root, "roundtrip${format.suffix}")
                ArchiveManager.compress(listOf(sourceDir), archive, format)
                val entries = ArchiveManager.list(archive)
                assertTrue(entries.any { it.path.endsWith("hello.txt") })
                val destination = File(outputDir, format.name).apply { mkdirs() }
                ArchiveManager.extractAll(archive, destination)
                assertEquals("Kairos archive test", File(destination, "source/hello.txt").readText())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun zipSlipArchiveIsRejectedWithoutWritingOutsideDestination() {
        val root = Files.createTempDirectory("kairos-archive-slip-test").toFile()
        try {
            val archive = File(root, "unsafe.zip")
            org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(archive).use { zip ->
                val entry = org.apache.commons.compress.archivers.zip.ZipArchiveEntry("../escaped.txt")
                zip.putArchiveEntry(entry)
                zip.write("no".toByteArray())
                zip.closeArchiveEntry()
            }
            val destination = File(root, "destination").apply { mkdirs() }
            try {
                ArchiveManager.extractAll(archive, destination)
                throw AssertionError("Expected Zip Slip path to be rejected")
            } catch (_: java.io.IOException) {
                // Expected.
            }
            assertFalse(File(root, "escaped.txt").exists())
            assertTrue(destination.listFiles().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tarZstdCompressionCanBeListedAndExtracted() {
        val root = Files.createTempDirectory("kairos-archive-tarzst-test").toFile()
        try {
            val sourceDir = File(root, "source").apply { mkdirs() }
            File(sourceDir, "hello.txt").writeText("Kairos tar.zst test")
            val archive = File(root, "roundtrip${ArchiveFormat.TAR_ZSTD.suffix}")
            try {
                ArchiveManager.compress(listOf(sourceDir), archive, ArchiveFormat.TAR_ZSTD)
            } catch (e: UnsatisfiedLinkError) {
                // zstd-jni solo trae la librería nativa para Android; en la JVM del host no carga.
                // Este caso se debe verificar en dispositivo (androidTest) o con la app real.
                org.junit.Assume.assumeNoException("zstd-jni no disponible en la JVM del host", e)
            }
            assertTrue(ArchiveManager.list(archive).any { it.path.endsWith("hello.txt") })
            val destination = File(root, "out").apply { mkdirs() }
            ArchiveManager.extractAll(archive, destination)
            assertEquals("Kairos tar.zst test", File(destination, "source/hello.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractionBudgetStopsAZipBombStyleStream() {
        // 3 KB de datos contra un presupuesto de 1 KB: debe cortar en vez de seguir escribiendo.
        val budget = ArchiveManager.ExtractBudget(1024)
        val input = java.io.ByteArrayInputStream(ByteArray(3 * 1024))
        val output = java.io.ByteArrayOutputStream()
        try {
            ArchiveManager.copyBounded(input, output, budget)
            throw AssertionError("Expected the size limit to be enforced")
        } catch (_: java.io.IOException) {
            // Expected.
        }
        assertTrue(output.size() <= 1024)
    }

    @Test
    fun extractionBudgetAllowsContentWithinTheLimit() {
        val budget = ArchiveManager.ExtractBudget(4096)
        val output = java.io.ByteArrayOutputStream()
        ArchiveManager.copyBounded(java.io.ByteArrayInputStream(ByteArray(2048)), output, budget)
        assertEquals(2048, output.size())
    }
}
