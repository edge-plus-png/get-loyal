> Current implementation and exact acceptance limits: [Production-readiness audit](../../docs/handheld/production-readiness-2026-09-23.md).

# Move Handheld staging

Native portrait Android standalone trading staging build, PAX A920 Pro target. Current hardware
verification used A920, not Pro. Read `../../docs/handheld/assessment.md` and the
release evidence before handover.

## Build

JDK 17 and Android SDK 35 are required. Create a local, untracked `local.properties`
with `sdk.dir=/path/to/android/sdk`, then:

```sh
python3 scripts/verify-shared-source.py
./gradlew :app:assembleRelease
```

Assembly depends on JVM tests. Output: `app/build/outputs/apk/release/app-release.apk`.
The staging release is R8-shrunk and debug-certificate signed; it is not a PAXSTORE
production upgrade. Package `uk.edge.move.handheld.staging` uses the fixed staging
Move origin. There is no endpoint override, generic API key or WebLink SDK. The pinned Move POSitive SDK and PAX printer libraries are included.

## UI verification with synthetic data

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w uk.edge.move.handheld.staging.benchmark.test/uk.edge.move.handheld.FixtureInstrumentation
adb -s SERIAL shell am start -W -n uk.edge.move.handheld.staging.benchmark/uk.edge.move.handheld.HandheldActivity
```

The signed test APK seeds **only** the separate debuggable `.benchmark` application,
refuses an existing pairing file, and labels its simulated operator/catalogue. It
never pairs with a real merchant. The fixture PIN is `2468`; its sole purpose is
synthetic UI verification. The test runner/fixture are absent from the staging
release APK. Network calls are disabled for this labelled debug fixture.

To repeat from scratch, clear **only that disposable benchmark package**, then
seed again. Never clear the actual staging or legacy packages to run this test.
The UI script is `../../scripts/handheld/fixture-ui-check.py SERIAL EVIDENCE_DIR`.

## Boundaries

`shared-counter` snapshots are immutable and hash-checked. Coordinate a reviewed
module extraction with Counter before modifying those sources. The app's
`OrderingDraft` adapter uses those snapshots; standalone completed sales/outbox commit in local SQLite and upload through the existing Move reporting contract.

No automatic syncing or payment operation is scheduled. The actual staging app is
paired with Prime and holds the published v32 menu; synthetic fixtures are separate.
Shared-table save/reservation is connected to staging. Cash reporting and PAX printer acknowledgement have been verified. Real App2App handoff and A920 Pro acceptance remain outstanding. See
`../../docs/handheld/service-context-parity.md` for current ordering support and evidence.
