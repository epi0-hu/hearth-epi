# Hearth

A fast, native Android roleplay chat client compatible with SillyTavern character cards,
lorebooks, chats and presets. Standalone: no SillyTavern server needed — it talks to
LLM APIs directly from your phone.

## Features

- **Character cards** — import PNG/JSON cards (V1, V2, V3), edit them, export back to PNG.
- **Chat** — streaming replies, swipes (arrows or swipe gesture), regenerate, continue,
  impersonate, edit/delete messages, multiple chats per character, collapsible "thinking".
- **Presets** — samplers (temp, top-p/k, min-p, penalties), context size, main prompt,
  post-history instructions, prefill, instruct templates for text completion.
  Imports SillyTavern chat-completion and text-completion preset JSON.
- **Lorebooks / World Info** — SillyTavern semantics: keys and `/regex/` keys, secondary
  keys with AND/NOT logic, constants, scan depth, recursion, probability, token budget,
  insertion positions (before/after character, at depth, around examples). Embedded card
  lorebooks work automatically; link a lorebook per character or mark it global.
- **APIs** — OpenAI-compatible chat completion (OpenRouter, OpenAI, DeepSeek, Mistral,
  Ollama…), Anthropic Claude, and text completion (KoboldCpp, llama.cpp, vLLM, TabbyAPI).
- **Your data stays compatible** — chats are SillyTavern JSONL, lorebooks are ST World Info JSON.

## Why it's fast

- Native Kotlin + Jetpack Compose, release builds shrunk and optimized with R8. No WebView.
- Zero third-party runtime libraries beyond AndroidX: in-house JSON, HTTP via the platform
  client. Small APK, quick cold start.
- Character list reads a tiny index file instead of parsing every card; avatars are
  pre-shrunk to 192px WebP thumbnails and LRU-cached.
- Streaming tokens are buffered off the main thread and drawn at ~30 fps; only the message
  being streamed recomposes, not the whole list.
- The message formatter (`*actions*`, `"dialogue"`, `**bold**`, code) is a single linear
  pass with no regex, cheap enough to run every frame.
- Chats are saved with debounced, atomic writes.

## Getting the APK (no computer needed)

1. Create a new GitHub repository and upload the contents of this folder
   (keep the folder structure, including `.github/`).
2. GitHub Actions builds automatically. When the "Build APK" run finishes, open the
   repository's **Releases** → **latest** and download `hearth.apk` on your phone.
3. Allow installs from your browser when Android asks.

Every push rebuilds and replaces the `latest` release. Builds are signed with the sideload
key in `keystore/`, so new versions install over old ones.

## Building locally

Requires JDK 17 and the Android SDK.

```
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest    # core engine tests
```

## Project layout

```
app/src/main/java/app/hearth/
  core/       Pure Kotlin engine (no Android deps): JSON, cards, macros,
              world info, prompt builder, API client, chat files
  data/       File storage, thumbnails
  ui/         Compose screens
  MainViewModel.kt, ChatSession.kt
app/src/test/ Unit tests for the core engine
```

## Notes

- API keys are stored in the app's private storage (not encrypted at rest).
- Cleartext HTTP is allowed so LAN backends work; use HTTPS for remote APIs.
- Token counts are estimated (characters ÷ 3.5, adjustable in Settings → Display).
