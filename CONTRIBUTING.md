# Contributing

Thanks for improving CAD to CNY Camera.

## Before opening a PR

1. Keep changes focused and explain the retail/OCR case they address.
2. Add or update a unit test when changing pure parsing/classification logic.
3. Run:

```bash
gradle testDebugUnitTest lintDebug assembleDebug
```

4. Do not commit APK/AAB files, Android build output, local SDK paths, signing credentials, or keystores.

For OCR bugs, a cropped/synthetic label example is preferable to a photo containing people, payment data, receipts, addresses, or other personal information.
