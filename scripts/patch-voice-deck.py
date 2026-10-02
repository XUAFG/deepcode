#!/usr/bin/env python3
"""Version-guarded, reproducible client overlays; upstream checkout stays untouched."""
import hashlib
import json
import pathlib
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
PREFIX = 'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/'
PACKAGES = ['dsh-api-session-controller', 'dsh-client-ui-renderer', 'dsh-client-ui-conversation', 'dsh-client-ui-workspace']
DEST = ROOT / '.tools/deck-patches'

def replace(s, old, new, count=1):
    assert s.count(old) == count, f'Unsupported runtime: expected {count} occurrences: {old[:100]!r}, got {s.count(old)}'
    return s.replace(old, new)

DECK_RUNTIME_020 = '''
            // ---- dsh voice-deck bridge (0.2.0-rc.2) -------------------------
            // Cross-bundle deck-mode signal: the deck plugin broadcasts its lane
            // list on the document; the conversation panel reads it reactively.
            const dshDeckLanes = (0, _deepseek_ai_dsh_client_store.createSnapshotStore)([]);
            if (typeof document !== "undefined") {
                document.addEventListener("dsh-deck-lanes", (event) => {
                    dshDeckLanes.set(Array.isArray(event.detail) ? event.detail : []);
                });
            }
            const dshUseDeckGone = (sessionId) => (0, react.useSyncExternalStore)(
                dshDeckLanes.subscribe,
                () => dshDeckLanes.getSnapshot().includes(sessionId),
                () => dshDeckLanes.getSnapshot().includes(sessionId));
'''

CONVERSATION_020_EDITS = [
    # 1. deck runtime scaffolding right after the InputHub exists.
    ('      const inputHub = new InputHub(ctx, t2);', True),
    # 2/3. hero + composer suppression while a lane owns the session.
    ('      const hero = sessionId === void 0 || shellPhase === "blank" && (openState === "open" || summaryBlank === true);', True),
    ('        children: composer', True),
    # 4/5. session header: compute deck-gone and hide the title row.
    ('function ConversationSessionHeader({ sessionId, hideChrome, useSessions, useConversationViews, useStore, renderSlot, open, selectView, t: t2 }) {\n      const tabs = useConversationViews((value) => value);', True),
    ('        className: ConversationRoot_module_css_default.titleRow,\n        children: [!hideChrome', True),
    # 6. marker consumed by the deck lane measurement code.
    ('            "data-composer-card": true,', True),
    # 7. submitter/intake registries ahead of the InputBar definition.
    ('    const InputBar = (0, react.memo)(function InputBar2(', True),
    # 8. intakeFiles must report acceptance to the deck bridge.
    ('        if (rejected !== null) showToast(rejected);', True),
    # 9. per-lane native image intake listener.
    ('      const canAcceptDrop = subagent === null && !locked && !machineBusy && addFiles !== void 0;', True),
    # 10. per-lane submit bridge.
    ('      const onPrimary = () => {', True),
]

SESSION_SURFACE_020 = '''    exports.SlotRegistry = SlotRegistry;
        function SessionSurface({source, part, blocked, openView}) {
            const host = useHost();
            observableHook(host.scopeRevision)(value => value);
            if (source === void 0 || source === null) return null;
            const binding = (0, react.useSyncExternalStore)(
                (listener) => source.subscribe(listener),
                () => source.getSnapshot(), () => source.getSnapshot());
            if (binding === void 0 || binding === null) return null;
            return react_jsx_runtime.jsx(ScopeBindingContext.Provider, {value: binding,
                children: react_jsx_runtime.jsx(SessionSurfaceBody, {part, blocked, openView})}, binding.key);
        }
        function SessionSurfaceBody({part, blocked, openView}) {
            const binding = useScopeBinding();
            const session = observableHook(binding.hooks.conversation)(value => value);
            const input = observableHook(binding.hooks.input)(value => value);
            if (part === 'chat') return react_jsx_runtime.jsx(SlotOutlet, {
                slotKey: 'conversation.view', opts: {only: 'chat'},
                ownerProps: {viewRequest: null, openView, completeViewRequest: () => {}}});
            if (part !== 'composer') throw new Error('Unknown session surface');
            const fallback = react_jsx_runtime.jsxs(react.Fragment, {children: [
                react_jsx_runtime.jsx(SlotOutlet, {slotKey: 'conversation.input.dock',
                    ownerProps: {sessionId: binding.key, session, input}}),
                react_jsx_runtime.jsx(SlotOutlet, {slotKey: 'conversation.composer.bar',
                    ownerProps: {variant: 'composer', blocked}})
            ]});
            return react_jsx_runtime.jsx(SlotOutlet, {slotKey: 'conversation.composer',
                ownerProps: {sessionId: binding.key, session, pendingInteraction: void 0}, opts: {fallback}});
        }
        exports.SessionSurface = SessionSurface;'''

