# Monolith

**Monolith** (`com.monolith.app`) is a native Android file manager, unified media suite, archive toolset, media downloader, and local-first AI workspace built with **Kotlin**, **Jetpack Compose (Material 3)**, and a multi-module Gradle architecture.

> **Language Note:** The app UI is currently available in **German** only.

## Highlights

- **File Explorer & Virtual Filesystem (`:feature:explorer`, `:core:vfs`):**
  - Fast local storage, SD card / USB OTG (with product-name labels), and Google Drive integration (multi-account support with drag-to-reorder account chips).
  - Auto-scrolling chip breadcrumbs (`Intern` / volume / Drive account -> subfolder segments), inline directory search, and unified `FileRow` & `LocalPathPickerDialog` across Explorer, Archive Manager, and Share-In flows.
  - Per-folder Drive directory caching with pull-to-refresh invalidation.
  - Background `FileTransferService` with byte-integrity checks, live transfer progress overlay (`TransferProgressDialog`), system notifications, persistent Google Drive offline downloads (files & folders), and incoming `ACTION_VIEW` / `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent handling (open directly or save to local storage / Google Drive).
- **Archive Manager (`:feature:explorer` — `ArchiveToolScreen`, `ArchiveEngine`):**
  - 2-tab **Extract / Pack** (`Entpacken` / `Packen`) workflow supporting **ZIP**, **7z**, **RAR**, **TAR.GZ**, and **TAR.XZ** archives with background archive discovery across internal and external storage.
  - **Encryption:** AES-256 (ZIP & 7z), ZipCrypto (ZIP), and 7z filename/header encryption (`encryptFilenames`) with password prompts and encrypted entry indicators.
  - **Connected & Multi-Volume Archives:** Create, auto-group, browse, extract, and merge split volumes (`.zip`/`.z01`, `.7z.001`, `.part1.rar`, `.r00`, `.tar.gz.001`).
  - **Background Service & Google Drive Support:** Pack and extract operations run through `FileTransferService` with `TransferProgressDialog`, supporting Google Drive source files and destination folders.
  - **In-Archive Management:** Browse folders inside archives, preview entries in built-in viewers, selectively extract, add/rename/delete entries, convert formats, and verify integrity.
- **App Manager (`:feature:explorer` — `AppManagerScreen`, `AppManagerRepository`):**
  - Filterable app list (`Installiert`, `Alle Apps`, `System`) with sorting (`Name`, `Größe`, `Cache`, `Aktualisiert`), inline search, and optional `StorageStatsManager` (`PACKAGE_USAGE_STATS`) size breakdown.
  - Single & batch APK / Split-APK export, sequential uninstall queue, cache cleanup, and permissions/notifications audit reports.
- **Storage Analyzer (`:feature:explorer` — `StorageAnalysisScreen`, `StorageAnalyzer`):**
  - Fast local/SD/USB volume scanning with compact capacity bar, category breakdown, duplicate detection (size + partial SHA-256), and multi-select delete/archive actions.
  - **Hierarchical Folder View:** Standard Explorer-style folder navigation with per-folder proportional percentage bars showing each subfolder and file's share of its parent directory, updating instantly on drill-down.
- **Media Downloader (`:feature:explorer` — `DownloaderScreen`, `MediaDownloadService`, `DownloaderEngine`):**
  - Powered by `yt-dlp` (defaulting to the **Nightly** update channel) and **FFmpeg** via `youtubedl-android`.
  - Preconfigured with smart VP9/H.264 video probing and M4A audio presets, SponsorBlock removal (`sponsor,selfpromo`), randomized sleep intervals (`3–12s`), metadata & chapter embedding, audio thumbnail embedding, and `--no-playlist` by default.
  - Modularly configurable via the top-right 3-dot menu (`Format & Qualität`, `SponsorBlock`, `Einbettung`, `Wartezeit`, `Zielordner & Name`, `yt-dlp Engine`), running sequentially in a foreground service with live progress and system notifications.
- **Unified Media Player (`:feature:player`, `:core:media`):**
  - Powered by `libmpv` / FFmpeg (`arm64-v8a`) with hardware decoding, track/subtitle selection, speed control, optional double-tap gesture controls, 128 MiB stream caching, picture-in-picture, background playback, and `MediaPlaybackService` system controls with 480p `ARGB_8888` thumbnails.
- **Built-in Viewers (`:feature:viewers`):**
  - Text editor and image viewer, plus "Open With" system app discovery and default-app associations.
- **Integrated Gemini AI Chat (`:feature:explorer`):**
  - Selectable Gemini models (`gemini-3.5-flash-lite`, `gemini-3.8-flash`, `gemini-4-hydrogen`, `gemini-4-neon`) with configurable thinking levels, streaming responses, collapsible thinking & tool-call traces, Markdown rendering, multi-API-key management, and customizable two-section base system prompt (`# Rolle & Verhalten` / `# Umgebung & Werkzeuge`).
  - Built-in read-only explorer tools (`list_directory`, `read_text_file`, `get_file_info`, `search_files`, `list_archive_contents`), plus opt-in experimental write/edit tools (`write_text_file`, `edit_text_file`, `create_directory`, `move_or_rename_path`, `delete_path`) and gated Android terminal access (`run_terminal_command` via `/system/bin/sh`).
