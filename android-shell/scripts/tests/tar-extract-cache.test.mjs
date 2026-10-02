#!/usr/bin/env node
// tar-extract-cache.test.mjs — tar-extract-cache 的纯函数单测（0.14.2-fx-2 G.3）。
//
// 【为什么不真解压】
// 任务硬约束：不得在验证里真解 148MB 快照产物（会与其它成员抢 IO，且耗时）。
// 本单测用**假 tar / 假 extract / 假 tempdir**（全部注入）验证缓存语义本身：
//   · 同一 tar 的 extract 只被调用一次（含并发合并）；
//   · 不同 tar 各自一次；
//   · 清单只列一次；
//   · dispose 清理建过的目录且幂等；
//   · 解压失败不污染缓存（可重试）；
//   · dispose 后再用必须抛错（防「已清理还在用」）。
//
// 用法：node scripts/tests/tar-extract-cache.test.mjs
import assert from 'node:assert/strict'
import test from 'node:test'
import { createTarExtractCache } from '../lib/tar-extract-cache.mjs'

/** 造一个受控 harness：记录 extract/list/mkdtemp/rm 的每一次调用。 */
function harness({ failOnce = false } = {}) {
  const calls = { extract: [], list: [], mkdirs: [], removed: [] }
  let extractFailures = failOnce ? 1 : 0
  let dirSeq = 0
  const cache = createTarExtractCache({
    extract: async (tarPath, destDir) => {
      calls.extract.push({ tarPath, destDir })
      if (extractFailures > 0) { extractFailures -= 1; throw new Error('boom') }
    },
    list: async (tarPath) => {
      calls.list.push(tarPath)
      return ['a.txt', 'b/c.txt']
    },
    makeTempDir: (prefix) => {
      assert.ok(prefix.startsWith('dsh-tar-extract-'), '临时目录前缀必须可识别')
      const dir = '/tmp/' + prefix + (dirSeq += 1)
      calls.mkdirs.push(dir)
      return dir
    },
    removeDir: (dir) => { calls.removed.push(dir) },
  })
  return { cache, calls }
}

test('同一 tar 的 extract 只被调用一次（重复 withExtracted 复用）', async () => {
  const { cache, calls } = harness()
  const seen = []
  await cache.withExtracted('/snap.tar.xz', async (dir) => { seen.push(dir) })
  await cache.withExtracted('/snap.tar.xz', async (dir) => { seen.push(dir) })
  await cache.withExtracted('/snap.tar.xz', async (dir) => { seen.push(dir) })
  assert.equal(calls.extract.length, 1, 'extract 必须只调用一次')
  assert.equal(calls.mkdirs.length, 1, '只许一份临时目录')
  assert.equal(new Set(seen).size, 1, '三次回调必须拿到同一个目录')
  assert.deepEqual(cache.stats(), { listCalls: 0, extractCalls: 1, tempDirsCreated: 1, tempDirsRemoved: 0, tarCount: 1, disposed: false })
  await cache.dispose()
})

test('并发 withExtracted 合并为一次 extract（不是各解一遍）', async () => {
  const { cache, calls } = harness()
  const results = await Promise.all([
    cache.withExtracted('/snap.tar.xz', async (dir) => 'r1:' + dir),
    cache.withExtracted('/snap.tar.xz', async (dir) => 'r2:' + dir),
    cache.withExtracted('/snap.tar.xz', async (dir) => 'r3:' + dir),
  ])
  assert.equal(calls.extract.length, 1, '并发必须共享同一次解压')
  assert.equal(calls.mkdirs.length, 1)
  const dirs = new Set(results.map((r) => r.split(':')[1]))
  assert.equal(dirs.size, 1, '并发回调必须拿到同一个目录')
  await cache.dispose()
})

test('不同 tar 各自解一次（缓存按路径分槽）', async () => {
  const { cache, calls } = harness()
  await cache.withExtracted('/a.tar.xz', async () => {})
  await cache.withExtracted('/b.tar.xz', async () => {})
  assert.equal(calls.extract.length, 2)
  assert.equal(calls.mkdirs.length, 2)
  await cache.dispose()
})

test('清单只列一次（同一 tar 重复 listEntries 命中缓存）', async () => {
  const { cache, calls } = harness()
  const n1 = await cache.listEntries('/snap.tar.xz')
  const n2 = await cache.listEntries('/snap.tar.xz')
  assert.deepEqual(n1, ['a.txt', 'b/c.txt'])
  assert.equal(n1, n2, '必须返回同一个数组引用（未重复构造）')
  assert.equal(calls.list.length, 1, 'list 只调一次')
  await cache.dispose()
})

test('dispose 清理建过的目录且幂等', async () => {
  const { cache, calls } = harness()
  await cache.withExtracted('/a.tar.xz', async () => {})
  await cache.withExtracted('/b.tar.xz', async () => {})
  await cache.dispose()
  assert.deepEqual(calls.removed, calls.mkdirs, '每个建过的目录都必须被清理')
  assert.equal(cache.stats().tempDirsRemoved, 2)
  await cache.dispose()
  assert.equal(cache.stats().tempDirsRemoved, 2, '二次 dispose 不得重复删')
  assert.equal(calls.removed.length, 2)
})

test('dispose 之后再使用必须抛错（防已清理还在用）', async () => {
  const { cache } = harness()
  await cache.dispose()
  await assert.rejects(() => cache.withExtracted('/a.tar.xz', async () => {}), /已 dispose/)
  await assert.rejects(() => cache.listEntries('/a.tar.xz'), /已 dispose/)
})

test('extract 失败不污染缓存（同一 tar 可重试成功）', async () => {
  const { cache, calls } = harness({ failOnce: true })
  await assert.rejects(() => cache.withExtracted('/snap.tar.xz', async () => {}), /boom/)
  const got = await cache.withExtracted('/snap.tar.xz', async (dir) => dir)
  assert.ok(got, '重试必须成功')
  assert.equal(calls.extract.length, 2, '第一次失败 + 第二次重试')
  await cache.dispose()
})

test('缺 extract 注入时构造即抛错（注入式契约）', () => {
  assert.throws(() => createTarExtractCache({}), /必须提供 extract/)
  assert.throws(() => createTarExtractCache({ extract: 'not-a-fn' }), /必须提供 extract/)
})

test('withExtracted 的 fn 非函数即抛错', async () => {
  const { cache } = harness()
  await assert.rejects(() => cache.withExtracted('/a.tar.xz', null), /必须是函数/)
  assert.equal(cache.stats().extractCalls, 0, '参数校验失败不得触发解压')
  await cache.dispose()
})
