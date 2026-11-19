package com.relab.apkanalysis

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
private const val MAX_ARCHIVE_ENTRIES = 100_000

data class ApkComponent(val type: String, val name: String, val exported: Boolean?)

data class ApkAnalysisReport(
    val fileName: String, val apkSizeBytes: Long, val archiveEntryCount: Int,
    val uncompressedBytes: Long, val dexFiles: List<String>, val dexVersions: List<String>,
    val nativeLibraries: Map<String, List<String>>, val resourceEntryCount: Int,
    val manifestFormat: String, val packageName: String?, val versionCode: String?,
    val versionName: String?, val permissions: List<String>,
    val components: List<ApkComponent>, val warnings: List<String>
)

/** Read-only offline APK inventory. It never extracts files or executes app code. */
class ApkAnalyzer {
    fun analyze(apk: File): ApkAnalysisReport {
        require(apk.isFile) { "APK file does not exist or is not a regular file." }
        require(apk.length() > 0L) { "APK file is empty." }
        val warnings = mutableListOf<String>()
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= MAX_ARCHIVE_ENTRIES) { "APK archive has too many entries." }
            val uncompressed = entries.fold(0L) { total, entry ->
                if (entry.size > 0L && Long.MAX_VALUE - total >= entry.size) total + entry.size else total
            }
            val dex = entries.filter {
                val name = it.name.substringAfterLast('/')
                name == "classes.dex" || Regex("""classes([2-9]|[1-9][0-9]+)\.dex""").matches(name)
            }.sortedBy { it.name }
            val dexVersions = dex.mapNotNull { entry ->
                val header = zip.getInputStream(entry).use { it.readNBytes(8) }
                if (header.size == 8 && header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x64,0x65,0x78,0x0a))) {
                    String(header, 4, 3, Charsets.US_ASCII)
                } else { warnings += "Malformed DEX header: ${entry.name}"; null }
            }
            val native = entries.mapNotNull { entry ->
                val parts = entry.name.split('/')
                if (parts.size >= 3 && parts[0] == "lib" && parts.last().endsWith(".so")) parts[1] to parts.last() else null
            }.groupBy({it.first},{it.second}).mapValues { it.value.distinct().sorted() }.toSortedMap()
            var format = "missing"
            var packageName: String? = null
            var versionCode: String? = null
            var versionName: String? = null
            val permissions = mutableListOf<String>()
            val components = mutableListOf<ApkComponent>()
            val manifest = zip.getEntry("AndroidManifest.xml")
            if (manifest == null) warnings += "AndroidManifest.xml is missing."
            else if (manifest.size > MAX_MANIFEST_BYTES) {
                format = "oversized"; warnings += "AndroidManifest.xml exceeds the inspection limit."
            } else {
                val bytes = zip.getInputStream(manifest).use { it.readNBytes(MAX_MANIFEST_BYTES + 1) }
                if (bytes.size > MAX_MANIFEST_BYTES) {
                    format = "oversized"; warnings += "AndroidManifest.xml exceeds the inspection limit."
                } else if (bytes.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar() == '<') {
                    format = "text-xml"
                    try {
                        val factory = DocumentBuilderFactory.newInstance().apply {
                            isNamespaceAware = true
                            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                            setFeature("http://xml.org/sax/features/external-general-entities", false)
                            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                        }
                        val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
                        packageName = root.getAttribute("package").takeIf(String::isNotBlank)
                        versionCode = root.getAttributeNS(ANDROID_NS, "versionCode").takeIf(String::isNotBlank)
                        versionName = root.getAttributeNS(ANDROID_NS, "versionName").takeIf(String::isNotBlank)
                        listOf("uses-permission","uses-permission-sdk-23","uses-permission-sdk-m").forEach { tag ->
                            val nodes = root.getElementsByTagName(tag)
                            for (i in 0 until nodes.length) (nodes.item(i) as? Element)
                                ?.getAttributeNS(ANDROID_NS, "name")?.takeIf(String::isNotBlank)?.let(permissions::add)
                        }
                        listOf("activity","activity-alias","service","receiver","provider").forEach { tag ->
                            val nodes = root.getElementsByTagName(tag)
                            for (i in 0 until nodes.length) {
                                val element = nodes.item(i) as? Element ?: continue
                                val name = element.getAttributeNS(ANDROID_NS, "name")
                                if (name.isNotBlank()) {
                                    val exported = element.getAttributeNS(ANDROID_NS, "exported").takeIf(String::isNotBlank)?.toBooleanStrictOrNull()
                                    components += ApkComponent(tag, name, exported)
                                }
                            }
                        }
                    } catch (error: Exception) {
                        warnings += "Text manifest parse failed: ${error.javaClass.simpleName}"
                    }
                } else {
                    format = "binary-android-xml"
                    warnings += "Binary Android XML manifest detected; metadata decoding is not implemented yet."
                }
            }
            if (dex.isEmpty()) warnings += "No DEX bytecode files were found."
            warnings += "Static inventory only; signing, obfuscation, dynamic loading and runtime behavior are not assessed."
            return ApkAnalysisReport(
                apk.name, apk.length(), entries.size, uncompressed, dex.map { it.name }, dexVersions,
                native, entries.count { it.name.startsWith("res/") || it.name == "resources.arsc" },
                format, packageName, versionCode, versionName, permissions.distinct().sorted(),
                components.sortedWith(compareBy<ApkComponent> { it.type }.thenBy { it.name }), warnings.distinct()
            )
        }
    }
}
