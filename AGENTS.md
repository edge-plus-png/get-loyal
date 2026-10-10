# Get Loyal agent instructions

Before implementation, read the GetEdgePlatform shared docs under `/Users/kevinsmith/Documents/Projects/GetEdgePlatform/Read me Docs/GetEdgePlatform`: README.md; docs/architecture/agent-access.md; docs/decisions/0002-use-modules-not-apps.md; docs/decisions/0006-shared-module-database-by-default.md; docs/contracts/module-contract.md; docs/contracts/environment-contract.md.

Read docs/HANDOVER.md before changing the pilot. Use Console Agent Access for merchant setup and management. No back doors, silent role impersonation or merchant-specific forks. Signed session merchant identity is authoritative. Every merchant-owned cloud record and query must be tenant scoped.

Move owns its checkout/payment integration. `reference/move-handheld` is a historical snapshot, not another maintained Move app. Do not change Move WebLink without explicit Edge permission; payment-outcome semantic changes require its two-stage approval. Do not infer approval from loyalty work.

Keep raw card tokens, PINs, secrets and terminal databases out of Git. Do not merge customers by masked PAN/last four, or treat a printed member number as authentication. Demo package compatibility is for the existing developer-terminal test only, not production distribution approval.

Keep running evidence and distinguish fixture tests from operator-observed PAX transactions. No production readiness claim until acceptance gates are proven.
