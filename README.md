# MOBIUS — Personal AI Operating Layer

> **Your Intelligence. Everywhere. Owned by You.**
> *Your phone, with a mind of its own — and it stays yours.*

Internal codename: **Gembul**. Built for **Firebase #BuildHybridAI 2026 (Android track)**.

MOBIUS is an Android app that puts an AI layer on your phone as a floating bubble. It understands
context (foreground app, notifications, time, recent tasks) and works **across apps**. The bubble is
only the entry point — the product is the layer behind it.

- **On-device first.** Gemini Nano (via Firebase AI Logic) handles requests by default.
- **Cloud is borrowed, never default.** Gemini cloud is called only when a task needs it *and* you switched on **Allow cloud AI** (OFF by default). Every routing decision is logged and visible in the app.
- **You own the core.** Orchestrator, memory, permissions and context management run locally. Subagents are temporary workers that report back; only the core decides what is remembered.

> ⚠️ **Hackathon demo, not a Play Store release.** MOBIUS uses an `AccessibilityService` for
> non-accessibility automation, which Google Play restricts. This repo and its demo video are a
> hackathon submission only.

---

## Status — read this first

**Built and running.** v4 was built 24 Nov 2026 (`assembleDebug`, ~1 min), APK 18.4 MB
(SHA-256 `c5f7691d…`, package `com.mobius` v0.1.0, compileSdk 35, aapt2-verified).
v3 is installed and running on a physical Xiaomi phone (all four capabilities ON:
bubble, accessibility, notifications, usage access). v4 (below) is built and
delivered, on-device testing pending.

| Area | Status |
|---|---|
| Gradle scaffold, manifest, resources | **Built**, APK verified |
| Core / agent / AI / UI Kotlin | **Compiled**, v4 |
| `OnDeviceAI.kt` imports for the experimental on-device SDK | **Verified** against the real AAR (`javap`); `InferenceMode`/`OnDeviceConfig` are top-level `com.google.firebase.ai` |
| Library versions (Firebase 18.2.0 / on-device 16.0.0-beta08 / App Check debug 20.1.0) | **Resolved by Gradle** (Kotlin 2.3.21, Room 2.8.5, Compose BOM 2025.12.01) |
| WhatsApp automation (view ids, Send-button label) | Written against WhatsApp's known layout, **needs a physical device to verify**; WhatsApp can change its UI at any time |
| Gemini Nano | **Unavailable on the test Xiaomi** (no AICore) — falls back to cloud when allowed |
| Cloud path | App Check **unenforced** (monitoring only; Google auto-enforces again 21 Jan 2027); model `gemini-3.8-flash`; last live test got a temporary Gemini 503 (free-tier overload), not an app bug |
| Two-layer Tools + Skills (v4) | **Built**: 12 closed tool primitives (incl. swipe via `dispatchGesture`), JSON skills + `SkillRegistry` + deterministic `SkillRunner`, example skill `shopee_price_search` — end-to-end routing on a real device **untested** |
| Voice input (v4) | **Built**: bubble mic button → Android `SpeechRecognizer` (free, id-ID) — on-device **untested** |

Build note: the build shell runs as root while `HOME=/home/hatch`, so Gradle must be
told `export GRADLE_USER_HOME=/home/hatch/.gradle` (plus `JAVA_HOME` = JDK 21,
`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`, `ANDROID_HOME` = Android SDK),
or it reads an empty cache under `/root/.gradle`. Use the cached Gradle 8.9 binary
directly; `./gradlew` cannot download its distribution through the proxy.

## Deviations from the original brief (deliberate)

1. **Bubbles API not implemented.** Only the `SYSTEM_ALERT_WINDOW` overlay (chat-head style) is built. The Bubbles API needs conversation shortcuts and a system opt-in, and cannot float over arbitrary apps the same way.
2. **Remote Config and Authentication are not wired in.** I couldn't verify their artifact versions and neither is needed for the MVP. They are listed under future work. Firebase AI Logic and App Check (the hard requirements) are in.
3. **Extra files beyond the section-4 tree:** `MobiusApp.kt` (composition root, App Check, WorkManager), `AppConfig.kt` (constants), plus resources, Gradle wrapper, `.gitignore`. Concrete agents live in `AgentFactory.kt`; Room entities/DAO and the TTL worker live in `MemoryStore.kt`; `BootReceiver` lives in `BubbleService.kt`.
4. **App name lives in one place:** `mobiusAppName` in `app/build.gradle.kts`. It feeds `AppConfig.APP_NAME` (Kotlin) and `@string/app_name` (launcher label). Rename there only.
5. **Confirm-before-send** (default ON) was added: an AI-drafted message is never sent without your approval unless you turn this off in Settings.

---

## Architecture

```
 BUBBLE (overlay) ──tap──▶ CORE ORCHESTRATOR (local)
                            ├─ TaskRouter      intent → subagent type
                            ├─ ContextManager  foreground app, notifications, time, recent tasks
                            ├─ PermissionManager  opt-in gate + preferences (DataStore)
                            └─ MemoryStore     Room — written ONLY by the core
                                   │
                  ┌────────────────┴───────────────┐
              AI ROUTER                      AGENT FACTORY
        Nano first · cloud gated             one SubAgent per task
        logs every decision                  (temp scope + TempStore, destroyed after)
          │          │                              │
   Gemini Nano   Gemini cloud                 reports result to the core
   (on-device)   (borrowed)                          │
                                       Accessibility · NotificationListener · Intents → other apps
```

