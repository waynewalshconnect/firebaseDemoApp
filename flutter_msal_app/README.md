# flutter_msal_app

A new Flutter project.

## Configuration

No B2C/backend values are hardcoded in the app. Copy `env/env.example.json` to
`env/env.json` and fill in your environment's B2C client ID, authority,
redirect URI, scopes, and exchange/profile endpoint URLs. Run or build with:

```powershell
flutter run --dart-define-from-file=env/env.json
```

The Android `BrowserTabActivity` redirect intent-filter is also generated from
`env/env.json` at build time (see `android/app/build.gradle.kts`), so switching
`env.json` to a different B2C tenant/app registration is enough to point the
whole app at a new environment without code changes.

## Getting Started


This project is a starting point for a Flutter application.

A few resources to get you started if this is your first Flutter project:

- [Learn Flutter](https://docs.flutter.dev/get-started/learn-flutter)
- [Write your first Flutter app](https://docs.flutter.dev/get-started/codelab)
- [Flutter learning resources](https://docs.flutter.dev/reference/learning-resources)

For help getting started with Flutter development, view the
[online documentation](https://docs.flutter.dev/), which offers tutorials,
samples, guidance on mobile development, and a full API reference.
