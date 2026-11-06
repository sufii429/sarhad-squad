# Sarhad Squad: Border War

Hold the Line.

Mission 1 prototype (v0.1.0) — a 2D top-down squad shooter for Android,
built with Kotlin + Jetpack Compose. Personal project; not on any store.

- Pick a side (Pakistan or India), then play Mission 1 — Dawn Patrol:
  clear 8 smuggler scouts and destroy 3 supply crates in the Punjab fields.
- Squad of three (HAWK, BULL, DOC) moves together with a virtual joystick,
  auto-fires at the nearest enemy; grenade, hold-position and medic revive.
- Stars and Command Points are awarded on the result screen.

Build: GitHub Actions (see build-workflow.yml — place it at
.github/workflows/build.yml in the repo). The workflow builds the debug
APK and publishes it to the fixed `build-latest` release.
