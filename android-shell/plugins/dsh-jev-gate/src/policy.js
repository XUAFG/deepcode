/**
 * Pure decision policy for dsh-jev-gate — no I/O, no engine imports, so every
 * rule below is unit-testable and reviewable in isolation.
 *
 * The gate answers exactly one question: may this pending approval request be
 * granted without waking the human? Two properties matter more than coverage:
 *
 *   1. FAIL CLOSED. Any missing input — no verdict, no command text, a low
 *      confidence, a non-finite probability, an unrecognised answer shape —
 *      resolves to `defer`, which delegates to the next answerer and therefore
 *      reproduces the deployment's existing behaviour exactly.
 *
 *   2. NEVER OUTSOURCE THE DENY LIST. A local pattern deny list runs BEFORE any
 *      model verdict, so categories the operator considers categorically unsafe
 *      can never be auto-granted no matter how the model scores them.
 */

/** How the gate behaves. `observe` records verdicts without acting on them. */
export const MODES = Object.freeze(['off', 'observe', 'enforce'])

/**
 * Command shapes that are never auto-granted, regardless of the model verdict.
 * Each entry carries the rationale so the list can be audited rather than
 * trusted. These are deliberately broad: a false positive only costs one human
 * prompt, which is the pre-existing behaviour.
 */
export const DEFAULT_HARD_DENY = Object.freeze([
  { id: 'recursive-root-delete', re: String.raw`\brm\s+(?:-[a-zA-Z]*[rR][a-zA-Z]*[fF]?[a-zA-Z]*\s+|-[a-zA-Z]*[fF][a-zA-Z]*[rR][a-zA-Z]*\s+)+(?:/(?:\s|$|\*)|~(?:\s|$)|/(?:system|vendor|data|sdcard|storage)\b|\$\{?HOME\}?)` },
  { id: 'filesystem-format', re: String.raw`\bmkfs(?:\.\w+)?\b|\bmke2fs\b|\bfdisk\b|\bparted\b` },
  { id: 'raw-device-write', re: String.raw`\bdd\b[^\n]*\bof\s*=\s*/dev/(?:block|sd|mmcblk|disk)|\bdd\b[^\n]*\bof\s*=\s*/` },
  { id: 'device-redirect', re: String.raw`>>?\s*/dev/(?:block|sd|mmcblk|disk|mem|kmem)` },
  { id: 'system-tree-write', re: String.raw`>>?\s*/(?:system|vendor|boot|proc|sys)(?:/|\s|$)` },
  { id: 'recursive-permission-change', re: String.raw`\bch(?:mod|own)\s+(?:-[a-zA-Z]+\s+)*(-R|-hR|--recursive)\b[^\n]*\s/(?:\s|$|\*)` },
  { id: 'privilege-escalation', re: String.raw`(?:^|[\s;&|])sudo\b|(?:^|[\s;&|])su\s+-|(?:^|[\s;&|])doas\b` },
  { id: 'remote-execute-pipe', re: String.raw`\b(?:curl|wget|fetch)\b[^\n|]*\|\s*(?:sudo\s+)?(?:sh|bash|zsh|ksh|dash|python3?|node|perl|ruby)\b` },
  { id: 'host-power', re: String.raw`\b(?:shutdown|reboot|halt|poweroff)\b|\binit\s+0\b` },
  { id: 'package-manager-mutation', re: String.raw`\b(?:apt|apt-get|dpkg|rpm|yum|dnf|apk|pacman)\b[^\n]*\b(?:install|remove|purge|upgrade|dist-upgrade|erase|autoremove)\b` },
  { id: 'fork-bomb', re: String.raw`:\(\)\s*\{[^\n]*\|[^\n]*&[^\n]*\}\s*;?\s*:` },
  { id: 'destructive-git', re: String.raw`\bgit\b[^\n]*\b(?:push\b[^\n]*(?:--force\b|-f\b)|reset\s+--hard\b|clean\s+-[a-zA-Z]*[fdx])` },
  { id: 'publish-or-release', re: String.raw`\b(?:npm|pnpm|yarn)\s+publish\b|\btwine\s+upload\b|\bgh\s+release\s+create\b` },
])

