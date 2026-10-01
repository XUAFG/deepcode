/**
 * Build step for dsh-jev-gate.
 *
 * The repo treats `plugins/*&#47;lib/` as build output and ignores it
 * (`android-shell/.gitignore`), so the sources live in `src/` and this step
 * emits `lib/`. The plugin is plain ESM with zero runtime dependencies, so the
 * "compile" is a copy — the value here is the verification that follows: every
 * emitted module is imported for real (proving syntax AND that each relative
 * import resolves), and the entry point is loaded against a stub context to
 * assert the Cordis plugin contract and both harness seams. A typo therefore
 * fails the build rather than the device.
 */
import { pathToFileURL } from 'node:url'
import { cpSync, mkdirSync, readdirSync, rmSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const src = join(here, 'src')
const lib = join(here, 'lib')

const sources = readdirSync(src).filter((f) => f.endsWith('.js')).sort()
if (sources.length === 0) throw new Error('src/ has no .js sources')

rmSync(lib, { recursive: true, force: true })
mkdirSync(lib, { recursive: true })
for (const file of sources) cpSync(join(src, file), join(lib, file))

const emitted = readdirSync(lib).filter((f) => f.endsWith('.js')).sort()
if (emitted.length !== sources.length) {
  throw new Error(`emitted ${emitted.length} modules, expected ${sources.length}`)
}

for (const file of emitted) {
  try {
    await import(pathToFileURL(join(lib, file)).href)
  } catch (error) {
    throw new Error(`lib/${file} failed to load: ${error?.message ?? String(error)}`)
  }
}

const mod = await import(pathToFileURL(join(lib, 'index.js')).href)
if (typeof mod.name !== 'string' || mod.name === '') throw new Error('missing exported plugin `name`')
if (typeof mod.apply !== 'function') throw new Error('missing exported `apply(ctx, config)`')

// Load the plugin against a stub context and assert both seams are wired. This
// catches a rename of either harness event at build time instead of on device.
const registered = []
const stub = {
  on: (event, listener, opts) => registered.push({ event, listener, opts }),
  get: () => undefined,
}
mod.apply(stub, { mode: 'observe' })
const events = registered.map((entry) => entry.event).sort()
if (events.join(',') !== 'approval/request,tools/pre-execute') {
  throw new Error(`unexpected seam registration: ${events.join(',') || '(none)'}`)
}
const observer = registered.find((entry) => entry.event === 'tools/pre-execute')
if (observer.opts?.prepend !== true) throw new Error('the pre-execute observer must be prepended')
const sentinel = Symbol('next')
const passedThrough = observer.listener(
  { callId: 'c1', name: 'bash', arguments: { command: 'ls' } },
  () => sentinel,
)
if (passedThrough !== sentinel) throw new Error('the pre-execute observer must return next() unchanged')

console.log(`dsh-jev-gate: emitted ${emitted.length} modules to lib/, plugin "${mod.name}" wired to both seams`)
