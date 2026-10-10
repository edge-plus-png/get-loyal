# Get Loyal

GetEdgePlatform loyalty module for Move, Tap and future integrations.

Start with [the handover](docs/HANDOVER.md). The current working implementation is an Android, single-terminal pilot in `android/`. No cloud service or web UI exists yet. Vercel has been created by Kevin; its project settings and Git connection have not been verified in this handover.

## Repository layout

- `android/`: our Kotlin loyalty companion, copied from the working Move V1 increment.
- `reference/move-handheld/`: source-only snapshot of the dedicated Move test app; reference only. Move remains the integration owner. Do not maintain a second Move fork here.
- `docs/reference/`: running investigation log, agreed scope and test manifest.
- `docs/HANDOVER.md`: status, architecture, acceptance gates and next work.

No APKs, local credentials, terminal databases, signing keys or Gradle caches are transferred. Existing installed test apps and Move checkout are unchanged.
