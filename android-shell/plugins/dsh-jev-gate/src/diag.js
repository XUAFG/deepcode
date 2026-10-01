/**
 * File-backed diagnostics for dsh-jev-gate.
 *
 * The engine's stdout (`engine.log`) rotates aggressively and never carries
 * in-process decision detail, so a silent failure on a real device would be
 * undiagnosable. This module appends one line per event to
 * `$DSH_HOME/jev-gate.log`, mirroring the trace channel `dsh-model-capability`
 * established for the same reason.
 *
 * The trace contains tool names, command text, verdicts and error codes. It
 * NEVER contains the API key: the key is redacted to a presence flag before it
 * can reach a log line.
 */
import { appendFileSync, statSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

/** Fallback matches the Android shell's DSH_HOME default. */
const DEFAULT_HOME = '/data/user/0/com.dsharnessmobile.shell/files/home/.dsh'

/** Upper bound per line, so a pathological command cannot bloat the trace. */
const MAX_FIELD = 600

function homeDir(env) {
  const home = env?.DSH_HOME
  return typeof home === 'string' && home !== '' ? home : DEFAULT_HOME
}

/** Absolute path of the trace file for one environment. */
export function logPath(env = process.env) {
  return `${homeDir(env)}/jev-gate.log`
}

function clip(value) {
  const text = typeof value === 'string' ? value : JSON.stringify(value)
  if (text === undefined) return String(value)
  return text.length > MAX_FIELD ? `${text.slice(0, MAX_FIELD)}…(+${text.length - MAX_FIELD})` : text
}

/**
 * Append one timestamped line. Diagnostics must never break the agent loop, so
 * every failure here is swallowed; a missing or read-only home simply means no
 * trace.
 * @param {string} message - already-formatted, single-line message.
 * @param {Record<string, string>} [fields] - optional key/value detail.
 * @param {NodeJS.ProcessEnv} [env] - environment supplying DSH_HOME.
 */
export function diag(message, fields, env = process.env) {
  if (env?.DSH_JSDEV_GATE_TRACE === '0') return
  try {
    const path = logPath(env)
    const detail = fields === undefined
      ? ''
      : ' ' + Object.entries(fields)
        .filter(([, v]) => v !== undefined && v !== null)
        .map(([k, v]) => `${k}=${clip(v)}`)
        .join(' ')
    appendFileSync(path, `${new Date().toISOString()} ${message}${detail}\n`)
  } catch {
    // never propagate
  }
}

/**
 * One-time note when the home directory is not writable, so the absence of a
 * trace is itself explained rather than mysterious.
 * @param {NodeJS.ProcessEnv} [env] - environment supplying DSH_HOME.
 * @returns {boolean} whether a trace file can be written right now.
 */
export function diagAvailable(env = process.env) {
  try {
    const path = logPath(env)
    mkdirSync(dirname(path), { recursive: true })
    statSync(path)
    return true
  } catch (error) {
    if (error?.code === 'ENOENT') {
      try {
        diag('trace channel created', undefined, env)
        return true
      } catch {
        return false
      }
    }
    return false
  }
}

/** Redact a secret to a presence flag. Used before any credential reaches a log. */
export function redact(secret) {
  if (typeof secret !== 'string' || secret === '') return '<absent>'
  return `<set:${secret.length}>`
}