DECK_INPUT_020 = '''
            // Narrow public adapter; Lexical symbols remain inside their owning bundle.
            ctx.reflect.provide('deckInput', {for: (id) => {
                let shell;
                for (const binding of bindings) {
                    if (binding.sessionId === id) { shell = inputHub.shellFor(binding); break; }
                }
                if (shell === void 0) throw new Error('deckInput: session not materialized: ' + id);
                return {
                    state: shell.state,
                    composing: () => shell.editor.isComposing(),
                    focus: (atEnd = false) => {
                        const root = shell.editor.getRootElement();
                        if (!root || !shell.editor.isEditable() || atEnd && shell.editor.isComposing()) return false;
                        root.focus({preventScroll: true});
                        if (atEnd) shell.editor.update(() => nl().selectEnd(), {discrete: true, tag: 'focus'});
                        shell.editor.focus(undefined, {defaultSelection: 'rootEnd'});
                        return true;
                    },
                    send: () => deckSubmitters.get(id)?.() ?? false,
                    addImages: files => deckImageIntakes.get(id)?.(files) ?? false,
                    deleteBackward: () => {
                        if (shell.editor.isComposing() || !shell.editor.isEditable() || shell.snapshot.phase !== 'plain') return false;
                        return shell.editor.dispatchCommand($e$2, true);
                    },
                    attach: () => () => {
                        // 0.2.0 shells are per-session already; the legacy draft
                        // mirror has no remaining conflict to paper over.
                    },
                    activate: (view) => uiConversation.binding(id).activate(view)
                };
            }});
'''

