#!/usr/bin/env node
// tar-extract-cache.integration.mjs — 自造小 tar 的端到端集成实证（0.14.2-fx-2 G.3）。
//
// 【为什么用自造小 tar】任务硬约束：不得真解 148MB 快照产物（抢 IO + 耗时）。此处现场造一个几 KB 的
// 真 tar.xz，用**真 tar 子进程**做 extract/list，验证缓存对真实归档的语义与单元测试一致。
// 这不属「产物门禁」，不读任何发布产物。
import { execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync, mkdirSync, existsSync, readdirSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createTarExtractCache } from '../lib/tar-extract-cache.mjs'

const scratch = mkdtempSync(join(tmpdir(), 'dsh-tarcache-demo-'))
function cleanup() { rmSync(scratch, { recursive: true, force: true }) }
process.on('exit', cleanup)

try {
  // ── 造小 tar（几 KB）────────────────────────────────────────────────────
  const src = join(scratch, 'src')
  mkdirSync(join(src, 'sub'), { recursive: true })
  writeFileSync(join(src, 'hello.txt'), 'hello from a tiny self-made tar\n')
  writeFileSync(join(src, 'sub', 'nested.txt'), 'nested payload\n')
  const tarPath = join(scratch, 'tiny.tar.xz')
  execFileSync('tar', ['-cJf', tarPath, '-C', src, '.'])
  const bytes = statSync(tarPath).size
  console.log('tiny_tar_bytes=' + bytes + ' (自造，非发布产物)')

  let extractCalls = 0
  const cache = createTarExtractCache({
    list: async (t) => execFileSync('tar', ['-tJf', t], { encoding: 'utf8' }).split(/\r?\n/).filter(Boolean),
    extract: async (t, dest) => { extractCalls += 1; execFileSync('tar', ['-xJf', t, '-C', dest]) },
  })

  console.log('--- 1) listEntries: 真 tar 列清单 ---')
  const names = await cache.listEntries(tarPath)
  console.log('entries=' + JSON.stringify(names.sort()))
  const names2 = await cache.listEntries(tarPath)
  console.log('same_ref_on_second_call=' + (names === names2))

  console.log('--- 2) withExtracted: 三个读者共享一次解压 ---')
  const dirs = []
  for (let i = 1; i <= 3; i += 1) {
    await cache.withExtracted(tarPath, async (dir) => { dirs.push(dir) })
  }
  console.log('reader_calls=3 distinct_dirs=' + new Set(dirs).size + ' real_extract_calls=' + extractCalls)
  const ok = existsSync(join(dirs[0], 'hello.txt')) && existsSync(join(dirs[0], 'sub', 'nested.txt'))
  console.log('extracted_content_present=' + ok)
  console.log('files_in_dir=' + JSON.stringify(readdirSync(dirs[0]).sort()))

  console.log('--- 3) stats（实测计数，非估算）---')
  console.log(JSON.stringify(cache.stats()))

  console.log('--- 4) dispose 清理 ---')
  const dirBefore = dirs[0]
  await cache.dispose()
  console.log('temp_dir_gone_after_dispose=' + !existsSync(dirBefore))
  console.log(JSON.stringify(cache.stats()))

  const pass = new Set(dirs).size === 1 && extractCalls === 1 && ok && !existsSync(dirBefore)
  console.log(pass ? 'TAR-EXTRACT-CACHE INTEGRATION PASSED（真 tar：3 读者共享 1 次解压 + 可清理）'
    : 'TAR-EXTRACT-CACHE INTEGRATION FAILED')
  process.exit(pass ? 0 : 1)
} catch (error) {
  console.error('INTEGRATION ERROR: ' + error.message)
  cleanup()
  process.exit(1)
}
