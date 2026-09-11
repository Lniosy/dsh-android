package com.zsdsh.dsh.engine

import android.content.Context
import android.os.Build
import java.io.File

class EnginePaths(private val context: Context) {
    val payloadDir: File = File(context.filesDir, "payload")
    val dshHome: File = File(payloadDir, "dshhome")
    val dshRoot: File = File(payloadDir, "dshroot")
    val workspace: File = File(context.getExternalFilesDir(null), "workspace")

    fun abiList(): List<String> = Build.SUPPORTED_ABIS.toList()

    fun nodeFile(): File? {
        val abis = abiList()
        val candidates = buildList {
            for (abi in abis) {
                add(File(payloadDir, "runtime/$abi/bin/node"))
                add(File(payloadDir, "runtime-$abi/bin/node"))
            }
            add(File(payloadDir, "runtime/bin/node"))
        }
        return candidates.firstOrNull { it.canExecute() || it.isFile }
    }

    fun runtimeLibDir(): File? {
        val node = nodeFile() ?: return null
        val lib = File(node.parentFile?.parentFile, "lib")
        return lib.takeIf { it.isDirectory }
    }

    fun binJs(): File? {
        val candidates = listOf(
            File(dshRoot, "node_modules/@deepseek-ai/dsh/lib/bin.js"),
            File(dshRoot, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js"),
            File(dshRoot, "lib/bin.js"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    fun ready(): Boolean = nodeFile()?.isFile == true && binJs()?.isFile == true

    fun importCandidates(): List<File> = listOf(
        File("/sdcard/dsh/payload"),
        File("/storage/emulated/0/dsh/payload"),
        File("/sdcard/ZSDSH/payload"),
        File("/storage/emulated/0/ZSDSH/payload"),
        File(context.getExternalFilesDir(null), "import-payload"),
    )

    fun importIfNeeded(): Boolean {
        if (ready()) return true
        val src = importCandidates().firstOrNull { File(it, "dshroot").isDirectory || File(it, "runtime").isDirectory }
            ?: return false
        src.copyRecursively(payloadDir, overwrite = true)
        importCredentials()
        return ready()
    }

    /** 只从 sdcard 导入 Key，绝不打进 APK。 */
    fun importCredentials(): Boolean {
        val dest = File(dshHome, ".credentials.yaml")
        if (dest.isFile && dest.length() > 20) return true
        val src = listOf(
            File("/sdcard/dsh/.credentials.yaml"),
            File("/storage/emulated/0/dsh/.credentials.yaml"),
            File("/sdcard/ZSDSH/.credentials.yaml"),
            File("/storage/emulated/0/ZSDSH/.credentials.yaml"),
            File(payloadDir.parentFile, "../payload-creds/.credentials.yaml"),
        ).firstOrNull { it.isFile && it.length() > 20 } ?: return dest.isFile
        dshHome.mkdirs()
        src.copyTo(dest, overwrite = true)
        dest.setReadable(false, false)
        dest.setReadable(true, true)
        dest.setWritable(false, false)
        dest.setWritable(true, true)
        return dest.isFile
    }

    fun ensureDirs() {
        payloadDir.mkdirs()
        dshHome.mkdirs()
        workspace.mkdirs()
    }
}
