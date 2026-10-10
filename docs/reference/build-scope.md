# Move card-linked loyalty — proposed build scope

Status: scope only; no production implementation or payment-flow change authorised by this document. Prepared from the corrected companion prototype, installed POSitive 4952 inspection and Kevin's demo observations. Keep this proposal alongside implementation/loyalty-test/LOYALTY-TEST-LOG.md.

## Product and architecture

Build a Move-managed loyalty programme with a separately signed Android companion on PAX terminals. Customers can earn/redeem with a payment credential, without entering a phone number or scanning for each purchase. Credentials initially represent anonymous loyalty memberships, not verified people. Keep business rules/backend in a shared Edge module, with Move providing merchant management and sale reporting. Use existing merchant identity, device assignment and Console Agent Access. Merchant variation is configuration, never merchant-specific APK code.

Move sends the basket to POSitive through the existing payment integration. POSitive provides its loyalty token/events to the companion. The companion asks the shared service for eligibility/reward and shows progress. POSitive restarts a discounted payment and requires another tap. Completion confirms redemption; it must not request the discount again. Move records the resulting paid amount and discount. Move does not currently receive the loyalty token in its ordinary final payment result; linking a sale to a membership needs an explicit correlation contract, not a guessed join by amount/time.

A separate APK is the starting architecture, not a permanent product limitation. Combining the companion components into Move may be possible later, but requires validating POSitive package selection, signing/registration, intent handling and Activity lifecycle. Do not reuse com.eft.positiveloyaltydemo as our production application identity. A new Edge-owned package, release key and PAX/MAXSTORE registration/whitelisting are deployment work.

## Evidence and limits

The guide describes a token generated per unique PAN and loyalty invocation for standalone, App2App and WebLink payments. That does not establish token portability across terminals, acquirers or reinstalls. It also does not establish exposure of PAR through ordinary payment results. Local cached guide: /private/tmp/pax-loyalty-guide-readable.txt. Public source: https://paxuk.gitbook.io/positive-loyalty-integration-document (live fetch unavailable during scoping; cached copy and downloaded source reviewed).

The downloaded code awards a stamp at amounts >= £5 and offers 10% on the next qualifying payment after five stamps. The guide's £1 example is different. Our corrected completion avoids a second REWARD on installed POSitive 4952. Kevin's photograph C10EB37E shows basket £10.80, discount £1.08, paid/sale £9.72 and completed Move screen; Kevin confirmed another tap was required. Stamp reset, cross-terminal behaviour, cancellation/retry and upload are not yet established by that photograph. The installed prototype is test software.

## Phase 0 — integration proof before cloud redemption

- Verify reward consumption/reset, decline, cancellation at the second tap, timeout, duplicate callback and restart behaviour on the installed version.
- Compare equality of the same credential token on two terminals in one merchant programme, after restart/reinstall and using phone/watch wallets. Never match by masked PAN/last four or publish raw token values in test logs.
- Inspect ordinary and additional post-transaction data for stable transaction IDs, outcome, terminal identity and paid/discount amounts. Prove how pre-card, redemption and completion events correlate, including two concurrent payments on different terminals.
- Prove an independently signed Edge package is invoked. Confirm whether selection is package-only or also signature/config dependent. Verify deployment through the actual provider; a developer-terminal sideload is not production distribution proof.
- Define allowed loyalty-enabled sale sources. Companion invocation can occur outside Move, so Move-only membership earning cannot rely on token+amount alone.

Go/no-go: if stable cross-terminal token equality or trustworthy event identity is unavailable, restrict pilot scope to the proven domain and explicitly document it. Do not promise global recognition or exactly-once cloud rewards without those proofs.

## Phase 1 — first merchant pilot

One active programme per merchant, GBP card sales only. Merchant can configure qualifying minimum, stamp target, reward fixed amount OR percentage with a cap, participating locations and enable/disable. Ship a clear default: five purchases of at least £5, reward on the next eligible purchase. Keep the tested 10% rule initially or configure £1; these are distinct policies. Version every rule; define treatment of already-earned balances when rules change. Do not erase earned rewards on edits.

Anonymous memberships keyed by an internal credential reference. One credential per membership initially; no customer app, QR or phone input needed at checkout. Same-programme balances shared across participating terminals only after token portability is proven. No automatic merging across merchants.

Android: authenticated terminal setup, merchant branding, visible stamp progress and exact discount before returning to POSitive, bounded lookup time, durable event queue, cached programme rules and clear offline behaviour. No raw card data/PAN collection or token display. No reward response on completion. The pre-payment reward operation reserves the entitlement; only validated authorised redemption consumes it. Persist enough state to resume after Activity/process/device restart.

Backend: enrol/lookup anonymous membership, evaluate policy, reserve reward atomically, record qualifying completion, confirm/release redemption and query balance. Use durable idempotency keys based on proven source event identity. Reject conflicting duplicates and wrong-merchant/device events. Two terminals cannot spend the same reward. No direct merchant impersonation or embedded privileged service credentials.

