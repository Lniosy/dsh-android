package com.zsdsh.dsh.privilege

import android.os.Build
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object ProcessIo {
    private val pool = Executors.newCachedThreadPool()

    fun readLimited(stream: InputStream, limit: Int = 512 * 1024): String {
        val bytes = stream.readBytes()
        val clipped = if (bytes.size > limit) bytes.copyOf(limit) else bytes
        return clipped.toString(Charset.forName("UTF-8"))
    }

    fun await(process: Process, timeoutMs: Long): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                return process.exitValue()
            }
            process.destroyForcibly()
            return -1
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                return process.exitValue()
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(50)
            }
        }
        process.destroy()
        return -1
    }

    fun collect(process: Process, timeoutMs: Long): Pair<Int, Pair<String, String>> {
        val out = pool.submit(Callable { readLimited(process.inputStream) })
        val err = pool.submit(Callable { readLimited(process.errorStream) })
        val code = await(process, timeoutMs)
        return code to (out.get(2, TimeUnit.SECONDS) to err.get(2, TimeUnit.SECONDS))
    }
}
