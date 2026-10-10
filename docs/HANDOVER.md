# Get Loyal handover — 10 October 2026

## Purpose and ownership

Kevin approved card-only V1 to explore demand. Get Loyal owns programmes, anonymous memberships, credentials, consent, stamp/reward ledger and reporting. Move and Tap should be clients of the same service. A PAX Android companion is one credential adapter; future QR/member-number adapters must not require a payment token.

Keep membership separate from credentials: one member can later have several linked cards. Recognition and programme data are merchant scoped, not automatically shared across unrelated businesses. Member numbers are references, not authority to merge or spend. Accounts, card linking and gift balances are later work.

## Actual implementation today

The Android code is ours, under `uk.edge.move.loyalty`, using Gradle scaffolding copied from the vendor demo. It is local SQLite only. The included Android README describes exact behaviour and limitations; its old Move-relative links refer to the original source location.

Working installed companion: package `com.eft.positiveloyaltydemo`, version 0.1-v1-pilot/code3, label Move Loyalty V1. `-PdemoCompatibility=true` retains the established test container identity because installed POSitive recognises it. Independent identity `uk.edge.move.loyalty.staging` has PAX components disabled pending legitimate registration and invocation proof. Do not ship the demo identity as an independent production product.

Dedicated Move client: `uk.edge.move.handheld.loyaltytest.staging`, 0.4-loyalty-v1/code4. Normal Move and WebLink unchanged. A920 developer terminal is the existing target. Grubs Up staging menu/pairing remain in Move. Existing APKs remain in Move artifacts/android/move-loyalty-v1-0.1; hashes are in the copied manifest. The independent APK there is an earlier inactive review build, not the final payment test build.

## Flow and data

Operator asks and explicitly ticks enrolment at checkout. Unknown cards without opt-in create no member. Known cards need no repeat enrolment. Move prepares a signature-protected merchant/terminal/operator/attempt/amount context with five-minute expiry. Companion hashes the merchant-scoped POSitive token, allocates a member UUID and random eight-digit public number, and records consent. Raw tokens are not persisted. Hashes remain pseudonymous identifiers.

Rules support minimum spend, stamp target, fixed/percentage rewards, enabled/paused and recurring MEMBER_DISCOUNT mode. Current terminal programme is enabled: £5 minimum, five stamps, 10% reward. After earning the target, the next qualifying use offers the reward. POSitive restarts the payment at the reduced amount and requires a second tap.

Only the card-presented phase issues a reward. Completion returns status6/no discount. Move's final structured result must balance original amount, discount and paid amount before the companion credits a stamp or consumes a reward. Duplicate event IDs are idempotent. Move retains final events in an acknowledged retry outbox. That outbox is not atomic with payment callback journalling; crash and late/expired context recovery remain incomplete.

## Evidence and unresolved questions

8 policy tests, 8 synthetic Android ledger tests, 103 Handheld JVM tests and lint passed. Management UI/programme save were verified. No live V1 opt-in/earning/redemption payment has yet been verified. Earlier corrected vendor-demo flow showed £10.80 less £1.08 = £9.72 and operator confirmed second tap; this does not prove the new V1 implementation.

Installed POSitive 1.00.22.4952 can subtract twice if rewards are returned in both card-presented and completion phases. The investigation log records this and the corrected acknowledgement. Do not reintroduce a reward at completion.

Guide: https://paxuk.gitbook.io/positive-loyalty-integration-document . POSitive supplies a card token to the companion; this is not proof that WebLink returns that token to Move. No PAR exposure, cross-terminal equality, physical-card/wallet equality or card-only identification for cash has been established. Treat phone/watch/physical credentials separately until tested. Provider registration/distribution of our independent package remains required work.

## Platform and deployment

Repo: https://github.com/edge-plus-png/get-loyal . Local folder: this checkout. Kevin created a Vercel project; no staging branch existed when handed over. No deployable web application is included, so do not claim Vercel health or deployment readiness.

Use main for production and staging for Git-driven staging deployments. Verify the actual Vercel project ID, linked repo, production branch and branch-scoped environment variables before deploying. No secrets are copied from Move. A custom domain is optional initially; a stable exact registered origin is needed for signed portal trust. A Vercel URL can serve that purpose when verified and registered. Do not substitute ephemeral deployment origins into platform trust.

Database convention: one Get Loyal module database per environment, shared across merchants with merchantId isolation. This does not mean reuse Move's operational database. Reuse platform identity, permissions, entitlement and Console trust, not a separate merchant login. Future module ID/route proposal: loyal / /loyal; confirm registry naming before implementation. Manifest, health and signed launch endpoints must meet the shared module contract.

## Next work, in order

1. Operator acceptance on current terminal: skip unknown card; opt in and earn one stamp; same credential earns again without opt-in; complete reward cycle with second tap and exactly one redemption; cancel/restart/second-card and pending final-event recovery. Record evidence without tokens.
2. Define authenticated multi-client API and tenant-scoped ledger contract: programme versions, members/credentials, consent, purchase attempts, reward reservation/confirmation/release, idempotency, reversals and audit. Authenticate device/client authority; do not trust body merchantId alone.
3. Implement shared backend and portal configuration/reporting through platform access. Prove merchant isolation and cross-terminal recognition. Introduce a recovery model for late callbacks and atomic durable confirmation.
4. Independent PAX package/signing/registration and distribution proof. Define consent withdrawal, audited adjustments/refunds, retention and member-number printing before merchant pilot.
5. Later: secure multi-card linking, customer accounts, recurring member discounts in real use, other credential adapters and separately modelled gift balances. Cash identification remains research.

Do not migrate or clear the installed local ledger as part of this handover. Do not dispatch payments automatically to prove setup.
