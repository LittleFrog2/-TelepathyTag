# TelepathyTag Collaboration Notes

This repository contains the project code for the Android UWB ranging tag demo:

- `Projects/FreeRTOS/AndroidRangingTag/` - DWM3001CDK firmware target.
- `Src/` - shared firmware source modified or used by the project.
- `MyTag_test/` - Android application project.
- `docs/` - implementation plans and project notes.

Large generated artifacts are intentionally not committed:

- firmware build output
- Android build output
- APK/HEX/ELF/MAP files
- local logcat captures
- local success backups
- IDE and Gradle caches

The local machine has a verified success backup at:

```text
backups/uwb_success_candidate_20260706_121256
```

That backup is kept outside Git because it contains generated binaries and logs.
