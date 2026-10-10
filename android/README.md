# Move Loyalty V1 — first on-terminal pilot build

This is an implementation increment, not the complete multi-terminal/cloud release. Normal Move and WebLink are unchanged. The dedicated Handheld experiment is version 0.4-loyalty-v1. New companion source is our own Kotlin implementation under uk.edge.move.loyalty; Gradle scaffolding came from the downloaded demo.

## Today's test identity

`-PdemoCompatibility=true` builds package com.eft.positiveloyaltydemo, versionCode 3, versionName 0.1-v1-pilot, label Move Loyalty V1. This uses the already-established local demo host identity/signature on the A920. It is not a claim that a new Edge package is independently whitelisted or production distributable. The prior demo preferences are retained by update-in-place but not enrolled/migrated into the new ledger. Rollback APK remains in artifacts/android/positive-loyalty-demo-move-test-1.1.

Without that property, the independent build is uk.edge.move.loyalty.staging and its POSitive components are disabled by default to avoid competing with the existing demo. It requires legitimate registration/configuration and separate invocation proof before enabling. Do not copy the demo identity into a production release.

## Included

- Operator explicitly checks “Customer agreed · enrol this card in loyalty” at checkout. Unchecked unknown credentials create no membership. The choice resets after dispatch or leaving checkout; existing enrolled credentials work without enrolment each time.
- Move prepares a merchant/terminal/attempt/amount-bound context through a signature-protected explicit receiver before sending a payment. A missing/rejected context does not dispatch PAX. Context has a five-minute deadline and binds the first credential; a second callback does not request another discount.
- Anonymous internal membership UUID and separate random eight-digit public membership number. Credential records are merchant-scoped SHA-256 references; raw POSitive tokens are not persisted or displayed. The data structure supports multiple credentials per membership later; no linking UI exists yet. Hash references remain pseudonymous identifiers.
- Settings: enabled/paused, minimum purchase, stamp target, fixed or percentage reward, STAMPS or MEMBER_DISCOUNT. GBP/integer minor units. Fixed discount leaves at least 1p. Rule version is checked during payment; active context prevents settings changes. Existing balances are retained across rule edits.
- Local SQLite member/credential/event ledger with explicit enrolment wording, operator, terminal, attempt and timestamp. Data is stored only on this terminal for now; no cloud API exists in this increment.
- Dashboard shows memberships and balances, total stamps earned, rewards redeemed and discounts given. Only the current connected merchant is reported. Settings are available from Move device settings only with terminalSettingsEnabled and a same-signed verified Move Activity caller. Launcher view is read-only.
- POSitive completion returns status=6 with no discount. Actual stamp/reward ledger mutation waits for Move's final structured status and balanced paid/discount amounts. Duplicate event IDs are rejected by the ledger; a cancelled/declined result does not credit a stamp or consume a reward.

## Use on A920

Open Move Loyalty Staging, sign in using the existing configured staff PIN, then menu → device settings → Loyalty programme & data. Set the programme and enable it. At checkout ask the customer, check enrolment only with agreement, then take a card payment. Skip leaves unknown credentials unregistered. Open the same dashboard to inspect data. Membership numbers are references, not merge/spend authorisation.

No automated test initiates a processor payment. Synthetic device tests use loyalty-v1-fixture.db and remove it afterward. Actual POSitive invocation/opt-in earning/redemption must be demonstrated by the operator after deployment; compilation and direct ledger tests do not prove it.

## Build

Companion: `sh ./gradlew -PdemoCompatibility=true :positive-loyalty-demo:testDebugUnitTest :positive-loyalty-demo:assembleDebug :positive-loyalty-demo:lintDebug`. Device fixture runner: assembleDebugAndroidTest with the same property, then install APKs and run com.eft.positiveloyaltydemo.test/androidx.test.runner.AndroidJUnitRunner. Independent package uses uk.edge.move.loyalty.staging.test instead.

Handheld from ../loyalty-test/handheld: `./gradlew assembleRelease`; required JVM/source-contract/lint gates remain in place. Shared Counter source is unchanged. APKs/evidence: artifacts/android/move-loyalty-v1-0.1.

## Remaining V1 work before merchant launch

- Move now retains final loyalty events in a local acknowledged outbox and retries them on foreground rendering. This is not atomic with the payment SQLite commit; recovery from a crash between callback journalling and outbox creation still needs proof. Unknown/expired sessions and conflicting results remain pending rather than silently credited. Do not describe this as end-to-end exactly-once.
- Persist/recover missing/late callbacks and expired sessions, and prove device restart/cancellation/second-card behaviour through POSitive. Current isolated ledger tests are not hardware proof.
- Shared module backend, cross-terminal token proof, merchant portal configuration/reporting and release signing/provider distribution.
- Membership number printing/receipt association, consent withdrawal and audited adjustments/refund policy. The number is allocated/displayed now; printing is not yet implemented.
- Secure card linking/account merging, wallets, cash identification and gift balances are out of this increment. Do not merge by masked PAN or last four.

See docs/proposals/move-card-linked-loyalty-build-scope.md for the broader agreed scope and implementation/loyalty-test/LOYALTY-TEST-LOG.md for evidence. This first local test increment does not supersede the full pilot acceptance gates.
