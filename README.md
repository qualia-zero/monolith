# Monolith

**Monolith** (`com.monolith.app`) is a native Android file manager, unified media suite, and local-first AI workspace built with **Kotlin**, **Jetpack Compose (Material 3)**, and a multi-module Gradle architecture.

> **Language Note:** The app UI is currently available in **German** only.

## Highlights

- **File Explorer & Virtual Filesystem (`:feature:explorer`, `:core:vfs`):**
  - Fast local storage, SD card / USB OTG (with product-name labels), and Google Drive integration (multi-account support with drag-to-reorder account chips).
  - Per-folder Drive directory caching with tactile pull-to-refresh invalidation.
  - Background `FileTransferService` with byte-integrity checks, live transfer progress overlay, system notifications, persistent Google Drive offline downloads (files & folders), and instant Android share-sheet integration.
- **Archive Manager (`:feature:explorer` — `ArchiveToolScreen`, `ArchiveEngine`):**
  - Pack, unpack, browse, and manage **ZIP**, **7z**, **RAR**, **TAR.GZ**, and **TAR.XZ** archives directly from the Monolith menu or Explorer.
  - **Encryption:** AES-256 (ZIP & 7z) and ZipCrypto (ZIP) support with password prompts and encrypted entry indicators.
  - **Connected & Multi-Volume Archives:** Create, auto-group, browse, extract, and merge split volumes (`.zip`/`.z01`, `.7z.001`, `.part1.rar`, `.r00`, `.tar.gz.001`) plus solid compression for 7z/TAR.
  - **In-Archive Management:** Browse folders inside archives, selectively extract, add/rename/delete entries, convert formats, and verify integrity.
- **Storage Analyzer & Low-Token AI (`:feature:explorer` — `StorageAnalysisScreen`, `StorageAnalyzer`, `StorageAiAdvisor`):**
  - Fast local/SD/USB volume scanning with capacity bar, segmented category breakdown, duplicate detection (size + partial SHA-256), and batch delete/archive actions.
  - **Disjoint Folder View:** Antichain tree-partitioning unwraps container/wrapper paths so parent folders (`Überordner`) and their subfolders (`Unterordner`) are never redundantly listed together.
  - **Low-Token Gemini AI Advisor:** Pre-aggregates storage stats into a ~350–500 token compact plaintext digest and queries Gemini in a single tool-free turn for cleanup, folder explanation, and archiving insights.
- **Unified Media Player (`:feature:player`, `:core:media`):**
  - Powered by `libmpv` / FFmpeg (`arm64-v8a`) with hardware decoding, track/subtitle selection, speed control, optional double-tap gesture controls, 128 MiB stream caching, picture-in-picture, background playback, and `MediaPlaybackService` system controls with 480p `ARGB_8888` thumbnails.
- **Built-in Viewers (`:feature:viewers`):**
  - Text editor and image viewer, plus "Open With" system app discovery and default-app associations.
- **Integrated Gemini AI Chat (`:feature:explorer`):**
  - Streaming responses, collapsible thinking & tool-call traces, Markdown rendering, multi-API-key management, customizable system prompt with quick-copy tool blocks, and read-only explorer tool calling (`list_directory`, `read_text_file`, `get_file_info`, `search_files`, `analyze_storage_summary`, `list_archive_contents`).
- **Design & Tactile Polish (`:core:ui`):**
  - **Obsidian** (textured stone aesthetic, cut-corner geometry, unified `MonolithDialog` & `MonolithStarMenu`) and **Phosphor** themes with Day/Night modes and granular per-action haptic feedback settings.

## Architecture

- `:app` — Application shell, `MonolithApp`, `MainActivity`, Material 3 theme host
- `:core:model` — Shared domain models (`VfsNode`, `VfsLocation`, `TransferProgress`, `FileCategory`, `AppSettings`)
- `:core:common` — MIME normalization (`MimeNormalizer`), settings persistence (`AppSettingsStore`), local rotating diagnostics (`MonolithDiagnostics`, zero telemetry)
- `:core:ui` — Material 3 themes (`Obsidian`, `Phosphor`), `MonolithDialog`, `MonolithStarMenu`, `MonolithSwitch`, and `MonolithHaptics`
- `:core:vfs` — Virtual filesystem abstraction (`VfsProvider`), `LocalVfsProvider`, `SafVfsProvider`, and `GoogleDriveVfsProvider`
- `:core:media` — `libmpv` / FFmpeg (`arm64-v8a`) JNI bridge (`is.xyz.mpv.MPVLib`), `MpvEngine`, `MediaPlaybackService`, and `ThumbnailEngine`
- `:feature:explorer` — File explorer UI, archive manager, storage analyzer, settings & haptics customization, and Gemini AI chat
- `:feature:viewers` — Built-in viewers (Text Editor, Image Viewer)
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
- **zip4j, Apache Commons Compress, XZ for Java, junrar:** Used in `:feature:explorer` for ZIP, 7z, RAR, and TAR archive processing and encryption.

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)** — see [LICENSE](LICENSE) for details.
