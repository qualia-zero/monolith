# Monolith

**Monolith** (`com.monolith.app`) is a native Android file manager, unified media suite, and local-first AI workspace built with **Kotlin**, **Jetpack Compose (Material 3)**, and a multi-module Gradle architecture.

> **Language Note:** The app UI is currently available in **German** only.

## Highlights

- **File Explorer & Virtual Filesystem (`:feature:explorer`, `:core:vfs`):**
  - Fast local storage, SD card / USB OTG (with product-name labels), and Google Drive integration (multi-account support with drag-to-reorder account chips).
  - Per-folder Drive directory caching with tactile pull-to-refresh invalidation.
  - Background `FileTransferService` with byte-integrity checks, live transfer progress overlay, system notifications, persistent Google Drive offline downloads (files & folders), and instant Android share-sheet integration.
- **Unified Media Player (`:feature:player`, `:core:media`):**
  - Powered by `libmpv` / FFmpeg (`arm64-v8a`) with hardware decoding, track/subtitle selection, speed control, optional double-tap gesture controls, 128 MiB stream caching, picture-in-picture, background playback, and `MediaPlaybackService` system controls with 480p `ARGB_8888` thumbnails.
- **Built-in Viewers (`:feature:viewers`):**
  - Archive browser, text editor, image viewer, and app manager, plus "Open With" system app discovery.
- **Integrated Gemini AI Chat (`:feature:explorer`):**
  - Streaming responses, collapsible thinking & tool-call traces, Markdown rendering, multi-API-key management, customizable system prompt with quick-copy tool blocks, and read-only explorer tool calling (`list_directory`, `read_text_file`, `get_file_info`, `search_files`).
- **Design & Tactile Polish (`:core:ui`):**
  - **Obsidian** (textured stone aesthetic, cut-corner geometry, unified `MonolithDialog` & `MonolithStarMenu`) and **Phosphor** themes with Day/Night modes and granular per-action haptic feedback settings.

## Architecture

- `:app` — Application shell, `MonolithApp`, `MainActivity`, Material 3 theme host
- `:core:model` — Shared domain models (`VfsNode`, `VfsLocation`, `TransferProgress`, `FileCategory`, `AppSettings`)
- `:core:common` — MIME normalization (`MimeNormalizer`), settings persistence (`AppSettingsStore`), local rotating diagnostics (`MonolithDiagnostics`, zero telemetry)
- `:core:ui` — Material 3 themes (`Obsidian`, `Phosphor`), `MonolithDialog`, `MonolithStarMenu`, `MonolithSwitch`, and `MonolithHaptics`
- `:core:vfs` — Virtual filesystem abstraction (`VfsProvider`), `LocalVfsProvider`, `SafVfsProvider`, and `GoogleDriveVfsProvider`
- `:core:media` — `libmpv` / FFmpeg (`arm64-v8a`) JNI bridge (`is.xyz.mpv.MPVLib`), `MpvEngine`, `MediaPlaybackService`, and `ThumbnailEngine`
- `:feature:explorer` — File explorer UI, settings & haptics customization, and Gemini AI chat
- `:feature:viewers` — Built-in viewers (Archive, Text Editor, Image Viewer, App Manager)
- `:feature:player` — `libmpv` video & audio player UI

## Build

Requires Android SDK (`compileSdk = 36`, `minSdk = 26`, `arm64-v8a`) and JDK 21 (auto-provisioned via Gradle Toolchains).

```bash
./gradlew :app:assembleDebug
```

To build a release APK:

```bash
./gradlew :app:assembleRelease
```

## Third-Party Components

- **mpv / FFmpeg (`libmpv`, `libav*`, `libsw*`):** Embedded in `:core:media` (`is.xyz.mpv.MPVLib`) for media decoding and playback.

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)** — see [LICENSE](LICENSE) for details.
