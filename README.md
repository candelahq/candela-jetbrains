# candela-jetbrains

JetBrains IDE plugin for [Candela](https://github.com/candelahq/candela) — real-time LLM cost tracking, budget warnings, and observability dashboard.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Works with **all JetBrains IDEs**: IntelliJ IDEA, WebStorm, PyCharm, GoLand, CLion, Rider, RubyMine, etc.

## Features

### 🔥 Status Bar Cost Tracker

Live token usage and cost in your IDE status bar, auto-refreshing every 60 seconds:

```
🔥 1.2M · $2.45 · 🟢45%
```

Hover for full breakdown: input/output tokens, request count, model-by-model stats, budget details, and active grants.

### 💰 Budget Warnings

Balloon notifications when budget usage crosses your threshold (default 80%):
- Yellow warning at threshold
- Red alert when exhausted
- Grant expiry countdowns

### 📋 Menu Actions

Access from **Tools → Candela**:

| Action | Description |
|--------|-------------|
| **Show Cost Summary** | Detailed token/cost breakdown with model stats |
| **Check Budget** | Budget meter with remaining balance and grants |
| **Open Dashboard** | Launch the Candela web dashboard |
| **Refresh Status** | Force refresh status bar data |

### ⚙️ Settings

Configure under **Settings → Tools → Candela**:

| Setting | Default | Description |
|---------|---------|-------------|
| Server URL | `http://localhost:8181` | Candela server URL |
| Status bar enabled | `true` | Show cost tracker in status bar |
| Refresh interval | `60s` | Auto-refresh interval (0 to disable) |
| Budget warning threshold | `80%` | Warning threshold |

---

## Installation

### From JetBrains Marketplace (Coming Soon)

Search for **"Candela"** in **Settings → Plugins → Marketplace**.

### From GitHub Releases

Download the `.zip` from [Releases](https://github.com/candelahq/candela-jetbrains/releases) and install:

**Settings → Plugins → ⚙️ → Install Plugin from Disk…**

### Build from Source

```bash
./gradlew buildPlugin
# Output: build/distributions/candela-jetbrains-0.1.0.zip
```

---

## Prerequisites

1. **Candela running locally**: `candela start` (requires [candela](https://github.com/candelahq/candela) v0.4.6+)
2. **Authentication**: `candela auth login` once for Google OAuth credentials
3. The plugin auto-detects Candela on the configured URL. If offline, the status bar shows `🕯️ offline`.

---

## Development

```bash
# Run a sandboxed IDE with the plugin loaded
./gradlew runIde

# Build the distribution zip
./gradlew buildPlugin

# Run tests
./gradlew test

# Verify plugin compatibility
./gradlew verifyPlugin
```

---

## Related

- [Candela](https://github.com/candelahq/candela) — OTel-native LLM observability platform
- [candela-desktop](https://github.com/candelahq/candela-desktop) — macOS desktop app
- [candela-vscode](https://open-vsx.org/extension/candelahq/candela-vscode) — VS Code extension
- [opencode-candela](https://www.npmjs.com/package/opencode-candela) — OpenCode plugin
- [candela-cline](https://www.npmjs.com/package/candela-cline) — Cline plugin

---

## License

Apache License 2.0. See [LICENSE](LICENSE) for details.
