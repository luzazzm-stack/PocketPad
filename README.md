# PocketPad

Turn your Android phone into a wireless gamepad for Windows.

Two parts:

- **`android/`** — PocketPad app (Kotlin + Jetpack Compose): the touchscreen gamepad.
- **`windows/`** — PocketPad Link (.NET 8): tray app that receives input and creates a
  virtual Xbox 360 controller via the ViGEm driver, so every game just works.

Transports: Wi-Fi / laptop hotspot (UDP), USB (adb reverse), Bluetooth (RFCOMM).

Wire format: [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md) — the single source of truth.

## Dev quickstart

- Windows side: `cd windows && dotnet build` (needs .NET 8 SDK + ViGEmBus driver)
- Android side: `cd android && ./gradlew assembleDebug` (needs JDK 17 + Android SDK)
- Tests: `dotnet test` / `./gradlew test`
