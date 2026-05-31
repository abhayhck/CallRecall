# CallRecall

Personal productivity app for notes linked to phone numbers. When a call arrives, a draggable floating button appears so you can open or create a note for that caller. All data is stored locally with Room — no network required.

## Requirements

- Android Studio Meerkat (2024.3.1) or newer recommended
- Android SDK Platform 36
- Device or emulator running **Android 14+** (API 34+)

## Setup

1. Open the project folder in Android Studio.
2. Sync Gradle.
3. Run on a physical device for full call and overlay testing (emulator support for telephony is limited).

## Permissions

On first launch, grant:

- **Phone** — detect incoming calls
- **Contacts** — pick contacts and resolve names
- **Notifications** — foreground service notification
- **Display over other apps** — floating button during calls

## Architecture

- **MVVM** with View Binding (XML layouts only)
- **Room** — single `ContactNote` per phone number
- **CallMonitoringService** — foreground call listener + overlay
- **BootReceiver** — restarts monitoring after reboot

## Package structure

```
com.callrecall
├── activities
├── adapters
├── database
├── models
├── repository
├── viewmodels
├── services
├── receivers
└── utils
```
