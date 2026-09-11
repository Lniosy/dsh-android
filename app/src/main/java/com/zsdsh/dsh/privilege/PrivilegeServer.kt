package com.zsdsh.dsh.privilege

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 只绑 127.0.0.1，给本机 DSH 插件补权限。
 * GET  /v1/status
 * POST /v1/exec  {"cmd":"...","timeoutMs":30000,"prefer":"ROOT|SHIZUKU"}
 */
class PrivilegeServer(
    private val privilege: PrivilegeManager,
    private val port: Int = PORT,
) {
    @Volatile
    private var running = false
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        if (running) return
        running = true
        thread(name = "zsdsh-privilege", isDaemon = true) {
            try {
                val socket = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
                server = socket
                Log.i(TAG, "privilege bridge on 127.0.0.1:$port")
                while (running) {
                    val client = socket.accept()
                    pool.execute {
                        client.use { handle(it.getInputStream(), it.getOutputStream()) }
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "privilege server failed", e)
            }
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
    }

    private fun handle(input: java.io.InputStream, output: java.io.OutputStream) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: "GET"
        val path = parts.getOrNull(1) ?: "/"
        var contentLength = 0
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }
        val body = if (contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read)
        } else {
            ""
        }
        val (code, json) = dispatch(method, path, body)
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        writer.write("HTTP/1.1 $code OK\r\n")
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("Content-Length: ${payload.size}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
        output.write(payload)
        output.flush()
    }

    private fun dispatch(method: String, path: String, body: String): Pair<Int, JSONObject> {
        return try {
            when {
                method == "GET" && path.startsWith("/v1/status") -> 200 to statusJson()
                method == "POST" && path.startsWith("/v1/exec") -> 200 to execJson(body)
                else -> 404 to JSONObject().put("ok", false).put("error", "not found")
            }
        } catch (e: Exception) {
            400 to JSONObject().put("ok", false).put("error", e.message ?: "bad request")
        }
    }

    private fun statusJson(): JSONObject {
        val s = privilege.status()
        return JSONObject()
            .put("ok", true)
            .put("active", s.active.name)
            .put("root", s.root)
            .put("shizukuBinder", s.shizukuBinder)
            .put("shizukuGranted", s.shizukuGranted)
            .put("suPath", s.suPath ?: JSONObject.NULL)
            .put("abi", s.abi)
            .put("port", port)
    }

    private fun execJson(body: String): JSONObject {
        val obj = JSONObject(body.ifBlank { "{}" })
        val prefer = obj.optString("prefer").takeIf { it.isNotBlank() }?.let {
            runCatching { PrivilegeKind.valueOf(it) }.getOrNull()
        }
        val result = privilege.exec(
            ExecRequest(
                cmd = obj.getString("cmd"),
                timeoutMs = obj.optLong("timeoutMs", 30_000),
                prefer = prefer,
            ),
        )
        return JSONObject()
            .put("ok", result.ok)
            .put("channel", result.channel.name)
            .put("code", result.code)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
    }

    companion object {
        const val PORT = 3091
        private const val TAG = "ZsdshPrivilege"
    }
}
