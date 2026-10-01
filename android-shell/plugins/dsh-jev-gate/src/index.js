/**
 * dsh-jev-gate — a machine answerer for the harness approval seam.
 *
 * The deployment ships one answerer: the human UI. Every sandbox-escalation
 * prompt therefore costs a full stop-and-wait. This plugin adds a second,
 * earlier answerer that asks Jev (TypeSafe's System One decision model) whether
 * the pending command is safe, and grants `allowed-once` only when the verdict
 * is confident. Anything else falls through to `next()`, which is exactly the
 * behaviour the deployment has today.
 *
 * Two harness seams are used, and the split is deliberate:
 *
 *   - `tools/pre-execute` is a PASSIVE observer. It always returns `next()`, so
 *     it cannot change any decision; its only job is to record the tool
 *     arguments against the call id. It runs first (`prepend`) so the record
 *     exists before execution can raise an approval.
 *
 *   - `approval/request` is the decision point. Its event carries `toolName`,
 *     `callId` and `reason` but deliberately NOT the arguments ("arguments are
 *     not duplicated here"), which is why the correlation through `callId` is
 *     needed to see the command being judged.
 *
 * Defaults to `mode: 'observe'`, which records what the gate *would* decide and
 * never grants anything. Promote to `enforce` by writing
 * `{"mode":"enforce"}` into `$DSH_HOME/jev-gate.json`.
 */
import { askJev, buildQuestions, buildState, channelDefaults } from './jev-client.js'
import { createOverrideReader, effectiveConfig, readKeyFile } from './config.js'
import { commandOf, decide, isCandidateTool, preflight } from './policy.js'
import { diag, logPath, redact } from './diag.js'

export const name = 'dsh-jev-gate'

/** Maximum remembered tool calls; bounds memory on a long-lived engine. */
const MAX_OBSERVATIONS = 256

/** How long a recorded call stays correlatable. */
const OBSERVATION_TTL_MS = 10 * 60 * 1000

/** Optional services are read through `ctx.get`; direct property access throws. */
function optionalService(ctx, serviceName) {
  try {
    const getter = ctx?.get
    if (typeof getter !== 'function') return undefined
    return getter.call(ctx, serviceName)
  } catch {
    return undefined
  }
}

/**
 * Resolve the bearer token. Precedence: process environment, the credential
 * store, then a plain file. The value is never logged — only its presence.
 * @param {object} ctx - cordis context.
 * @param {ReturnType<typeof normalizeConfig>} config - effective configuration.
 * @returns {Promise<{ key?: string, source: string }>} the token and where it came from.
 */
async function resolveKey(ctx, config) {
  const fromEnv = process.env?.[config.apiKeyRef]
  if (typeof fromEnv === 'string' && fromEnv !== '') return { key: fromEnv, source: 'env' }

  const credentials = optionalService(ctx, 'credentials')
  if (credentials && typeof credentials.resolve === 'function') {
    try {
      const resolved = await credentials.resolve(config.apiKeyRef)
      if (resolved && typeof resolved.value === 'string' && resolved.value !== '') {
        return { key: resolved.value, source: 'credentials' }
      }
    } catch {
      // A credential seam that refuses this ref is not an error; keep falling through.
    }
  }

  const fromFile = readKeyFile(config.apiKeyFile)
  if (fromFile !== undefined) return { key: fromFile, source: 'file' }
  return { source: 'none' }
}

/**
 * Cordis plugin entry point.
 * @param {object} ctx - cordis context.
 * @param {unknown} [patchConfig] - plugin config from the cordis patch.
 */
export function apply(ctx, patchConfig) {
  const readOverride = createOverrideReader(process.env)

  /** callId -> { name, args, at }. Correlation only; never used to decide. */
  const observations = new Map()
  let announced = false

  function remember(exec) {
    const callId = exec?.callId
    if (callId === undefined || callId === null) return
    observations.set(callId, {
      name: typeof exec?.name === 'string' ? exec.name : '',
      args: exec?.arguments,
      at: Date.now(),
    })
    if (observations.size > MAX_OBSERVATIONS) {
      const cutoff = Date.now() - OBSERVATION_TTL_MS
      for (const [id, entry] of observations) {
        if (entry.at < cutoff) observations.delete(id)
      }
      while (observations.size > MAX_OBSERVATIONS) {
        const oldest = observations.keys().next()
        if (oldest.done) break
        observations.delete(oldest.value)
      }
    }
  }

  function take(callId) {
    if (callId === undefined || callId === null) return undefined
    const entry = observations.get(callId)
    if (entry !== undefined) observations.delete(callId)
    return entry
  }

  ctx.on('tools/pre-execute', (exec, next) => {
    try {
      remember(exec)
    } catch {
      // Observation must never affect execution.
    }
    return next()
  }, { prepend: true })

  ctx.on('approval/request', async (req, next) => {
    let config
    let verdict
    try {
      config = effectiveConfig(patchConfig, readOverride)
      if (config.mode === 'off') return await next()

      const toolName = typeof req?.toolName === 'string' ? req.toolName : ''
      if (!isCandidateTool(toolName, config.tools)) return await next()

      const observed = take(req?.callId)
      const command = commandOf(observed?.args)

      if (!announced) {
        announced = true
        diag('gate active', {
          mode: config.mode,
          tools: config.tools.join('|'),
          threshold: String(config.allowThreshold),
          minConfidence: String(config.minConfidence),
          trace: logPath(process.env),
        })
      }

      // Local checks run before the network for two reasons: a categorically
      // unsafe command must never be transmitted to a third party for
      // judgement, and a call that cannot be judged must not be paid for.
      const local = preflight({ mode: config.mode, candidate: true, command, config })
      if (!local.ok) {
        diag(config.mode === 'enforce' ? 'defer: local' : 'observe: candidate approval', {
          tool: toolName,
          cmd: command,
          why: local.reason,
          reason: req?.reason,
        })
        return await next()
      }

      if (config.mode !== 'enforce') {
        diag('observe: would consult Jev', { tool: toolName, cmd: command, reason: req?.reason })
        return await next()
      }

      const { key, source } = await resolveKey(ctx, config)
      if (key === undefined) {
        diag('defer: no api key', { ref: config.apiKeyRef, source })
        return await next()
      }

      const channel = channelDefaults(config.provider)
      const response = await askJev({
        endpoint: config.endpoint || channel.endpoint,
        model: config.model || channel.model,
        apiKey: key,
        state: buildState({
          command,
          toolName,
          justification: typeof req?.reason === 'string' ? req.reason : undefined,
          maxCommandChars: config.maxCommandChars,
        }),
        questions: buildQuestions(),
        timeoutMs: config.timeoutMs,
      })

      verdict = decide({
        mode: config.mode,
        toolName,
        command,
        candidate: true,
        answers: response?.answers,
        config,
      })

      diag('decided', {
        tool: toolName,
        cmd: command,
        action: verdict.action,
        why: verdict.reason,
        risk: verdict.risk === undefined ? '-' : String(verdict.risk),
        confidence: verdict.confidence === undefined ? '-' : String(verdict.confidence),
        latency: response === undefined ? '-' : String(response.latencyMs),
        failure: response?.failure,
        keySource: source,
        key: redact(key),
      })

      if (verdict.action === 'allow') return 'allowed-once'
      return await next()
    } catch (error) {
      diag('defer: exception', { error: error?.message ?? String(error) })
      return await next()
    }
  })
}
