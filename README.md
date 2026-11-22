# Battery Master AI — v1 source

A native Android battery-monitoring app (Kotlin, Jetpack Compose, Material 3, Hilt, Room, DataStore, WorkManager). It records real `BatteryManager` data, tags every value with how it was obtained, estimates capacity and health from your own charging sessions, and explains its findings offline.

> **Build status: NOT compiled, NOT tested.** This project was written in an environment with no Android SDK and no network access, so Gradle could not download the Android Gradle Plugin, Kotlin, Compose or AndroidX. No APK/AAB was produced and no test has been run. Expect some compile errors on the first build; send them back and they can be fixed. Static checks were run instead: imports resolve, brackets balance, XML parses, every `R.string` exists, and the test expectations were traced by hand.

---

## 1. Build it

Requirements: Android Studio (Narwhal or newer), JDK 17+, Android SDK 36.

1. Open the `BatteryMasterAI` folder in Android Studio and let Gradle sync. The wrapper is pinned to Gradle 8.14.3.
2. If Studio offers newer versions of AGP, Kotlin, KSP or Hilt, upgrade **Kotlin, KSP and Hilt together**, because their versions are coupled.
3. Debug build: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
4. Unit tests: `./gradlew :app:testDebugUnitTest`
5. Instrumented tests (on a device or emulator): `./gradlew :app:connectedDebugAndroidTest`

### Release signing (APK + AAB)
Create a keystore, then add `keystore.properties` in the project root (it is git-ignored):
```
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```
Then run `./gradlew :app:assembleRelease :app:bundleRelease`. Without this file, release builds are produced unsigned.

### Build on GitHub (no local Android setup needed)
`.github/workflows/android.yml` runs on every push:
1. **Debug job:** builds `app-debug.apk` and runs the unit tests.
2. **Release job:** builds the release APK and AAB.

Download the outputs from the run page under **Artifacts**.

To get a **signed** release, add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` output |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Create a keystore once with:
`keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias battery`

Without the secrets, the release outputs are unsigned. The debug APK installs on any phone either way.

---

## 2. Architecture

```
ui/            Compose screens + ViewModels (no hardware or DB access)
  home, health, power (charging/discharging + session detail), analytics, doctor, settings
  charts/      Reusable interactive Canvas chart (zoom, pan, tap tooltips, legends, bands, PNG export)
domain/        Pure Kotlin, unit-tested
  model/       Measurement + Reading (Available | Unavailable), DataClass, Confidence
  reliability/ Unit/sign learning for current; voltage/temperature/charge-counter validation
  session/     Per-session statistics
  health/      CapacityEstimator, HealthCalculator
  prediction/  LinearRegression, RuntimePredictor, CapacityForecaster
  doctor/      BatteryDoctor (transparent scoring)
  assistant/   BatteryAssistant (local, rule-based)
  alerts/ protection/ compat/ analytics/ recording/ usecase/
data/
  source/      BatteryDataSource (the only BatteryManager caller) + ReliabilityEngine
  db/          Room entities, DAOs, mappers (schema exported to app/schemas)
  recording/   BatteryRecorder (single write path) + SessionManager
  repo/ prefs/ export/ protection/
