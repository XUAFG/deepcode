// combo-probe-P1.test.mjs — compose 探针在 rc.1 上的安装回归（0.14.1 块F P0-2，0.14.2 随版重写）。
//
// P1 的产品意义：设备上 `t_compose_total` 曾恒为 -1，因为 `[perf] TOTAL` 只有**测量 preload** 会产，
// 而发行路径里没有探针——「我们在量」与「壳侧读得到」互相假装成立。P1 把探针装进引擎产物本身。
//
// 本测试守四件事（combo 家族 A3/A4/A5/C3 全部撤销后，P1 是仅存的 combo 侧补丁，且已无前置）：
//   ① 前置声明与登记表一致（requires 里的 id 都存在，且不牵连已撤销的补丁）；
//   ② 在**未打补丁的 rc.1 真产物夹具**上可施加、幂等、且 `node --check` 过；
//   ③ 打印的三行字段集合是壳侧/count-compose 的解析契约，字段不得随补丁增减而消失；
//   ④ 主线程门：worker 不得成为 TOTAL 的最后一个打印者（0.14.1 的假绿形态）。
//
// 用法：node scripts/patches/tests/combo-probe-p1.test.mjs
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, copyFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import { versionedFixture } from './lib/fixture.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..', '..')
const TARGET = 'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-modules/lib/index.js'
const FIXTURE = versionedFixture('dsh-client-modules', 'lib', 'index.js')

const failures = []
const check = (label, ok, detail) => {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (ok || detail === undefined ? '' : ' -> ' + detail))
  if (!ok) failures.push(label)
}

const registry = JSON.parse(readFileSync(join(here, '..', 'registry.json'), 'utf8'))
const p1 = registry.patches.find((p) => p.id === 'combo-probe-P1')
check('P1 在登记表内且 scope=engine', Boolean(p1) && p1.scope === 'engine')
check('P1 前置全部存在（不牵连已撤销的 combo 补丁；A4 退役后应为空）',
  (p1.requires ?? []).every((id) => registry.patches.some((x) => x.id === id)), JSON.stringify(p1.requires ?? []))
check('P1 的 requires 为空（A4 退役后无前置）',
  JSON.stringify(p1?.requires ?? []) === JSON.stringify([]), JSON.stringify(p1?.requires ?? null))

const scratch = mkdtempSync(join(tmpdir(), 'p1-test-'))
try {
  const target = join(scratch, TARGET)
  mkdirSync(dirname(target), { recursive: true })
  copyFileSync(FIXTURE, target)
  const pristine = readFileSync(target, 'utf8')
  check('前置：夹具是未打本方补丁的真产物', !pristine.includes('dsh-mobile'))

  const apply = () => spawnSync(process.execPath,
    [join(repoRoot, 'scripts', 'patches', 'apply-patches.mjs'), scratch, '--apply', '--scope', 'engine',
      '--only', 'combo-probe-P1'], { encoding: 'utf8' })
  const first = apply()
  check('P1 在 rc.1 真产物上施加成功（A4 已于 2026-09-25 退役，不再作为前置）', first.status === 0,
    (first.stdout + first.stderr).trim().split('\n').slice(-2).join(' | '))
  const patched = readFileSync(target, 'utf8')
  check('探针标记与打印器在场',
    patched.includes('dsh-mobile combo probe (P1)') && patched.includes('dshMobileComboProbeEmit'))
  const parse = spawnSync(process.execPath, ['--check', target], { encoding: 'utf8' })
  check('打过探针的产物可被 node 解析', parse.status === 0, (parse.stderr || '').split('\n')[0])
  apply()
  check('再施加零改动（幂等）', readFileSync(target, 'utf8') === patched)

  // ③ 解析契约：三行各自的字段集合（壳侧 LogCollector 与 scripts/perf/count-compose.mjs 按此解析）
  const composeLine = (patched.match(/console\.log\(`\[perf\] compose [^\n]*/) || [''])[0]
  const totalLine = (patched.match(/console\.log\(`\[perf\] TOTAL[^\n]*/) || [''])[0]
  check('compose 行字段齐全（#n/at/dur/instances/records/singles/cache）',
    ['at=', 'dur=', 'instances=', 'records=', 'singles='].every((f) => composeLine.includes(f)), composeLine.slice(0, 80))
  check('TOTAL 行字段齐全（calls/totalMs/instances/firstAt/singles + loop/cache 由函数位展开提供）',
    ['calls=', 'totalMs=', 'instances=', 'firstAt=', 'singles='].every((f) => totalLine.includes(f))
    && totalLine.includes('dshMobileComboProbeLoopLine()') && totalLine.includes('${cache}'), totalLine.slice(0, 80))
  const loopLine = (patched.match(/function dshMobileComboProbeLoopLine\(\) \{[\s\S]*?\n\}/) || [''])[0]
  check('loop 字段无 monitor 时打印 -1（省字段与造假同级）',
    loopLine.includes('loopP99Ms=-1 loopSamples=-1') && loopLine.includes('loopP99Ms='))
  check('cache/singles 以哨兵值缺席而非省字段',
    patched.includes('"comboCache=none hits=0 misses=0"') && /\? value : -1/.test(patched))
  // ④ 主线程门：非主线程块必须是**空**的，探针安装只发生在 else 分支
  const gateAt = patched.indexOf('if (!isMainThread) {')
  const elseAt = patched.indexOf('} else {', gateAt)
  const wrapAt = patched.indexOf('dshMobileComboProbeProto.compose = function')
  check('worker 不安装探针（gate 块内无打印，安装在 else 分支）',
    gateAt >= 0 && elseAt > gateAt && wrapAt > elseAt, `gate=${gateAt} else=${elseAt} wrap=${wrapAt}`)
} finally {
  rmSync(scratch, { recursive: true, force: true })
}

if (failures.length) {
  console.error(`combo-probe-p1: ${failures.length} 项失败`)
  process.exit(1)
}
console.log('combo-probe-p1: 全部检查通过（rc.1 真产物上安装 + 解析契约）')
