#!/usr/bin/env node
// Apply the registered marketplace patches to source-built output while keeping
// the shared patch runner byte-identical to the coordination repository.
import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const args = process.argv.slice(2)
if (args.length < 1) {
  console.error('usage: node apply-source-marketplace-patches.mjs <vendorRoot> [patch arguments]')
  process.exit(2)
}

const sourcePath = resolve('scripts/patches/apply-patches.mjs')
const source = readFileSync(sourcePath, 'utf8')
// Anchors are matched against LF-normalised text: a Windows checkout can carry
// CRLF, or a mix of both endings after a partial conversion, which would fail
// the anchor assertion for a reason unrelated to the shared runner's content.
// The generated runner is a temporary file, so its endings are irrelevant, and
// the hashes below still cover the shared runner's real bytes.
const normalized = source.replaceAll('\r\n', '\n')
const sha256 = (value) => createHash('sha256').update(value).digest('hex')
const adapterSource = readFileSync(fileURLToPath(import.meta.url))
const hereLine = 'const HERE = dirname(fileURLToPath(import.meta.url))'
// 本适配器只做一件事：把生成副本的 HERE 指回 scripts/patches（生成文件落在 .deploy-tmp，
// 按 import.meta.url 推路径会指向错误目录）。
//
// 0.1.7 之前这里还有第二处改写：market-A 的市场补丁在源码构建产物上要接受另一种
// `requireApproval` 闭合形态。上游 0.1.7 自修了那个 waterfall 崩溃、market-A 退役
// （registry.json 的 retired 段），锚点随之从共享执行器消失——本改写已成死代码，删除。
// 锚点整体失配不需要本适配器兜底：共享执行器对「check 为假且 apply 零改动」本来就判红。
if (normalized.split(hereLine).length !== 2) {
  throw new Error('shared patch runner changed; review the source-build marketplace adapter before updating it')
}
const adapted = normalized
  .replace(hereLine, "const HERE = join(process.cwd(), 'scripts', 'patches')")

const reportRoot = resolve('.deploy-tmp/source-build')
mkdirSync(reportRoot, { recursive: true })
const generatedPath = join(reportRoot, 'apply-patches-source.mjs')
writeFileSync(generatedPath, adapted)
const result = spawnSync(process.execPath, [generatedPath, ...args], { cwd: process.cwd(), stdio: 'inherit' })
if (result.error) throw result.error
if (result.status !== 0) process.exit(result.status ?? 1)

const target = resolve(args[0], 'dshmarketplace-plugin/lib/index.js')
const report = {
  sharedPatchRunner: 'scripts/patches/apply-patches.mjs',
  sharedPatchRunnerSha256: sha256(source),
  generatedPatchRunner: '.deploy-tmp/source-build/apply-patches-source.mjs',
  generatedPatchRunnerSha256: sha256(adapted),
  sourceAdapter: 'scripts/source-build/apply-source-marketplace-patches.mjs',
  sourceAdapterSha256: sha256(adapterSource),
  registrySha256: sha256(readFileSync('scripts/patches/registry.json')),
  marketplacePatchOutput: args[0] + '/dshmarketplace-plugin/lib/index.js',
  marketplacePatchOutputSha256: sha256(readFileSync(target)),
  arguments: args,
  sourceBuildOnlyRule: "Only the generated copy's HERE anchor is re-pointed at scripts/patches; the registry patches themselves are applied unmodified. The market-A A-3 closure rewrite was removed when upstream 0.1.7 retired that patch.",
}
writeFileSync(join(reportRoot, 'marketplace-patch-adapter.json'), JSON.stringify(report, null, 2) + '\n')
console.log('source-built marketplace patches applied with a generated, source-only runner adapter')