Full detail: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Permissions: [`docs/PERMISSIONS.md`](docs/PERMISSIONS.md).

### The ten principles, and where they are enforced

| | Principle | Where |
|---|---|---|
| P1 | Core runs locally | `core/` — no network code outside `ai/` |
| P2 | Subagent = temporary worker | `AgentFactory` creates one per task; `AgentScope.runAndDestroy` |
| P3 | On-device AI first | `AIRouter` tries `OnDeviceAI` first for light tasks |
| P4 | Cloud = borrowed | `AIRouter` is the only caller of `CloudAI`; needs *Allow cloud AI* |
| P5 | Memory owned by core | `AgentTools` has **no** `MemoryStore`; `Orchestrator.finish()` is the only writer |
| P6 | Task done = runtime stopped | `AgentScope.destroy()` in `finally`; `TtlCleanupWorker` (WorkManager) |
| P7 | Bubble = entry point | `bubble/` only forwards text to `Orchestrator.submit()` |
| P8 | Permissions opt-in | `PermissionManager` + onboarding; every feature degrades (see PERMISSIONS.md) |
| P9 | Zero capital | Gemini Developer API free tier, no paid services |
| P10 | Privacy by design | notifications in RAM only (10 min TTL); memory never sent to cloud |

---

## How Firebase AI Logic is used (runtime, not just imported)

- **`ai/OnDeviceAI.kt`** — `Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(..., onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_ON_DEVICE))` → `generateContent(prompt)`. Also `FirebaseAIOnDevice.checkStatus()` / `download()` for Nano availability.
- **`ai/CloudAI.kt`** — the same SDK with the cloud model only.
- **`ai/AIRouter.kt`** — our own routing on top (instead of the SDK's built-in `PREFER_ON_DEVICE`) because we must enforce the user's *Allow cloud AI* gate and log every decision. It uses `ONLY_ON_DEVICE` for Nano and a cloud-only model for cloud, so the SDK can never silently go to the cloud on our behalf.
- **App Check** — `MobiusApp.installAppCheck()` installs `DebugAppCheckProviderFactory` (debug tokens are accepted for the submission).

Prompts are called for: **intent parsing** (every task), **reply drafting** (WhatsApp task), **chat answers**.

If Nano is unavailable: the log shows `blocked: Nano unavailable … cloud needed but "Allow cloud AI" is OFF` and the app asks you to enable cloud; with cloud enabled it shows `cloud (Gemini): Nano unavailable … -> cloud (allowed by user)`.

## The end-to-end task

> “Reply to the last WhatsApp message” (optionally: “… and say I'll be 10 minutes late”)

1. Bubble → Orchestrator. 2. Nano classifies intent (`reply_whatsapp`). 3. `ReplyTask` subagent reads the last WhatsApp notification (NotificationListener). 4. Drafts a reply (or uses your dictated words). 5. You approve it. 6. It fires the notification's own PendingIntent to open that exact chat, types the text and presses **Send** via the AccessibilityService, then checks the input box cleared. 7. It reports `Replied to X at HH:mm` and is destroyed. 8. The core stores that one line (TTL) and the bubble shows “Done”.

Safety notes: the Send button is matched **only** if labelled Send/Kirim/Enviar/… (WhatsApp reuses that slot for the voice-message button, which must never be pressed). If anything is not found, nothing is sent and the bubble says why.

## Build and run

1. **Android Studio** (current stable), JDK 17, a **physical Android device** (Pixel 8+ recommended for Nano).
2. Create a Firebase project; in **Build → AI Logic** enable the **Gemini Developer API**. Add an Android app with package `com.mobius`, download **`google-services.json`** into `app/`. (A `google-services.json` for the hackathon Firebase project is already included in this repo — replace it with your own project's file if you fork. It is a public client config, not a secret.)
3. Open the project, let Gradle sync. If sync complains about versions (AGP / compileSdk / Firebase), bump them in `build.gradle.kts` / `app/build.gradle.kts`; the Firebase versions to use are on the [official hybrid inference page](https://firebase.google.com/docs/ai-logic/hybrid/android/get-started).
4. Run once; copy the **App Check debug token** from Logcat (tag `DebugAppCheckProvider`) and register it in **Firebase console → App Check → Apps → Manage debug tokens**. Also enable App Check enforcement for AI Logic if you want it enforced.
5. In the app, walk through onboarding. **Sideloaded APK?** On Android 13+ open *App info → ⋮ → Allow restricted settings* before enabling Accessibility / Notification access.
6. On a Nano-capable phone: Settings → *Check* → *Download Nano* if offered.
7. Tap **Start bubble**, open WhatsApp contacts' conversation from another phone, then ask via the bubble.

## MVP scope

**In:** bubble overlay (drag, tap, text input, **voice input** via mic button), orchestrator + TaskRouter + ContextManager, hybrid routing with visible log, one cross-app task (WhatsApp reply), Room memory with TTL + History, permission onboarding, **two-layer Tools + Skills** (12 closed tool primitives, JSON skills, deterministic Runner, example Shopee price-search skill), this README, demo shot list.

**Out / future:** Bubbles API, Remote Config feature flags, anonymous Auth, multi-app chains, scheduled autonomous tasks, cloud sync, Wear OS, iOS (hybrid inference is Android-only today), a Play-Store-safe variant.

## Demo

See [`docs/DEMO_SHOTLIST.md`](docs/DEMO_SHOTLIST.md) (≤ 3 minutes).

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
