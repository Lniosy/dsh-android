package com.zsdsh.dsh.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class PayloadUnpacker(private val context: Context) {
    fun hasBundle(): Boolean = try {
        context.assets.open(ASSET).use { true }
    } catch (_: Exception) {
        false
    }

    fun extract(dest: File, onProgress: ((String) -> Unit)? = null): Boolean {
        if (!hasBundle()) return false
        dest.mkdirs()
        val staging = File(dest, ".staging")
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs() && !staging.isDirectory) {
            Log.e(TAG, "cannot create staging ${staging.absolutePath}")
            return false
        }
        return try {
            onProgress?.invoke("正在展开内置运行时…")
            var linksText = ""
            context.assets.open(ASSET).use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    var seen = 0
                    var total = 0
                    while (entry != null) {
                        val name = entry.name.trimStart('/')
                        if (name.contains("..") || name.startsWith("/") || name.startsWith(".staging")) {
                            zip.closeEntry()
                            entry = zip.nextEntry
                            continue
                        }
                        if (name == "MANIFEST.txt") {
                            val text = zip.readBytes().decodeToString()
                            total = text.lineSequence()
                                .firstOrNull { it.startsWith("entries=") }
                                ?.substringAfter("=")
                                ?.toIntOrNull()
                                ?: 0
                            zip.closeEntry()
                            entry = zip.nextEntry
                            continue
                        }
                        if (name == "LINKS.txt") {
                            linksText = zip.readBytes().decodeToString()
                            zip.closeEntry()
                            entry = zip.nextEntry
                            continue
                        }
                        val out = File(staging, name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { destStream -> zip.copyTo(destStream) }
                            seen += 1
                            if (seen == 1 || seen % 400 == 0) {
                                val label = if (total > 0) "正在展开运行时 $seen/$total" else "正在展开运行时 $seen"
                                onProgress?.invoke(label)
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            applyLinks(staging, linksText)
            publish(staging, dest)
            File(dest, "runtime").walkTopDown()
                .filter { it.name == "node" && it.isFile }
                .forEach { it.setExecutable(true, true) }
            onProgress?.invoke("运行时已就绪")
            true
        } catch (e: Exception) {
            Log.e(TAG, "extract bundled payload failed", e)
            staging.deleteRecursively()
            false
        }
    }

    private fun applyLinks(staging: File, linksText: String) {
        if (linksText.isBlank()) return
        linksText.lineSequence().forEach { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size != 2) return@forEach
            val link = File(staging, parts[0])
            val target = File(staging, parts[1])
            if (!target.isFile) return@forEach
            link.parentFile?.mkdirs()
            if (link.exists()) link.delete()
            target.copyTo(link, overwrite = true)
        }
    }

    private fun publish(staging: File, dest: File) {
        listOf("runtime", "dshroot").forEach { name ->
            val from = File(staging, name)
            if (!from.exists()) return@forEach
            val to = File(dest, name)
            if (to.exists()) to.deleteRecursively()
            if (!from.renameTo(to)) {
                from.copyRecursively(to, overwrite = true)
                from.deleteRecursively()
            }
        }
        File(dest, "dshhome").mkdirs()
        staging.deleteRecursively()
    }

    companion object {
        const val ASSET = "payload.zip"
        private const val TAG = "PayloadUnpacker"
    }
}
