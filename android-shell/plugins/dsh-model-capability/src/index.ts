/**
 * dsh-model-capability — host-side capability discovery for user-declared provider
 * routes (issues #122 / #125: a custom provider has no selectable reasoning effort).
 *
 * Sources, in strict order — a capability that no source states stays absent:
 *   1. `endpoint-descriptor` — a passive GET of metadata the endpoint itself returns;
 *   2. `vendor-descriptor`  — explicit capability schemas (OpenRouter / Google / Ollama);
 *   3. `engine-catalog`     — exact model-id lookup in the pi-ai vendor catalogs the
 *      engine ships (a vendor *declaration*, never a name heuristic);
 *   4. `active-probe`       — only with explicit approval, and only after a negative
 *      control proves the endpoint actually validates the effort field.
 *
 * Write-back is opt-in per call (or by the startup pass) and field-level: it only
 * fills missing model-level fields and never overwrites a value the user declared.
 */
import { readFileSync, appendFileSync } from 'node:fs'
import { Context } from '@deepseek-ai/cordis'
import { defineTool } from '@deepseek-ai/dsh-tools'
import {
  DIALECT_COMPAT_KEYS,
  THINKING_LEVELS,
  probePassive,
  probeReasoningEfforts,
  type FetchLike,
  type Modality,
  type ModelCapabilities,
  type ProbeReport,
  type ReasoningEfforts,
} from './capability-probe.js'
import { hasCapabilities, lookupCatalog, type CatalogSnapshot } from './catalog-lookup.js'
import { providerFromSettings, type SettingsLike } from './settings-config.js'
import { applyModelPatch, createStampStore, type ModelPatch, type SettingsWriteLike } from './settings-writer.js'
import { capabilitySignature } from './signature.js'
import {
  MODELS_DEV_URL,
  buildModelsDevSnapshot,
  effortsFromLevels,
  isStale,
  lookupModelsDev,
  mergeModelsDev as mergeModelsDevEntries,
  modelsDevCachePath,
  parseModelsDev,
  readModelsDevSnapshot,
  writeModelsDevSnapshot,
  type ModelsDevSnapshot,
} from './models-dev.js'

export const name = 'dsh-model-capability'

/** `settings` is the write-back seam; `tools` registers the discovery tools. */
export const inject = ['tools', 'settings'] as const

export {
  THINKING_LEVELS,
  probePassive,
  probeReasoningEfforts,
  parseDescriptor,
  parseOllamaShow,
  effortsFrom,
} from './capability-probe.js'
export type {
  ActiveProbeResult,
  ModelCapabilities,
  ProbeReport,
  ProviderConfig,
  ReasoningEfforts,
  ThinkingLevel,
} from './capability-probe.js'
export { providerFromSettings } from './settings-config.js'
export type { SettingsLike } from './settings-config.js'
export { lookupCatalog, effortsOf, hasCapabilities } from './catalog-lookup.js'
export type { CatalogEntry, CatalogMatch, CatalogSnapshot } from './catalog-lookup.js'
export { planModelPatch, applyModelPatch } from './settings-writer.js'
export type { ModelPatch, PlanResult, WriteResult } from './settings-writer.js'
export {
  MODELS_DEV_URL,
  MODELS_DEV_TTL_MS,
  MODELS_DEV_MAX_BYTES,
  parseModelsDev,
  buildModelsDevSnapshot,
  isStale,
  lookupModelsDev,
  mergeModelsDev,
  effortsFromLevels,
  modelsDevCachePath,
  readModelsDevSnapshot,
  writeModelsDevSnapshot,
  thinkingLevelsFrom,
} from './models-dev.js'
export type { ModelsDevEntry, ModelsDevSnapshot, ModelsDevAgreement } from './models-dev.js'

export interface PluginConfig {
  /** Fill missing model capabilities automatically on startup (default true). */
  autoApply?: boolean
  /** Seconds to wait after startup before the automatic pass (default 8). */
  startupDelaySeconds?: number
  /**
   * Fallback safety-net poll interval in seconds (default 120), used only when the
   * event-driven trigger below is unavailable. The trigger path is
   * `settings/document-updated`, so this is a backstop rather than the primary
   * mechanism — see the auto-apply comment in `apply()` for the measured reason it
   * cannot be 5s.
   */
  pollIntervalSeconds?: number
  /** Restrict the automatic pass to these routes (default: every declared route). */
  routes?: string[]
  /**
   * S3: consult the models.dev encyclopedia (default true). A failed fetch degrades
   * to the previous snapshot, and with none the source is simply absent — it never
   * blocks S1/S2/S4.
   */
  modelsDev?: boolean
  /**
   * S5: fallback values the USER explicitly declared. Absent means there is no S5 —
   * an unstated capability stays absent rather than becoming a factory constant.
   * Values from here are tagged `user-fallback` on write-back.
   */
  fallbacks?: {
    contextWindow?: number
    maxTokens?: number
    input?: Modality[]
    reasoningEfforts?: ReasoningEfforts
  }
}

interface CredentialsLike {
  resolve(ref: unknown): Promise<{ value?: string } | undefined>
}

interface CtxLike {
  settings?: SettingsLike & SettingsWriteLike
  logger?: (name: string) => { info?: (msg: string) => void; warn?: (msg: string) => void; debug?: (msg: string) => void }
  effect?: (fn: () => () => void) => void
  /** cordis 服务查询：未提供的服务返回 undefined（不要直接读 ctx.<service>——未 inject 会抛）。 */
  get?: (name: string) => unknown
  tools: { register(tool: unknown): void }
}

/** 可选服务读取：未声明 inject 时直接读属性会抛（cordis 4 实测），统一走 ctx.get。 */
function optionalService<T>(ctx: unknown, name: string): T | undefined {
  try {
    const getter = (ctx as { get?: (n: string) => unknown }).get
    if (typeof getter !== 'function') return undefined
    return getter.call(ctx, name) as T | undefined
  } catch {
    return undefined
  }
}

let cachedSnapshot: CatalogSnapshot | undefined
let snapshotTried = false

/**
 * 诊断轨迹（DSH_MODEL_CAPABILITY_TRACE=0 可关）：追加到 $DSH_HOME/model-capability.log。
 * 引擎 stdout（engine.log）轮转很快、只留极短尾部，自动补给的静默失败在真机上无法定位——
 * 这条文件轨迹是现场排障的唯一可靠面（不含任何凭据）。
 */
function diag(message: string): void {
  if (process.env.DSH_MODEL_CAPABILITY_TRACE === '0') return
  try {
    const home = process.env.DSH_HOME ?? '/data/user/0/com.dsharnessmobile.shell/files/home/.dsh'
    appendFileSync(`${home}/model-capability.log`, `${new Date().toISOString()} ${message}\n`)
  } catch {
    // 诊断失败不影响主流程
  }
}