Offline first release: payment continues without cloud reward if eligibility cannot be established promptly; queue identifiable qualifying events for later credit. Cloud rewards require online reservation; never offer the same cached reward independently on multiple terminals. Decide how the customer sees deferred credit. The guide's five-minute no-confirmation rule preserves entitlement, but it is not proof of processor cancellation; use a documented reservation/late-confirmation policy to avoid double redemption when confirmation arrives late.

Move integration: paid sale/discount/tender/item tax allocation remains consistent. Add programme/reward/event references only through a verified association. Manage programme and show earned/redeemed/outstanding counts, discounts given, exceptions and an operator-audited adjustment tool with role permissions. Keep loyalty ledger separate from the payment ledger; recovering loyalty must not fabricate approval/cancellation or erase financial records. Refund earning/reward reversal policy must be defined and tested for the pilot, even if initial refunds use an audited manual loyalty adjustment.

## Conceptual data and endpoints

Entities: Programme + immutable RuleVersion; anonymous Membership; CredentialReference; LoyaltyEvent ledger; RewardEntitlement; RedemptionReservation; DeviceRegistration; OperatorAdjustment. Every record is merchant/programme scoped; credentials may include a provider/token-domain discriminator once proven. Store a keyed pseudonymous token reference for lookup; hashing does not make an identifier automatically anonymous. Retention/deletion and later customer linking are explicit features. No PAN, CVV or token leakage through logs/receipts.

Conceptual operations: device configuration, credential lookup, reward reserve, qualifying-event ingestion, redemption confirm/release, balance/history and permissioned adjustments. Final URLs/schema fit existing module contracts after repository design review; this scope does not introduce a back door or new privileged access contract.

## Phase 2 — customer and programme possibilities

| Capability | Feasibility / extra dependency |
| --- | --- |
| Multiple cards per member | Many credentials can point to one membership after customer-controlled linking. Present/prove each credential; never merge by last four. |
| Physical card + phone/watch | Treat as separate credentials until linked. Wallet recognition must be tested; PAR is not proven exposed. |
| Points/spend tiers | Backend policy extension; define rounding, refunds and liability reporting. |
| Stamp rewards/fixed or percentage discount | Natural first release; keep qualifying and redemption rules distinct. |
| Free coffee/item, category-specific offers | Requires basket/item data, entitlement validation and item/tax allocation in Move. Token+transaction amount alone is insufficient. |
| Customer wallet page / optional registration | View balance and link credentials; registration optional for anonymous programme. |
| Multi-location merchant programme | Shared ledger plus proven token portability and concurrent reservation. |
| Network across different merchants | Explicit programme governance, token-domain proof and reward funding/settlement; separate project. |
| Loyalty with cash | Needs alternative credential presentation/customer lookup; payment-card callbacks cannot identify a cash customer. |
| Other acquirers/terminal families | Separate adapter and hardware acceptance; not established by this PAX/Elavon demo. |
| WebLink checkout | PAX guide supports a companion route, but final discount/result semantics and Move WebLink mapping need independent verification and protected approval. |

Apple states Apple Pay uses a device-specific account number, distinct from the primary number. Thus matching plastic and wallet via a PAN-derived loyalty token is not assured. Source: https://support.apple.com/en-sg/guide/security/sec0f005981a/web. Google Pay and replacement-wallet/card behaviour need direct tests; no shared-identity claim is made.

## Acceptance and release gates

One qualifying authorised sale yields one stamp despite repeat delivery; declines/cancellations yield none. One reward yields one discount despite second tap/retries. A failed redemption preserves entitlement. Successful redemption consumes exactly once. Lost/late callbacks, app restart and concurrent terminals cannot silently lose or double spend rewards. Final Move amounts match the discounted basket; upload/reporting agrees. Merchant/device isolation, permissions and audited adjustments are tested. No sensitive identifiers appear in operator screens or logs. Independent package invocation and stable release-signing upgrades are proven. Explicit production release approval follows pilot evidence.

Any changes to WebLink contract/blueprint/runtime/fixtures/tests follow the existing explicit approval rule. Payment-outcome semantic changes require the two-stage approval described in AGENTS.md. This scoping exercise edits neither integration.

## Indicative work and sequencing

Planning ranges for one experienced engineer using existing Move/Edge foundations, not a delivery commitment: integration proof 2–5 working days; shared backend/policy/ledger 5–8; companion 4–7; merchant management and Move association/reporting 4–7; recovery/hardware/pilot verification 5–8. Approximately 20–35 engineering days, plus provider registration/distribution lead time. Tests run throughout. Identity/correlation gaps can change both scope and estimate. A local branded demo is smaller, but is not shared or production loyalty.

Recommended first milestone: independently branded/registered companion, configurable five-stamp programme, shared balances on two terminals, one fixed or percentage reward, visible progress and verified decline/cancel/retry handling. Defer customer accounts, credential linking, free-item rewards and cross-merchant programmes.

## Implementation checkpoint

Kevin subsequently authorised starting card-based V1. The first single-terminal test increment is implemented under implementation/move-loyalty-v1, with the Handheld bridge in implementation/loyalty-test/handheld. See its README and running log for the exact installed test identities and validation. It does not complete the shared-backend, membership-printing or production-release scope above.
