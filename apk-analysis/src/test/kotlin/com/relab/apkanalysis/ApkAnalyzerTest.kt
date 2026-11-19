package com.relab.apkanalysis

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkAnalyzerTest {
    @Test fun inventoriesDexNativeResourcesAndTextManifest() {
        val apk = makeApk(mapOf(
            "AndroidManifest.xml" to """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.example.demo" android:versionCode="7" android:versionName="1.2"><uses-permission android:name="android.permission.CAMERA"/><uses-permission android:name="android.permission.RECORD_AUDIO"/><application><activity android:name=".MainActivity" android:exported="true"/></application></manifest>""".toByteArray(),
            "classes.dex" to byteArrayOf(0x64,0x65,0x78,0x0a,0x30,0x33,0x35,0x00),
            "classes2.dex" to byteArrayOf(0x64,0x65,0x78,0x0a,0x30,0x33,0x39,0x00),
            "classes10.dex" to byteArrayOf(0x64,0x65,0x78,0x0a,0x30,0x33,0x39,0x00),
            "lib/arm64-v8a/libsample.so" to byteArrayOf(1,2,3), "res/layout/main.xml" to byteArrayOf(1)
        ))
        try {
            val report = ApkAnalyzer().analyze(apk)
            assertEquals("org.example.demo", report.packageName)
            assertEquals("7", report.versionCode); assertEquals("1.2", report.versionName)
            assertEquals(listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"), report.permissions)
            assertEquals("text-xml", report.manifestFormat)
            assertEquals(3, report.dexFiles.size); assertEquals(listOf("035","039","039"), report.dexVersions)
            assertEquals(listOf("libsample.so"), report.nativeLibraries["arm64-v8a"])
            assertEquals(".MainActivity", report.components.single().name)
            assertEquals(1, report.resourceEntryCount)
            assertTrue(report.securityFindings.any { it.title == "Sensitive permission declared" && it.evidence == "android.permission.CAMERA" })
            assertTrue(report.securityFindings.any { it.title == "Exported component has no component-level permission" && it.evidence.contains(".MainActivity") })
        } finally { apk.delete() }
    }

    @Test fun decodesBinaryManifestMetadata() {
        val apk = makeApk(mapOf("AndroidManifest.xml" to binaryManifestFixture()))
        try {
            val report = ApkAnalyzer().analyze(apk)
            assertEquals("binary-android-xml", report.manifestFormat)
            assertEquals("org.example.binary", report.packageName)
            assertEquals("9", report.versionCode)
            assertEquals("2.0", report.versionName)
            assertEquals(listOf("android.permission.CAMERA"), report.permissions)
            assertEquals(".HomeActivity", report.components.single().name)
            assertEquals(true, report.components.single().exported)
            assertTrue(report.securityFindings.any { it.title == "Sensitive permission declared" })
            assertTrue(report.securityFindings.any { it.title == "Exported component has no component-level permission" })
            assertTrue(report.warnings.any { it.contains("decoded") })
        } finally { apk.delete() }
    }

    private fun binaryManifestFixture(): ByteArray {
        val strings=listOf("manifest","package","org.example.binary","versionCode","versionName","9","2.0","uses-permission","name","android.permission.CAMERA","application","activity",".HomeActivity","exported")
        val ix=strings.withIndex().associate{it.value to it.index}
        fun u16(o:java.io.ByteArrayOutputStream,v:Int){o.write(v and 255);o.write(v ushr 8 and 255)}
        fun i32(o:java.io.ByteArrayOutputStream,v:Int){repeat(4){o.write(v ushr(it*8) and 255)}}
        fun chunk(type:Int,payload:ByteArray,header:Int=8):ByteArray{val o=java.io.ByteArrayOutputStream();u16(o,type);u16(o,header);i32(o,header+payload.size);o.write(payload);return o.toByteArray()}
        val encoded=strings.map{v->val bytes=v.toByteArray(Charsets.UTF_8);java.io.ByteArrayOutputStream().also{o->o.write(v.length);o.write(bytes.size);o.write(bytes);o.write(0)}.toByteArray()}
        var rel=0;val offsets=encoded.map{val x=rel;rel+=it.size;x}
        val sp=java.io.ByteArrayOutputStream();i32(sp,strings.size);i32(sp,0);i32(sp,0x100);i32(sp,28+strings.size*4);i32(sp,0);offsets.forEach{i32(sp,it)};encoded.forEach{sp.write(it)}
        val pool=chunk(1,sp.toByteArray(),28)
        fun start(tag:String,attrs:List<Triple<String,String?,Pair<Int,Int>?>>):ByteArray{
         val p=java.io.ByteArrayOutputStream();i32(p,1);i32(p,-1);i32(p,-1);i32(p,ix.getValue(tag));u16(p,20);u16(p,20);u16(p,attrs.size);u16(p,0);u16(p,0)
         attrs.forEach{(name,raw,typed)->i32(p,-1);i32(p,ix.getValue(name));i32(p,raw?.let(ix::getValue)?:-1);u16(p,8);p.write(0);p.write(typed?.first?:3);i32(p,typed?.second?:ix.getValue(raw!!))}
         return chunk(0x0102,p.toByteArray(),16)
        }
        fun end(tag:String):ByteArray{val p=java.io.ByteArrayOutputStream();i32(p,1);i32(p,-1);i32(p,-1);i32(p,ix.getValue(tag));return chunk(0x0103,p.toByteArray(),16)}
        val body=java.io.ByteArrayOutputStream();body.write(pool)
        body.write(start("manifest",listOf(Triple("package","org.example.binary",null),Triple("versionCode",null,0x10 to 9),Triple("versionName","2.0",null))))
        body.write(start("uses-permission",listOf(Triple("name","android.permission.CAMERA",null))));body.write(end("uses-permission"))
        body.write(start("application",emptyList()));body.write(start("activity",listOf(Triple("name",".HomeActivity",null),Triple("exported",null,0x12 to 1))))
        body.write(end("activity"));body.write(end("application"));body.write(end("manifest"))
        val content=body.toByteArray();val result=java.io.ByteArrayOutputStream();u16(result,3);u16(result,8);i32(result,8+content.size);result.write(content);return result.toByteArray()
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