/** Loads the build-time catalog snapshot shipped beside this module. */
export function loadCatalogSnapshot(): CatalogSnapshot | undefined {
  if (snapshotTried) return cachedSnapshot
  snapshotTried = true
  try {
    cachedSnapshot = JSON.parse(readFileSync(new URL('./catalog-snapshot.json', import.meta.url), 'utf8')) as CatalogSnapshot
  } catch {
    cachedSnapshot = undefined
  }
  return cachedSnapshot
}

/**
 * Keep only the wire-dialect compat keys, and only when the catalog actually
 * declares a thinking format — a partial dialect (e.g. only maxTokensField)
 * would still let pi-ai fall back to a detected default (issue #134).
 * @param compat - unanimous compat map from the catalog lookup.
 * @returns the dialect keys to write, or undefined when the dialect is unknown.
 */
export function pickDialect(compat: Record<string, unknown> | undefined): Record<string, unknown> | undefined {
  if (!compat || compat.thinkingFormat === undefined) return undefined
  const out: Record<string, unknown> = {}
  for (const key of DIALECT_COMPAT_KEYS) {
    const value = compat[key]
    if (value !== undefined) out[key] = value
  }
  return out
}

/**
 * Injects models.dev (S3) capabilities into a probe report and recomputes unknowns.
 *
 * Runs before {@link mergeCatalog} (S4): models.dev is the upstream encyclopedia and
 * the engine catalog is the offline floor, so the encyclopedia wins the fields it
 * states. A lookup returns every provider that describes the id, and the fields they
 * disagree on are recorded as conflicts and left unwritten (same rule as S4).
 * @param report - report to enrich in place.
 * @param snapshot - retained models.dev snapshot, or undefined when absent.
 * @returns the same report.
 */
export function mergeModelsDevInto(
  report: ProbeReport,
  declared: string[],
  snapshot: ModelsDevSnapshot | undefined,
  /** Filled with levels that must wait for a wire dialect (see applyDeferredModelsDevLevels). */
  deferred?: Map<string, ReasoningEfforts>,
): ProbeReport {
  if (!snapshot) return report
  const byId = new Map(report.models.map((model) => [model.id, model]))
  for (const id of declared) {
    if (!byId.has(id)) {
      const fresh: ModelCapabilities = { id, sources: {} }
      byId.set(id, fresh)
      report.models.push(fresh)
    }
  }
  for (const model of byId.values()) {
    const hits = lookupModelsDev(snapshot, model.id)
    if (hits.length === 0) continue
    const agreed = mergeModelsDevEntries(hits)
    report.notes.push(`${model.id}: models.dev 命中 ${agreed.providers.join('/')}`)
    for (const conflict of agreed.conflicts) report.notes.push(`${model.id}: ${conflict}`)
    if (agreed.input && !model.input) {
      model.input = agreed.input
      model.sources.input = 'models-dev'
    }
    if (agreed.contextWindow && !model.contextWindow) {
      model.contextWindow = agreed.contextWindow
      model.sources.contextWindow = 'models-dev'
    }
    if (agreed.maxTokens && !model.maxTokens) {
      model.maxTokens = agreed.maxTokens
      model.sources.maxTokens = 'models-dev'
    }
    // 与引擎目录同纪律：只有方言明确时才写 reasoningEfforts。models.dev 不声明 wire 方言，
    // 所以档位写入仍然要等 S4（或用户显式 compat）给出 thinkingFormat——否则跳过并记 note。
    const levels = effortsFromLevels(agreed.thinkingLevels)
    if (levels && !model.reasoningEfforts) {
      const dialect = pickDialect(model.compat)
      if (dialect) {
        model.reasoningEfforts = levels
        model.sources.reasoningEfforts = 'models-dev'
      } else {
        // 方言还没到（models.dev 不声明 wire 方言，S4 目录可能稍后给出）。先挂起，
        // 由 applyDeferredModelsDevLevels 在 S4 之后决定写或跳过——方言门本身不动。
        deferred?.set(model.id, levels)
      }
    }
  }
  report.models = [...byId.values()].sort((a, b) => a.id.localeCompare(b.id))
  report.unknown = declared.filter((id) => {
    const found = byId.get(id)
    return !found || Object.keys(found.sources).length === 0
  })
  return report
}

/**
 * Injects user-declared fallback values (S5) into a probe report.
 *
 * S5 exists ONLY when the user explicitly configured `fallbacks`; there is no factory
 * default. Applying a constant to every model regardless of what it is would be
 * inventing a fact — a 262144 context window written onto an 8k model reads to the user
 * as capacity the gateway then refuses. So an unconfigured fallback leaves the
 * capability absent, and a configured one is tagged `user-fallback` to keep it
 * distinguishable from a declaration by endpoints, encyclopedias, or catalogs.
 * @param report - report to enrich in place.
 * @param fallbacks - user-declared values, or undefined when none were configured.
 * @param configured - true when the user actually supplied a `fallbacks` block.
 * @returns the same report.
 */
export function mergeFallbacks(
  report: ProbeReport,
  declared: string[],
  fallbacks: { contextWindow?: number; maxTokens?: number; input?: Modality[]; reasoningEfforts?: ReasoningEfforts } | undefined,
  configured: boolean,
): ProbeReport {
  if (!configured || !fallbacks) return report
  // 先确保每个 declared 模型都有条目：models.dev 关闭/无缓存时它不会建条目（提前返回），
  // 而「用户配了兜底值」恰恰最需要在这种情况下生效——否则 S5 会在最该用的场景哑掉。
  const byId = new Map(report.models.map((model) => [model.id, model]))
  for (const id of declared) {
    if (!byId.has(id)) {
      const fresh: ModelCapabilities = { id, sources: {} }
      byId.set(id, fresh)
      report.models.push(fresh)
    }
  }
  report.models = [...byId.values()].sort((a, b) => a.id.localeCompare(b.id))
  for (const model of report.models) {
    for (const key of ['input', 'contextWindow', 'maxTokens', 'reasoningEfforts'] as const) {
      const value = fallbacks[key]
      if (value === undefined) continue
      if (model[key] !== undefined) continue
      if (Object.prototype.hasOwnProperty.call(model.sources, key)) continue
      ;(model as unknown as Record<string, unknown>)[key] = value
      model.sources[key] = 'user-fallback'
    }
  }
  report.unknown = declared.filter((id) => {
    const found = report.models.find((m) => m.id === id)
    return !found || Object.keys(found.sources).length === 0
  })
  return report
}

/**
 * Writes levels that models.dev reported but could not yet write, now that the
 * engine catalog (S4) may have supplied the wire dialect.
 *
 * The dialect gate is unchanged: a level set is written only when a dialect is
 * explicit, exactly as for a catalog-sourced effort map. When no dialect exists the
 * levels are dropped with a note — the encyclopedia saying "this model offers
 * high/max" does not say how the gateway spells them, and guessing has already cost
 * a real 400 (issue #134).
 * @param report - report to update in place.
 * @param deferred - levels parked by {@link mergeModelsDev}.
 * @returns the same report.
 */
