#!/usr/bin/env node
// Cross-compile the pinned node-pty source for the Termux ARM64 Node runtime.
// This uses the authenticated Termux Node headers and a pinned Google NDK.
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { existsSync, mkdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'

const args = process.argv.slice(2)
let ptyRoot, apiRoot, headersRoot, ndkRoot, reportPath
if (args[0] === '--direct' && args.length === 6) {
  ;[, ptyRoot, apiRoot, headersRoot, ndkRoot, reportPath] = args
} else if (args.length === 4) {
  const [engineRoot, headers, ndk, report] = args
  const subprocess = realpathSync(join(resolve(engineRoot), 'node_modules/@deepseek-ai/dsh-subprocess-local'))
  const subprocessRequire = createRequire(join(subprocess, 'package.json'))
  ptyRoot = dirname(subprocessRequire.resolve('node-pty/package.json'))
  apiRoot = dirname(createRequire(join(ptyRoot, 'package.json')).resolve('node-addon-api/package.json'))
  headersRoot = headers
  ndkRoot = ndk
  reportPath = report
} else {
  console.error('usage: build-node-pty-android.mjs <engine-deploy> <node-headers> <NDK-r30> <report.json>')
  console.error('   or: build-node-pty-android.mjs --direct <node-pty> <node-addon-api> <node-headers> <NDK-r30> <report.json>')
  process.exit(2)
}

ptyRoot = realpathSync(resolve(ptyRoot))
apiRoot = realpathSync(resolve(apiRoot))
headersRoot = realpathSync(resolve(headersRoot))
ndkRoot = realpathSync(resolve(ndkRoot))
const ptyPackage = JSON.parse(readFileSync(join(ptyRoot, 'package.json'), 'utf8'))
const apiPackage = JSON.parse(readFileSync(join(apiRoot, 'package.json'), 'utf8'))
if (ptyPackage.name !== 'node-pty' || ptyPackage.version !== '1.2.0-beta.15') {
  throw new Error(`unexpected node-pty source: ${ptyPackage.name}@${ptyPackage.version}`)
}
if (apiPackage.name !== 'node-addon-api' || !/^7\./.test(apiPackage.version)) {
  throw new Error(`unexpected node-addon-api source: ${apiPackage.name}@${apiPackage.version}`)
}
const headerVersion = readFileSync(join(headersRoot, 'node_version.h'), 'utf8')
const nodeMajor = Number(headerVersion.match(/^#define NODE_MAJOR_VERSION (\d+)$/m)?.[1])
if (nodeMajor !== 24) throw new Error(`expected signed Termux Node 24 headers, found major ${nodeMajor}`)
const ndkProperties = readFileSync(join(ndkRoot, 'source.properties'), 'utf8')
const ndkRevision = ndkProperties.match(/^Pkg\.Revision\s*=\s*(.+)$/m)?.[1]
if (ndkRevision !== '30.0.16248370') throw new Error(`expected Google NDK r30, found ${ndkRevision}`)

const host = process.platform === 'win32' ? 'windows-x86_64' : process.platform === 'linux' ? 'linux-x86_64' : null
if (!host) throw new Error(`unsupported build host ${process.platform}`)
const bin = join(ndkRoot, 'toolchains', 'llvm', 'prebuilt', host, 'bin')
const ext = process.platform === 'win32' ? '.exe' : ''
const clang = join(bin, `clang++${ext}`)
const readelf = join(bin, `llvm-readelf${ext}`)
if (!existsSync(clang) || !existsSync(readelf)) throw new Error(`Google NDK toolchain is incomplete at ${bin}`)
const source = join(ptyRoot, 'src', 'unix', 'pty.cc')
const output = join(ptyRoot, 'prebuilds', 'android-arm64', 'pty.node')
for (const candidate of ['build/Release/pty.node', 'build/Debug/pty.node']) {
  if (existsSync(join(ptyRoot, candidate))) {
    throw new Error(`${candidate} would shadow the Android binding in node-pty's loader`)
  }
}
mkdirSync(dirname(output), { recursive: true })
const compileArgs = [
  '--target=aarch64-linux-android26', '-fPIC', '-shared', '-std=c++17',
  '-O2', '-fvisibility=hidden', '-static-libstdc++',
  `-I${headersRoot}`, `-I${apiRoot}`, source, '-o', output,
]
function run(executable, runArgs) {
  const result = spawnSync(executable, runArgs, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`${executable} exited ${result.status}:\n${result.stdout}\n${result.stderr}`)
  return result.stdout
}
run(clang, compileArgs)
const elfHeader = run(readelf, ['-h', output])
const dynamic = run(readelf, ['-d', output])
const symbols = run(readelf, ['--wide', '-s', output])
if (!elfHeader.includes('Class:                             ELF64')
  || !elfHeader.includes('Machine:                           AArch64')
  || !elfHeader.includes('Type:                              DYN')) {
  throw new Error('compiled pty.node is not an ARM64 shared ELF library')
}
const needed = [...dynamic.matchAll(/Shared library: \[([^\]]+)\]/g)].map((match) => match[1]).sort()
const permitted = new Set(['libc.so', 'libdl.so', 'libm.so'])
if (needed.length === 0 || needed.some((library) => !permitted.has(library))) {
  throw new Error(`pty.node has an unexpected shared-library dependency: ${needed.join(', ')}`)
}
if (!symbols.includes('napi_register_module_v1') || !symbols.includes(' forkpty@LIBC')) {
  throw new Error('pty.node is missing the Node-API entry point or Android forkpty import')
}
const hash = (file) => createHash('sha256').update(readFileSync(file)).digest('hex')
const report = {
  package: { name: ptyPackage.name, version: ptyPackage.version, packageJsonSha256: hash(join(ptyRoot, 'package.json')) },
  nodeAddonApi: { version: apiPackage.version, packageJsonSha256: hash(join(apiRoot, 'package.json')) },
  termuxNodeMajor: nodeMajor,
  ndkRevision,
  ndkSourcePropertiesSha256: hash(join(ndkRoot, 'source.properties')),
  compilerSha256: hash(clang),
  target: 'aarch64-linux-android26',
  command: ['clang++', ...compileArgs.map((item) => item === source ? 'src/unix/pty.cc' : item === output ? 'prebuilds/android-arm64/pty.node' : item.startsWith('-I') ? '-I<authenticated-header-or-package>' : item)],
  inputs: {
    ptyCcSha256: hash(source),
    bindingGypSha256: hash(join(ptyRoot, 'binding.gyp')),
    napiHeaderSha256: hash(join(apiRoot, 'napi.h')),
    nodeApiHeaderSha256: hash(join(headersRoot, 'node_api.h')),
    nodeVersionHeaderSha256: hash(join(headersRoot, 'node_version.h')),
  },
  output: { path: 'prebuilds/android-arm64/pty.node', sha256: hash(output), size: readFileSync(output).length, needed },
}
writeFileSync(resolve(reportPath), JSON.stringify(report, null, 2) + '\n')
console.log(`compiled node-pty Android ARM64 binding: ${report.output.sha256} (${report.output.size} bytes)`)