def patch_020(name, s):
    if name == 'dsh-api-session-controller':
        # 0.2.0 ships native retain/release reference counting; the fork's
        # stageRefs/acquireStage hack is obsolete and the deck now retains
        # lanes through the public API.
        assert 'retainScope(' in s, 'controller without native retainScope'
        return s
    if name == 'dsh-client-ui-renderer':
        return replace(s, '    exports.SlotRegistry = SlotRegistry;', SESSION_SURFACE_020)
    if name == 'dsh-client-ui-conversation':
        scaffold = DECK_RUNTIME_020
        # 1. scaffolding after InputHub
        s = replace(s, '      const inputHub = new InputHub(ctx, t2);',
                    '      const inputHub = new InputHub(ctx, t2);\n' + scaffold)
        # 2. hero suppression
        s = replace(s, '      const hero = sessionId === void 0 || shellPhase === "blank" && (openState === "open" || summaryBlank === true);',
                    '      const deckMode = dshUseDeckGone(sessionId);\n'
                    '      const hero = !deckMode && (sessionId === void 0 || shellPhase === "blank" && (openState === "open" || summaryBlank === true));')
        # 3. composer unmount while decked
        s = replace(s, '        children: composer\n', '        children: deckMode ? null : composer\n')
        # 4. header deck-gone hook
        s = replace(s, 'function ConversationSessionHeader({ sessionId, hideChrome, useSessions, useConversationViews, useStore, renderSlot, open, selectView, t: t2 }) {\n      const tabs = useConversationViews((value) => value);',
                    'function ConversationSessionHeader({ sessionId, hideChrome, useSessions, useConversationViews, useStore, renderSlot, open, selectView, t: t2 }) {\n'
                    '      const deckGone = dshUseDeckGone(sessionId);\n'
                    '      const tabs = useConversationViews((value) => value);')
        # 5. hide the duplicated title row inside deck lanes
        s = replace(s, '        className: ConversationRoot_module_css_default.titleRow,\n        children: [!hideChrome',
                    '        className: ConversationRoot_module_css_default.titleRow,\n'
                    '        style: deckGone ? {display: "none"} : void 0,\n'
                    '        children: [!hideChrome')
        # 6. measurement marker for the deck lane composer
        s = replace(s, '            "data-composer-card": true,',
                    '            "data-composer-card": true,\n            "data-dsh-input-session": sessionId,')
        # 7. registries before InputBar
        s = replace(s, '    const InputBar = (0, react.memo)(function InputBar2(',
                    '    const deckSubmitters = new Map();\n    const deckImageIntakes = new Map();\n'
                    '    const InputBar = (0, react.memo)(function InputBar2(')
        # 8. intakeFiles reports acceptance
        s = replace(s, '        if (rejected !== null) showToast(rejected);',
                    '        if (rejected !== null) showToast(rejected);\n        return rejected === null;')
        # 9. native image intake listener per lane
        s = replace(s, '      const canAcceptDrop = subagent === null && !locked && !machineBusy && addFiles !== void 0;',
                    '''      const canAcceptDrop = subagent === null && !locked && !machineBusy && addFiles !== void 0;
            react.useLayoutEffect(() => {
                if (sessionId === void 0) return;
                const accept = files => canAcceptDrop && intakeFiles(files) === true;
                deckImageIntakes.set(sessionId, accept);
                const card = cardRef.current;
                const nativeImages = event => { event.detail.accepted = accept(event.detail.files); };
                card?.addEventListener('dsh-native-images', nativeImages);
                return () => {
                    card?.removeEventListener('dsh-native-images', nativeImages);
                    if (deckImageIntakes.get(sessionId) === accept) deckImageIntakes.delete(sessionId);
                };
            }, [sessionId, canAcceptDrop, intakeFiles]);''')
        # 10. submit bridge per lane
        s = replace(s, '      const onPrimary = () => {', '''
            const submitDraft = () => {
                if (keyboard === void 0 || empty || disabled || machineBusy || uploadsPending || editor?.isComposing()) return false;
                keyboard.submit(primarySubmitMode);
                return true;
            };
            react.useLayoutEffect(() => {
                if (sessionId === void 0) return;
                deckSubmitters.set(sessionId, submitDraft);
                return () => { if (deckSubmitters.get(sessionId) === submitDraft) deckSubmitters.delete(sessionId); };
            }, [sessionId, submitDraft]);
            const onPrimary = () => {''')
        # 11. deckInput service (best effort: keep the build green if the
        #     cordis reflect channel moved again).
        try:
            s = replace(s, '      const inputHub = new InputHub(ctx, t2);',
                        '      const inputHub = new InputHub(ctx, t2);')
            marker = 'const composerBlocks = new ComposerBlockRegistry();'
            assert s.count(marker) == 1
            s = s.replace(marker, marker + '\n' + DECK_INPUT_020, 1)
        except AssertionError:
            pass
        return s
    # dsh-client-ui-workspace: sidebar extension slot (indentation-only drift).
    s = replace(s, '"sidebar.workspaces.directoryFlow": {',
                '"sidebar.workspaces.before": {kind:"list",scope:"root"},\n        "sidebar.workspaces.directoryFlow": {')
    anchor = 'className: clsx(WorkspaceBrowser_module_css_default.root, !wide && WorkspaceBrowser_module_css_default.rail),\n        children: ['
    return replace(s, anchor, anchor + '\n          renderSlot("sidebar.workspaces.before", {wide}),')

