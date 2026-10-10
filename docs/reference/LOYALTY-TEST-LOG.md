# Move / POSitive loyalty experiment — running log

This log distinguishes source/code evidence, observed terminal results and remaining live acceptance work. It is not a production release approval. No card tokens, merchant credentials or staff PINs belong here.

## 7 October 2026 — duplicate-discount investigation

The retained Grubs Up staging basket EE486F9B was £12.80. POSitive returned APPROVED, Amount=1024p and Discount=128p. Its retained receipt shows Amount £11.52, Discount -£1.28 and Total £10.24. Move keeps the payment pending because 1024+128 does not equal 1280. These callback/receipt amounts do not establish the Elavon-authorised charge.

Inspection of installed POSitive 1.00.22.4952 shows it caches the original amount for both loyalty callbacks. A status=5 REWARD response subtracts discount_amount from the current transaction amount, including at completion, without checking the loyalty phase. The original vendor demo returns REWARD in both phases. This explains the arithmetic 1280 → 1152 → 1024, with Discount still 128. The specific live callback sequence was not recorded; acquirer evidence is still required for the held sale.

Evidence: [installed POSitive trace](../../artifacts/android/handheld-loyalty-diagnostics-0.3/installed-positive-discount-trace.md) and its adjacent focused bytecode extract. The [PAX flow guide](https://paxuk.gitbook.io/positive-loyalty-integration-document) describes redemption confirmation and removing stamps, but does not specify the exact acknowledgement status.

## 7 October 2026 — approved companion-app correction

Kevin authorised changing the completion response and maintaining this log. The vendor source was copied from Downloads into `positive-loyalty-demo`; the original download remains unchanged. Only the test companion app is changed. Move Handheld, WebLink and the retained pending sale are untouched.

- Card-presentation eligibility and discount calculation remain unchanged: five stamps, qualifying amount at least £5, 10% discount.
- On completion, an earlier announced reward consumes the stamps and clears the pending flag, then returns RESULT_OK with status=6 (OPEN_APP), without discount_amount or is_reward. It does not issue another REWARD.
- If five stamps exist but no reward was announced earlier, completion retains the stamps and shows that the reward is ready for the next qualifying payment. It does not falsely report a discount applied or consume the reward.
- Other stamp-earning branches remain unchanged.

Status=6 is selected from the demo's existing non-reward acknowledgement. Installed POSitive routes a non-REWARD completion through its null-discount path, which returns control without subtracting money. This is implementation evidence for this installed version, not a claim that PAX documents status=6 as a redemption acknowledgement.

Test build: same package `com.eft.positiveloyaltydemo`, versionCode 2, versionName `1.1-move-test`, label `Loyalty Demo · Move Test`. Build/test results and artifact details are appended below.

### Installation constraint

The installed official demo APK certificate SHA-256 is `e54a2b290088ad500a21bb2ab71ff41e07306caa5f0f02e4f8c911d73d692b72`. Our local debug certificate is `181052d68bf52e7f6ce6c9f7d15ff93a894613dca7df7c5b93d7dab48040a663`. The vendor archive contains no signing keystore. Android therefore cannot update this app in place with our signature. No uninstall, app-data clearing or replacement has been performed. Obtaining a build signed with the original key would preserve app data; replacing the installed app with our differently signed build would reset its local loyalty data and requires an explicit operator decision.

### Remaining hardware acceptance

The correction has not yet been proven with a live redemption. Once an install route is agreed, test a qualifying stamp, reaching five stamps, a single reward request, second tap, approved redemption, zero remaining stamps, and agreement between POSitive, Move and acquirer amounts. For a £12.80 reward basket the expected single-discount values are £1.28 discount and £11.52 payment. Cancellation/retry, repeated callbacks and restart recovery remain separate acceptance cases; this narrow correction does not establish their correctness. Do not retry the retained approved payment or use a fresh charge to resolve it.

### Build verification completed

`testDebugUnitTest`, `assembleDebug` and `lintDebug` passed. Three completion regression tests passed; the vendor's existing arithmetic sample test also passed. APK signature verification passed, and APK metadata confirms the preserved package, versionCode 2, versionName 1.1-move-test and API 24 minimum. Android lint findings are retained in the artifact report; no errors prevented lint completion.

Build from `positive-loyalty-demo` with `sh ./gradlew :positive-loyalty-demo:testDebugUnitTest :positive-loyalty-demo:assembleDebug :positive-loyalty-demo:lintDebug`. SDK location is configured through ignored local.properties. The build needs JDK/Gradle dependency access.

Artifact directory: [positive-loyalty-demo-move-test-1.1](../../artifacts/android/positive-loyalty-demo-move-test-1.1/manifest.json). It contains the APK, SHA-256 manifest, source correction patch, JUnit reports and lint XML. APK SHA-256: `41e4dab2ded932d5897554fba598ca6fa6f7f8f154815d5bccf6f4ed196e57a0`.

**Installation status: not installed**, because the vendor signer differs. Existing terminal apps, vendor stamps and the held Move payment were not modified during this correction. Live payment verification remains outstanding.

## 7 October 2026 — authorised replacement installed

After being informed that the signing-key mismatch required removing the installed demo and resetting its stamps, Kevin replied “do that”. Only `com.eft.positiveloyaltydemo` was uninstalled on A920 `0822879784`; uninstall succeeded. The corrected APK was installed successfully with the same package, versionCode 2 and versionName 1.1-move-test. The C20SE was not touched.

Package inspection confirms the `com.plm.OPEN_APP` loyalty Activity and `com.nominateloyalty.confirm` receiver remain registered, the app is enabled and not stopped. Its launcher opened normally and showed “No tokens yet”, confirming fresh local loyalty data. The launcher was closed after verification. No POSitive payment, simulated transaction, terminal setting change or Move payment-state edit was performed.

This entry supersedes the earlier **not installed** status. Installation and launch are verified; end-to-end POSitive invocation, stamp earning and live single-discount redemption remain unverified. The held Move payment remains pending and must not be retried as part of this test.

## 7 October 2026 — operator-authorised stuck test reset

Kevin explicitly clarified that this was a test and no authorisation occurred. This is the operator's statement, not a newly retrieved acquirer record. The earlier inconsistent APPROVED callback remains preserved as test evidence; no financial outcome was inferred from it.

A temporary same-package, same-signed debuggable copy of Move Loyalty Staging enabled a controlled local reset. Runtime source was unchanged; the temporary build used an external Gradle init script. The app was stopped while the SQLite ledger was snapshotted and checked. Only reference EE486F9B was selected. Its original attempt and processor callback were retained in the attempts archive with operator action ARCHIVE_UNCHARGED_STAGING_TEST, actor Kevin/Edge, reason and timestamp. Its original draft was retained under operatorArchivedDraft:EE486F9B. The active attempt was removed and the working basket emptied, preserving merchant, terminal and service context.

SQLite integrity passed and the written device ledger matched the verified recovery snapshot. All five existing sales, callbacks and trading-day records were unchanged. Pairing/configuration files were untouched. No sale/refund, cancellation result or processor payment was created. The original non-debuggable 0.3 diagnostics APK is restored after the reset; verification follows below. Private local before/after ledger snapshots are held under /private/tmp/move-loyalty-recovery-20261007 and contain retained payment evidence; they are not public documentation.

Recovery verification: original APK restore succeeded; run-as again reports “not debuggable”, confirming temporary access is removed. Operator-authorised staff login succeeded for Grubs Up / Bridgwater / device loyaly. The menu opens without the waiting-card or unresolved-amount screen. No card tender was pressed.

## 7 October 2026 — observed successful single-discount demo redemption

Kevin supplied a terminal photograph showing Move Loyalty Staging's completed Approved screen for reference C10EB37E: Basket £10.80, Loyalty discount £1.08, Card paid £9.72 and Sale total £9.72. These displayed values balance, showing one 10% discount and successful local sale completion, with New sale available rather than the earlier unresolved-amount lock. Kevin had previously reported the first stamp being awarded.

Evidence scope: operator-provided terminal photograph, not an independently retrieved processor/acquirer record. The photograph does not establish the number of taps, the stamp count before redemption, stamp reset afterward or staging upload success. Those checks remain outstanding. Source image: /tmp/codex-remote-attachments/01a0ffdd-c7ff-7790-92ae-86da2db1a8d6/2F91F83A-DF7A-4975-839F-C0F580C83F89/1-Photo-1.jpg.

## 7 October 2026 — obsolete app cleanup

At Kevin’s request, removed the old local-only package uk.edge.move.handheld.loyaltytest from A920 0822879784. Uninstall succeeded. Current Grubs Up app uk.edge.move.handheld.loyaltytest.staging and companion com.eft.positiveloyaltydemo were retained. Normal Move uk.edge.move was not removed.

## V1 approved — first single-terminal implementation

Kevin approved starting card-based V1 to explore demand. Built a new Kotlin companion implementation with membership/credential separation, random public numbers, explicit operator consent, programme configuration (stamps or recurring member discount) and local member/event reporting. The first increment stores data on this terminal; the broader shared-backend V1 remains incomplete. Cash, accounts/card merging, gift balances and membership printing are not implemented.

Our independent package uk.edge.move.loyalty.staging was built and used for synthetic device tests with payment components disabled. Installed POSitive 4952 checks known package identities, so today's active test uses our code inside the established com.eft.positiveloyaltydemo container, not a claimed independently whitelisted production package. Same-key update preserves old demo preferences but does not migrate/enrol their credentials. The independent review app and synthetic test runners are removed after verification.

Updated the dedicated Handheld test app to 0.4-loyalty-v1, code 4. Its one-shot checkout checkbox records customer agreement, binds a signature-protected companion context to merchant/terminal/attempt/amount/operator and expires after five minutes. Settings/data entry requires existing terminal-settings permission and a verified same-signed Move caller. A credential is not automatically enrolled when the operator skips the checkbox. POSitive completion never issues another reward; stamp credit/reward consumption waits for final structured Move amounts. Final loyalty events are retained locally and acknowledged/retried; late/missing-context and conflicting events remain unresolved rather than silently credited. Full crash/recovery acceptance remains outstanding.

Both test APKs installed in place on A920 0822879784. Existing Grubs Up/Bridgwater/loyaly pairing and 196-product/three-screen menu retained. Existing operator-authorised staff login opened Loyalty programme & data; Programme settings was available only on the permitted Move launch. Enabled the familiar £5 minimum, five stamps, 10% reward through the UI. No processor payment was dispatched by the agent. Normal Move/WebLink and other devices are unchanged.

Verification: eight policy JVM tests, eight Android ledger fixture tests and 103 Handheld JVM regressions passed, plus the six-source contract guard, companion lint and Handheld release lint. Fixture tests cover skipped opt-in, one membership for repeat use, duplicate completion, reward once, cancelled purchase, merchant isolation, mismatched amounts and a different second credential. These are isolated simulations, not POSitive end-to-end evidence. APKs/hashes/reports: artifacts/android/move-loyalty-v1-0.1. Implementation/limitations: ../move-loyalty-v1/README.md.

Next operator acceptance: skip an unknown card and confirm no membership; explicitly enrol a card and confirm one member/one stamp; repeat without ticking enrolment and confirm the same member; complete a reward cycle and inspect balances/reporting. Then prove cancellation and restart/outbox recovery. Shared backend and production distribution remain follow-on work within the broader scope.
