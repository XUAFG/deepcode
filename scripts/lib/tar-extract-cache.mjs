// tar-extract-cache.mjs — 进程内「同一 tar 只解一次」的共享缓存（0.14.2-fx-2 G.3）。
//
// 【为什么必须存在（G.0 实测）】
// 发布门禁里有 12 条门禁各自独立 `tar -xJf` 同一个 148MB 快照，单跑合计 **411.5s**——同一份字节被
// 解了 12 遍。用户的原始诉求是「校验比命还长，打包都用不了那么久」，这条是其中最大的一块。
//
// 【本模块做什么】
// 提供两件事，都按「同一 tar 路径 → 只做一次」缓存：
//   1. `listEntries(tar)`：条目清单（成员名数组）；
//   2. `withExtracted(tar, fn)`：把 tar 解到**同一份临时目录**后交给 fn，重复调用复用同一目录。
//
// 【刻意的设计约束（对应任务书「只许解一次、只许一份临时目录、必须可清理」）】
//   · 只解一次：同一 tar 路径的 `extract` 最多被调用一次（并发调用共享同一 in-flight promise，
//     不是「各自解一遍」）；
//   · 只一份临时目录：每个 tar 路径一个目录，重入 `withExtracted` 复用；
//   · 可清理：`dispose()` 删掉本实例建过的所有临时目录；幂等。
//
// 【可注入（纯函数式）】
// 解压与列清单**不写死在这里**：调用方通过 `extract` / `list` 注入真实实现（真实现走 tar 子进程）。
// 这样单测可以用**假 tar / 假 reader** 验证「命中 / 只解一次 / 清理」，完全不碰真归档——
// 这正是本任务被批准的验证方式（不许在单测里真解 148MB 产物）。
//
// 【用法】
//   const cache = createTarExtractCache({ extract: realExtract, list: realList })
//   const names = await cache.listEntries(tarPath)
//   await cache.withExtracted(tarPath, async (dir) => { ... })
//   await cache.dispose()
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

/**
 * 建一个 tar 解压缓存实例。
 *
 * @param {object} deps 注入的依赖（便于纯函数单测）。
 * @param {(tarPath: string, destDir: string) => Promise<void>} deps.extract 把 tar 解到 destDir。
 * @param {(tarPath: string) => Promise<string[]>} [deps.list] 列条目名；缺省则首次 withExtracted 后用 extract 结果目录自行列举由调用方负责。
 * @param {string} [deps.tmpRoot] 临时目录根（缺省系统 tmpdir）；可在测试里指向受控目录。
 * @param {(prefix: string) => string} [deps.makeTempDir] 造临时目录（缺省 fs.mkdtempSync）；测试可注入以断言调用次数。
 * @param {(dir: string) => void} [deps.removeDir] 删目录（缺省 fs.rmSync recursive）；测试可注入以断言清理。
 * @returns {{ listEntries: Function, withExtracted: Function, dispose: Function, stats: Function }}
 */
export function createTarExtractCache(deps = {}) {
  const { extract, list, tmpRoot = tmpdir() } = deps
  if (typeof extract !== 'function') throw new TypeError('createTarExtractCache: 必须提供 extract(tarPath, destDir)')

  const makeTempDir = deps.makeTempDir ?? ((prefix) => mkdtempSync(join(tmpRoot, prefix)))
  const removeDir = deps.removeDir ?? ((dir) => rmSync(dir, { recursive: true, force: true }))

  // 每个 tar 路径的槽位：清单、解压目录、in-flight promise、以及实测计数（供报告与断言）。
  const slots = new Map()
  const counters = { listCalls: 0, extractCalls: 0, tempDirsCreated: 0, tempDirsRemoved: 0 }
  let disposed = false

  const slotOf = (tarPath) => {
    if (!slots.has(tarPath)) {
      slots.set(tarPath, { names: undefined, dir: undefined, extractPromise: undefined, listPromise: undefined })
    }
    return slots.get(tarPath)
  }

  const assertLive = () => {
    if (disposed) throw new Error('tar-extract-cache 已 dispose，不得再使用')
  }

  /** 条目清单：同一 tar 只调一次底层 list。 */
  async function listEntries(tarPath) {
    assertLive()
    const slot = slotOf(tarPath)
    if (slot.names !== undefined) return slot.names
    if (typeof list !== 'function') throw new Error('未注入 list()，无法列条目')
    if (slot.listPromise === undefined) {
      counters.listCalls += 1
      slot.listPromise = Promise.resolve()
        .then(() => list(tarPath))
        .then((names) => { slot.names = names; return names })
        .catch((error) => { slot.listPromise = undefined; throw error })
    }
    return slot.listPromise
  }

  /**
   * 解到**同一份**临时目录后交给 fn；并发/重复调用共享同一次解压。
   * @param {string} tarPath 归档路径（缓存键）。
   * @param {(dir: string) => any} fn 在解压目录上做事的回调。
   * @returns {Promise<any>} fn 的返回值。
   */
  async function withExtracted(tarPath, fn) {
    assertLive()
    if (typeof fn !== 'function') throw new TypeError('withExtracted: fn 必须是函数')
    const slot = slotOf(tarPath)
    // 并发合并：第二个调用者拿到的是同一个 promise，不会触发第二次 extract。
    if (slot.extractPromise === undefined) {
      const dir = makeTempDir('dsh-tar-extract-')
      counters.tempDirsCreated += 1
      slot.dir = dir
      counters.extractCalls += 1
      slot.extractPromise = Promise.resolve()
        .then(() => extract(tarPath, dir))
        .then(() => dir)
        .catch((error) => {
          // 解压失败：释放该槽位，让后续调用可重试；已建的临时目录交由 dispose 统一清理。
          slot.extractPromise = undefined
          throw error
        })
    }
    const dir = await slot.extractPromise
    return fn(dir)
  }

  /**
   * 清理本实例建过的全部临时目录（幂等）。
   * @returns {Promise<void>}
   */
  async function dispose() {
    if (disposed) return
    disposed = true
    for (const slot of slots.values()) {
      if (slot.dir !== undefined) {
        removeDir(slot.dir)
        counters.tempDirsRemoved += 1
        slot.dir = undefined
      }
    }
    slots.clear()
  }

  /** 实测计数（给报告/断言用，不是估算）。 */
  function stats() {
    return { ...counters, tarCount: slots.size, disposed }
  }

  return { listEntries, withExtracted, dispose, stats }
}
