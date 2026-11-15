# Monolith

**Monolith** (`com.monolith.app`) is a native Android file manager and unified media suite built from the ground up with **Kotlin**, **Jetpack Compose (Material 3)**, and a multi-module Gradle architecture.

## Architecture

- `:app` — Application shell, `MonolithApp`, `MainActivity`, Material 3 Day/Night theme
- `:core:model` — Shared domain models (`VfsNode`, `VfsLocation`, `TransferProgress`, `FileCategory`)
- `:core:common` — MIME normalization (`MimeNormalizer`), local diagnostics (`MonolithDiagnostics`, 512 KiB rotating log, zero telemetry)
- `:core:ui` — Material 3 theme & shared UI components
- `:core:vfs` — Virtual filesystem abstraction (`VfsProvider`) and `LocalVfsProvider`
- `:core:media` — Unified `libmpv` / FFmpeg (`arm64-v8a`) JNI bridge (`is.xyz.mpv.MPVLib`) & TLS bundle (`cacert.pem`)
- `:feature:explorer` — Minimalist file explorer UI
- `:feature:viewers` — Built-in viewers (Archive, Text Editor, Image Viewer, App Manager)
- `:feature:player` — Unified `libmpv` video & audio player UI

## Build

Requires Android SDK (`compileSdk = 36`, `minSdk = 26`) and JDK 21 (auto-provisioned via Gradle Toolchains).

```bash
./gradlew :app:assembleDebug
```
