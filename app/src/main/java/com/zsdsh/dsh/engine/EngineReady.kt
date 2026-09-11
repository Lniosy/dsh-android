package com.zsdsh.dsh.engine

import java.net.HttpURLConnection
import java.net.URL

object EngineReady {
    fun httpOk(url: String = DshEngine.URL, timeoutMs: Int = 800): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            code in 200..599
        } catch (_: Exception) {
            false
        }
    }
}
