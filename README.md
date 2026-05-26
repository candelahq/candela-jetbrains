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

### From GitHub Releases (recommended)

1. Go to the [latest release](https://github.com/candelahq/candela-jetbrains/releases/latest)
2. Download **`candela-jetbrains-x.x.x.zip`**
3. In your JetBrains IDE, open **Settings → Plugins**
4. Click the **⚙️ gear icon** → **Install Plugin from Disk…**
5. Select the downloaded `.zip` file
6. Click **OK** and **Restart IDE**

> **Note:** The plugin is pending review on the JetBrains Marketplace. Once approved, you'll be able to install directly from **Settings → Plugins → Marketplace** by searching for "Candela".

### Build from Source

```bash
git clone https://github.com/candelahq/candela-jetbrains.git
cd candela-jetbrains
nix develop              # enters dev shell with JDK 21 + Gradle
./gradlew buildPlugin    # output: build/distributions/candela-jetbrains-*.zip
```

Then install the `.zip` from disk using the steps above.

---

## Prerequisites

1. **Candela running locally**: `candela start` (requires [candela](https://github.com/candelahq/candela) v0.4.6+)
2. **Authentication**: `candela auth login` once for Google OAuth credentials
3. The plugin auto-detects Candela on the configured URL. If offline, the status bar shows `🕯️ offline`.

---

## Development

```bash
# Enter dev shell (JDK 21 + Gradle 8.14 + Kotlin)
nix develop

# Run a sandboxed IDE with the plugin loaded
nix develop -c ./gradlew runIde

# Build the distribution zip
nix develop -c ./gradlew buildPlugin

# Run tests
nix develop -c ./gradlew test
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