/** The effective configuration after defaults and user overrides are merged. */
export const DEFAULTS = Object.freeze({
  mode: 'observe',
  provider: 'openrouter',
  model: 'typesafe/jev-1.13',
  endpoint: 'https://openrouter.ai/api/alpha/decisions',
  apiKeyRef: 'JEV_API_KEY',
  apiKeyFile: '',
  tools: Object.freeze(['bash', 'pwsh', 'shell', 'shell-termux']),
  allowThreshold: 0.1,
  minConfidence: 0.75,
  timeoutMs: 4000,
  maxCommandChars: 2000,
  hardDenyPatterns: DEFAULT_HARD_DENY,
})

function asNumber(value, fallback) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function asString(value, fallback) {
  return typeof value === 'string' && value.trim() !== '' ? value : fallback
}

/**
 * Merge user config over the defaults, dropping anything malformed. Invalid
 * input degrades to the default rather than throwing: a plugin that cannot
 * load is worse than one running its conservative defaults.
 * @param {unknown} raw - config object from the cordis patch, or undefined.
 * @returns {typeof DEFAULTS} a complete, validated configuration.
 */
export function normalizeConfig(raw) {
  const source = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {}
  const patterns = Array.isArray(source.hardDenyPatterns) && source.hardDenyPatterns.length > 0
    ? source.hardDenyPatterns
      .map((entry, index) => {
        if (typeof entry === 'string') return { id: `custom-${index}`, re: entry }
        const re = entry && typeof entry === 'object' ? entry.re : undefined
        return typeof re === 'string' ? { id: asString(entry?.id, `custom-${index}`), re } : undefined
      })
      .filter((entry) => entry !== undefined)
    : DEFAULT_HARD_DENY
  return {
    mode: MODES.includes(source.mode) ? source.mode : DEFAULTS.mode,
    provider: asString(source.provider, DEFAULTS.provider),
    model: asString(source.model, DEFAULTS.model),
    endpoint: asString(source.endpoint, DEFAULTS.endpoint),
    apiKeyRef: asString(source.apiKeyRef, DEFAULTS.apiKeyRef),
    apiKeyFile: typeof source.apiKeyFile === 'string' ? source.apiKeyFile : '',
    tools: Array.isArray(source.tools) && source.tools.length > 0
      ? source.tools.filter((t) => typeof t === 'string')
      : DEFAULTS.tools,
    allowThreshold: Math.min(1, Math.max(0, asNumber(source.allowThreshold, DEFAULTS.allowThreshold))),
    minConfidence: Math.min(1, Math.max(0, asNumber(source.minConfidence, DEFAULTS.minConfidence))),
    timeoutMs: Math.max(250, asNumber(source.timeoutMs, DEFAULTS.timeoutMs)),
    maxCommandChars: Math.max(64, asNumber(source.maxCommandChars, DEFAULTS.maxCommandChars)),
    hardDenyPatterns: patterns,
  }
}

/**
 * The command text an approval decision is actually about. Bash-family tools
 * carry `command`; anything else falls back to the serialized arguments so the
 * model still sees the call, and a tool with no readable text is refused a
 * verdict rather than judged on its name alone.
 * @param {unknown} args - the tool arguments recorded at pre-execute.
 * @returns {string} command text, possibly empty.
 */
export function commandOf(args) {
  if (typeof args === 'string') return args
  if (!args || typeof args !== 'object') return ''
  const record = /** @type {Record<string, unknown>} */ (args)
  for (const key of ['command', 'cmd', 'script', 'code', 'input']) {
    const value = record[key]
    if (typeof value === 'string' && value !== '') return value
  }
  try {
    return JSON.stringify(args) ?? ''
  } catch {
    return ''
  }
}

/**
 * Whether this tool may be judged at all. Restricting the set keeps the gate's
 * blast radius to shell command approvals.
 * @param {string} toolName - the tool the approval request is about.
 * @param {readonly string[]} allowed - configured tool allow list.
 * @returns {boolean} true when the tool is in scope.
 */
export function isCandidateTool(toolName, allowed) {
  if (typeof toolName !== 'string') return false
  return allowed.includes(toolName)
}

/**
 * First local deny-list match, if any. Runs before any model verdict and
 * short-circuits it: a categorically unsafe shape is never auto-granted.
 * @param {string} command - command text.
 * @param {ReadonlyArray<{id: string, re: string}>} patterns - configured patterns.
 * @returns {string | undefined} the matched pattern id.
 */
export function hardDenyMatch(command, patterns) {
  if (typeof command !== 'string' || command === '') return undefined
  for (const pattern of patterns) {
    try {
      if (new RegExp(pattern.re, 'i').test(command)) return pattern.id
    } catch {
      // An invalid operator-supplied regex is a non-match, never a crash.
    }
  }
  return undefined
}

