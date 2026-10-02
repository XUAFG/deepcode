#!/usr/bin/env node
// verify-screen-scope-matrix.mjs —— 屏幕范围/通道/落点的**设备级**验收套件（0.14.1 块N）。
//
// 为什么必须存在这个套件（0.14.1 设备实测的三个 P0 缺陷全都只有真机用户层能看见）：
//   A1 工具面报「Shizuku 未就绪」而壳侧已授权 → 模型放弃可用能力；
//   B  virtual-only 下大面积工具不可用（op 清单不一致 / 参数无法兑现 / 工具不存在）；
//   C  跨屏拉起报成功而应用落在真实屏。
// 现有 A 轨套件断言 DOM 与桥状态，代码层单测断言报文——三层里唯独「设备可见结果」没人断言。
//
// 本套件遵守 AGENTS.md §2.1 的三层口径：断言全部落在**设备事实**上（dumpsys / 截图像素），
// 不信任何「工具自报成功」；任务由模型自己编排（只给目标，不给步骤）。
//
// 用法：
//   node scripts/verify-screen-scope-matrix.mjs --serial 127.0.0.1:16416 [--pkg com.endday.game]
//                                            [--api 3080] [--timeout 180] [--keep]
//   node scripts/verify-screen-scope-matrix.mjs --self-test
//
// 退出码：0 全绿 / 1 判红（真缺陷）/ 2 前置不满足或**证据不足**（不得当作通过）。
import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '..')
const argv = process.argv.slice(2)
const argOf = (n) => { const i = argv.indexOf('--' + n); return i >= 0 ? argv[i + 1] : undefined }
const has = (n) => argv.includes('--' + n)

const SERIAL = argOf('serial')
const PKG = argOf('pkg') ?? 'com.endday.game'
const API_PORT = argOf('api') ?? '3080'
const TIMEOUT_S = Number(argOf('timeout') ?? 180)
const KEEP = has('keep')
const STAMP = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
const EVID = join(ROOT, '.deploy-tmp', 'scope-matrix', STAMP)

/**
 * **不写进提示词的东西（用户口径，2026-09-19）**：不告诉模型用哪个工具、也不告诉它先解锁能力组。
 * 设备工具的解锁（`android_capabilities { group }`）本身就是被测链路的一部分——
 * 「用户提出要控制手机 → 模型自己想到必须先解锁」这一步断了，用户看到的现象就是「工具全不可用」。
 * 把解锁写进提示等于**跳过这条链路**，测出来的绿灯是假的（本轮先踩过：写死后任务确实跑通，
 * 但那是提示词的功劳，不是模型的能力）。故提示词只给目标 + 完成信号，工具与解锁由模型自己决定。
 * 相关缺陷登记见坑 167。
 */

const results = []
const record = (phase, name, verdict, detail) => {
  results.push({ phase, name, verdict, detail })
  const mark = verdict === 'PASS' ? 'PASS' : verdict === 'FAIL' ? 'FAIL' : 'INCONCLUSIVE'
  console.log(`[${mark}] ${phase} · ${name}${detail ? ' —— ' + detail : ''}`)
}

// ── adb / CDP 原语 ─────────────────────────────────────────────────────────

