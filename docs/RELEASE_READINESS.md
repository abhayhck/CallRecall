# CallRecall publication audit

Reviewed on 2026-10-25 (Asia/Kolkata). This is a source/policy review, not Google Play approval.

## Current decision

**Do not submit the current permission configuration as publication-ready yet.**
The manifest contains `READ_CALL_LOG` and `PROCESS_OUTGOING_CALLS`, but the app has
no default-phone/default-assistant role implementation and no approved Play exception.
Working on a development phone does not establish distribution eligibility.

Google restricts the Call Log group to default handlers or reviewed exceptions.
The published enterprise CRM exception requires corporate login; this personal,
local-only app has none. Caller-ID eligibility has not been established. Backing up
CallRecall notes does not itself make this an app that backs up call history.
An exception needs an accurate declaration and case-specific review, not a renamed
feature. [Call Log permission policy](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en)

## Recommended next investigation: caller-identification role

Android documents `CallScreeningService` as a user-selected caller-ID service for
incoming and outgoing calls. It can receive calls from saved contacts when
`READ_CONTACTS` is granted. Telecom selects one screening app, so choosing CallRecall
would replace the current caller-ID/screening app; it is separate from the dialer.
[CallScreeningService reference](https://developer.android.com/reference/android/telecom/CallScreeningService)

Android describes this API as avoiding `READ_CALL_LOG` for screening/caller-ID use.
A prototype should use the provided call details, promptly allow incoming calls,
retain phone-state handling for call end/dismissal, and then remove the old call-log
and outgoing-broadcast paths once verified. This needs user agreement to the role
change, real-device tests, and accurate Play disclosures; API support is not approval.
[Android caller-ID API guidance](https://developer.android.com/about/versions/10/features#call-screening)

## Permission inventory

| Manifest permission | Actual use | Publication action |
| --- | --- | --- |
| `READ_PHONE_STATE` | Phone-state receiver and telephony callback | Explain the call-triggered note feature; handle revocation. |
| `READ_CALL_LOG` | Caller-number broadcast availability and recent-call fallback | Restricted-permission gate: eligible default role/approved exception or redesign. |
| `PROCESS_OUTGOING_CALLS` | Outgoing-number receiver | Same gate; deprecated API additionally needs a modern-platform design review. |
| `READ_CONTACTS` | Contact selection and resolving saved caller names | Explain automatic name lookup; keep manual naming available without access. |
| `SYSTEM_ALERT_WINDOW` | Draggable call-note window | User-granted special access, clear in-app explanation, dismissible UI. |
| `POST_NOTIFICATIONS` | Call-time foreground-service notification | Request in context; distinguish notification consent from manually using notes. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Optional call-time telephony listener | Complete FGS justification and demonstration; approval is not implied by a valid manifest. |

`PROCESS_OUTGOING_CALLS` and its broadcast are deprecated; the documented platform
replacement is `CallRedirectionService`, which is not a drop-in call-note listener.
Do not introduce a redirecting/dialer role just to label the existing app compliant.
[Android Telecom documentation](https://developer.android.com/develop/connectivity/telecom)

`specialUse` requires a manifest explanation and Play review. The console declaration
needs feature details, interruption impact, and a demonstration video.
[Android service types](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use),
[Play FGS declaration](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)

Special overlay access goes through Settings. Permission-denial handling must preserve
reasonable alternatives. The current manual-number/note path is an alternative;
review repeated setup prompts and separate optional contact/notification requests
from essential call-feature setup before submission.
[Sensitive permissions policy](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en)

## Low-risk fixes completed in this audit

- Declared telephony hardware as optional. Offline notes and backups remain usable
  without telephony; this resolves the manifest's blocking hardware lint error.
- Removed an unused direct battery-optimization exemption request helper. The app's
  existing background-settings menu uses the ordinary Settings page.
- Removed unreachable pre-Android-14 service branches (minimum SDK is 34).
- Added monochrome adaptive-icon entries for themed launchers.
- Constructed date formatters per call, avoiding stale locale and shared mutable formatter state.

No call permissions or call-detection features were removed. No APK/AAB was created,
installed, uploaded, or signed during the audit.

## Remaining release gates

1. **Choose a truthful permission strategy.** Establish an eligible user-selected
   role or a genuine reviewed exception, or design a Play edition that avoids the
   restricted permissions. A generic note app and a full default dialer are different
   products; this requires a product decision before implementation. Investigate a
   caller-identification role only if it accurately describes the intended feature;
   do not assume it guarantees approval.
2. **Foreground-service reliability.** The coordinator currently attempts service
   startup before the delayed overlay appears. On Android 15+ the overlay-based
   exemption requires an already-visible overlay unless another exemption applies.
   The failure is caught, but the backup listener may not start. Test and revise
   ordering as appropriate for the chosen call architecture.
   [Background FGS restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
3. **Notification visibility and stopping.** Review the call service's current
   minimum-importance notification/channel, its explanation and user stopping path
   against the final FGS design.
4. **Privacy material.** Prepare a public privacy policy and accurate Data safety
   answers from actual behavior: local notes, contacts/call access, user-directed
   document-provider exports, optional encrypted files, and separate Android system
   backup. Do not claim that no data ever leaves the device when users can export
   to a cloud provider or enable Android backup.
5. **Device validation.** Test normal and denied/revoked permissions, incoming and
   outgoing calls, unknown/hidden callers, ending/rejecting calls, drag dismissal,
   rotation/autosave, and encrypted/plain backup restore on a second installation.
6. **Release engineering.** Configure signing securely, inspect the eventual merged
   release manifest and dependencies, verify the target-API requirement at submission,
   and test a signed release through the intended distribution channel. No signing
   key or Play Console approval is currently represented by this audit.

## Validation

Run without packaging an APK:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug --offline
```

Verified: both tasks passed, with 53 unit tests and zero lint errors. The remaining
lint warnings are dependency-version notices and the redundant `mipmap-anydpi-v26`
folder qualifier. Compiler deprecation warnings for the current call/overlay APIs
remain tracked by the call-architecture review.
Dependency update notices should be assessed together with the Android Gradle plugin
upgrade (the existing plugin warns that compile SDK 36 exceeds its tested API level).
Do not blindly update all dependencies during the permission redesign.
