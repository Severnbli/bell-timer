# Android app

- Add every user-visible string to `app/src/main/res/values/` (Russian default),
  `values-en/` and `values-be/`.
- Preserve the release signing key in the repository secrets and the
  `applicationId` so installed apps can update. See the README signing setup
  and `.github/workflows/android-release.yml` for release signing inputs.

After each app change, run from the repo root with JDK 17 and Android SDK
platform 37 installed as described in the README.

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest lintDebug
```