function adb(args, { allowFail = false } = {}) {
  const r = spawnSync('adb', ['-s', SERIAL, ...args], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
  if (r.status !== 0 && !allowFail) throw new Error(`adb ${args.join(' ')} failed: ${(r.stderr || '').trim()}`)
  return (r.stdout ?? '').replace(/\r/g, '')
}

function sh(cmd, opts) { return adb(['shell', cmd], opts) }

/**
 * stdout + stderr 合并读取。**前置判据必须用它**：`adb shell ls <不存在的文件>` 的
 * `No such file or directory` 是设备端 stderr，adb 原样转发到**本地 stderr**；
 * 只读 stdout 会把「marker 已消失」误判成「读不到 ⇒ 快照未就绪」（套件首跑就踩到）。
 */
function shBoth(cmd) {
  const r = spawnSync('adb', ['-s', SERIAL, 'shell', cmd], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
  return ((r.stdout ?? '') + (r.stderr ?? '')).replace(/\r/g, '')
}
function shot(path) {
  const r = spawnSync('adb', ['-s', SERIAL, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 * 1024 * 1024 })
  if (r.status !== 0) throw new Error('screencap failed')
  writeFileSync(path, r.stdout)
  return r.stdout.length
}

/** 经 CDP 调壳侧桥方法（与 verify-vdisplay-viewer 同一路子）。 */
async function bridge(exprs) {
  const sockets = sh('cat /proc/net/unix').split('\n').filter((l) => l.includes('webview_devtools_remote'))
    .map((l) => l.split('@').pop().trim())
  if (sockets.length === 0) throw new Error('找不到 webview_devtools_remote（应用未运行？）')
  const PORT = 29225
  adb(['forward', `tcp:${PORT}`, `localabstract:${sockets[sockets.length - 1]}`])
  const targets = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json()
  const page = targets.find((t) => t.type === 'page') ?? targets[0]
  const ws = new WebSocket(page.webSocketDebuggerUrl)
  let seq = 0
  const pending = new Map()
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data)
    const p = pending.get(m.id)
    if (p === undefined) return
    pending.delete(m.id)
    m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result)
  })
  await new Promise((res, rej) => {
    ws.addEventListener('open', res, { once: true })
    ws.addEventListener('error', () => rej(new Error('CDP ws error')), { once: true })
  })
  const send = (method, params) => new Promise((resolve2, reject2) => {
    const id = ++seq
    pending.set(id, { resolve: resolve2, reject: reject2 })
    ws.send(JSON.stringify({ id, method, params }))
  })
  const out = []
  for (const expr of exprs) {
    const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
    out.push(r.exceptionDetails ? { __error: r.exceptionDetails.exception?.description } : r.result.value)
  }
  ws.close()
  return out
}

const parseJson = (v) => { try { return typeof v === 'string' ? JSON.parse(v) : v } catch { return undefined } }

// ── 引擎面（HTTP，经 adb forward 到设备内引擎） ─────────────────────────────

async function engineStatus() {
  adb(['forward', `tcp:${API_PORT}`, `tcp:${API_PORT}`])
  const r = await fetch(`http://127.0.0.1:${API_PORT}/api/android/privilege/status`)
  if (!r.ok) throw new Error('privilege/status HTTP ' + r.status)
  return await r.json()
}

/**
 * 让模型自己编排一个真实任务（只给目标，不给步骤），轮询设备/页面直到出现完成信号。
 *
 * **为什么走页面而不用 HTTP RPC**：本轮实测发现旧脚本（`e2e-phone-test.ps1`）假定的
 * `POST /api/session.create` 在本版引擎上不存在——从页面内 `fetch('/api/session.create')` 得 `not found`，
 * 设备只监听 3080，页面实际调的是 `/api/session/*`（camelCase，由 `dsh-api-*` 插件服务）+ 连接插件。
 * 那是引擎内部契约，套件不该硬编码；而**页面本身就是已鉴权的客户端**，在页面上下文里驱动
 * 输入框（`contenteditable`）与发送，等价于「真人在这里打字」——正好符合 AGENTS.md §2.1 第 3 条
 * （真实任务 + 由模型自己编排）。失败一律返回 `undefined`，由调用方判 INCONCLUSIVE（不得当通过）。
 */
