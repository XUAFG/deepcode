/**
 * Field-level settings write-back for discovered capabilities (0.13.5 W3)。
 *
 * Invariants (decision D6):
 *   - only *missing* fields are filled; a value the user already declared is
 *     never overwritten (not even with a "better" one);
 *   - only model-level entries are written — never a provider-wide setting;
 *   - the route's other keys and every other model's entry are left byte-identical;
 *   - one `set` on `providers.<route>.models` under the current revision, with a
 *     single re-read/retry on SETTINGS_CONFLICT.
 *
 * 0.14.0-preview（ST-03 / F-PLUG-02）追加**来源戳**：
 *   「只填空」有一个已知代价——端点能力若后来变了（换网关、供应商升级），我们上一轮写下的旧值
 *   会永久留存，因为它在「字段已存在」这条规则下不可再动。为此写回时记录「这个值是我们写的」
 *   （字段级来源戳，进程内），下一轮若**新发现的值与戳不同**，则允许覆盖**我们自己写下的**旧值；
 *   用户手写值没有戳，因此永不覆盖。进程重启后戳丢失 → 退回「只填空」（保守面，宁可不动）。
 */
import type { ReasoningEfforts } from './capability-probe.js'
import { canonicalJson } from './signature.js'

export interface ModelPatch {
  id: string
  reasoningEfforts?: ReasoningEfforts
  input?: string[]
  contextWindow?: number
  maxTokens?: number
  compat?: Record<string, unknown>
  /** Provenance label recorded in the change log, e.g. 'engine-catalog'. */
  source?: string
}

/** 字段级来源戳：记录「该字段现在的值是我方在某个来源下写下的」。 */
export interface ProvenanceStamp {
  value: unknown
  source: string
}

export interface StampStore {
  get(modelId: string, field: string): ProvenanceStamp | undefined
  set(modelId: string, field: string, stamp: ProvenanceStamp): void
}

export function createStampStore(): StampStore {
  const map = new Map<string, Map<string, ProvenanceStamp>>()
  return {
    get(modelId, field) {
      return map.get(modelId)?.get(field)
    },
    set(modelId, field, stamp) {
      const fields = map.get(modelId) ?? new Map<string, ProvenanceStamp>()
      fields.set(field, stamp)
      map.set(modelId, fields)
    },
  }
}

/** 写回计划里「实际写入」的字段明细（用于记录来源戳与结构化日志）。 */
export interface WrittenField {
  id: string
  field: string
  value: unknown
}

