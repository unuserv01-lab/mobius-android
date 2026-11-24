# MOBIUS Permission Model — opt-in by owner

Every cross-app capability needs explicit user consent, explained in plain language in onboarding.
If one is denied the dependent feature is disabled, the reason is shown, and the app never crashes.

| Permission | Used for | How granted | If denied |
|---|---|---|---|
| `SYSTEM_ALERT_WINDOW` | Floating bubble over apps | Settings → Draw over apps | Bubble hidden; app works from the launcher (Home tab) |
| `BIND_ACCESSIBILITY_SERVICE` | Read screen, click, type, navigate other apps | Settings → Accessibility → MOBIUS | Cross-app tasks disabled (the WhatsApp agent fails *before* any AI call); AI chat still works |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Read notifications (e.g. WhatsApp) | Settings → Notification access | Notification-based tasks disabled; context has no notifications |
| `PACKAGE_USAGE_STATS` | Which app is in the foreground | Settings → Usage access | Context falls back to the accessibility "last window" signal, else time only |
| `POST_NOTIFICATIONS` (Android 13+) | Status notice for the foreground service | Runtime prompt | No status notification (service still runs) |
| `INTERNET` | Cloud AI when permitted | Manifest | Cloud unavailable; on-device only |
| `FOREGROUND_SERVICE` (+ `_SPECIAL_USE`) | Keep the core + bubble alive | Manifest + started from the app | Core runs only while the app is open |
| `RECEIVE_BOOT_COMPLETED` | Restart the core after reboot | Manifest | Core starts on manual launch |

## Cloud-AI gate (separate from Android permissions)

- Setting **Allow cloud AI**, default **OFF**.
- OFF → `AIRouter` never calls the cloud model, even if Nano is unavailable. It logs a `blocked`
  entry and tells the user the task needs cloud.
- Every cloud call (and every blocked one) is logged and shown in History.
- When ON, the cloud prompt may contain: the request text and, for reply tasks, the sender name and text of the one WhatsApp message being answered. Stored memory is never sent.

## Data handling

| Data | Where | Lifetime |
|---|---|---|
| Captured notifications (sender, text, PendingIntent) | RAM only (`NotificationCache`) | 10 minutes, max 30 items |
| Subagent scratch data | RAM only (`TempStore`) | Until the task ends |
| Task outcome lines, routing log | Room on device | User-selected TTL: 1 h / 24 h / 7 d; deletable any time |
| Preferences, permission flags | DataStore on device | Until changed / app data cleared |

## Sideloaded install note

On Android 13+, apps installed from an APK may have Accessibility and Notification access greyed
out. Open **App info → ⋮ → Allow restricted settings**, then enable them. Onboarding links to App info.

## Play Store

`AccessibilityService` used for non-accessibility automation is restricted on Google Play. This
project is a hackathon demo (public repo + video), **not** a Play Store release.
