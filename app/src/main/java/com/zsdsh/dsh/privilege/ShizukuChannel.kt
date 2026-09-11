package com.zsdsh.dsh.privilege

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

class ShizukuChannel {
    fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun permissionGranted(): Boolean = try {
        binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun isAvailable(): Boolean = permissionGranted()

    fun requestPermission(requestCode: Int = REQUEST_CODE) {
        if (!binderAlive()) return
        if (permissionGranted()) return
        if (Shizuku.shouldShowRequestPermissionRationale()) return
        Shizuku.requestPermission(requestCode)
    }

    fun exec(cmd: String, timeoutMs: Long): ExecResult {
        if (!isAvailable()) {
            return ExecResult(
                ok = false,
                channel = PrivilegeKind.SHIZUKU,
                stderr = if (binderAlive()) "shizuku permission denied" else "shizuku not running",
            )
        }
        return try {
            val process = newProcess(
                arrayOf("sh", "-c", cmd),
                sanitizedEnv(),
                "/",
            )
            val (code, streams) = ProcessIo.collect(process, timeoutMs)
            ExecResult(
                ok = code == 0,
                channel = PrivilegeKind.SHIZUKU,
                code = code,
                stdout = streams.first,
                stderr = streams.second,
            )
        } catch (e: Exception) {
            ExecResult(
                ok = false,
                channel = PrivilegeKind.SHIZUKU,
                stderr = e.message ?: "shizuku exec failed",
            )
        }
    }

    private fun newProcess(cmd: Array<String>, env: Array<String>, dir: String): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, cmd, env, dir) as Process
    }

    private fun sanitizedEnv(): Array<String> {
        val banned = setOf("LD_LIBRARY_PATH", "LD_PRELOAD", "LD_DEBUG")
        return System.getenv()
            .filterKeys { it !in banned }
            .map { "${it.key}=${it.value}" }
            .toTypedArray()
    }

    companion object {
        const val REQUEST_CODE = 1091
    }
}