export interface PlanResult {
  /** The complete models array to write (unchanged entries preserved verbatim). */
  models: unknown[]
  /** Human-readable list of fields actually added. */
  changes: string[]
  /** Model ids that were declared but could not be patched, with the reason. */
  skipped: string[]
  /** Machine-readable counterpart of [changes]. */
  written: WrittenField[]
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function idOf(entry: unknown): string | undefined {
  if (typeof entry === 'string') return entry
  if (isRecord(entry) && typeof entry.id === 'string') return entry.id
  return undefined
}

function isEmpty(value: unknown): boolean {
  if (value === undefined || value === null) return true
  if (Array.isArray(value)) return value.length === 0
  if (isRecord(value)) return Object.keys(value).length === 0
  return false
}

/**
 * 该字段是否可写：
 * - 缺失（只填空，既有语义）；或
 * - 现值**逐字等于**我方上一轮写下的戳值，且本轮发现值与之不同（源变化 → 允许刷新我方旧写入）。
 * 用户手写值没有戳 → 恒不可写。
 */
function canWrite(
  stamps: StampStore | undefined,
  id: string,
  field: string,
  current: unknown,
  next: unknown,
): boolean {
  if (isEmpty(current)) return true
  if (!stamps) return false
  const stamp = stamps.get(id, field)
  if (!stamp) return false
  if (canonicalJson(stamp.value) !== canonicalJson(current)) return false
  return canonicalJson(next) !== canonicalJson(current)
}

/**
 * Builds the write-back plan. [patches] are applied in order; the first patch for
 * a given id wins for a field that is still missing after earlier patches.
 */
export function planModelPatch(rawModels: unknown, patches: ModelPatch[], stamps?: StampStore): PlanResult {
  const models = Array.isArray(rawModels) ? [...rawModels] : []
  const changes: string[] = []
  const skipped: string[] = []
  const written: WrittenField[] = []
  if (models.length === 0) {
    for (const patch of patches) skipped.push(`${patch.id}: 路由未声明该模型（不凭空创建条目）`)
    return { models, changes, skipped, written }
  }

  for (const patch of patches) {
    const index = models.findIndex((entry) => idOf(entry) === patch.id)
    if (index < 0) {
      skipped.push(`${patch.id}: 路由未声明该模型（不凭空创建条目）`)
      continue
    }
    let entry = models[index]
    if (typeof entry === 'string') entry = { id: entry }
    if (!isRecord(entry)) {
      skipped.push(`${patch.id}: 条目形态无法写入`)
      continue
    }
    const next: Record<string, unknown> = { ...entry }
    const tag = patch.source ? ` (${patch.source})` : ''
    const source = patch.source ?? ''

    if (patch.reasoningEfforts && canWrite(stamps, patch.id, 'reasoningEfforts', next.reasoningEfforts, patch.reasoningEfforts)) {
      next.reasoningEfforts = patch.reasoningEfforts
      changes.push(`${patch.id}: 补 reasoningEfforts=${Object.keys(patch.reasoningEfforts).join('/')}${tag}`)
      written.push({ id: patch.id, field: 'reasoningEfforts', value: patch.reasoningEfforts })
    }
    if (patch.input && patch.input.length > 0 && canWrite(stamps, patch.id, 'input', next.input, patch.input)) {
      next.input = patch.input
      changes.push(`${patch.id}: 补 input=${patch.input.join('/')}${tag}`)
      written.push({ id: patch.id, field: 'input', value: patch.input })
    }
    if (typeof patch.contextWindow === 'number' && canWrite(stamps, patch.id, 'contextWindow', next.contextWindow, patch.contextWindow)) {
      next.contextWindow = patch.contextWindow
      changes.push(`${patch.id}: 补 contextWindow=${patch.contextWindow}${tag}`)
      written.push({ id: patch.id, field: 'contextWindow', value: patch.contextWindow })
    }
    if (typeof patch.maxTokens === 'number' && canWrite(stamps, patch.id, 'maxTokens', next.maxTokens, patch.maxTokens)) {
      next.maxTokens = patch.maxTokens
      changes.push(`${patch.id}: 补 maxTokens=${patch.maxTokens}${tag}`)
      written.push({ id: patch.id, field: 'maxTokens', value: patch.maxTokens })
    }
    if (patch.compat) {
      const existingCompat = isRecord(next.compat) ? { ...next.compat } : {}
      const added: string[] = []
      const addedEntries: Array<[string, unknown]> = []
      for (const [key, value] of Object.entries(patch.compat)) {
        if (canWrite(stamps, patch.id, `compat.${key}`, existingCompat[key], value)) {
          existingCompat[key] = value
          added.push(key)
          addedEntries.push([`compat.${key}`, value])
        }
      }
      if (added.length > 0) {
        next.compat = existingCompat
        changes.push(`${patch.id}: 补 compat.${added.join(',')}${tag}`)
        for (const [field, value] of addedEntries) written.push({ id: patch.id, field, value })
      }
    }

    if (changes.length > 0 || next !== entry) models[index] = next
  }

  return { models, changes, skipped, written }
}

/** 写回尝试上限（首尝试 + 至多 2 次重试）。有界：不得无界循环。 */
export const MAX_WRITE_ATTEMPTS = 3
/** 重试退避基数（毫秒）。冲突是并发写者的信号，退避让它们先落定。 */
export const WRITE_RETRY_BACKOFF_MS = 25

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * Renders the two revision numbers a SETTINGS_CONFLICT carries.
 *
 * The settings service exposes them as structured fields (
 * `dsh/packages/settings/settings/src/index.ts:47-51`), so a rejection report can name
 * both sides instead of only repeating the message. Falls back to the message when the
 * error came from somewhere else and carries no numbers.
 * @param error - the rejected write's error.
 * @returns a ` expected=<n> actual=<n>` suffix, or an empty string.
 */
function conflictRevisions(error: unknown): string {
  const expected = (error as { expected?: unknown })?.expected
  const actual = (error as { actual?: unknown })?.actual
  if (typeof expected !== 'number' && typeof actual !== 'number') return ''
  return ` expected=${String(expected)} actual=${String(actual)}`
}

export interface SettingsDescriptorLike {
  ns: string
  value: unknown
  revision: number
}

export interface SettingsWriteLike {
  describe(options?: { namespaces?: readonly string[] }): SettingsDescriptorLike[]
  mutate(ns: string, ops: unknown[], expectedRevision?: number): Promise<unknown>
}

export interface WriteResult {
  wrote: boolean
  reason: string
  changes: string[]
  skipped: string[]
  written?: WrittenField[]
}

function modelsOf(descriptor: SettingsDescriptorLike, route: string): unknown {
  const section = isRecord(descriptor.value) ? descriptor.value : {}
  const providers = isRecord(section.providers) ? section.providers : {}
  const routeConfig = isRecord(providers[route]) ? providers[route] as Record<string, unknown> : {}
  return routeConfig.models
}

/**
 * Applies [patches] to one route's model list. Returns what happened; never throws
 * for an ordinary rejection (the caller reports it).
 *
 * [stamps] 可选：成功写入后把「我方写下的值」记入戳表；下一轮据此才允许刷新我方旧写入。
 */
export async function applyModelPatch(
  settings: SettingsWriteLike | undefined,
  route: string,
  patches: ModelPatch[],
  logger?: { info?: (msg: string) => void; warn?: (msg: string) => void; trace?: (msg: string) => void },
  stamps?: StampStore,
  descriptorOverride?: SettingsDescriptorLike,
): Promise<WriteResult> {
  if (!settings) return { wrote: false, reason: 'settings-unavailable', changes: [], skipped: [] }
  if (patches.length === 0) return { wrote: false, reason: 'nothing-to-apply', changes: [], skipped: [] }

  const sourceOf = new Map(patches.map((patch) => [patch.id, patch.source ?? '']))

  /**
   * One write attempt. `attempt > 0` means this is a retry, so the caller-supplied
   * descriptor is ignored and a fresh one is read: a conflict means the revision we
   * held is stale, and reusing it would fail identically forever.
   *
   * L.2（2026-09-26）：旧实现只重试 **1** 次，且重试时丢弃 override。实测（真实 CAS 语义复现）
   * 单次重试在「同进程还有别的写者」时不够：settings 的 revision 由**服务端** revisions Map
   * 持有（dsh/packages/settings/settings/src/index.ts:313-315），任何一次别的写入都会推进它；
   * 而冲突比较发生在 configEditor.edit 的**回调内部**（:390-395），用的是那一刻重算的值。
   * 于是「describe -> mutate」之间被插进 2 次以上外部推进时，1 次重试必然用尽。
   * 现改为有界 3 次尝试（首尝试 + 至多 2 次重试），每次重试都重读。
   */
  const attemptOnce = async (attempt: number): Promise<WriteResult> => {
    // 复用调用方刚读到的描述符可省掉一次全量 describe（见 index.ts tick 的成本注释）；
    // 重试路径必须重读（冲突意味着手里那份 revision 已过期，复用只会再撞一次）。
    const descriptor = (attempt === 0 && descriptorOverride !== undefined)
      ? descriptorOverride
      : settings.describe({ namespaces: ['llm-pi-ai'] }).find((d) => d.ns === 'llm-pi-ai')
    if (!descriptor) return { wrote: false, reason: 'namespace-absent', changes: [], skipped: [] }
    const plan = planModelPatch(modelsOf(descriptor, route), patches, stamps)
    if (plan.changes.length === 0) {
      return { wrote: false, reason: 'no-change', changes: [], skipped: plan.skipped, written: [] }
    }
    try {
      await settings.mutate('llm-pi-ai', [{ op: 'set', path: ['providers', route, 'models'], value: plan.models }], descriptor.revision)
      if (stamps) {
        for (const field of plan.written) stamps.set(field.id, field.field, { value: field.value, source: sourceOf.get(field.id) ?? '' })
      }
      if (attempt > 0) logger?.info?.(`dsh-model-capability: write-back for route ${route} succeeded on attempt ${String(attempt + 1)}`)
      return { wrote: true, reason: 'wrote', changes: plan.changes, skipped: plan.skipped, written: plan.written }
    } catch (error) {
      const conflict = (error as { code?: string })?.code === 'SETTINGS_CONFLICT'
      if (conflict && attempt + 1 < MAX_WRITE_ATTEMPTS) {
        // 冲突是可重试的：退避一小段再重读重试。退避让并发写者有机会先落定。
        logger?.info?.(`dsh-model-capability: SETTINGS_CONFLICT for route ${route}; retry ${String(attempt + 2)}/${String(MAX_WRITE_ATTEMPTS)}`)
        if (attempt + 1 > 1) await sleep(WRITE_RETRY_BACKOFF_MS * (attempt + 1))
        return attemptOnce(attempt + 1)
      }
      // 诊断（X1 2026-09-25；L.2 2026-09-26 追加 expected/actual）：logger.trace 由 index.ts 接到 diag()
      // 并落盘 $DSH_HOME/model-capability.log（设备实测该文件确实存在、含 tick/runAutoPass 行），故拒绝真因可见。
      // 把异常文本 + code + 两侧 revision + 栈交给 logger.warn 与这条落盘 trace。
      const detail = (error as Error)?.message ?? String(error)
      const stack = (error as Error)?.stack ?? ''
      // SettingsConflictError 暴露 expected/actual（settings/src/index.ts:47-51）；取不到时回落 message。
      const conflictDetail = conflict ? conflictRevisions(error) : ''
      logger?.warn?.(`dsh-model-capability: write-back failed for route ${route}: ${detail}`)
      logger?.trace?.(`write-back REJECTED route=${route} revision=${String(descriptor.revision)} attempt=${String(attempt + 1)}`
        + ` code=${String((error as { code?: string })?.code)} exname=${(error as Error)?.name ?? '?'}${conflictDetail} message=${detail}`
        + (stack ? ` stack=${stack.replace(/\s+/g, ' ').slice(0, 900)}` : ''))
      return { wrote: false, reason: conflict ? 'conflict-retry-failed' : 'mutate-rejected', changes: [], skipped: plan.skipped, written: [] }
    }
  }

  return attemptOnce(0)
}
