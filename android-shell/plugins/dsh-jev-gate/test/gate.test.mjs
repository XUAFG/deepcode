/**
 * End-to-end tests for the gate: the real plugin is loaded against a stub
 * context and a mock Jev endpoint, so the correlation through `callId`, the
 * mode switch, and every failure path are exercised without a device.
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const HOME = mkdtempSync(join(tmpdir(), 'jev-gate-'))
process.env.DSH_HOME = HOME
process.env.JEV_API_KEY = 'sk-test-key'

const { apply } = await import('../lib/index.js')

/** Captures the seams the plugin registers, and exposes them for driving. */
function harness(patchConfig) {
  const listeners = {}
  const ctx = {
    on(event, listener) {
      listeners[event] = listener
    },
    get() {
      return undefined
    },
  }
  apply(ctx, patchConfig)
  return listeners
}

/** A `next()` standing in for the rest of the answerer chain. */
const FALLTHROUGH = Symbol('fallthrough')
const next = async () => FALLTHROUGH

/** Run one approval through the gate, with the arguments primed as reality does. */
async function runApproval(listeners, { toolName = 'bash', command, reason = 'needs wider access', answers, fetchImpl }) {
  listeners['tools/pre-execute'](
    { callId: 'call-1', name: toolName, arguments: { command, sandbox_permissions: 'workspace-write' } },
    next,
  )
  const previous = globalThis.fetch
  if (fetchImpl !== undefined) globalThis.fetch = fetchImpl
  try {
    return await listeners['approval/request']({ toolName, callId: 'call-1', reason }, next)
  } finally {
    globalThis.fetch = previous
  }
}

/** A fetch that answers with the given Jev response body. */
function jevFetch(body, { status = 200 } = {}) {
  const calls = []
  const impl = async (url, init) => {
    calls.push({ url, body: JSON.parse(init.body) })
    return { ok: status >= 200 && status < 300, status, json: async () => body }
  }
  impl.calls = calls
  return impl
}

const CLEARED = { model: 'jev-1.13.0', answers: { risk: { type: 'noul', noul: 0.03, confidence: 0.94 } }, usage: {} }
const RISKY = { model: 'jev-1.13.0', answers: { risk: { type: 'noul', noul: 0.72, confidence: 0.91 } }, usage: {} }

test('observe mode records the candidate but never grants', async () => {
  const listeners = harness({ mode: 'observe' })
  const fetchImpl = jevFetch(CLEARED)
  const outcome = await runApproval(listeners, { command: 'npm run build', fetchImpl })
  assert.equal(outcome, FALLTHROUGH)
  assert.equal(fetchImpl.calls.length, 0, 'observe must not call Jev')
})

test('enforce + confident low risk grants allowed-once', async () => {
  const listeners = harness({ mode: 'enforce' })
  const fetchImpl = jevFetch(CLEARED)
  const outcome = await runApproval(listeners, { command: 'npm run build', fetchImpl })
  assert.equal(outcome, 'allowed-once')
  assert.equal(fetchImpl.calls.length, 1)

  const sent = fetchImpl.calls[0].body
  assert.equal(sent.model, 'typesafe/jev-1.13')
  assert.equal(sent.state.command, 'npm run build')
  assert.equal(sent.state.tool, 'bash')
  assert.equal(sent.state.agent_stated_justification, 'needs wider access')
  assert.equal(sent.questions.risk.type, 'noul', 'questions must carry a type')
  assert.ok(sent.questions.risk.instructions.length > 0)
})

test('enforce + high risk defers to the human', async () => {
  const listeners = harness({ mode: 'enforce' })
  const outcome = await runApproval(listeners, { command: 'npm run build', fetchImpl: jevFetch(RISKY) })
  assert.equal(outcome, FALLTHROUGH)
})

test('the deny list short-circuits before any network call', async () => {
  const listeners = harness({ mode: 'enforce' })
  const fetchImpl = jevFetch(CLEARED)
  const outcome = await runApproval(listeners, { command: 'rm -rf /', fetchImpl })
  assert.equal(outcome, FALLTHROUGH)
  assert.equal(fetchImpl.calls.length, 0, 'a categorically unsafe shape must never reach the model')
})

test('a non-candidate tool is ignored silently', async () => {
  const listeners = harness({ mode: 'enforce' })
  const fetchImpl = jevFetch(CLEARED)
  const outcome = await runApproval(listeners, { toolName: 'read_file', command: 'x', fetchImpl })
  assert.equal(outcome, FALLTHROUGH)
  assert.equal(fetchImpl.calls.length, 0)
})

test('no API key defers instead of throwing', async () => {
  const listeners = harness({ mode: 'enforce' })
  const saved = process.env.JEV_API_KEY
  delete process.env.JEV_API_KEY
  try {
    const outcome = await runApproval(listeners, { command: 'npm run build', fetchImpl: jevFetch(CLEARED) })
    assert.equal(outcome, FALLTHROUGH)
  } finally {
    process.env.JEV_API_KEY = saved
  }
})

test('a failing endpoint defers', async () => {
  const listeners = harness({ mode: 'enforce' })
  const failing = async () => {
    throw new Error('ECONNREFUSED')
  }
  assert.equal(await runApproval(listeners, { command: 'npm run build', fetchImpl: failing }), FALLTHROUGH)
})

test('a non-2xx response defers', async () => {
  const listeners = harness({ mode: 'enforce' })
  const outcome = await runApproval(listeners, {
    command: 'npm run build',
    fetchImpl: jevFetch({ error: 'rate limited' }, { status: 429 }),
  })
  assert.equal(outcome, FALLTHROUGH)
})

test('a malformed body defers', async () => {
  const listeners = harness({ mode: 'enforce' })
  const outcome = await runApproval(listeners, {
    command: 'npm run build',
    fetchImpl: jevFetch({ answers: { risk: { noul: 'very safe' } } }),
  })
  assert.equal(outcome, FALLTHROUGH)
})

test('mode off and an unseen callId both defer', async () => {
  const off = harness({ mode: 'off' })
  assert.equal(await runApproval(off, { command: 'npm run build', fetchImpl: jevFetch(CLEARED) }), FALLTHROUGH)

  const listeners = harness({ mode: 'enforce' })
  const fetchImpl = jevFetch(CLEARED)
  const previous = globalThis.fetch
  globalThis.fetch = fetchImpl
  try {
    // No pre-execute priming: the arguments are unknown, so there is nothing to judge.
    const outcome = await listeners['approval/request']({ toolName: 'bash', callId: 'never-seen', reason: 'x' }, next)
    assert.equal(outcome, FALLTHROUGH)
    assert.equal(fetchImpl.calls.length, 0)
  } finally {
    globalThis.fetch = previous
  }
})
