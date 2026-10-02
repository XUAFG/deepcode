// check-snapshot-secrets.mjs — 快照敏感内容门禁（跨平台；Windows 走 wsl/win tar、Linux 原生 tar）
//
// 对应原 scripts/check-snapshot-secrets.ps1（PowerShell + cmd/findstr，仅 Windows 可跑）。
// 改为 Node + lib.shell 的 sh()：同一逻辑跑本地/云端，避免两套漂移。
// 检查（路径级 + settings.yaml 内容级）：credentials / sessions / storages / anon-id /
// settings.yaml 内容（不得含 sk-/apiKey 实际值/私钥头）/ 私有源映射 / npmrc。
//
// review C3（2026-09-14）实锤：旧实现在 `tar -tJf` 失败时 catch 后**静默继续**——在空成员列表上
// 跑全部匹配必然全 PASS，实测垃圾文件也 PASS exit 0（无机密防线）。现收紧：
//   ① 归档不可读 / 成员列表为空 / 不是发行快照（无 usr/ 前缀 / 无引擎包）→ **硬失败**，不接受 SKIP 结案；
//   ② 支持 `--require`（构建链/发布链传参）：任何降级（settings.yaml 读取失败）即失败；
//   ③ SKIP 全部计数并打印汇总（ST-31；发布链要求 SKIP=0）。
//
// 用法：node scripts/check-snapshot-secrets.mjs <snapshot.tar.xz> [--require]
// 退出 0 = 通过；1 = 检出敏感内容或归档不可判定；2 = 用法错误。
import { wslPath, sh } from './lib/shell.mjs'

const args = process.argv.slice(2)
const snap = args.find((a) => !a.startsWith('--'))
const REQUIRE = args.includes('--require') || process.env.DSH_REQUIRE_SNAPSHOT_SECRETS === '1'
if (!snap) {
  console.error('用法: node scripts/check-snapshot-secrets.mjs <snapshot.tar.xz> [--require]')
  process.exit(2)
}
const abs = wslPath(snap)

let skipped = 0
function failHard(msg) {
  console.error('FAIL[archive]: ' + msg)
  console.error('SNAPSHOT_SECRET_CHECK_FAILED')
  process.exit(1)
}

let listing = ''
try {
  listing = sh(`tar -tJf "${abs}" 2>/dev/null`, { maxBuffer: 96 * 1024 * 1024 })
} catch (error) {
  failHard('归档不可读（tar -tJf 失败）：' + String(error).slice(0, 240)
    + '\n  旧实现把这里当「全不命中」→ 空成员列表上全部检查全 PASS（review C3 的假绿）；现直接判红')
}
const lines = listing.split('\n').map((l) => l.trim()).filter(Boolean)
if (lines.length === 0) failHard('归档成员列表为空（不是 tar.xz / 解压失败）')
if (!lines.some((l) => l.startsWith('usr/'))) failHard('成员列表里没有 usr/ 前缀——看起来不是发行快照（拒绝空集假绿）')
if (!lines.some((l) => l.includes('node_modules/@deepseek-ai/dsh/'))) failHard('成员列表里没有 @deepseek-ai/dsh 引擎包——不是发行快照')

let fail = false
function failOut(name, hits) {
  console.error(`FAIL[${name}]: ${hits.slice(0, 3).join('; ')}`)
  fail = true
}

// 路径级敏感文件（在单一 listing 内匹配）
function pathHits(re) { return lines.filter((l) => re.test(l)) }

const creds = pathHits(/home\/\.dsh\/\.credentials/i)
if (creds.length) failOut('credentials', creds)
const sess = pathHits(/home\/\.dsh\/sessions\//i)
if (sess.length) failOut('sessions', sess)
const stor = pathHits(/home\/\.dsh\/storages\//i)
if (stor.length) failOut('storages', stor)
const anon = pathHits(/\.anonymous-user-id/i)
if (anon.length) failOut('anon-id', anon)
const npmrc = pathHits(/home\/\.npmrc/i)
if (npmrc.length) failOut('npmrc', npmrc)

// settings.yaml 内容级（0.13.0 C1/Q14）：允许非机密 seed 模板存在，但不得含真实凭据形态
const importedSettings = lines.filter((path) => path.split('/').pop()?.toLowerCase() === 'settings.yaml.imported')
if (importedSettings.length) failOut('imported-user-settings', importedSettings)
const hasSettings = lines.filter((path) => path === 'home/.dsh/settings.yaml')
if (hasSettings.length) {
  let content = ''
  let readFailed = false
  try { content = sh(`tar -xOf "${abs}" home/.dsh/settings.yaml 2>/dev/null`) } catch { readFailed = true }
  if (readFailed || content.trim() === '') {
    const msg = 'settings.yaml 内容读取为空/失败（归档部分损坏？）——存在性检查已命中，内容级检查未执行'
    if (REQUIRE) failHard(msg + '\n  --require 档不得以 SKIP 结案')
    skipped += 1
    console.log('SKIP(#' + skipped + ')  ' + msg)
  } else {
    const leakRe = /(sk-|apiKey\s*:\s*\S|api[_-]?key\s*=\s*\S|BEGIN (RSA|OPENSSH|PRIVATE)|dsh web: \S*\/\?token=[A-Za-z0-9_-]{40,})/i
    const m = leakRe.exec(content)
    if (m) { console.error(`FAIL[settings-yaml-secret]: ${m[0]}`); fail = true }
  }
}

// 私有源映射：仅 @dsh-android 插件的 .js.map 才算泄露（npm 公共依赖带 map 属正常）
const privMaps = lines.filter((l) => l.includes('@dsh-android') && l.includes('.js.map'))
if (privMaps.length) {
  console.error(`FAIL[private-sourcemap]: ${privMaps.slice(0, 3).join('; ')}`)
  fail = true
}

if (fail) {
  console.error('SNAPSHOT_SECRET_CHECK_FAILED')
  process.exit(1)
}
console.log('SNAPSHOT_SECRET_CHECK_PASSED（成员 ' + lines.length + '，SKIP=' + skipped + '）')
