package com.zsdsh.dsh.privilege

import android.app.Application
import android.os.Build

class PrivilegeManager(private val app: Application) {
    val root = RootChannel()
    val shizuku = ShizukuChannel()

    fun kind(): PrivilegeKind = when {
        root.isAvailable() -> PrivilegeKind.ROOT
        shizuku.isAvailable() -> PrivilegeKind.SHIZUKU
        else -> PrivilegeKind.NONE
    }

    fun status(): PrivilegeStatus = PrivilegeStatus(
        active = kind(),
        root = root.isAvailable(),
        shizukuBinder = shizuku.binderAlive(),
        shizukuGranted = shizuku.permissionGranted(),
        suPath = root.suPath(),
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
    )

    fun exec(request: ExecRequest): ExecResult {
        if (request.cmd.isBlank()) {
            return ExecResult(ok = false, channel = PrivilegeKind.NONE, stderr = "empty command")
        }
        val order = when (request.prefer) {
            PrivilegeKind.SHIZUKU -> listOf(PrivilegeKind.SHIZUKU, PrivilegeKind.ROOT)
            PrivilegeKind.ROOT -> listOf(PrivilegeKind.ROOT, PrivilegeKind.SHIZUKU)
            else -> listOf(PrivilegeKind.ROOT, PrivilegeKind.SHIZUKU)
        }
        var last = ExecResult(ok = false, channel = PrivilegeKind.NONE, stderr = "no privilege channel")
        for (kind in order) {
            val channelReady = when (kind) {
                PrivilegeKind.ROOT -> root.isAvailable()
                PrivilegeKind.SHIZUKU -> shizuku.isAvailable()
                PrivilegeKind.NONE -> false
            }
            if (!channelReady) continue
            last = when (kind) {
                PrivilegeKind.ROOT -> root.exec(request.cmd, request.timeoutMs)
                PrivilegeKind.SHIZUKU -> shizuku.exec(request.cmd, request.timeoutMs)
                PrivilegeKind.NONE -> last
            }
            if (last.ok) return last
        }
        return last
    }
}
