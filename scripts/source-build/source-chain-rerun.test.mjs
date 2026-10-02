#!/usr/bin/env node
// Run actual workflow guards against dummy local inputs, never upstream builds.
import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { spawnSync, execFileSync } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, symlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
const ROOT = resolve(import.meta.dirname, '../..')
const yaml = readFileSync(join(ROOT, '.github/workflows/build-apk-source.yml'), 'utf8').replaceAll('\r\n', '\n')
function step(name) {
  const start = yaml.indexOf('      - name: ' + name + '\n')
  assert.notEqual(start, -1)
  const end = yaml.indexOf('\n      - name:', start + 1)
  const block = yaml.slice(start, end < 0 ? undefined : end)
  const marker = '        run: |\n'
  assert.ok(block.includes(marker))
  return block.slice(block.indexOf(marker) + marker.length).split('\n').map(line => line.startsWith('          ') ? line.slice(10) : line).join('\n')
}
function fixture(fn) {
  const root = mkdtempSync(join(tmpdir(), 'source-rerun-'))
  try { return fn(root) } finally { rmSync(root, { recursive: true, force: true }) }
}
function bash(script, root, extra = {}) {
  const r = spawnSync('/bin/bash', ['--noprofile', '--norc', '-c', 'set -euo pipefail\n' + script], {
    cwd: root, encoding: 'utf8', timeout: 10000, maxBuffer: 262144,
    env: { PATH: join(root, 'bin') + ':/usr/bin:/bin', HOME: root, TMPDIR: root, LC_ALL: 'C', GITHUB_WORKSPACE: root, ...extra },
  })
  if (r.error) throw r.error
  return r
}
const linux = { skip: process.platform !== 'linux' }
const bootstrap = step('Authenticate official Termux bootstrap and extract signing keys')
const guard = bootstrap.slice(bootstrap.indexOf('bootstrap_sha256='), bootstrap.indexOf('\n# 先清空'))
const good = Buffer.from('authenticated dummy bootstrap\n')
const sha = createHash('sha256').update(good).digest('hex')
function prepare(root) {
  mkdirSync(join(root, 'scripts/source-build'), { recursive: true })
  mkdirSync(join(root, '.deploy-tmp/source-build'), { recursive: true })
  mkdirSync(join(root, 'bin'))
  writeFileSync(join(root, 'scripts/source-build/prepare-termux-bootstrap.py'), 'BOOTSTRAP_SHA256 = "' + sha + '"\n')
  writeFileSync(join(root, 'download'), good)
  const curl = [
    '#!/bin/bash', 'set -eu', 'printf "download\\n" >> "$HOME/calls"',
    'while [ "$#" -gt 0 ]; do if [ "$1" = "--output" ]; then output="$2"; break; fi; shift; done',
    'if [ "' + '$' + '{INTERRUPT:-0}" = 1 ] && [ ! -f "$HOME/interrupted" ]; then printf partial > "$output"; touch "$HOME/interrupted"; exit 22; fi',
    'cp "$HOME/download" "$output"', '',
  ].join('\n')
  writeFileSync(join(root, 'bin/curl'), curl)
  chmodSync(join(root, 'bin/curl'), 0o755)
  return join(root, '.deploy-tmp/source-build/bootstrap-aarch64.zip')
}
for (const cached of ['valid', 'absent', 'wrong', 'truncated']) {
  test('bootstrap ' + cached + ': only authenticated hits reuse', linux, () => fixture(root => {
    const cache = prepare(root)
    if (cached !== 'absent') writeFileSync(cache, cached === 'valid' ? good : Buffer.from(cached))
    const r = bash(guard, root)
    assert.equal(r.status, 0, r.stderr)
    assert.deepEqual(readFileSync(cache), good)
    assert.equal(existsSync(join(root, 'calls')), cached !== 'valid')
    assert.ok(bootstrap.includes('python3 scripts/source-build/prepare-termux-bootstrap.py'))
  }))
}
test('interrupted bootstrap download retries next invocation', linux, () => fixture(root => {
  const cache = prepare(root)
  assert.equal(bash(guard, root, { INTERRUPT: '1' }).status, 22)
  assert.notDeepEqual(readFileSync(cache), good)
  const r = bash(guard, root, { INTERRUPT: '1' })
  assert.equal(r.status, 0, r.stderr)
  assert.deepEqual(readFileSync(cache), good)
  assert.equal(readFileSync(join(root, 'calls'), 'utf8').trim().split('\n').length, 2)
}))
test('bad downloaded bytes are rejected before bootstrap extraction', linux, () => fixture(root => {
  const cache = prepare(root)
  writeFileSync(join(root, 'download'), 'bad download')
  assert.equal(bash(guard, root).status, 0)
  const destination = join(root, 'extracted/usr')
  const checked = spawnSync('/usr/bin/python3', [join(ROOT, 'scripts/source-build/prepare-termux-bootstrap.py'),
    '--extract-bootstrap-only', cache, destination], {
    encoding: 'utf8', timeout: 10000,
    env: { PATH: '/usr/bin:/bin', HOME: root, TMPDIR: root, LC_ALL: 'C' },
  })
  assert.notEqual(checked.status, 0)
  assert.ok((checked.stderr + checked.stdout).includes('SHA-256 mismatch'))
  assert.equal(existsSync(destination), false)
}))
test('source reset restores inputs but preserves ignored dependencies', linux, () => fixture(root => {
  const source = join(root, '.deploy-tmp/deepseek-harness')
  mkdirSync(source, { recursive: true })
  const git = (...args) => execFileSync('git', ['-C', source, ...args], { env: { PATH: '/usr/bin:/bin', HOME: root, GIT_CONFIG_NOSYSTEM: '1' } })
  git('init', '-q'); git('config', 'user.name', 'Fixture'); git('config', 'user.email', 'fixture@example.invalid')
  writeFileSync(join(source, '.gitignore'), 'node_modules/\nlib/\n*.tsbuildinfo\n')
  writeFileSync(join(source, 'source.txt'), 'pinned'); git('add', '.'); git('commit', '-qm', 'fixture')
  writeFileSync(join(source, 'source.txt'), 'interrupted override'); writeFileSync(join(source, 'untracked.txt'), 'remove')
  mkdirSync(join(source, 'node_modules')); writeFileSync(join(source, 'node_modules/sentinel'), 'keep')
  const checkout = step('Checkout pinned DeepSeek Harness source')
  const reset = checkout.slice(checkout.indexOf('test "$(realpath'), checkout.indexOf('\ngit -C .deploy-tmp/deepseek-harness checkout --detach'))
  const r = bash(reset, root)
  assert.equal(r.status, 0, r.stderr)
  assert.equal(readFileSync(join(source, 'source.txt'), 'utf8'), 'pinned')
  assert.equal(existsSync(join(source, 'untracked.txt')), false)
  assert.equal(readFileSync(join(source, 'node_modules/sentinel'), 'utf8'), 'keep')
}))
test('deploy cleanup is scoped and rejects empty workspace and linked parent', linux, () => fixture(root => {
  const harness = step('Build and pack DeepSeek Harness from source')
  const cleanup = harness.slice(harness.indexOf('test -n "$GITHUB_WORKSPACE"'), harness.indexOf('\ncorepack pnpm@12.2.0 --pm-on-fail=ignore --filter'))
  mkdirSync(join(root, '.deploy-tmp/engine-deploy'), { recursive: true })
  writeFileSync(join(root, '.deploy-tmp/sibling'), 'keep')
  assert.equal(bash(cleanup, root).status, 0)
  assert.equal(existsSync(join(root, '.deploy-tmp/engine-deploy')), false)
  assert.equal(readFileSync(join(root, '.deploy-tmp/sibling'), 'utf8'), 'keep')
  assert.ok(harness.includes('git show "$GITHUB_SHA:scripts/snapshot-config/engine-overlay.json" > scripts/snapshot-config/engine-overlay.json'))
  assert.notEqual(bash(cleanup, root, { GITHUB_WORKSPACE: '' }).status, 0)
  const other = join(root, 'other'); mkdirSync(other); writeFileSync(join(other, 'sentinel'), 'keep')
  rmSync(join(root, '.deploy-tmp'), { recursive: true }); symlinkSync(other, join(root, '.deploy-tmp'))
  assert.notEqual(bash(cleanup, root).status, 0)
  assert.equal(readFileSync(join(other, 'sentinel'), 'utf8'), 'keep')
}))