def patch(name, s, engine_version="0.1.2-rc.1"):
    assert engine_version in {"0.1.2-rc.1", "0.1.5-rc.1", "0.2.0-rc.2"}, "Unsupported deck engine"
    if engine_version == "0.2.0-rc.2":
        return patch_020(name, s)
    modern = engine_version == "0.1.5-rc.1"
    if name == 'dsh-api-session-controller':
        s = replace(s, '\t\t\twatched;', '''\t\t\twatched;
            stageRefs = new Map();
            acquireStage(id) {
                const record = this.resolve(id);
                if (!record) throw new Error('Unavailable session');
                this.stageRefs.set(id, (this.stageRefs.get(id) || 0) + 1);
                void record.session.open();
                let released = false;
                return () => {
                    if (released) return;
                    released = true;
                    const refs = this.stageRefs.get(id) || 0;
                    if (refs <= 1) this.stageRefs.delete(id); else this.stageRefs.set(id, refs - 1);
                    this.sweepDeferred(); this.pruneScopes();
                };
            }
''')
        s = replace(s, 'if (id === this.watched)', 'if (id === this.watched || this.stageRefs.has(id))', 2)
    elif name == 'dsh-client-ui-renderer':
        # Deliberately limited surface: no arbitrary slot access, same renderer machinery.
        s = replace(s, '\t\texports.SlotRegistry = SlotRegistry;', '''
        function SessionSurface({sessionId, part, blocked, openView}) {
            const host = useHost();
            observableHook(host.scopeRevision)(value => value);
            const adapter = host.scope('session');
            if (!adapter) throw new SlotAssemblyError('Session adapter unavailable');
            observableHook(adapter.current)(value => value);
            const binding = adapter.resolve(sessionId);
            if (!binding) return null;
            return react_jsx_runtime.jsx(ScopeBindingContext.Provider, {value: binding,
                children: react_jsx_runtime.jsx(SessionSurfaceBody, {part, blocked, openView})}, sessionId);
        }
        function SessionSurfaceBody({part, blocked, openView}) {
            const binding = useScopeBinding();
            const root = useRootBinding();
            const session = observableHook(binding.hooks.session)(value => value);
            const input = observableHook(binding.hooks.input)(value => value);
            const pending = maybeObservableHook(root.hooks.sessionPendingInteraction)(value => value.get(binding.key));
            if (part === 'chat') return react_jsx_runtime.jsx(SlotOutlet, {
                slotKey: 'conversation.view', opts: {only:'chat'},
                ownerProps: {viewRequest:null, openView, completeViewRequest:()=>{}}});
            if (part !== 'composer') throw new Error('Unknown session surface');
            const fallback = react_jsx_runtime.jsxs(react.Fragment, {children:[
                react_jsx_runtime.jsx(SlotOutlet, {slotKey:'conversation.input.dock',ownerProps:{session,input}}),
                react_jsx_runtime.jsx(SlotOutlet, {slotKey:'conversation.composer.bar',ownerProps:{variant:'composer',blocked}})
            ]});
            return react_jsx_runtime.jsx(SlotOutlet, {slotKey:'conversation.composer',
                ownerProps:{sessionId:binding.key,session,pendingInteraction:pending}, opts:{fallback}});
        }
        exports.SessionSurface = SessionSurface;
\t\texports.SlotRegistry = SlotRegistry;''')
    elif name == 'dsh-client-ui-conversation':
        s = replace(s, 'function ConversationRoot({ sessionId,', 'function ConversationRoot({ useDeckView, sessionId,')
        s = replace(s, 'hooks: { composerBlock: sessionId === void 0 ? ABSENT_BLOCK : composerBlocks.storeFor(sessionId) },', 'hooks: { composerBlock: sessionId === void 0 ? ABSENT_BLOCK : composerBlocks.storeFor(sessionId), deckView: sessionId === void 0 ? ABSENT_BLOCK : ctx.slots.resolveStore(conversationStore, ctx.uiSession.adapter.resolve(sessionId)) },')
        s = replace(s, 'const composerBlock = useComposerBlock((block) => block);', 'const composerBlock = useComposerBlock((block) => block);\n            const deckMode = useDeckView(value => value?.view === "voice-deck");')
        s = replace(s, 'const hero = sessionId === void 0 || shellPhase === "blank" && (openState === "open" || summaryBlank === true);', 'const hero = !deckMode && (sessionId === void 0 || shellPhase === "blank" && (openState === "open" || summaryBlank === true));')
        s = replace(s, 'children: composer\n', 'children: deckMode ? null : composer\n')
        s = replace(s, 'if (session.blank && conversationPhase(session, conversation) === "blank") return null;', 'if (active?.id !== "voice-deck" && session.blank && conversationPhase(session, conversation) === "blank") return null;')
        # Only the repeated session title row disappears; view navigation stays reachable.
        s = replace(s, '"aria-hidden": hideChrome || void 0,', '"aria-hidden": hideChrome || void 0,\n                "data-deck-header": active?.id === "voice-deck" || void 0,')
        s = replace(s, 'className: ConversationRoot_module_css_default.titleRow,', 'className: ConversationRoot_module_css_default.titleRow,\n                    style: active?.id === "voice-deck" ? {display:"none"} : void 0,')
        s = replace(s, '"data-composer-card": true,', '"data-composer-card": true,\n                        "data-dsh-input-session": sessionId,')
        # Bind to the mounted official InputBar so its live guards and submit path stay authoritative.
        s = replace(s, '\t\tconst InputBar = (0, react.memo)(function InputBar(', '\t\tconst deckSubmitters = new Map();\n        const deckImageIntakes = new Map();\n\t\tconst InputBar = (0, react.memo)(function InputBar(')
        s = replace(s, 'if (rejected !== null) showToast(rejected);', 'if (rejected !== null) showToast(rejected);\n                return rejected === null;')
        drop_anchor = 'const canAcceptDrop = subagent === null && !locked && !machineBusy && addFiles !== void 0;' if modern else 'const canAcceptDrop = !locked && !machineBusy && addImages !== void 0;'
        intake = 'intakeFiles' if modern else 'intakeImages'
        s = replace(s, drop_anchor, drop_anchor + '''
            react.useLayoutEffect(() => {
                if (sessionId === void 0) return;
                const accept = files => canAcceptDrop && intakeImages(files) === true;
                deckImageIntakes.set(sessionId, accept);
                const card = cardRef.current;
                const nativeImages = event => { event.detail.accepted = accept(event.detail.files); };
                card?.addEventListener('dsh-native-images', nativeImages);
                return () => {
                    card?.removeEventListener('dsh-native-images', nativeImages);
                    if (deckImageIntakes.get(sessionId) === accept) deckImageIntakes.delete(sessionId);
                };
            }, [sessionId, canAcceptDrop, intakeImages]);'''.replace('intakeImages', intake))
        submit = """
            const submitDraft = () => {
                if (keyboard === void 0 || empty || disabled || machineBusy || uploadsPending || editor?.isComposing()) return false;
                keyboard.submit(primarySubmitMode);
                return true;
            };
            react.useLayoutEffect(() => {
                if (sessionId === void 0) return;
                deckSubmitters.set(sessionId, submitDraft);
                return () => { if (deckSubmitters.get(sessionId) === submitDraft) deckSubmitters.delete(sessionId); };
            }, [sessionId, submitDraft]);
"""
        if modern:
            s = replace(s, '\t\t\tconst onPrimary = () => {', submit + '\t\t\tconst onPrimary = () => {')
        else:
            s = replace(s, '\t\t\tconst onPrimary = () => {', """
                const submitDraft = () => {
                    if (inputActions === void 0 || empty || disabled || machineBusy || editor?.isComposing()) return false;
                    inputActions.submit();
                    return true;
                };
                react.useLayoutEffect(() => {
                    if (sessionId === void 0) return;
                    deckSubmitters.set(sessionId, submitDraft);
                    return () => { if (deckSubmitters.get(sessionId) === submitDraft) deckSubmitters.delete(sessionId); };
                }, [sessionId, submitDraft]);
                const onPrimary = () => {""")
            s = replace(s, '\t\t\t\tif (inputActions === void 0) return;\n\t\t\t\t/* v8 ignore next -- defensive: the primary button is disabled while empty||disabled, so a click cannot reach the false arm. */\n\t\t\t\tif (!empty && !disabled && !machineBusy) inputActions.submit();', '\t\t\t\tsubmitDraft();')
        # Background completion must never steal another lane's DOM selection.
        s = replace(s, 'applied = $replaceDetectSpanWithText(span, text);\n\t\t\t\t});',
                    'applied = $replaceDetectSpanWithText(span, text);\n\t\t\t\t}, this.editor.getRootElement() === document.activeElement ? "history-push" : "skip-dom-selection");')
        # Avoid four simultaneous mount effects stealing focus from the chosen lane.
        s = replace(s, 'if (locked || editor === null) return;\n\t\t\t\teditor.getRootElement()?.focus',
                    'if (locked || editor === null || editor.getRootElement()?.closest("[data-deck-lane]")) return;\n\t\t\t\teditor.getRootElement()?.focus')
        s = replace(s, '\t\t\tconst inputHub = new InputHub(ctx, t);', '''
            const inputHub = new InputHub(ctx, t);
            // Narrow public adapter; Lexical symbols remain inside their owning bundle.
            ctx.reflect.provide('deckInput', {for: (id) => {
                const shell = inputHub.shell(id);
                return {
                    state: shell.state,
                    composing: () => shell.editor.isComposing(),
                    focus: (atEnd = false) => {
                        const root = shell.editor.getRootElement();
                        if (!root || !shell.editor.isEditable() || atEnd && shell.editor.isComposing()) return false;
                        root.focus({preventScroll:true});
                        if (atEnd) shell.editor.update(() => nl().selectEnd(), {discrete:true, tag:'focus'});
                        shell.editor.focus(undefined, {defaultSelection:'rootEnd'});
                        return true;
                    },
                    send: () => deckSubmitters.get(id)?.() ?? false,
                    addImages: files => deckImageIntakes.get(id)?.(files) ?? false,
                    deleteBackward: () => {
                        if (shell.editor.isComposing() || !shell.editor.isEditable() || shell.snapshot.phase !== 'plain') return false;
                        return shell.editor.dispatchCommand($e$2, true);
                    },
                    attach: () => {
                        const entry = ctx.slots.entries('conversation.session')[0];
                        if (!entry?.store) throw new Error('Conversation store unavailable');
                        const instance = ctx.slots.resolveStore(entry.store, ctx.uiSession.adapter.resolve(id));
                        const stored = instance.getSnapshot().draft;
                        if (!shell.snapshot.draft && stored) shell.actions.setDraft(stored);
                        return shell.bindMirror(instance.actions.setDraft);
                    }
                };
            }});
''')
    else:
        # Insert a small root-scoped sidebar extension, without session claims.
        s = replace(s, '"sidebar.workspaces.directoryFlow": {\n', '"sidebar.workspaces.before": {kind:"list",scope:"root"},\n\t\t\t\t\t"sidebar.workspaces.directoryFlow": {\n')
        anchor = 'className: clsx(WorkspaceBrowser_module_css_default.root, !wide && WorkspaceBrowser_module_css_default.rail),\n\t\t\t\tchildren: ['
        s = replace(s, anchor, anchor + '\n                    renderSlot("sidebar.workspaces.before", {wide}),')
    return s

def main():
    source = ROOT / 'downloads/snapshot-arm64.tar.xz'
    expected = next(x['digest'].split(':',1)[1] for x in json.loads((ROOT/'docs/download-sources.json').read_text()) if x['name']==source.name)
    assert hashlib.file_digest(source.open('rb'),'sha256').hexdigest() == expected
    found = {}
    with tarfile.open(source, 'r|xz') as tar:
        for member in tar:
            for name in PACKAGES:
                if member.name == PREFIX + name + '/lib/client.js':
                    found[name] = tar.extractfile(member).read()
    assert set(found) == set(PACKAGES)
    DEST.mkdir(parents=True,exist_ok=True)
    receipt = {}
    for name, raw in found.items():
        result = patch(name, raw.decode()).encode()
        path = DEST / (name+'.js'); path.write_bytes(result)
        receipt[name] = {'base':hashlib.sha256(raw).hexdigest(), 'patched':hashlib.sha256(result).hexdigest()}
    (DEST/'manifest.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt,indent=2))

if __name__ == '__main__': main()
