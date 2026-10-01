/**
 * Runtime configuration for dsh-jev-gate.
 *
 * Plugin config in `cordis.patch.yml` is baked into the APK's snapshot, so
 * changing it means rebuilding and reinstalling. The gate therefore also reads
 * an optional override file at `$DSH_HOME/jev-gate.json`, re-read whenever its
 * mtime changes. That lets the operator flip `mode` from `observe` to
 * `enforce`, or retune a threshold, without touching the build.
 *
 * Precedence, low to high: built-in defaults, the cordis patch config, the
 * override file. A malformed override file is ignored, never fatal.
 */
import { readFileSync, statSync } from 'node:fs'
import { normalizeConfig } from './policy.js'

/** Name of the override file inside DSH_HOME. */
export const OVERRIDE_FILE = 'jev-gate.json'

const DEFAULT_HOME = '/data/user/0/com.dsharnessmobile.shell/files/home/.dsh'

/** Absolute path of the override file for one environment. */
export function overridePath(env = process.env) {
  const home = typeof env?.DSH_HOME === 'string' && env.DSH_HOME !== '' ? env.DSH_HOME : DEFAULT_HOME
  return `${home}/${OVERRIDE_FILE}`
}

/**
 * Create a reader that re-parses the override file only when it changes, so a
 * per-approval call stays cheap (one `stat`) while still picking up live edits.
 * @param {NodeJS.ProcessEnv} [env] - environment supplying DSH_HOME.
 * @returns {() => Record<string, unknown>} current override object, or `{}`.
 */
export function createOverrideReader(env = process.env) {
  let cachedMtime = -1
  let cached = {}
  return function readOverride() {
    try {
      const path = overridePath(env)
      const mtime = statSync(path).mtimeMs
      if (mtime === cachedMtime) return cached
      const parsed = JSON.parse(readFileSync(path, 'utf8'))
      cached = parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
      cachedMtime = mtime
      return cached
    } catch {
      // Missing or unreadable override is the normal case on a fresh install.
      cachedMtime = -1
      cached = {}
      return cached
    }
  }
}

/**
 * Compose the effective configuration for one decision.
 * @param {unknown} patchConfig - config from the cordis patch.
 * @param {() => Record<string, unknown>} readOverride - live override reader.
 * @returns {ReturnType<typeof normalizeConfig>} the effective configuration.
 */
export function effectiveConfig(patchConfig, readOverride) {
  const override = readOverride()
  const hasOverride = override && Object.keys(override).length > 0
  if (!hasOverride) return normalizeConfig(patchConfig)
  const patch = patchConfig && typeof patchConfig === 'object' && !Array.isArray(patchConfig) ? patchConfig : {}
  return normalizeConfig({ ...patch, ...override })
}

/**
 * Read a bearer token from a plain file, trimming surrounding whitespace and
 * ignoring an empty file. Used only when neither the environment nor the
 * credential store supplies a key.
 * @param {string} path - absolute path supplied by the operator.
 * @returns {string | undefined} the token, or `undefined`.
 */
export function readKeyFile(path) {
  if (typeof path !== 'string' || path === '') return undefined
  try {
    const value = readFileSync(path, 'utf8').trim()
    return value === '' ? undefined : value
  } catch {
    return undefined
  }
}
