# Changelog

All notable changes to the Candela JetBrains plugin are documented here.

## v0.2.0 — 2026-08-09

### Smart Replace Selection

- **Replace Selection button**: Code blocks in LLM responses now show "⇄ Replace Selection" when triggered from an editor action, replacing the original selection in-place with `Cmd+Z` undo support
- **RangeMarker tracking**: Uses IntelliJ's `RangeMarker` API to safely track selection offsets across async document edits — no more stale offset bugs
- **Thread-safe context**: `AtomicReference<SelectionContext?>` prevents double-click race conditions
- **Graceful degradation**: Invalid markers fall back to Insert at Cursor instead of erroring
- **Context persistence**: Selection context persists across code blocks for A/B iteration workflows

### Acceptance Telemetry

- **Structured code block events**: `copy`, `insert`, `replace`, and `replace_fallback` events with code length metrics
- **Conditional telemetry**: Events only emitted on successful document modification
- **OTel-ready**: Structured log format ready for forwarding to the Candela proxy's OTel pipeline

### Quality

- **RangeMarker lifecycle**: Proper disposal of abandoned markers in `sendMessage`, `clearChat`, and `dispose`
- **Button binding fix**: Each Replace button captures its context at creation time, preventing stale references
- **Platform integration tests**: 5 new tests covering RangeMarker behavior, offset tracking, and edge cases

## v0.1.1 — 2026-06-27

### Chat Tool Window

- **Streaming LLM chat**: Full chat tool window with SSE streaming, model selection, and conversation management
- **Editor context actions**: "Ask Candela...", "Explain Code", "Generate Tests" send selected code to chat
- **Code block actions**: Copy and Insert at Cursor for generated code blocks
- **Markdown rendering**: Fenced code blocks, inline code, bold, italic, headings, lists
- **Settings**: Chat server URL, system prompt, max tokens configuration
- **Candela icons**: Custom tool window icon from the Candela design system

### Hardening (Review Feedback)

- Fix memory leak in ChatPanel disposal
- Gson null safety for SSE chunk parsing
- Thread-safe streaming UI with AtomicLong throttle
- Inline code rendering order fix in MarkdownRenderer
- Model validation before sending requests
- URL validation in settings with ConfigurationException
- Safe code fence helper for backtick-containing code

## v0.1.0 — 2026-06-20

### Initial Release

- **Status bar widget**: Live token counts and spend with auto-refresh
- **Budget warnings**: Notifications when approaching daily limits
- **Grant display**: Active bonus grants with expiry countdowns
- **Dashboard launcher**: Open the Candela web dashboard
- **Settings**: Server URL, refresh interval, budget threshold configuration
