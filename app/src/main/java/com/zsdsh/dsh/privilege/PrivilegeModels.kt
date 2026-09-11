package com.zsdsh.dsh.privilege

enum class PrivilegeKind {
    ROOT,
    SHIZUKU,
    NONE,
}

data class ExecRequest(
    val cmd: String,
    val timeoutMs: Long = 30_000,
    val prefer: PrivilegeKind? = null,
)

data class ExecResult(
    val ok: Boolean,
    val channel: PrivilegeKind,
    val code: Int = -1,
    val stdout: String = "",
    val stderr: String = "",
)

data class PrivilegeStatus(
    val active: PrivilegeKind,
    val root: Boolean,
    val shizukuBinder: Boolean,
    val shizukuGranted: Boolean,
    val suPath: String?,
    val abi: String,
)