background/    MonitoringService (optional FGS), SampleWorker (WorkManager), BootReceiver
widget/ tile/ notifications/ di/
```

**Deviation from the spec:** the spec asks for about 26 Gradle modules. This v1 uses one `:app` module with the same layers as packages. The boundaries are the same (UI → use cases/repositories → data source), but one module is far less likely to break on a first build that can't be tested here. Splitting `domain/` into its own pure-Kotlin module is the natural next step.

### Data flow
`ACTION_BATTERY_CHANGED` / `BatteryManager` → `BatteryDataSource` (raw) → `ReliabilityEngine` (classified `BatterySnapshot`) → `BatteryRecorder` (throttle → Room → sessions → alerts → widget). The UI reads live snapshots from `BatteryRepository` and history from `HistoryRepository`.

---

## 3. How the key numbers are produced

| Value | Class shown in UI | Method |
|---|---|---|
| Level, status, health flag, charge counter, cycle count | System | Read from Android |
| Temperature, voltage, current | Measured | Read from Android, then units and plausibility are validated |
| Current direction and unit | — | Learned per device: µA vs mA from magnitude, and the sign from votes while charging vs unplugged. Shown as *Calibrating* until learned |
| Power | Calculated | V × I, only when current is verified. Labelled battery-side, not charger output |
| Capacity | Estimate | Charge-counter rise ÷ level rise over sessions spanning ≥30% with no gaps, or integrated current as a fallback. Median of up to 10 sessions; confidence from count and spread. Counters that only mirror the level are detected and rejected |
| Health % | Estimate | Capacity ÷ **design capacity entered by the user** (Android has no public API for design capacity) |
| Runtime and time to target | Forecast | Linear regression on recent level, with a range; falls back to the 7-day average (labelled Estimate) |
| Capacity forecast | Forecast | Linear trend with a 95% prediction band. Needs ≥6 estimates over ≥30 days; it never gives a replacement date |

---

## 4. Permissions

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` | Charging target, full charge, low battery and temperature alerts |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Optional continuous monitoring, used only after the user turns it on |
| `RECEIVE_BOOT_COMPLETED` | Restart monitoring after a reboot, if it was enabled |
| `VIBRATE` | Alert vibration |
| `SYSTEM_ALERT_WINDOW` | Optional live-current overlay ("Display over other apps"), only after the user enables it |
| `PACKAGE_USAGE_STATS` | Optional per-app usage estimate ("Usage access"), granted by the user in system settings |

There is **no INTERNET permission**. All data stays on the device.

**Play Store note:** a `specialUse` foreground service needs a written justification in the Play Console. The subtype text is in the manifest. If Play rejects it, the app still works with WorkManager-only sampling (about every 15 minutes).

---

## 5. Known Android limitations (by design, not bugs)

- **Charging can't be stopped by a regular app.** `ChargingControlEngine` detects capabilities. This build ships only notification-only protection, because there is no documented, verifiable vendor API to integrate against. The registry is ready for manufacturer or opt-in root controllers, and the app never claims charging stopped unless a controller confirms it.
- Design capacity isn't exposed. The user enters it.
- `CURRENT_NOW` units and sign vary by manufacturer. The app handles this by learning them, as described above.
- Background precision depends on Doze. Continuous monitoring is opt-in.
- Per-app energy use isn't available to regular apps, so it isn't shown.

---

## 6. Spec coverage in this v1

| Area | Status |
|---|---|
| Real-time monitoring, reliability engine, unavailable states | ✅ |
| Charging and discharge sessions, history, session detail | ✅ |
| Capacity estimation with methodology and confidence; health, wear, trend | ✅ |
| Smart charging target (70–100%, custom), alerts, protection history | ✅ notification-only |
| Charts: level, charging, discharging, thermal, electrical, health/forecast; Live/1H/24H/7D/30D/1Y/custom; zoom, pan, tooltips, legends, min/avg/max, previous-period comparison, SQL aggregation | ✅ |
| Export: CSV (measurements, sessions), chart PNG, PDF Doctor report | ✅ |
| AI Doctor (evidence-based, transparent score) and local assistant | ✅ rule-based and offline |
| Notifications with channels, sound toggle and de-duplication | ✅ |
| Widget | ⚠️ 1 resizable widget (spec: 6) |
| Quick Settings tile | ✅ status only; no fake toggle |
| Device compatibility screen | ✅ |
| Settings, theme, dynamic colour, retention, delete all, per-app language hook | ✅ English only |
| Multi-module Gradle structure | ❌ single module, see §2 |
| App usage analysis (UsageStatsManager) | ❌ not in v1 |
| Optional cloud AI | ❌ not in v1; nothing leaves the device |
| On-device ML model | ❌ statistical methods only |
| Google Play Billing / premium tier | ❌ not in v1 (needs a Play Console account) |
| Backup/restore | ⚠️ Android Auto Backup rules only; no in-app restore |
| Migration test | ⚠️ schema v1 only; add `MigrationTestHelper` tests with the first schema change |

## 7. Tests (written, not executed)

- `app/src/test`: normalisers (units, sign, plausibility), capacity estimator (accept/reject cases, derived-counter detection, integration fallback), health median/confidence, regression, runtime and charge predictors, forecaster, alert rules (once per session, cooldown, hysteresis), sample throttle, session stats (screen on/off split, energy, gaps), Doctor scoring and penalties, assistant routing and missing data.
- `app/src/androidTest`: Room de-duplication, full charge session → closed session → capacity estimate, SQL bucket aggregation, delete-all.

Device testing across manufacturers, measuring the app's own battery cost, and the release build have not been done.
