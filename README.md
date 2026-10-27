# Hearth

A fast, native Android LLM Frontend

## Features

- **Character cards** — import PNG/JSON cards (V1, V2, V3), edit them, export back to PNG.
- **Chat** — streaming replies, swipes (arrows or swipe gesture), regenerate, continue,
  impersonate, edit/delete messages, multiple chats per character, collapsible "thinking".
- **Presets with prompt modules** — SillyTavern prompt-manager presets (Sola, etc.) import
  with all their modules: switch each on/off, reorder, edit role/text, place relative or
  in-chat at a depth, add your own. Samplers, context size, prefill, instruct templates.
  `{{setvar}}`/`{{getvar}}` and the usual macros work.
- **Regex scripts** — SillyTavern-compatible: user input / AI output / world info / reasoning,
  display-only or prompt-only, depth limits, trim strings, macros in patterns, live tester.
  Import single scripts, exported lists, or presets/cards that carry them.
- **HTML in messages** — status panels, styled boxes, `<details>`, tables and images render
  in place (WebView only for messages that contain HTML; scripts off unless you allow them).
- **Group chats** — natural (mentions + talkativeness), list or manual turn order, mute
  members, tap a member to make them speak, optional joined cards.
- **Branching** — start a new chat from any message.
- **Backup & restore** — one zip with everything; also imports a zipped SillyTavern data
  folder (characters, chats, group chats, lorebooks, presets, regex).
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

## Notes

- API keys are stored in the app's private storage (not encrypted at rest).
- Cleartext HTTP is allowed so LAN backends work; use HTTPS for remote APIs.
- Token counts are estimated (characters ÷ 3.5, adjustable in Settings → Display).