export function applyDeferredModelsDevLevels(report: ProbeReport, deferred: Map<string, ReasoningEfforts>): ProbeReport {
  if (deferred.size === 0) return report
  for (const model of report.models) {
    const levels = deferred.get(model.id)
    if (!levels || model.reasoningEfforts) continue
    const dialect = pickDialect(model.compat)
    if (dialect) {
      model.reasoningEfforts = levels
      model.sources.reasoningEfforts = 'models-dev'
      continue
    }
    report.notes.push(
      `${model.id}: models.dev 列出推理档位（${Object.keys(levels).join('/')}）但未给出 wire 方言`
      + '——跳过 reasoningEfforts 写入，避免按错误方言发送导致请求被拒',
    )
  }
  return report
}

/** Injects catalog-derived capabilities into a probe report and recomputes unknowns. */export function mergeCatalog(
  report: ProbeReport,
  declared: string[],
  snapshot: CatalogSnapshot | undefined,
  api: string | undefined,
): ProbeReport {
  const byId = new Map<string, ModelCapabilities>(report.models.map((model) => [model.id, model]))
  for (const id of declared) {
    if (!byId.has(id)) {
      const fresh: ModelCapabilities = { id, sources: {} }
      byId.set(id, fresh)
      report.models.push(fresh)
    }
  }
  for (const model of byId.values()) {
    const match = lookupCatalog(snapshot, model.id, api)
    if (match.providers.length > 0) report.notes.push(`${model.id}: 引擎目录命中 ${match.providers.join('/')}`)
    for (const conflict of match.conflicts) report.notes.push(`${model.id}: ${conflict}`)
    const capabilities = match.capabilities
    // 方言优先（issue #134）：reasoningEfforts 只有在「pi-ai 知道该模型的方言」时才写。
    // 目录里同名模型来自多个厂商、thinkingFormat 冲突或缺失时，pi-ai 会按探测默认
    // （未知 baseURL → openai）序列化 reasoning_effort，真实网关可能直接 400。
    const dialect = pickDialect(capabilities.compat)
    if (dialect && !model.compat) {
      model.compat = dialect
    }
    if (capabilities.reasoningEfforts && !model.reasoningEfforts) {
      if (dialect) {
        model.reasoningEfforts = capabilities.reasoningEfforts
        model.sources.reasoningEfforts = 'engine-catalog'
      } else {
        report.notes.push(
          `${model.id}: 目录未给出统一 thinkingFormat（方言不明）——跳过 reasoningEfforts 写入，`
          + '避免按错误方言发送推理等级导致请求被拒；如需档位请在设置里显式声明 compat.thinkingFormat',
        )
      }
    }
    if (capabilities.input && !model.input) {
      model.input = capabilities.input
      model.sources.input = 'engine-catalog'
    }
    if (capabilities.contextWindow && !model.contextWindow) {
      model.contextWindow = capabilities.contextWindow
      model.sources.contextWindow = 'engine-catalog'
    }
    if (capabilities.maxTokens && !model.maxTokens) {
      model.maxTokens = capabilities.maxTokens
      model.sources.maxTokens = 'engine-catalog'
    }
  }
  report.models = [...byId.values()].sort((a, b) => a.id.localeCompare(b.id))
  report.unknown = declared.filter((id) => {
    const found = byId.get(id)
    return !found || Object.keys(found.sources).length === 0
  })
  return report
}

function summarize(report: ProbeReport): string {
  const lines = [`提供商路由 ${report.route}：抓取 ${report.fetched.length} 个端点，识别 ${report.models.length} 个模型`]
  for (const model of report.models) {
    const parts: string[] = []
    if (model.input) parts.push('模态 ' + model.input.join('/'))
    if (model.contextWindow) parts.push('上下文 ' + model.contextWindow)
    if (model.maxTokens) parts.push('输出上限 ' + model.maxTokens)
    if (model.reasoningEfforts) parts.push('推理等级 ' + Object.keys(model.reasoningEfforts).join('/'))
    const sources = Object.entries(model.sources).map(([key, source]) => `${key}<-${source}`).join(' ')
    lines.push(`- ${model.id}: ${parts.length > 0 ? parts.join('，') : '未声明任何能力'}${sources ? ' [' + sources + ']' : ''}`)
  }
  if (report.unknown.length > 0) {
    lines.push(`未获得能力元数据：${report.unknown.map((id) => `${id}（S1-S5 均无声明）`).join(', ')}`)
  }
  for (const note of report.notes) lines.push('注：' + note)
  return lines.join('\n')
}

/** Turns a report into field-level patches, keeping each field's provenance. */
export function patchesFrom(report: ProbeReport): ModelPatch[] {
  const patches: ModelPatch[] = []
  for (const model of report.models) {
    const patch: ModelPatch = { id: model.id }
    let any = false
    if (model.reasoningEfforts) { patch.reasoningEfforts = model.reasoningEfforts; any = true }
    if (model.compat) { patch.compat = model.compat; any = true }
    if (model.input) { patch.input = model.input; any = true }
    if (model.contextWindow) { patch.contextWindow = model.contextWindow; any = true }
    if (model.maxTokens) { patch.maxTokens = model.maxTokens; any = true }
    if (any) {
      const sources = Object.values(model.sources)
      patch.source = [...new Set(sources)].join('+')
      patches.push(patch)
    }
  }
  return patches
}

