package com.zsdsh.dsh.engine

import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class DshEngine(private val context: Context) {
    val paths = EnginePaths(context)
    private val started = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null

    @Volatile
    var lastError: String? = null
        private set

    fun isRunning(): Boolean = process?.isAlive == true

    fun start(): Boolean {
        if (isRunning()) return true
        paths.ensureDirs()
        paths.importIfNeeded()
        paths.importCredentials()
        val node = paths.nodeFile()
        val bin = paths.binJs()
        if (node == null || !node.isFile) {
            lastError = "缺少 Node 运行时。请安装完整 APK，或按 ABI=${paths.abiList()} 放入 files/payload/runtime/<abi>/bin/node"
            return false
        }
        if (bin == null) {
            lastError = "缺少 DSH 0.1.5 内核：payload/dshroot/node_modules/@deepseek-ai/dsh/lib/bin.js"
            return false
        }
        if (!node.canExecute()) {
            node.setExecutable(true, true)
        }
        linkHomeNodeModules()
        overlayBundledPlugin()
        overlayAndroidShims()
        return try {
            val env = HashMap(System.getenv())
            paths.runtimeLibDir()?.let { env["LD_LIBRARY_PATH"] = it.absolutePath }
            env["DSH_HOME"] = paths.dshHome.absolutePath
            env["DSH_WORKSPACE"] = paths.workspace.absolutePath
            env["HOME"] = paths.dshHome.absolutePath
            env["TMPDIR"] = context.cacheDir.absolutePath
            env["NODE_PATH"] = java.io.File(paths.dshRoot, "node_modules").absolutePath
            val path = env["PATH"].orEmpty()
            env["PATH"] = listOf("/system/bin", "/system/xbin", "/vendor/bin", path)
                .filter { it.isNotBlank() }
                .joinToString(":")
            val command = listOf(
                node.absolutePath,
                "--expose-internals",
                bin.absolutePath,
                "web",
                "--host", "127.0.0.1",
                "--port", PORT.toString(),
                "--no-open",
            )
            val log = java.io.File(paths.payloadDir, "engine.log")
            val builder = ProcessBuilder(command)
                .directory(paths.workspace)
                .redirectErrorStream(true)
                .redirectOutput(log)
            builder.environment().clear()
            builder.environment().putAll(env)
            process = builder.start()
            Thread.sleep(250)
            if (process?.isAlive != true) {
                lastError = log.takeIf { it.isFile }?.readLines()?.lastOrNull().orEmpty()
                    .ifBlank { "node 启动后立刻退出" }
                process = null
                started.set(false)
                Log.e(TAG, "dsh exited immediately: $lastError")
                return false
            }
            started.set(true)
            lastError = null
            Log.i(TAG, "dsh started abi=${Build.SUPPORTED_ABIS.firstOrNull()} pid=${pidOf(process)}")
            true
        } catch (e: Exception) {
            lastError = e.message ?: "engine start failed"
            Log.e(TAG, "dsh start failed", e)
            false
        }
    }

    private fun overlayAndroidShims() {
        writeAsset("cordis.patch.yml", java.io.File(paths.dshHome, "cordis.patch.yml"))
        val ptyDir = java.io.File(paths.dshRoot, "node_modules/node-pty")
        ptyDir.mkdirs()
        writeAsset("node-pty.js", java.io.File(ptyDir, "index.js"))
        writeAsset("node-pty.package.json", java.io.File(ptyDir, "package.json"))
        writeAsset("koffi.js", java.io.File(paths.dshRoot, "node_modules/koffi/index.js"))
        writeAsset(
            "node-addon-system-flock.js",
            java.io.File(paths.dshRoot, "node_modules/@deepseek-ai/node-addon-system/lib/flock.js"),
        )
        patchBashLocalSandboxMode()
        patchBashLocalShell()
        patchSessionPersistenceCopy()
    }

    private fun patchBashLocalShell() {
        val file = java.io.File(paths.dshRoot, "node_modules/@deepseek-ai/dsh-bash-local/lib/index.js")
        if (!file.isFile) return
        var text = file.readText()
        if (text.contains("zsdsh-android-sh")) return
        val next = text.replace(
            "\"bash\",\n\t\t\t\"-c\",",
            "\"/system/bin/sh\", /* zsdsh-android-sh */\n\t\t\t\"-c\",",
        )
        if (next != text) file.writeText(next)
    }

    private fun patchSessionPersistenceCopy() {
        val file = java.io.File(
            paths.dshRoot,
            "node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
        )
        if (!file.isFile) return
        var text = file.readText()
        if (text.contains("zsdsh-copyfile-fallback")) return
        val broken = """try { await link(tmp, finalPath); } catch (linkErr) {
			try { await rename(tmp, finalPath); } catch (renameErr) { throw linkErr; }
		}"""
        val fixed = """try { await link(tmp, finalPath); } catch (linkErr) {
			const { copyFile } = await import("node:fs/promises");
			await copyFile(tmp, finalPath); /* zsdsh-copyfile-fallback */
		}"""
        if (text.contains(broken)) {
            text = text.replace(broken, fixed)
        } else {
            text = text.replace("await link(tmp, finalPath);", """try { await link(tmp, finalPath); } catch (linkErr) {
			const { copyFile } = await import("node:fs/promises");
			await copyFile(tmp, finalPath); /* zsdsh-copyfile-fallback */
		}""")
        }
        val broken2 = """try { await internals.fs.rename(staged, currentPath); } catch (renameErr) {
			if (isEEXIST(renameErr)) return false;
			throw linkErr;
		}"""
        if (text.contains(broken2)) {
            text = text.replace(
                broken2,
                """const { copyFile } = await import("node:fs/promises");
		await copyFile(staged, currentPath); /* zsdsh-copyfile-fallback-current */""",
            )
        } else if (text.contains("await internals.fs.link(staged, currentPath);") &&
            !text.contains("zsdsh-copyfile-fallback-current")
        ) {
            text = text.replace(
                "await internals.fs.link(staged, currentPath);",
                """try { await internals.fs.link(staged, currentPath); } catch (linkErr) {
		if (typeof isEEXIST === "function" && isEEXIST(linkErr)) return false;
		const { copyFile } = await import("node:fs/promises");
		await copyFile(staged, currentPath); /* zsdsh-copyfile-fallback-current */
	}""",
            )
        }
        file.writeText(text)
    }

    private fun patchBashLocalSandboxMode() {
        val file = java.io.File(paths.dshRoot, "node_modules/@deepseek-ai/dsh-bash-local/lib/index.js")
        if (!file.isFile) return
        val needle = """static inject = ["subprocess"];"""
        val patch = """$needle
	get sandboxMode() { return "danger-full-access"; }"""
        val text = file.readText()
        if (text.contains("""get sandboxMode() { return "danger-full-access"; }""")) return
        if (!text.contains(needle)) return
        file.writeText(text.replace(needle, patch))
    }

    private fun writeAsset(name: String, dest: java.io.File) {
        dest.parentFile?.mkdirs()
        try {
            context.assets.open(name).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "write asset $name failed", e)
        }
    }

    private fun overlayBundledPlugin() {
        val dest = java.io.File(
            paths.dshRoot,
            "node_modules/@zsdsh/dsh-tool-android/lib/index.js",
        )
        dest.parentFile?.mkdirs()
        try {
            context.assets.open("dsh-tool-android.js").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "overlay plugin failed", e)
        }
    }

    private fun linkHomeNodeModules() {
        val link = java.io.File(paths.dshHome, "node_modules")
        val target = java.io.File(paths.dshRoot, "node_modules")
        if (link.exists() || !target.isDirectory) return
        try {
            android.system.Os.symlink(target.absolutePath, link.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "symlink node_modules failed, copying plugin only", e)
            val pluginSrc = java.io.File(target, "@zsdsh/dsh-tool-android")
            val pluginDst = java.io.File(link, "@zsdsh/dsh-tool-android")
            if (pluginSrc.isDirectory) {
                pluginDst.parentFile?.mkdirs()
                pluginSrc.copyRecursively(pluginDst, overwrite = true)
            }
        }
    }

    fun stop() {
        process?.destroyForcibly()
        process = null
        started.set(false)
    }

    private fun pidOf(process: Process?): String {
        return try {
            process?.javaClass?.getMethod("pid")?.invoke(process)?.toString() ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }

    fun uiUrl(): String {
        val log = java.io.File(paths.payloadDir, "engine.log")
        if (!log.isFile) return URL
        val line = log.readLines().lastOrNull { it.contains("dsh web: http://") } ?: return URL
        val match = Regex("""https?://\S+""").find(line)
        return match?.value?.trim() ?: URL
    }

    companion object {
        const val PORT = 3080
        const val URL = "http://127.0.0.1:$PORT"
        private const val TAG = "ZsdshEngine"
    }
}
