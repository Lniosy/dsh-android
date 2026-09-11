import { defineTool } from '@deepseek-ai/dsh-tools'

export const name = 'zsdsh-android'
export const inject = ['tools']

const BRIDGE = 'http://127.0.0.1:3091'
const objectSchema = { type: 'object', additionalProperties: true }

async function call(path, body) {
  const res = await fetch(`${BRIDGE}${path}`, {
    method: body ? 'POST' : 'GET',
    headers: { 'content-type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  })
  const json = await res.json()
  if (!res.ok && json?.error) {
    throw new Error(json.error)
  }
  return json
}

function renderJson(_args, value) {
  return [{ type: 'text', text: JSON.stringify(value, null, 2) }]
}

export function apply(ctx) {
  ctx.inject(['workspaceRegistry'], (wctx) => {
    const dir = process.env.DSH_WORKSPACE
    if (!dir) return
    Promise.resolve()
      .then(async () => {
        if (wctx.workspaceRegistry.list().length > 0) return
        await wctx.workspaceRegistry.create(dir, 'Android')
        wctx.logger.info('zsdsh-android: created default workspace')
      })
      .catch((err) => {
        wctx.logger.warn(`zsdsh-android: workspace bootstrap failed: ${err}`)
      })
  })

  ctx.tools.register(defineTool({
    name: 'android_privilege_status',
    description: '查询当前特权通道：Root 优先，Shizuku 兜底。无特权时提示用户授权。',
    parameters: {},
    output: { schema: objectSchema, render: renderJson },
    async execute() {
      return await call('/v1/status')
    },
  }))

  ctx.tools.register(defineTool({
    name: 'android_shell',
    description: '在 Root 或 Shizuku 通道执行一条 shell。默认 Root 优先。不要传交互式程序。',
    parameters: {
      cmd: { type: 'string', required: true, description: '要执行的 shell 命令' },
      prefer: { type: 'string', description: 'ROOT 或 SHIZUKU' },
      timeoutMs: { type: 'number', description: '超时毫秒，默认 30000' },
    },
    output: { schema: objectSchema, render: renderJson },
    async execute(args) {
      return await call('/v1/exec', {
        cmd: args.cmd,
        prefer: args.prefer,
        timeoutMs: args.timeoutMs || 30000,
      })
    },
  }))

  ctx.tools.register(defineTool({
    name: 'android_screenshot',
    description: '特权截图到 /sdcard/dsh/screen.png 并返回路径。',
    parameters: {},
    output: { schema: objectSchema, render: renderJson },
    async execute() {
      const path = '/sdcard/dsh/screen.png'
      const prep = await call('/v1/exec', { cmd: 'mkdir -p /sdcard/dsh' })
      const shot = await call('/v1/exec', { cmd: `screencap -p ${path}` })
      return { prep, shot, path }
    },
  }))

  ctx.tools.register(defineTool({
    name: 'android_input',
    description: '特权模拟输入：tap / swipe / text / keyevent。',
    parameters: {
      action: { type: 'string', required: true, description: 'tap|swipe|text|keyevent' },
      x: { type: 'number' },
      y: { type: 'number' },
      x2: { type: 'number' },
      y2: { type: 'number' },
      text: { type: 'string' },
      key: { type: 'string', description: '如 KEYCODE_BACK / KEYCODE_HOME' },
    },
    output: { schema: objectSchema, render: renderJson },
    async execute(args) {
      let cmd
      switch (args.action) {
        case 'tap':
          cmd = `input tap ${args.x} ${args.y}`
          break
        case 'swipe':
          cmd = `input swipe ${args.x} ${args.y} ${args.x2} ${args.y2}`
          break
        case 'text':
          cmd = `input text ${JSON.stringify(String(args.text || '').replace(/ /g, '%s'))}`
          break
        case 'keyevent':
          cmd = `input keyevent ${args.key || 'KEYCODE_BACK'}`
          break
        default:
          throw new Error(`unknown action: ${args.action}`)
      }
      return await call('/v1/exec', { cmd })
    },
  }))

  ctx.tools.register(defineTool({
    name: 'android_app',
    description: '特权应用操作：list / launch / force_stop。',
    parameters: {
      action: { type: 'string', required: true, description: 'list|launch|force_stop' },
      packageName: { type: 'string' },
    },
    output: { schema: objectSchema, render: renderJson },
    async execute(args) {
      const pkg = args.packageName || ''
      let cmd
      switch (args.action) {
        case 'list':
          cmd = 'pm list packages -3'
          break
        case 'launch':
          cmd = `monkey -p ${pkg} -c android.intent.category.LAUNCHER 1`
          break
        case 'force_stop':
          cmd = `am force-stop ${pkg}`
          break
        default:
          throw new Error(`unknown action: ${args.action}`)
      }
      return await call('/v1/exec', { cmd })
    },
  }))
}