export function apply(ctx: Context, config: PluginConfig = {}) {
  const c = ctx as unknown as CtxLike
  const settings = c.settings
  const log = c.logger?.('dsh-model-capability')
  // diag() 落在 $DSH_HOME/model-capability.log：引擎 stdout 轮转太快，写回被拒的真因只有这里留得住。
  const logWithTrace = log === undefined ? undefined : { ...log, trace: diag }
  const snapshot = loadCatalogSnapshot()
  if (snapshot) log?.info?.(`catalog snapshot: ${snapshot.source} / ${String(snapshot.modelCount ?? 0)} models`)
  else log?.info?.('catalog snapshot absent — engine-catalog stage disabled')

  /**
   * models.dev cache state, held per apply() call.
   *
   * The snapshot is loaded ONCE here, synchronously, from the local cache file. Network
   * work happens in exactly two places, neither of which is the tick's synchronous path:
   *   1. {@link refreshModelsDevOnStartup} — one background refresh per process start, fired
   *      from the auto-apply effect before the first pass, so a clean install has S3 data
   *      without anyone calling a tool;
   *   2. an explicit tool call, which may also force a refetch.
   * A network fetch on the tick path is exactly the regression T1 removed (24-26% resident
   * CPU), so tick only ever reads this in-memory snapshot.
   */
  const modelsDevPath = modelsDevCachePath()
  let modelsDevSnapshot: ModelsDevSnapshot | undefined = readModelsDevSnapshot(modelsDevPath)
  let modelsDevFetched = false
  /** Startup-refresh latch: at most one attempt per process, success or failure. */
  let startupRefreshStarted = false

  /**
   * One-line cache state for diagnostics; the 2026-09-26 field report could only infer
   * "S3 never ran" from a missing file, so this makes the state directly readable.
   * @returns disabled | no-cache | stale | fresh.
   */
  const modelsDevStatus = (): string => {
    if (config.modelsDev === false) return 'disabled'
    if (!modelsDevSnapshot) return 'no-cache'
    return isStale(modelsDevSnapshot.generatedAt) ? 'stale' : 'fresh'
  }
  /**
   * Fetches models.dev and persists it when it fits the budget.
   *
   * Failure is never fatal: a fetch error keeps the previous snapshot, and with no
   * snapshot at all the source is simply absent (S1/S2/S4 carry on unchanged). This
   * runs only from an explicit call path (tool, or the one-time startup warm-up),
   * never from tick.
   * @param force - refetch even when the cached snapshot is fresh.
   * @returns the snapshot in use, or undefined when the source is absent.
   */
  const ensureModelsDev = async (
    force = false,
    /**
     * When false the call is a pure cache read: it never performs I/O. The tick path
     * passes false so the automatic pass can use S3 without any network work on its
     * synchronous path (the T1 regression was exactly that).
     */
    allowNetwork = true,
  ): Promise<ModelsDevSnapshot | undefined> => {
    if (config.modelsDev === false) return undefined
    if (!allowNetwork) return modelsDevSnapshot
    if (!force && modelsDevSnapshot && !isStale(modelsDevSnapshot.generatedAt)) return modelsDevSnapshot
    if (modelsDevFetched && !force) return modelsDevSnapshot
    const fetchImpl = globalThis.fetch as unknown as FetchLike | undefined
    if (typeof fetchImpl !== 'function') return modelsDevSnapshot
    try {
      const response = await fetchImpl(MODELS_DEV_URL, { headers: { accept: 'application/json' }, method: 'GET' })
      if (!response.ok) {
        diag(`models.dev fetch -> HTTP ${String(response.status)}；沿用旧缓存（${modelsDevSnapshot ? '有' : '无'}）`)
        return modelsDevSnapshot
      }
      const entries = parseModelsDev(JSON.parse(await response.text()))
      const next = buildModelsDevSnapshot(entries)
      const bytes = writeModelsDevSnapshot(modelsDevPath, next)
      if (bytes === undefined) {
        diag(`models.dev 快照超过尺寸上限（${String(next.modelCount)} 条），不落盘；本进程仍用内存副本`)
      }
      modelsDevSnapshot = next
      modelsDevFetched = true
      diag(`models.dev 拉取成功 modelCount=${String(next.modelCount)} bytes=${String(bytes ?? -1)}`)
      return next
    } catch (error) {
      diag(`models.dev 拉取失败（${(error as Error)?.message ?? String(error)}）；沿用旧缓存（${modelsDevSnapshot ? '有' : '无'}）`)
      return modelsDevSnapshot
    }
  }

  /** Result of a startup refresh attempt, for the caller's diagnostics. */
  const refreshModelsDevOnStartup = async (): Promise<'disabled' | 'fresh' | 'fetched' | 'failed' | 'no-fetch'> => {
    if (config.modelsDev === false) return 'disabled'
    if (startupRefreshStarted) return modelsDevSnapshot ? 'fresh' : 'no-fetch'
    startupRefreshStarted = true
    // 只在「陈旧或无缓存」时拉：新鲜缓存不该在每次启动都产生流量。
    if (modelsDevSnapshot && !isStale(modelsDevSnapshot.generatedAt)) {
      diag('models.dev 启动刷新：缓存新鲜，跳过')
      return 'fresh'
    }
    diag(`models.dev 启动刷新：${modelsDevSnapshot ? '缓存陈旧' : '无缓存'}，开始拉取`)
    const result = await ensureModelsDev(true, true)
    if (result === undefined) return 'failed'
    return modelsDevFetched ? 'fetched' : 'failed'
  }

  // modelsDev 状态只有在 modelsDevPath/modelsDevStatus 声明之后才可读；放在这里而不是
  // 函数开头，是因为启动那一行 diag 要报告缓存状态（这次现场就是靠「缓存文件不存在」反推的）。
  diag(`apply(): settings=${settings ? 'yes' : 'no'} catalog=${snapshot ? `${snapshot.source} models=${String(snapshot.modelCount ?? 0)}` : 'absent'} autoApply=${String(config.autoApply)} startupDelay=${String(config.startupDelaySeconds ?? 8)} modelsDev=${modelsDevStatus()} cache=${modelsDevPath}`)

  /** Resolves the route's API key: explicit settings value first, then the credential ref. */
  async function resolveConfig(route: string, sectionOverride?: unknown) {
    const config = providerFromSettings(settings, route, sectionOverride)
    if (!config) return undefined
    const credentials = optionalService<CredentialsLike>(ctx, 'credentials')
    if (!config.apiKey && config.apiKeyEnv && credentials) {
      try {
        const resolved = await credentials.resolve(config.apiKeyEnv)
        if (resolved?.value) config.apiKey = resolved.value
      } catch {
        // 凭据不可读不阻断被动探测（有些端点 /models 免鉴权）
      }
    }
    return config
  }

  async function discover(
    route: string,
    options: {
      active?: boolean
      confirm?: boolean
      levels?: string[]
      offline?: boolean
      /** Force a models.dev refetch before resolving. */
      refresh?: boolean
      /** Stage selector; 'auto' (default) walks the whole ladder. */
      source?: 'auto' | 'endpoint' | 'catalog' | 'modelsdev' | 'offline'
      /**
       * Consult S3 from the cached snapshot while still skipping every network call.
       * The automatic pass sets this so it can use models.dev without doing I/O on
       * the tick path; the public `offline` flag keeps its "engine catalog only"
       * meaning, so tool behavior is unchanged.
       */
      useCachedModelsDev?: boolean
      /** When false, S3 is read from cache only and never fetched. */
      allowModelsDevNetwork?: boolean
    } = {},
    sectionOverride?: unknown,
  ) {
    const providerConfig = await resolveConfig(route, sectionOverride)
    if (!providerConfig) {
      diag(`discover(${route}): providerFromSettings 返回 undefined（路由或 baseURL 不在 llm-pi-ai 里）`)
      return undefined
    }
    diag(`discover(${route}): baseURL=${providerConfig.baseURL} api=${providerConfig.api ?? '-'} models=${JSON.stringify(providerConfig.models ?? [])} apiKey=${providerConfig.apiKey ? 'yes' : 'no'}`)
    const fetchImpl = globalThis.fetch as unknown as FetchLike
    // 来源梯选择：'offline' 与 'catalog' 都只走离线地板；'modelsdev' 只走 S3；
    // 'endpoint' 只走 S1/S2；'auto'（默认）按 S1->S2 -> S3 -> S4 -> S5 全走。
    const source = options.source ?? 'auto'
    const skipNetwork = options.offline === true || source === 'offline' || source === 'catalog' || source === 'modelsdev'
    const report = skipNetwork
      ? { route, fetched: [], models: [], unknown: [...(providerConfig.models ?? [])], notes: [] } as ProbeReport
      : await probePassive(providerConfig, { fetchImpl })
    const deferred = new Map<string, ReasoningEfforts>()
    // 自动补给路径（useCachedModelsDev）只读缓存，绝不发网络请求；显式工具调用才允许拉取。
    const wantModelsDev = source === 'modelsdev' || (source === 'auto' && (options.useCachedModelsDev === true || options.offline !== true))
    if (wantModelsDev) {
      const allowNetwork = options.allowModelsDevNetwork !== false
      const dev = await ensureModelsDev(options.refresh === true, allowNetwork)
      mergeModelsDevInto(report, providerConfig.models ?? [], dev, deferred)
    }
    if (source !== 'endpoint' && source !== 'modelsdev') {
      mergeCatalog(report, providerConfig.models ?? [], snapshot, providerConfig.api)
      applyDeferredModelsDevLevels(report, deferred)
    }
    mergeFallbacks(report, providerConfig.models ?? [], config.fallbacks, config.fallbacks !== undefined)
    if (options.active) {
      if (!options.confirm) {
        report.notes.push('active=true 但缺少 confirm=true（主动探测会消耗额度，需用户明确批准）——本次仅做被动发现')
      } else {
        const levels = options.levels && options.levels.length > 0 ? options.levels : ['low', 'medium', 'high']
        const url = providerConfig.baseURL.replace(/\/+$/, '') + '/chat/completions'
        const headers: Record<string, string> = {
          'content-type': 'application/json',
          ...(providerConfig.apiKey ? { authorization: `Bearer ${providerConfig.apiKey}` } : {}),
          ...(providerConfig.headers ?? {}),
        }
        // 负控（决策 D7）：端点若连无效值都接受，则「接受某个等级」不构成证据。
        const control = await probeReasoningEfforts({
          url, headers, levels: ['__dsh_invalid__'], fetchImpl, timeoutMs: 15_000,
          body: (level) => ({ model: report.models[0]?.id ?? '', messages: [{ role: 'user', content: 'ping' }], max_tokens: 1, reasoning_effort: level }),
        })
        const validates = control.rejected.length > 0
        if (!validates) {
          report.notes.push('负控失败：端点接受无效 reasoning_effort 值 → 接受性探测不可信，本次不据此写入等级（只保留被动/目录结论）')
        } else {
          for (const model of report.models) {
            if (model.reasoningEfforts) continue
            const result = await probeReasoningEfforts({
              url, headers, levels, fetchImpl,
              body: (level) => ({ model: model.id, messages: [{ role: 'user', content: 'ping' }], max_tokens: 1, reasoning_effort: level }),
            })
            if (result.efforts) {
              model.reasoningEfforts = result.efforts
              model.sources.reasoningEfforts = 'active-probe'
            }
            if (result.rejected.length > 0) report.notes.push(`${model.id}: 端点拒绝的等级 ${result.rejected.map((r) => r.level).join(', ')}`)
            for (const item of result.inconclusive) report.notes.push(`${model.id}: 等级 ${item.level} 结果不确定（${item.reason}）`)
          }
        }
      }
    }
    return { providerConfig, report }
  }

  const probeTool = defineTool({
    name: 'model_capability_probe',
    description:
      'Discover capability metadata for a user-declared provider route, in source order: endpoint descriptor, vendor schema, models.dev encyclopedia, engine catalog. ' +
      'Never inferred from URLs or names; unknown stays unknown. ' +
      'Active probes spend quota, so they need active=true and confirm=true, and are discarded unless a negative control proves the endpoint validates the field. ' +
      'apply=true writes the discovered fields back (missing fields only).',
    parameters: {
      provider: { type: 'string', required: true, description: 'llm-pi-ai provider route id, e.g. "my-gateway"' },
      active: { type: 'boolean', description: 'Also run active reasoning-effort probes (default false)' },
      confirm: { type: 'boolean', description: 'Explicit user approval for active probes (required when active=true)' },
      levels: { type: 'array', items: { type: 'string' }, description: 'Candidate effort words for active probes (default low/medium/high)' },
      apply: { type: 'boolean', description: 'Write discovered capabilities back to settings (missing fields only)' },
      offline: { type: 'boolean', description: 'Skip network entirely and use only the engine catalog (default false)' },
      source: { type: 'string', description: 'Source stage (default auto)' },
      refresh: { type: 'boolean', description: 'Force models.dev refetch' },
    },
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          ok: { type: 'boolean', required: true },
          text: { type: 'string', required: true },
          report: { type: 'object', additionalProperties: true },
          applied: { type: 'object', additionalProperties: true },
        },
      },
      render: (_args, value: Record<string, unknown>) => [{ type: 'text', text: String(value.text ?? '') }],
    },
    execute: async (args: { provider: string; active?: boolean; confirm?: boolean; levels?: string[]; apply?: boolean; offline?: boolean; source?: 'auto' | 'endpoint' | 'catalog' | 'modelsdev' | 'offline'; refresh?: boolean }) => {
      const found = await discover(args.provider, args)
      if (!found) {
        return { ok: false, text: `未在 llm-pi-ai 设置中找到提供商路由「${args.provider}」或其 baseURL——请先在设置页填写自定义提供商。`, report: {} } as never
      }
      const { report } = found
      let applied: Record<string, unknown> | undefined
      if (args.apply) {
        const result = await applyModelPatch(settings, args.provider, patchesFrom(report), logWithTrace)
        applied = result as unknown as Record<string, unknown>
        report.notes.push(result.wrote
          ? `已写回 ${result.changes.length} 项：${result.changes.join('；')}`
          : `未写回（${result.reason}）${result.changes.length > 0 ? '：' + result.changes.join('；') : ''}`)
      }
      return { ok: true, text: summarize(report), report, applied } as never
    },
  })

  const applyTool = defineTool({
    name: 'model_capability_apply',
    description:
      'Discover and write back missing model-level capabilities for one user-declared provider route. ' +
      'Only fills fields the route does not declare; never overwrites a user value. ' +
      'Spends quota only with active=true and confirm=true.',
    parameters: {
      provider: { type: 'string', required: true, description: 'llm-pi-ai provider route id' },
      active: { type: 'boolean', description: 'Also run active reasoning-effort probes (default false)' },
      confirm: { type: 'boolean', description: 'Explicit approval for active probes' },
      offline: { type: 'boolean', description: 'Use only the engine catalog (default true for apply)' },
      source: { type: 'string', description: 'Source stage (default auto)' },
    },
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          ok: { type: 'boolean', required: true },
          text: { type: 'string', required: true },
          applied: { type: 'object', additionalProperties: true },
        },
      },
      render: (_args, value: Record<string, unknown>) => [{ type: 'text', text: String(value.text ?? '') }],
    },
    execute: async (args: { provider: string; active?: boolean; confirm?: boolean; offline?: boolean; source?: 'auto' | 'endpoint' | 'catalog' | 'modelsdev' | 'offline' }) => {
      const found = await discover(args.provider, { ...args, offline: args.offline ?? true, source: args.source ?? 'offline' })
      if (!found) {
        return { ok: false, text: `未找到提供商路由「${args.provider}」。` } as never
      }
      const result = await applyModelPatch(settings, args.provider, patchesFrom(found.report), logWithTrace)
      const lines = [summarize(found.report)]
      lines.push(result.wrote
        ? `已写回：${result.changes.join('；')}`
        : `未写回（${result.reason}）`)
      if (result.skipped.length > 0) lines.push(`跳过：${result.skipped.join('；')}`)
      return { ok: true, text: lines.join('\n'), applied: result as unknown as Record<string, unknown> } as never
    },
  })

  ctx.tools.register(probeTool)
  ctx.tools.register(applyTool)
  log?.info?.('model_capability_probe / model_capability_apply registered')

  // 自动补给（决策 D5）：延迟一轮，只做目录 + 被动，
  // 只写「缺失且无歧义」的字段；失败静默落日志。
  //
  // 回归场景（2026-09-10 用户口径）：用户在设置页「添加自定义供应商」后，
  // 不重启、不手改 settings.yaml，思考档位就应出现。
  //
  // 【T1 常驻 CPU（2026-09-25）】旧实现用「每 5 秒全量 describe 取签名」实现该回归，
  // 实测代价是常驻 24-26% CPU（4 次 --cpu-prof：w3 24% / w4 26% / c3 25% / c4 24%，
  // 证据 .deploy-tmp/boot-attribution/REPORT.md §5）。真因不在本插件的循环，
  // 而在 describe 的实现：settings 服务没有「只读自有命名空间」的轻量路径，
  // 每次 describe 都走 configEditor.configuration() → 对**全 profile 每个 entry**
  // 重做 inherited() = flatten(composeEntries(全量层 patch)) + structuredClone。
  // 单次 describe 实测 300-1500 ms inclusive（w3 七次调用合计 4218 ms）。
  // **所以「把 poll 从 5s 调到 30s」只是把同样的全量重算摊薄，不改变单次成本**；
  // 真正要减的是**describe 的调用次数**。三条并行手段：
  //   ① 事件驱动：上游在「raw 段变化」时 emit settings/document-updated（出货
  //      dsh-settings/lib/index.js:521-547 bumpRevision → emitDocumentUpdated，
  //      经 ctx.events 共享总线派发）。这是**唯一能同时做到「即时」与「零轮询」**的路子。
  //   ② 兜底轮询：默认 120s（从 5s 提高两个量级），只在上游事件面不可用时兜底。
  //   ③ 每次 tick 只读一次描述符并在内部复用（旧实现同一 tick 内 describe 2-3 次：
  //      signatureOf + routesToConsider + 每个 route 的 providerFromSettings）。
  //
  // 为什么用事件而不是「只保留轮询但调大间隔」：调大间隔会把「用户添加供应商 → 档位出现」
  // 的延迟从秒级拉到分钟级，等于用功能退化换 CPU；而事件正是「设置变了」的权威信号，
  // 既不轮询也不延迟。事件的**数据面**（为什么事件够用、以及与 settings.watch 的关系）
  // 见下方 subscribeSettingsEvents 的注释。
  if (config.autoApply !== false && settings) {
    const delayMs = Math.max(0, config.startupDelaySeconds ?? 8) * 1000
    let signature = ''
    /** 是否已建立签名基线：未建立时首轮直接跑，省掉一次无意义的比较读。 */
    let baselineSet = false
    /** 本 tick 内共享的描述符：避免同一 tick 对 llm-pi-ai 重复 describe。 */
    let tickSection: unknown
    /** 事件已触发但本轮尚未消费时置真：tick 看到它即跑一轮，保证「先到的事件不丢」。 */
    let eventPending = false
    /** effect 是否已停止（deferrer 的回调在事务外跑，需自行判断存活）。 */
    const stoppedRef = { value: false }

    /**
     * 读一次 llm-pi-ai 描述符，并在**本 tick 内**复用给 routesToConsider / signatureOf /
     * providerFromSettings / applyModelPatch。这是 T1 的第三条手段：旧实现在同一 tick 里
     * describe 2-3 次，每次都付一次全量 configuration() + structuredClone。
     * @returns 描述符，读失败时 undefined（调用方按「无路由」处理）。
     */
    const readDescriptor = () => {
      if (tickSection === undefined) {
        try {
          tickSection = settings.describe({ namespaces: ['llm-pi-ai'] }).find((d) => d.ns === 'llm-pi-ai')
        } catch {
          tickSection = null
        }
      }
      return tickSection === null ? undefined : tickSection as { ns: string; value: unknown; revision: number } | undefined
    }

    const routesToConsider = (): string[] => {
      // describe(options) 的 options 被实现忽略 → 必须按 ns 查找（不能用 [0]）
      const section = readDescriptor()?.value as { providers?: Record<string, unknown> } | undefined
      return config.routes ?? Object.keys(section?.providers ?? {})
    }

    /**
     * 能力补给触发签名（0.14.0-preview / ST-03）：**值敏感**——含路由级键（baseURL/api 等）的规范化值
     * 与模型级能力字段的值，结构增删同样改变签名。旧实现只记「字段有无」，换网关/手改配置不触发重跑。
     * 见 src/signature.ts 与计划文档 §4.2 ST-03。
     */
    const signatureOf = (): string => {
      try {
        return capabilitySignature(readDescriptor()?.value, config.routes)
      } catch {
        return ''
      }
    }

    /** 来源戳：记录「该字段现在的值是我方写下的」，使新发现的值可刷新我方旧写入（用户手写值无戳）。 */
    const stamps = createStampStore()

    const runAutoPass = async () => {
      const routes = routesToConsider()
      diag(`runAutoPass: routes=${JSON.stringify(routes)}`)
      // L.2（2026-09-26）：**每个 route 各取一次描述符**。
      // 旧实现只读一次就传给所有 route（:838 的 descriptor override），但任一 route 写成功后
      // settings 的 revision 就会推进（服务端 revisions Map），于是后续 route 手里的 override
      // 必然过期——它们只能靠重试兜底，而重试本身又被并发写者挤掉。
      // 在循环内重取比「靠重试兜底」更正确：override 的语义是「刚读到的、还没被任何人改过」，
      // 一旦本轮写过一次，它就**不再是**刚读到的了；在源头纠正比在下游反复撞墙便宜且可推理
      // （重试次数有限，源头每轮只多一次 describe）。
      const tickValue = readDescriptor()?.value
      // L.2：本轮里已经写成功过吗？写成功才会推进 revision，才让手里的描述符过期。
      // **不能无条件每个 route 重读**——那会给首轮多加一次 describe，破坏 T1 的
      // 「每 tick describe 次数上界」判据（t1-poll-cost.test.mjs 的 E-P2-4 会判红，实测踩到）。
      // 只在「本轮已经写过」之后才重读：首轮零额外开销，多路由时也只在必要时才多读一次。
      let wroteThisPass = false
      for (const route of routes) {
        try {
          // offline 保持既有「不联网」语义，useCachedModelsDev 让 S3 用缓存参与补给；
          // allowModelsDevNetwork=false 保证 tick 绝不触发网络（T1 回归判据，见 t1-poll-cost.test.mjs）。
          const found = await discover(route, { offline: true, useCachedModelsDev: true, allowModelsDevNetwork: false }, tickValue)
          if (!found) continue
          diag(`runAutoPass(${route}): models=${found.report.models.length} efforts=${JSON.stringify(found.report.models.map((m) => [m.id, m.reasoningEfforts ?? null]))}`)
          // 自动补给写回全部缺失字段（不只是 reasoningEfforts）：S3/S5 的价值就是补长尾的
          // contextWindow/maxTokens/input，只放 reasoningEfforts 会让这两个源在自动路径上形同虚设。
          // 用户已有的值不会被动：planModelPatch 只填空，来源戳进一步保护我方旧写入。
          const patches = patchesFrom(found.report)
          diag(`runAutoPass(${route}): patches=${patches.length}`)
          if (patches.length === 0) continue
          // 只在「本轮已写过」之后重读：那时描述符才真的过期；否则沿用 tick 首次读到的。
          if (wroteThisPass) {
            tickSection = undefined
            readDescriptor()
          }
          const descriptor = readDescriptor()
          const result = await applyModelPatch(settings, route, patches, logWithTrace, stamps, descriptor)
          if (result.wrote) wroteThisPass = true
          diag(`runAutoPass(${route}): wrote=${result.wrote} reason=${result.reason} changes=${JSON.stringify(result.changes)}`)
          if (result.wrote) log?.info?.(`auto-apply ${route}: ${result.changes.join('；')}`)
        } catch (error) {
          diag(`runAutoPass(${route}) failed: ${(error as Error)?.message ?? String(error)}`)
          log?.warn?.(`auto-apply ${route} failed: ${(error as Error)?.message ?? String(error)}`)
        }
      }
    }

    /**
     * 一轮补给检查。`force` 为真时跳过签名短路（事件已经告诉我们「设置变了」，
     * 不必再花一次 describe 去重新求签名——这正是省 CPU 的关键：事件路径下每轮
     * 只 describe 一次，轮询路径才需要「先签名后决定」的两次读）。
     * @param force - 事件驱动路径为 true。
     */
    // 轮询 tick 与事件 tick 可能并发；两者共用 tickSection（每 tick 的描述符缓存），
    // 交叠会互相清掉对方的缓存并多做一次全量 describe。用一个在跑标志串行化：
    // 跑动期间到达的请求只置 rerun，由当前这轮结束后补跑（不丢事件、不并发）。
    let ticking = false
    let rerun = false

    const tick = async (force = false) => {
      if (ticking) {
        rerun = true
        return
      }
      ticking = true
      try {
        do {
          rerun = false
          tickSection = undefined
          eventPending = false
          // 首轮（baseline 尚未建立）必须直接跑，不做「先取签名再比较」——
          // signature 初值是空串，比较必然不等，那次 describe 是纯浪费
          // （离线实测：启动轮 2 次 describe 里正好有 1 次是它）。
          if (!force && baselineSet) {
            const next = signatureOf()
            diag(`tick: signature=${next.slice(0, 120)} changed=${next !== signature}`)
            if (next === signature) break
          } else {
            diag(force ? 'tick: event-driven (settings/document-updated)' : 'tick: first pass (baseline)')
          }
          await runAutoPass()
          // 写回会改变签名，刷新一次基线避免下一轮重复执行
          tickSection = undefined
          signature = signatureOf()
          baselineSet = true
          // 跑动期间设置又变了 → 再跑一轮（事件只来自真实写入，不会自旋）
          if (eventPending) rerun = true
          force = true
        } while (rerun)
      } finally {
        ticking = false
      }
    }

    /**
     * 把回调送出当前异步上下文（X1 修复，2026-09-25）。
     *
     * 为什么必须送出：hmr 用 `AsyncLocalStorage` 标记「事务执行中」
     * （dsh-hmr/src/index.ts:130 `executing`，:140 嵌套即抛
     * `HMR transactions cannot be nested`）。`settings/document-updated` 是在**用户那次
     * 写事务之内**同步 emit 的（出货 settings 的 write → configEditor.edit → hmr.runExclusive），
     * 所以事件回调里再调 `settings.mutate` → `configEditor.edit` → 又一次 `runExclusive` 就被拒。
     *
     * 关键：**在事务内新调度的普通调度器会继承该上下文**——setTimeout / setImmediate /
     * queueMicrotask / process.nextTick / Promise.then 全部继承（离线实测，
     * `.deploy-tmp/fix-b1/als-escape.mjs`）。只有**在事务外预建**的 async 资源不会继承：
     * 预建 MessageChannel 的 onmessage 落在干净上下文（`.deploy-tmp/fix-b1/als3.mjs`，ESCAPED）。
     * 因此这里在 effect 装配时（彼时不在任何事务内）预建通道，事件到来时只 postMessage。
     * @returns 送出函数与释放函数。
     */
    const createDeferrer = () => {
      if (typeof MessageChannel === 'function') {
        const channel = new MessageChannel()
        // 队列而不是单槽：同一轮里可能同步 defer 多次（例如启动刷新 + 一个 document-updated
        // 事件），单槽会让后一次覆盖前一次，静默丢掉一个回调。
        const queue: Array<() => void> = []
        channel.port1.onmessage = () => {
          const run = queue.shift()
          run?.()
        }
        // 不让诊断通道拖住进程退出（Android 上尤其重要）
        ;(channel.port1 as unknown as { unref?: () => void }).unref?.()
        ;(channel.port2 as unknown as { unref?: () => void }).unref?.()
        return {
          defer: (fn: () => void) => { queue.push(fn); channel.port2.postMessage(0) },
          dispose: () => { queue.length = 0; channel.port1.close(); channel.port2.close() },
        }
      }
      // 兜底：预建 promise 链（continuation 在事务外注册，同样不继承上下文）
      // 判据同 als3.mjs 的 premade-gate：第二跳仍为 ESCAPED。
      let wake: (() => void) | undefined
      let closed = false
      const queue: Array<() => void> = []
      const arm = (): Promise<void> => new Promise<void>((resolve) => { wake = resolve })
      let gate = arm()
      void (async () => {
        for (;;) {
          await gate
          if (closed) return
          gate = arm()
          // 一次唤醒清空当前队列（与 MessageChannel 版同语义：不丢回调）。
          while (queue.length > 0) queue.shift()?.()
        }
      })()
      return {
        defer: (fn: () => void) => { queue.push(fn); wake?.() },
        dispose: () => { closed = true; queue.length = 0; wake?.() },
      }
    }

    /**
     * 订阅「设置文档变化」事件作为主触发器。
     *
     * 依据（出货 0.14.1 `dsh-settings/lib/index.js:515-547`）：该服务在**raw 段**变化时
     * bumpRevision → emitDocumentUpdated('settings/document-updated', ns, revision)，
     * 经共享的 `ctx.events` 总线 emit 派发；同一总线对所有插件可见（cordis Context
     * 只在 root 构造一个 EventsService，见 vendor/cordis/src/context.ts:80）。
     * 因此本插件不需要 `settings.watch`（那是「自有命名空间」的接口，见
     * `register()` 返回的 scope.watch；llm-pi-ai 归 llm-pi-ai 插件所有，我们不是它的 owner），
     * 也不需要轮询：事件本身就是「用户改了配置」的权威信号。
     *
     * 事件只带 (ns, revision)，不带值——所以回调里仍然要读一次描述符。这次读是**必要的**
     * 而不是浪费：没有它无从知道新值；而它每「一次真实用户修改」只发生一次，
     * 不再是「每 5 秒一次」。
     * @param deferrer - 把 tick 送出当前 HMR 事务的通道（见 createDeferrer）。
     */
    const subscribeSettingsEvents = (deferrer: { defer: (fn: () => void) => void }) => {
      const on = (ctx as unknown as {
        on?: (name: string, listener: (...args: unknown[]) => void) => () => void
      }).on
      if (typeof on !== 'function') {
        diag('settings/document-updated 不可订阅（ctx.on 缺席）→ 退化为轮询兜底')
        return undefined
      }
      try {
        return on.call(ctx, 'settings/document-updated', (...args: unknown[]) => {
          const ns = String(args[0] ?? '')
          if (ns !== 'llm-pi-ai') return
          diag(`settings/document-updated ns=${ns} rev=${String(args[1])}`)
          // 落一个「有变化待消费」标记：tick 在跑则它会在本轮结束后补跑，
          // 没在跑则这次调用直接跑。两种情况下事件都不会被丢掉。
          eventPending = true
          // 必须经 deferrer 送出事务上下文，否则 tick 内的 settings.mutate 会被 hmr 拒绝（X1）。
          deferrer.defer(() => { if (!stoppedRef.value) void tick(true) })
        })
      } catch (error) {
        diag(`settings/document-updated 订阅失败（${(error as Error)?.message ?? String(error)}）→ 退化为轮询兜底`)
        return undefined
      }
    }

    c.effect?.(() => {
      let stopped = false
      stoppedRef.value = false
      diag('auto-apply effect armed')
      // deferrer 必须在**事务外**预建：它的 async 资源不能带上 hmr 的事务上下文，
      // 否则事件回调里触发的写回仍会被 hmr 判为嵌套（见 createDeferrer）。
      const deferrer = createDeferrer()
      // 先订阅再决定轮询间隔：事件可用时轮询只是「怕漏事件」的安全网，可以很稀；
      // 事件不可用时轮询就是**唯一**触发器，稀到 120s 会把「添加供应商 → 档位出现」
      // 的延迟从秒级退化到 2 分钟（功能退化）。因此间隔按事件面是否可用分两档取：
      //   事件可用   -> 默认 120s（安全网；常态下永不触发）
      //   事件不可用 -> 默认 30s （唯一触发器；把延迟与 CPU 折中）
      // 两档都用 Math.max(30, ...) 钳下界：用户显式配 5s 会被钳到 30，
      // 这是**有意**的，防止把 poll 配回过 5s 又打回 24-26% 常驻 CPU（见上方成本注释）。
      const unsubscribe = subscribeSettingsEvents(deferrer)
      const eventDriven = unsubscribe !== undefined
      const fallbackSeconds = eventDriven ? 120 : 30
      const pollMs = Math.max(30, config.pollIntervalSeconds ?? fallbackSeconds) * 1000
      diag(`poll interval=${String(pollMs / 1000)}s eventDriven=${String(eventDriven)} modelsDev=${modelsDevStatus()}`)
      // 【P2-S3 启动后台刷新（2026-09-26）】方案 §2.2 的降级链是「无缓存 -> 拉；有缓存且未陈旧 ->
      // 不拉；陈旧 -> 后台拉」。此前只实现了「显式调工具才拉」，于是干净安装上没人调工具 =>
      // 缓存永远不存在 => S3 永不自动生效（设备实测：models-dev.json 不存在、日志 0 命中）。
      // 这里补上缺失的一环：每个进程启动最多一次后台刷新。
      // 纪律：经 deferrer 送出（与 tick 同一条逃逸通道，避开 hmr 事务上下文）；
      //      仅陈旧/无缓存才真的拉；失败只记 diag、不阻断、不重试；
      //      拉完写缓存，由既有的 tick 路径自然消费（tick 仍 0 网络）。
      // 先于启动轮触发：这样首个 auto-pass 就大概率能读到刚拉下来的 S3 数据。
      deferrer.defer(() => {
        if (stopped) return
        void refreshModelsDevOnStartup().then((outcome) => {
          diag(`models.dev 启动刷新结果=${outcome} 状态=${modelsDevStatus()}`)
        })
      })
      // 启动轮与轮询轮也可能落在别的 hmr 事务里（例如启动期 profile 重载），
      // 同样经 deferrer 送出，保证任何一路触发都能写回成功。
      const startTimer = setTimeout(() => { if (!stopped) deferrer.defer(() => { if (!stopped) void tick() }) }, delayMs)
      const interval = setInterval(() => { if (!stopped) deferrer.defer(() => { if (!stopped) void tick() }) }, pollMs)
      return () => {
        stopped = true
        stoppedRef.value = true
        clearTimeout(startTimer)
        clearInterval(interval)
        unsubscribe?.()
        deferrer.dispose()
      }
    })
  } else {
    diag(`auto-apply 未启用（autoApply=${String(config.autoApply)} settings=${settings ? 'yes' : 'no'}）`)
  }
}