- **Design & Tactile Polish (`:core:ui`):**
  - **Obsidian** (textured stone aesthetic, cut-corner geometry, unified `MonolithDialog` & `MonolithStarMenu`) and **Phosphor** themes with Day/Night modes and granular per-action haptic feedback (`Ordner öffnen`, `Monolith-Menü öffnen`, `Gedrückthalten`, `Schalter umlegen`, `Pfad-Navigation`, `3-Punkte-Menü öffnen`, `Datei öffnen`, `Aktualisieren`).

## Architecture

- `:app` — Application shell, `MonolithApp`, `MainActivity`, `IncomingIntentHandler`, `OpenWithDialog`, `TransferProgressDialog`, Material 3 theme host
- `:core:model` — Shared domain models (`VfsNode`, `VfsLocation`, `TransferProgress`, `FileCategory`, `AppSettings`)
- `:core:common` — MIME normalization (`MimeNormalizer`), settings persistence (`AppSettingsStore`), local rotating diagnostics (`MonolithDiagnostics`, zero telemetry)
- `:core:ui` — Material 3 themes (`Obsidian`, `Phosphor`), `MonolithDialog`, `MonolithStarMenu`, `MonolithSwitch`, `MonolithRefreshIndicator`, and `MonolithHaptics`
- `:core:vfs` — Virtual filesystem abstraction (`VfsProvider`), `LocalVfsProvider`, `GoogleDriveVfsProvider`, `GoogleDriveAuthManager`, and `FileTransferService`
- `:core:media` — `libmpv` / FFmpeg (`arm64-v8a`) JNI bridge (`is.xyz.mpv.MPVLib`), `MpvEngine`, `MediaPlaybackService`, and `ThumbnailEngine`
- `:feature:explorer` — File explorer UI, archive manager, app manager, hierarchical storage analyzer, `yt-dlp` media downloader, Gemini AI chat, and settings & haptics customization
- `:feature:viewers` — Built-in viewers (Text Editor, Image Viewer)
- `:feature:player` — `libmpv` video & audio player UI

## Build

Requires Android SDK (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`, `arm64-v8a`) and JDK 21 (auto-provisioned via Gradle Toolchains).

```bash
./gradlew :app:assembleDebug
```

To build a release APK:

```bash
./gradlew :app:assembleRelease
```

## Third-Party Components

- **mpv / FFmpeg (`libmpv`, `libav*`, `libsw*`):** Embedded in `:core:media` (`is.xyz.mpv.MPVLib`) for media decoding and playback.
- **youtubedl-android / yt-dlp / FFmpeg:** Used in `:feature:explorer` for media downloading, SponsorBlock filtering, and container/metadata post-processing.
- **zip4j, Apache Commons Compress, XZ for Java, junrar:** Used in `:feature:explorer` for ZIP, 7z, RAR, and TAR archive processing and encryption.

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)** — see [LICENSE](LICENSE) for details.