/**
 * The local gate that runs BEFORE any network call. Everything decidable
 * without the model is decided here, for two reasons: a categorically unsafe
 * command must never be transmitted to a third party for judgement, and a call
 * that cannot be judged must not be paid for.
 * @param {object} input - the local facts for one pending approval.
 * @param {string} input.mode - gate mode.
 * @param {boolean} input.candidate - whether the tool is in scope.
 * @param {string} input.command - command text.
 * @param {typeof DEFAULTS} input.config - effective configuration.
 * @returns {{ ok: true } | { ok: false, reason: string }} whether to consult the model.
 */
export function preflight(input) {
  if (input.mode === 'off') return { ok: false, reason: 'mode-off' }
  if (!input.candidate) return { ok: false, reason: 'tool-not-in-scope' }
  const deny = hardDenyMatch(input.command, input.config.hardDenyPatterns)
  if (deny !== undefined) return { ok: false, reason: `hard-deny:${deny}` }
  if (input.command === '') return { ok: false, reason: 'no-command-text' }
  return { ok: true }
}

/**
 * Read the risk probability out of a Jev response, tolerating the shapes the
 * several gateways actually return. A value outside [0, 1] or of the wrong type
 * is treated as absent, which defers.
 * @param {unknown} answers - the `answers` map from the Jev response.
 * @returns {{ risk?: number, confidence?: number, keys: string[] }} parsed fields.
 */
export function readVerdict(answers) {
  const empty = { keys: [] }
  if (!answers || typeof answers !== 'object') return empty
  const map = /** @type {Record<string, unknown>} */ (answers)
  const keys = Object.keys(map)
  const riskEntry = map.risk
  if (!riskEntry || typeof riskEntry !== 'object') return { keys }
  const entry = /** @type {Record<string, unknown>} */ (riskEntry)
  const rawRisk = entry.noul ?? entry.probability ?? entry.value
  const risk = typeof rawRisk === 'number' && Number.isFinite(rawRisk) && rawRisk >= 0 && rawRisk <= 1
    ? rawRisk
    : undefined
  const rawConfidence = entry.confidence
  const confidence = typeof rawConfidence === 'number' && Number.isFinite(rawConfidence)
    ? Math.min(1, Math.max(0, rawConfidence))
    : undefined
  return { risk, confidence, keys }
}

/**
 * The single decision rule. Everything that is not a confident clear becomes
 * `defer`, which hands the request back to the human answerer.
 * @param {object} input - the resolved inputs for one decision.
 * @param {string} input.mode - gate mode.
 * @param {string} input.toolName - tool under decision.
 * @param {string} input.command - command text.
 * @param {boolean} input.candidate - whether the tool is in scope.
 * @param {unknown} input.answers - raw Jev `answers` map, absent on failure.
 * @param {typeof DEFAULTS} input.config - effective configuration.
 * @returns {{ action: 'allow' | 'defer', reason: string, risk?: number, confidence?: number }} the outcome.
 */
export function decide(input) {
  const { mode, candidate, command, answers, config } = input
  const local = preflight({ mode, candidate, command, config })
  if (!local.ok) return { action: 'defer', reason: local.reason }

  const verdict = readVerdict(answers)
  if (verdict.risk === undefined) return { action: 'defer', reason: 'no-risk-answer', keys: verdict.keys }

  if (verdict.confidence === undefined) {
    return { action: 'defer', reason: 'no-confidence', risk: verdict.risk }
  }
  if (verdict.confidence < config.minConfidence) {
    return { action: 'defer', reason: 'low-confidence', risk: verdict.risk, confidence: verdict.confidence }
  }
  if (verdict.risk > config.allowThreshold) {
    return { action: 'defer', reason: 'risk-above-threshold', risk: verdict.risk, confidence: verdict.confidence }
  }
  // Defence in depth: granting requires the enforcing mode explicitly. The
  // caller already short-circuits observe mode, but a future reordering must
  // not be able to turn a dry run into a grant.
  if (mode !== 'enforce') {
    return { action: 'defer', reason: 'mode-not-enforcing', risk: verdict.risk, confidence: verdict.confidence }
  }
  return { action: 'allow', reason: 'cleared', risk: verdict.risk, confidence: verdict.confidence }
}