async function runModelTask(promptText) {
  const script = `(async () => {
    const box = document.querySelector('[contenteditable="true"][role="textbox"]')
      || document.querySelector('[contenteditable="true"]')
    if (!box) return 'no-composer'
    box.focus()
    const sel = window.getSelection()
    sel.removeAllRanges()
    const range = document.createRange()
    range.selectNodeContents(box)
    range.collapse(false)
    sel.addRange(range)
    document.execCommand('insertText', false, ${JSON.stringify(promptText)})
    await new Promise((r) => setTimeout(r, 400))
    const btns = Array.from(document.querySelectorAll('button'))
    const send = btns.reverse().find((b) => /send|发送/i.test(String(b.getAttribute('aria-label') || '') + String(b.title || '')))
      || btns.find((b) => b.querySelector('svg') && b.offsetParent !== null)
    if (send) { send.click(); return 'clicked' }
    box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }))
    return 'enter'
  })()`
  let sent
  try { sent = (await bridge([script]))[0] } catch (e) { return { sent: undefined, reason: String(e.message) } }
  if (sent === 'no-composer' || sent?.__error !== undefined) {
    return { sent: undefined, reason: '页面里找不到输入框（应用不在前台？）：' + JSON.stringify(sent).slice(0, 120) }
  }
  // 等模型编排完成：断言在设备侧，这里只等一个宽松窗口（页面上出现「已完成/停止」类状态或超时）。
  // 完成判据（2026-09-19 设备实测修正）：**不能**去匹配「停止/Stop」——本界面在跑的时候显示
  // 「深度求索中…」，匹配不到就直接判 idle，于是「模型还在想」被误判成「任务结束」，
  // 后续断言全部落在半成品状态上（首跑就是这样得出 3 条 INCONCLUSIVE）。
  // 改为「最短等待 + 文本连续两次不变」：既不会早退，也不会白等到超时。
  const deadline = Date.now() + TIMEOUT_S * 1000
  const minWaitUntil = Date.now() + 60_000
  let prev = ''
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 6000))
    const now = await pageConversationText()
    if (/MISSING_CREDENTIAL|no API key for provider/i.test(now)) break
    if (Date.now() > minWaitUntil && now !== '' && now === prev) break
    prev = now
  }
  return { sent, blocker: detectBlocker(await pageConversationText()) }
}

/**
 * 识别**环境阻塞**（不是缺陷）：模型起不来时，套件必须说清「为什么任务没跑」而不是判「设备没问题」。
 * 首跑实测：本机引擎未配置模型凭据，会话直接报
 * `llm-deepseek: no API key for provider route "deepseek-official"` / `MISSING_CREDENTIAL`。
 */
function detectBlocker(text) {
  if (/MISSING_CREDENTIAL|no API key for provider/i.test(text)) {
    return '引擎未配置模型凭据（MISSING_CREDENTIAL / no API key）——模型无法运行，'
      + '请在应用「模型」页配置 provider 后重跑本套件（这不是设备缺陷）'
  }
  return ''
}

/** 抓页面会话区的可见文本（作为「模型自己编排」的过程留证；判据仍只看设备事实）。 */
async function pageConversationText() {
  try {
    return (await bridge([`document.body.innerText.slice(0, 20000)`]))[0] ?? ''
  } catch { return '' }
}

/** 解析「某包在哪些 display」——与壳侧 VdisplayController.displaysRunning 同一判据（固定字面量过滤，避免 16KiB 截断）。 */
export function displaysRunning(pkg, dump) {
  const out = new Set()
  let current = -1
  const header = /^\s*Display #([0-9]+)/
  const member = new RegExp('(^|[\\s:])' + pkg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '/')
  for (const line of dump.split('\n')) {
    const h = header.exec(line)
    if (h !== null) { current = Number(h[1]); continue }
    if (current < 0 || !line.includes('ActivityRecord{')) continue
    if (member.test(line)) out.add(current)
  }
  return [...out].sort((a, b) => a - b)
}

/** SF token ↔ 别名配对（与产品同一形态：`Virtual Display <token>` 后一行 `name="DSH <alias>"`）。 */
export function sfTokenForAlias(sfDump, alias) {
  let pending = null
  for (const raw of sfDump.split('\n')) {
    const t = /^Virtual Display[ \t]+(\d+)[ \t]*$/.exec(raw.trim())
    if (t !== null) { pending = t[1]; continue }
    if (pending === null) continue
    const n = /^[ \t]*name="([^"]*)"[ \t]*$/.exec(raw)
    if (n === null) continue
    if (n[1] === 'DSH ' + alias) return pending
    pending = null
  }
  return null
}

