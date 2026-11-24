# MOBIUS — Demo Video Shot List (≤ 3:00, English, YouTube)

**Setup before recording:** physical Android phone with MOBIUS installed and onboarding done; a
second phone/WhatsApp Web ready to send a message to this phone; screen recording on; captions or
English voiceover. If the phone has no Gemini Nano, record *both* cloud-OFF (blocked) and cloud-ON
(fallback) to show the hybrid logic honestly.

| Time | Shot | What to show | Say / caption |
|---|---|---|---|
| 0:00–0:15 | Title card, then home screen | Logo, tagline “Your Intelligence. Everywhere. Owned by You.” | “MOBIUS is a personal AI layer that lives on your phone, understands context, and works across apps.” |
| 0:15–0:40 | Onboarding | Scroll the permission cards; show “If you skip: …” text; grant overlay + accessibility + notification access | “Everything is opt-in, explained in plain language, and degrades gracefully if you say no.” |
| 0:40–1:00 | Settings | Show **Allow cloud AI = OFF** and the Gemini Nano status (Check) | “On-device Gemini Nano is the default. Cloud is borrowed intelligence, off until you allow it.” |
| 1:00–1:15 | Another phone sends a WhatsApp message | Notification arrives on the demo phone | “A message arrives while I'm in another app.” |
| 1:15–1:30 | Bubble | Phone shows any app; bubble floating; drag it; tap it; input sheet opens | “The bubble is just the entry point.” Type: *Reply to the last WhatsApp message* |
| 1:30–2:05 | Task runs | Chip statuses: Understanding → Reading → Drafting → confirm sheet with the draft → **Send** → WhatsApp opens the chat, types, sends → bubble “Done. Replied to X at HH:mm.” | “Nano parses the intent and drafts the reply on-device. A temporary subagent operates WhatsApp through accessibility, only after I approve.” |
| 2:05–2:25 | History | Open History: routing log line `on-device (Gemini Nano)` (and a `blocked` / `cloud` line if shown), plus the stored “Replied to X at HH:mm” entry with Delete | “Every routing decision is logged. The core decides what to remember, and I can delete it.” |
| 2:25–2:45 | Hybrid fallback | (If Nano missing) with cloud OFF: request shows “needs cloud AI”; toggle ON; retry; routing log shows `cloud (Gemini): Nano unavailable → cloud (allowed by user)` | “If the phone can't run Nano, MOBIUS asks before borrowing the cloud.” |
| 2:45–3:00 | Close | Repo URL, “Firebase AI Logic · Gemini Nano · App Check”, Apache 2.0 | “Built with Firebase AI Logic hybrid inference. Open source.” |

**Recording tips:** keep each status chip on screen ≥1 s; avoid showing real contacts' names/numbers
(use a test contact); show the Logcat App Check debug token only if you blur it; do not exceed 3:00.

## Text description for the submission (starter)

*How Firebase AI Logic is used:* MOBIUS uses the Firebase AI Logic Android SDK with the Gemini
Developer API backend. Requests go through a custom router that calls Gemini Nano on-device first
(`InferenceMode.ONLY_ON_DEVICE`) and the cloud Gemini model only when the task is heavy or Nano is
unavailable — and only if the user enabled “Allow cloud AI”. Prompts are issued at runtime for intent
parsing, reply drafting and chat. App Check (debug provider) is enabled.

*Value proposition:* a private, user-owned AI layer that acts across apps, keeps memory and control
on the device, and treats the cloud as borrowed intelligence.
