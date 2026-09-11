package com.zsdsh.dsh.privilege

import java.io.File

class RootChannel {
    @Volatile
    private var cachedSu: String? = null

    @Volatile
    private var lastProbeOk = false

    fun suPath(): String? {
        cachedSu?.let { return it }
        val found = CANDIDATES.firstOrNull { File(it).canExecute() }
            ?: CANDIDATES.firstOrNull { File(it).exists() }
            ?: "su"
        cachedSu = found
        return found
    }

    fun isAvailable(): Boolean {
        val su = suPath() ?: return false
        if (lastProbeOk) return true
        lastProbeOk = probe(su)
        return lastProbeOk
    }

    fun exec(cmd: String, timeoutMs: Long): ExecResult {
        val su = suPath() ?: return ExecResult(
            ok = false,
            channel = PrivilegeKind.ROOT,
            stderr = "su not found",
        )
        return try {
            val process = ProcessBuilder(su, "-c", cmd)
                .redirectErrorStream(false)
                .start()
            val (code, streams) = ProcessIo.collect(process, timeoutMs)
            ExecResult(
                ok = code == 0,
                channel = PrivilegeKind.ROOT,
                code = code,
                stdout = streams.first,
                stderr = streams.second,
            )
        } catch (e: Exception) {
            lastProbeOk = false
            ExecResult(
                ok = false,
                channel = PrivilegeKind.ROOT,
                stderr = e.message ?: "root exec failed",
            )
        }
    }

    private fun probe(su: String): Boolean {
        return try {
            val process = ProcessBuilder(su, "-c", "id -u")
                .redirectErrorStream(true)
                .start()
            val (code, streams) = ProcessIo.collect(process, 4_000)
            code == 0 && streams.first.trim() == "0"
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val CANDIDATES = listOf(
            "/product/bin/su",
            "/debug_ramdisk/su",
            "/debug_ramdisk/magisk",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/system/bin/magisk",
        )
    }
}