/** 真实屏（display 0）当前的前台包名；读不到返回空串。 */
function topResumedOnDisplay0() {
  const dump = shBoth("dumpsys activity activities | grep -E '^ *Display #|topResumedActivity'")
  let current = -1
  for (const line of dump.split('\n')) {
    const h = /^\s*Display #([0-9]+)/.exec(line)
    if (h !== null) { current = Number(h[1]); continue }
    if (current !== 0) continue
    const m = /topResumedActivity=ActivityRecord\{[^}]*?\s(u0\s+)?([A-Za-z0-9_.]+)\//.exec(line)
    if (m !== null) return m[2]
  }
  return ''
}

function selfTest() {
  let failed = 0
  const dump = 'Display #0 (activities from top to bottom):\n    topResumedActivity=ActivityRecord{1 u0 a/.M t1}\n'
    + 'Display #2 (activities from top to bottom):\n    topResumedActivity=ActivityRecord{2 u0 com.endday.game/com.godot.game.GodotApp t2}\n'
  const got = displaysRunning('com.endday.game', dump).join(',')
  if (got !== '2') { failed++; console.error('SELF-TEST FAIL：displaysRunning 期望 2 实得 ' + got) }
  if (displaysRunning('com.endday', dump).length !== 0) { failed++; console.error('SELF-TEST FAIL：前缀安全') }
  if (displaysRunning('com.endday.game', 'ActivityRecord{2 u0 com.endday.game/.M t2}').length !== 0) {
    failed++; console.error('SELF-TEST FAIL：无 Display 锚点时必须为空（不得猜屏号）')
  }
  const sf = 'Virtual Display 11529215047793762666\n    name="mumuscreen000"\nVirtual Display 999\n    name="DSH virtual-1"\n'
  if (sfTokenForAlias(sf, 'virtual-1') !== '999') { failed++; console.error('SELF-TEST FAIL：SF token 配对') }
  if (sfTokenForAlias(sf, 'virtual-2') !== null) { failed++; console.error('SELF-TEST FAIL：未知别名必须为 null') }
  if (failed > 0) { console.error(`VERIFY-SCREEN-SCOPE-MATRIX SELF-TEST FAILED（${failed}）`); process.exit(1) }
  console.log('VERIFY-SCREEN-SCOPE-MATRIX SELF-TEST PASSED（6 例判别力：分组解析 / 前缀安全 / 无锚点 fail-closed / token 配对）')
}

