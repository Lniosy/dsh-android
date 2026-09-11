import { spawn as cpSpawn } from "node:child_process"

/** Android 无真正 PTY，用 child_process 顶上，保证 subprocess 服务能起来。 */
export function spawn(file, args = [], options = {}) {
  const child = cpSpawn(file, args, {
    cwd: options.cwd,
    env: options.env,
    stdio: ["pipe", "pipe", "pipe"],
  })
  const dataFns = []
  const exitFns = []
  const emitData = (chunk) => {
    const text = Buffer.isBuffer(chunk) ? chunk.toString("utf8") : String(chunk)
    for (const fn of dataFns) fn(text)
  }
  child.stdout?.on("data", emitData)
  child.stderr?.on("data", emitData)
  child.on("exit", (code, signal) => {
    for (const fn of exitFns) fn({ exitCode: code ?? 1, signal: signal ?? 0 })
  })
  return {
    pid: child.pid ?? -1,
    write(data) {
      child.stdin?.write(data)
    },
    kill(sig) {
      try {
        child.kill(sig)
      } catch {}
    },
    onData(fn) {
      dataFns.push(fn)
      return { dispose() {} }
    },
    onExit(fn) {
      exitFns.push(fn)
      return { dispose() {} }
    },
    resize() {},
  }
}
