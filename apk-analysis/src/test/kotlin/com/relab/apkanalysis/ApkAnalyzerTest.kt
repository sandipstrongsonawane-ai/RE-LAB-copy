package com.relab.apkanalysis

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkAnalyzerTest {
    @Test fun inventoriesDexNativeResourcesAndTextManifest() {
        val apk = makeApk(mapOf(
            "AndroidManifest.xml" to """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.example.demo" android:versionCode="7" android:versionName="1.2"><uses-permission android:name="android.permission.CAMERA"/><application><activity android:name=".MainActivity" android:exported="true"/></application></manifest>""".toByteArray(),
            "classes.dex" to byteArrayOf(0x64,0x65,0x78,0x0a,0x30,0x33,0x35,0x00),
            "classes2.dex" to byteArrayOf(0x64,0x65,0x78,0x0a,0x30,0x33,0x39,0x00),
            "classes10.dex" to byteArrayOf(0x64,0x65,0x78,0x0a,0x30,0x33,0x39,0x00),
            "lib/arm64-v8a/libsample.so" to byteArrayOf(1,2,3), "res/layout/main.xml" to byteArrayOf(1)
        ))
        try {
            val report = ApkAnalyzer().analyze(apk)
            assertEquals("org.example.demo", report.packageName)
            assertEquals("7", report.versionCode); assertEquals("1.2", report.versionName)
            assertEquals(listOf("android.permission.CAMERA"), report.permissions)
            assertEquals("text-xml", report.manifestFormat)
            assertEquals(3, report.dexFiles.size); assertEquals(listOf("035","039","039"), report.dexVersions)
            assertEquals(listOf("libsample.so"), report.nativeLibraries["arm64-v8a"])
            assertEquals(".MainActivity", report.components.single().name)
            assertEquals(1, report.resourceEntryCount)
        } finally { apk.delete() }
    }

    @Test fun reportsBinaryManifestAsNotDecoded() {
        val apk = makeApk(mapOf("AndroidManifest.xml" to byteArrayOf(3,0,8,0,1,2,3,4)))
        try {
            val report = ApkAnalyzer().analyze(apk)
            assertEquals("binary-android-xml", report.manifestFormat)
            assertNull(report.packageName)
            assertTrue(report.warnings.any { it.contains("not implemented") })
        } finally { apk.delete() }
    }

    @Test fun rejectsMissingAndEmptyFiles() {
        assertThrows(IllegalArgumentException::class.java) {
            ApkAnalyzer().analyze(File(System.getProperty("java.io.tmpdir"), "no-such-relab.apk"))
        }
        val empty = File.createTempFile("relab-empty", ".apk")
        try { assertThrows(IllegalArgumentException::class.java) { ApkAnalyzer().analyze(empty) } }
        finally { empty.delete() }
    }

    private fun makeApk(entries: Map<String, ByteArray>): File {
        val file = File.createTempFile("relab-test", ".apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return file
    }
}