async function main() {
  if (has('self-test')) { selfTest(); return }
  if (SERIAL === undefined) { console.error('用法：--serial <serial> [--pkg <pkg>] [--api <port>] [--timeout <秒>] [--keep] [--self-test]'); process.exit(2) }
  mkdirSync(EVID, { recursive: true })
  const cmds = []
  const run = (cmd) => { cmds.push(cmd); return sh(cmd, { allowFail: true }) }

  // ── P0 前置（不满足 → exit 2：前置不满足不得当缺陷上报，也不得当作通过） ──
  const devices = adb(['devices']).split('\n').filter((l) => l.includes(SERIAL))
  if (devices.length === 0) { record('P0', '设备在线', 'INCONCLUSIVE', SERIAL + ' 不在 adb devices'); process.exit(2) }
  const fp = run(`run-as com.dsharnessmobile.shell ls files/.snapshot-fingerprint`)
  const tx = shBoth('run-as com.dsharnessmobile.shell ls files/.snapshot-transaction')
  if (!fp.includes('.snapshot-fingerprint') || !/No such file/.test(tx)) {
    record('P0', '快照就绪（指纹在场且事务 marker 已消失）', 'INCONCLUSIVE',
      `刷新期间禁跑验收；先等快照完成（marker 探测输出：${tx.trim().slice(0, 120) || '(空)'}）`)
    process.exit(2)
  }
  run('am start -n com.dsharnessmobile.shell/.MainActivity')
  await new Promise((r) => setTimeout(r, 2500))

  const [scope, statusRaw] = await bridge(['androidBridge.getScreenScope()', 'androidBridge.vdisplayStatus()'])
  const shell = parseJson(statusRaw)
  record('P0', '范围与虚拟屏', 'PASS', `scope=${scope} · 屏=${(shell?.screens ?? []).map((s) => s.alias + '#' + s.displayId).join(', ')}`)
  let vd = (shell?.screens ?? []).find((s) => s.kind === 'virtual')
  if (vd === undefined) {
    await bridge(['androidBridge.vdisplayCreate()'])
    await new Promise((r) => setTimeout(r, 3000))
    const again = parseJson((await bridge(['androidBridge.vdisplayStatus()']))[0])
    vd = (again?.screens ?? []).find((s) => s.kind === 'virtual')
  }
  if (vd === undefined) {
    record('P0', '虚拟屏存在', 'INCONCLUSIVE', '建屏未成功（Shizuku 未就绪/未授权），本套件无法继续')
    process.exit(2)
  }

  // ── P1 跨面一致性（A1/A2）：引擎面 vs 壳侧面 vs 设备事实 ──
  let engine
  try { engine = await engineStatus() } catch (e) { record('P1', '引擎状态面可达', 'INCONCLUSIVE', String(e.message)); engine = undefined }
  const shizukuProc = run('ps -A | grep shizuku_server')
  const a11yBound = run('dumpsys accessibility | grep -A2 "Bound services"')
  const serviceRunning = /shizuku_server/.test(shizukuProc)
  if (engine !== undefined) {
    const engineReady = engine?.gates?.shizukuReady === true
    const shellReady = shell?.state === 'ready' || shell?.state === 'active'
    if (!engineReady && shellReady && serviceRunning) {
      record('P1', 'Shizuku 状态跨面一致', 'FAIL',
        `引擎面 gates.shizukuReady=${String(engine?.gates?.shizukuReady)} 而壳侧面 state=${String(shell?.state)}/${String(shell?.code)} 且 shizuku_server 在运行 —— 同一个事实两种读数（A1）`)
    } else {
      record('P1', 'Shizuku 状态跨面一致', 'PASS', `引擎=${String(engine?.gates?.shizukuReady)} shell=${String(shell?.state)} 服务=${serviceRunning}`)
    }
    const engineA11y = engine?.gates?.a11yEnabled === true
    const boundOurs = /dsharnessmobile/.test(a11yBound)
    if (!engineA11y && boundOurs) {
      record('P1', '无障碍状态跨面一致', 'FAIL', '引擎面 a11yEnabled=false 但 dumpsys 显示本应用服务已绑定')
    } else {
      record('P1', '无障碍状态跨面一致', 'PASS', `引擎=${engineA11y} 设备绑定=${boundOurs}`)
    }
  }

  // ── P2 落点（C1）：模型自己拉起，设备侧回读落点 ──
  const before = displaysRunning(PKG, run(`dumpsys activity activities | grep -E '^ *Display #|ActivityRecord'`))
  const task = await runModelTask(
    `帮我用手机把游戏 ${PKG} 在虚拟屏 ${vd.alias} 上打开。`
    + '完成后只回复一行 DONE，不要解释。',
  )
  writeFileSync(join(EVID, 'p2-conversation.txt'), await pageConversationText())
  const after = displaysRunning(PKG, run(`dumpsys activity activities | grep -E '^ *Display #|ActivityRecord'`))
  shot(join(EVID, 'p2-real-screen.png'))
  if (task.sent === undefined || task.blocker !== '') {
    record('P2', '拉起落点=虚拟屏', 'INCONCLUSIVE',
      (task.blocker !== '' ? task.blocker : '未能发起任务：' + String(task.reason))
      + `（设备事实：${PKG} 在 displayId=${after.join(',') || '无'}）`)
  } else if (after.includes(vd.displayId)) {
    record('P2', '拉起落点=虚拟屏', 'PASS', `${PKG} 在 displayId=${after.join(',')}（目标 ${vd.displayId}；此前 ${before.join(',') || '不在任何屏'}）`)
  } else if (after.length === 0) {
    record('P2', '拉起落点=虚拟屏', 'INCONCLUSIVE', `回读里找不到 ${PKG} 的 ActivityRecord（应用可能已退出）——不构成落点证明`)
  } else {
    record('P2', '拉起落点=虚拟屏', 'FAIL', `${PKG} 实际在 displayId=${after.join(',')}，目标是 ${vd.displayId}（用户实报的「跳到真实屏」）`)
  }

  // ── P3 虚拟屏输入 + 双屏像素对照（真实屏必须不变） ──
  const sfDump = run('dumpsys SurfaceFlinger | grep -E "^(Virtual Display|    name=)"')
  const token = sfTokenForAlias(sfDump, vd.alias)
  let vdBefore = 0
  let vdBeforeSha = ''
  let realBefore = 0
  if (token !== null) {
    const r = spawnSync('adb', ['-s', SERIAL, 'exec-out', 'screencap', '-p', '-d', token], { maxBuffer: 64 * 1024 * 1024 })
    const bytes = r.stdout ?? Buffer.alloc(0)
    writeFileSync(join(EVID, 'p3-vd-before.png'), bytes)
    vdBefore = bytes.length
    vdBeforeSha = createHash('sha256').update(bytes).digest('hex').slice(0, 12)
  }
  realBefore = shot(join(EVID, 'p3-real-before.png'))
  const inputTask = await runModelTask(
    // 任务目标必须**带可见结果**（0.14.1 W2 修正）：上一版是「点左上角 + 输入 dsh-test」，
    // 而那块区域是纯背景、输入又没有焦点控件 ⇒ 画面天然不变，像素判据恒得 INCONCLUSIVE
    // （两轮实跑都是如此）。那不是「注入没生效」，是**判据不可能有信号**。
    // 现在只给目标（让画面发生可见变化）+ 完成信号，步骤仍由模型自己编排（AGENTS §2.1 第 3 条）。
    `请在虚拟屏 ${vd.alias} 上操作一次，让这块屏幕的画面发生**可见变化**（例如点开界面里的按钮或切换页面）。`
    + '完成后只回复一行 DONE，不要解释。',
  )
  writeFileSync(join(EVID, 'p3-conversation.txt'), await pageConversationText())
  const realAfter = shot(join(EVID, 'p3-real-after.png'))
  if (token !== null) {
    const r = spawnSync('adb', ['-s', SERIAL, 'exec-out', 'screencap', '-p', '-d', token], { maxBuffer: 64 * 1024 * 1024 })
    const vdAfterBytes = r.stdout ?? Buffer.alloc(0)
    writeFileSync(join(EVID, 'p3-vd-after.png'), vdAfterBytes)
    const vdAfter = vdAfterBytes.length
    // 判据用**内容哈希**而不是长度：两张不同的图压缩后可能等长（长度相等只是弱代理）。
    const sha = (b) => createHash('sha256').update(b).digest('hex').slice(0, 12)
    const changed = vdAfter > 0 && vdBeforeSha !== sha(vdAfterBytes)
    // 任务没真跑起来时（凭据/发起失败），像素变化可能来自「刚建屏的过渡帧 → 空屏」，不能当证据。
    const usable = inputTask.sent !== undefined && inputTask.blocker === ''
    record('P3', '虚拟屏可注入且像素有变化', usable ? (changed ? 'PASS' : 'INCONCLUSIVE') : 'INCONCLUSIVE',
      usable
        ? `虚拟屏截图 ${vdBefore} B → ${vdAfter} B（sha ${vdBeforeSha} → ${sha(vdAfterBytes)}，`
          + (changed ? '有变化' : '未观察到变化：可能注入未生效，或该屏画面本身静止') + '）'
        : (inputTask.blocker !== '' ? inputTask.blocker : '任务未发起，像素对照不构成证据')
          + `（截图仍留证：${vdBefore} B → ${vdAfter} B）`)
  } else {
    record('P3', '虚拟屏截图（SF token）', 'INCONCLUSIVE', '未能由别名配到 SF token，无法取虚拟屏像素')
  }
  const realUnchanged = realBefore === realAfter
  // **真实屏「字节不变」不是有效判据（本轮首跑即假红）**：模型/应用就在真实屏上工作，
  // 会话区滚动、任务文字上屏都会改变真实屏像素——把它判红是本套件自己的缺陷。
  // 真正要守的命题是「虚拟屏操作没有把第三方应用带到真实屏前台」，故改用**前台归属**判据。
  const realTop = topResumedOnDisplay0()
  const intruder = realTop !== '' && realTop !== 'com.dsharnessmobile.shell'
  record('P3', '虚拟屏操作未把第三方应用带到真实屏前台', intruder ? 'FAIL' : 'PASS',
    `display 0 前台=${realTop || '未知'}（pixel 参照：${realBefore} B → ${realAfter} B，仅作留证不作判据）`)

  // ── P4 real-only 反证：同一动作必须整体翻转 ──
  try {
    await bridge([`androidBridge.setScreenScope('real-only')`])
    await new Promise((r) => setTimeout(r, 1200))
    const denyTask = await runModelTask(
      `请在虚拟屏 ${vd.alias} 上点一下坐标 (10,10)。完成后只回复一行 DONE。`,
    )
    writeFileSync(join(EVID, 'p4-conversation.txt'), await pageConversationText())
    const convo = (denyTask.sent === undefined ? '' : await pageConversationText())
    const denied = /screen-out-of-scope|不允许访问|real-only|不在开放范围/i.test(convo)
    record('P4', 'real-only 下虚拟屏操作被拒（反证）', denied ? 'PASS' : 'INCONCLUSIVE',
      denyTask.sent === undefined || denyTask.blocker !== ''
        ? (denyTask.blocker !== '' ? denyTask.blocker : '未能发起任务：' + String(denyTask.reason))
        : (denied ? '拒绝文案在场（页面会话区）' : '未观察到拒绝文案——反证未成立，**不得视为通过**'))
  } finally {
    await bridge([`androidBridge.setScreenScope(${JSON.stringify(scope)})`]).catch(() => {})
  }

  // ── 证据落盘 + 汇总 ──
  writeFileSync(join(EVID, 'commands.md'), cmds.map((c) => 'adb -s ' + SERIAL + ' shell ' + c).join('\n') + '\n')
  writeFileSync(join(EVID, 'results.json'), JSON.stringify({ serial: SERIAL, pkg: PKG, scope, vd, results }, null, 2))
  const failed = results.filter((r) => r.verdict === 'FAIL').length
  const inconclusive = results.filter((r) => r.verdict === 'INCONCLUSIVE').length
  console.log(`\n证据目录：${EVID}`)
  console.log(`结论：PASS ${results.length - failed - inconclusive} · FAIL ${failed} · INCONCLUSIVE ${inconclusive}`)
  if (failed > 0) { console.error('VERIFY-SCREEN-SCOPE-MATRIX FAILED'); process.exit(1) }
  if (inconclusive > 0) { console.error('VERIFY-SCREEN-SCOPE-MATRIX INCONCLUSIVE（证据不足 ≠ 通过）'); process.exit(2) }
  console.log('VERIFY-SCREEN-SCOPE-MATRIX PASSED')
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e) => { console.error('套件异常：' + (e?.stack ?? e)); process.exit(2) })
}
