# Robust LSP for Rider

This plugin connects [robust-lsp](https://github.com/Ertanic/robust-lsp) to JetBrains Rider through [LSP4IJ](https://github.com/redhat-developer/lsp4ij).

It currently starts Robust LSP for YAML files and provides the language-server features supported by the upstream server, including completion, diagnostics, inlay hints, and navigation where available.

## Requirements

- JetBrains Rider
- The YAML plugin
- LSP4IJ
- A Robust Toolbox project containing one of these solution layouts:

  - `SpaceStation14.sln` or `SpaceStation14.slnx` at the project root
  - `RobustToolbox/RobustToolbox.sln` or `RobustToolbox/RobustToolbox.slnx`

The plugin searches upward from the Rider project directory for the nearest matching solution root and sends that directory to the language server as its workspace root.

## Installation

On first use, the plugin downloads the latest platform binary from the Robust LSP GitHub releases and stores it at:

```text
%USERPROFILE%\.robust-lsp\robust-lsp.exe
```

On non-Windows systems the path is `~/.robust-lsp/robust-lsp`.

To use a specific executable, set `LSP_SERVER_PATH` before starting Rider. On Windows, `.exe` is added automatically when it is omitted.

## Usage

Open a Robust Toolbox solution in Rider and open a YAML file. The server is started automatically when a mapped YAML file requires it.

The project must be opened at, or below, the directory containing the solution file. If no supported solution is found, the upstream server rejects initialization and LSP4IJ reports `Server not initialized`.

## Development

Build and verify the plugin with:

```text
gradlew.bat build
```

Run a Rider instance with the plugin using the `runIde` Gradle task or the provided `Run IDE with Plugin` run configuration.

The plugin targets Rider `2025.3.5` and uses LSP4IJ `0.20.1`.

## Project layout

```text
src/main/kotlin/RobustLanguageServerFactory.kt  LSP4IJ factory, installer, and root handling
src/main/resources/META-INF/plugin.xml          Plugin metadata and YAML mappings
```
