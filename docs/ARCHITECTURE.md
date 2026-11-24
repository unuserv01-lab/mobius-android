# MOBIUS Architecture

## 1. Layers

| Layer | Package | Responsibility | Runs |
|---|---|---|---|
| Entry point | `bubble/`, `ui/` | Bubble overlay, input/confirm panel, launcher UI. Forwards text, shows results. | Foreground service + activity |
| **Core** | `core/` | Orchestrator, TaskRouter, ContextManager, PermissionManager, MemoryStore | Local, always |
| AI layer | `ai/` | AIRouter (routing + gate + log), OnDeviceAI (Nano), CloudAI (Gemini), PromptBuilder | Local; cloud only when gated |
| Workers | `agent/` | SubAgent, AgentFactory, AgentScope, TempStore | Per task only |
| Cross-app | `accessibility/`, `notification/` | ScreenReader, ActionExecutor, NotificationListener | On demand |

`MobiusApp` is the composition root: it owns lazy singletons of the core so the activity, the
bubble service and the WorkManager job all share one `MemoryStore`, `PermissionManager` and
`Orchestrator`.

## 2. Task flow

```
BubbleService.onSubmit(text)
  └─ Orchestrator.submit(text, hooks)               [one task at a time, Mutex]
       ├─ ContextManager.snapshot()                 foreground app, notifications (RAM), time, recent tasks
       ├─ TaskRouter.route()
       │    └─ AIRouter.generate(intent prompt)     Nano first; logs decision
       ├─ AgentFactory.create(type) → SubAgent      new instance, new AgentScope
       ├─ SubAgent.run()                            AgentScope.runAndDestroy { execute() }  ← always torn down
       │    ├─ read last WA notification            NotificationCache
       │    ├─ AIRouter.generate(reply prompt)      (unless user dictated the text)
       │    ├─ hooks.confirmSend()                  human approval (setting, default ON)
       │    ├─ firePendingIntent → open chat
       │    ├─ ScreenReader.findChatInput / ActionExecutor.setText
       │    ├─ ScreenReader.findSendButton / ActionExecutor.click
       │    └─ verify input cleared → AgentResult
       └─ Orchestrator.finish()                     core decides what to persist → MemoryStore
```

## 3. Hybrid AI routing (`AIRouter`)

```
nano = OnDeviceAI.status()
if nano == AVAILABLE and task is light:
    try Nano  → log "on-device"            return
cloudReason = heavy | Nano unavailable | Nano failed
if "Allow cloud AI" is OFF:
    if nano == AVAILABLE and task is heavy: use Nano anyway → log "on-device"   return
    log "blocked"; throw CloudRequiredException  (UI asks the user to enable cloud)
else:
    CloudAI.generate → log "cloud (allowed by user)"
```

Every branch writes a `RoutingLogEntry` (model, reason, task id, TTL) shown in History. The SDK's own
`PREFER_ON_DEVICE` mode is intentionally **not** used, since it could reach the cloud without
passing our gate; `OnDeviceAI` uses `ONLY_ON_DEVICE` and `CloudAI` has no on-device config.

## 4. Memory model

`MemoryEntry(id, type: fact|preference|task_result, content, source, created_at, ttl_seconds, expires_at)`.

- **Single writer.** `MemoryStore` is not in `AgentTools`. A subagent returns an `AgentResult`
  containing optional `MemoryProposal`s; `Orchestrator.finish()` filters them (allowed types, ≤3,
  ≤200 chars) and always adds one outcome-only `task_result` line. Message bodies and AI answers
  are never stored.
- **TTL.** `TtlCleanupWorker` (WorkManager, every 15 min) deletes expired memory and routing-log
  rows and prunes the in-memory notification cache. The app also purges on start and when History
  opens.
- **Privacy.** Memory never goes to the cloud; the cloud prompt contains only the request text and,
  for reply tasks, the one message being answered.

## 5. Subagent lifecycle

`AgentFactory.create` → `SubAgent` (own `AgentScope` = `SupervisorJob` + `TempStore`) →
`runAndDestroy { execute }` → `finally { job.cancel(); temp.clear() }`. No instance is cached, no
service is started for a subagent, and cancellation of the caller also destroys the scope.

## 6. Overlay hosting notes

- The bubble window is `FLAG_NOT_FOCUSABLE`, so it never becomes the active window.
- The input/confirm panel is focusable (needed for the keyboard). It is **closed before** a task
  starts acting on another app: while it is on screen it would be the active window, and
  `ScreenReader` must see the target app. `ScreenReader.rootFor(pkg)` also scans all windows by
  package rather than trusting `rootInActiveWindow`.
- Compose in a `Service`: `BubbleService.OverlayOwner` supplies Lifecycle / SavedState / ViewModelStore.
- Drag uses raw screen coordinates (`DragTouchListener`) because the window moves while dragging.

## 7. Known limitations

- WhatsApp UI ids / labels can change; the agent fails safe (nothing sent) when it cannot find the input or a Send-labelled button.
- On-device Gemini Nano is single-turn text only and token-limited; prompts are kept short.
- The experimental on-device SDK (`firebase-ai-ondevice`, beta) may change API surface.
- One task at a time.
