<img src="docs/DFile-Logo.png" alt="DFiles logo" width="96" height="96">

# DFiles

### A fast, modern file manager built with JavaFX

DFiles is a desktop file manager inspired by GNOME Files (Nautilus), built for people who want
a clean, keyboard- and mouse-friendly way to browse their filesystem without giving up quick
access to a terminal, Git, VS Code, AI-assisted scripting, and common archive/text operations. It
keeps a small SQLite cache so recently visited folders paint instantly, watches the current folder
in the background so external changes show up without a manual refresh, and stays out of your way
with a resizable, collapsible layout and full support for English, Spanish, French, Portuguese,
and Italian.

![DFiles screenshot](docs/screenshot.png)

## Features

- **Two-pane browsing** — a sidebar of standard Places (Home, Desktop, Documents, Downloads,
  Music, Pictures, Videos, Computer) plus user-defined Bookmarks, alongside a sortable file table
- **Resizable, collapsible layout** — drag the divider between the sidebar and the file table, drag
  the divider between Places and Bookmarks, and hide the whole sidebar with one click
- **Background folder watching** — the current folder updates live as files change on disk, with a
  SQLite-backed cache for instant repeat visits
- **Git integration** — status, pull, push, commit, log, and init, run from the toolbar with output
  shown inline
- **Archive support** — browse the contents of zip, tar, tar.gz, tar.bz2, tar.xz, rar, and 7z files,
  extract them in place, or compress any folder into a new archive, choosing from whichever formats
  are available on your system
- **Text file tools** — a head/tail viewer for quickly skimming large logs, and a find-and-replace
  dialog (plain text or regex) that always saves to a new versioned file, never overwriting the original
- **Terminal & VS Code shortcuts** — open a terminal or VS Code at the current or selected folder
  in one click
- **AI-assisted script generation** — describe what you want in plain English and have OpenAI or a
  local [Llamafile](https://github.com/Mozilla-Ocho/llamafile) server write a bash script for you,
  review and save it, then run it against the current folder with output streamed live. Every
  saved script is content-hashed (SHA-256) and timestamped with when it was created and last
  updated, and the generated code itself notes which provider and model produced it
- **Data conversion** — turn CSV, TSV, JSON, a Markdown table, YAML, or an Excel (.xlsx) file into
  SQL or CSV, from pasted text, a local file, or a URL, with a choice of source encoding
  (UTF-8, UTF-16, Latin-1, US-ASCII, Windows-1252). SQL output is a single `INSERT` wrapped in a
  transaction, with an optional `CREATE OR REPLACE TABLE` header and inferred column types. The
  source is only ever read — results always go to a new file you pick, never back over the
  original. Right-click a supported file for a shortcut straight into the dialog
- **File properties** — Unix-style permissions, size, item counts, and modified dates, shown in the
  status bar for a single selection and in a full Properties dialog for any file or folder
- **Internationalized UI** — English, Español, Français, Português, and Italiano, auto-detected
  from your OS locale on first launch and remembered after that
- **Built-in Help** — a bundled documentation page for each supported language, cross-linked and
  one click away from the toolbar
- **Structured logging** — application activity and errors are logged to `~/.dfiles/dfiles.log`
  via Log4j2

## Requirements

To run the packaged jar, only a Java 21 runtime is needed. To build from source you'll also need
Maven:

- **Java 21** or later ([Eclipse Temurin](https://adoptium.net/) or any OpenJDK 21+ distribution) —
  **required**
- **Apache Maven 3.9+** — only needed to build from source
- **JavaFX 21.0.3** — fetched automatically by Maven; no separate SDK install needed

Everything else is optional and only needed for the matching feature:

| Tool | Needed for |
| --- | --- |
| `git` | The Git toolbar menu (status, pull, push, commit, log, init) |
| `OPENAI_API_KEY` env var | The OpenAI backend for AI-assisted scripts |
| [Llamafile](https://github.com/Mozilla-Ocho/llamafile) | The local AI backend, served at `http://localhost:8080` |
| VS Code (`code` CLI) | "Open in VS Code" |
| `tar` / `unrar` / `7z` | Archive formats beyond zip |
| A terminal emulator | "Open Terminal" |

On Linux, if the packaged jar fails to start with `Unable to open DISPLAY`, your shell's `DISPLAY`
environment variable doesn't point at your actual graphical session — see the in-app Help (or
`src/main/resources/help/help_en.html`) for the fix.

## Build

```bash
mvn package
```

This produces a self-contained runnable jar at `target/dfiles.jar` (via `maven-shade-plugin`),
bundling JavaFX, SQLite JDBC, and Log4j2.

## Run

During development, run directly with the JavaFX Maven plugin:

```bash
mvn javafx:run
```

Or run the packaged jar:

```bash
java -jar target/dfiles.jar
```

DFiles stores its settings, bookmarks, file cache, and saved scripts (prompt, content, content
hash, run output, and created/updated timestamps) in `~/.dfiles/dfiles.sqlite`, and writes logs to
`~/.dfiles/dfiles.log`. Scripts under test run from a scratch file in
`<OS temp folder>/.dfiles/`, deleted again once the run finishes.

## Documentation

A pre-built copy of the API docs (Javadoc) is committed under [`docs/javadoc/`](docs/javadoc/index.html) —
clone the repo and open `docs/javadoc/index.html` locally, or enable GitHub Pages
(Settings → Pages → Deploy from branch → `main` / `docs`) to serve it at
`https://<user>.github.io/DFiles/javadoc/`.

To regenerate it after making changes:

```bash
mvn javadoc:javadoc
```

This writes fresh output to `target/reports/apidocs/`; copy it over `docs/javadoc/` to update the
committed copy. User-facing help is bundled in the app itself
(`src/main/resources/help/help_*.html`, one file per supported language) and opens from the
toolbar's Help button.

## Contributing

DFiles is open-source and started with development assistance from
[Claude Code](https://claude.com/claude-code), Anthropic's AI coding assistant. Voluntary
contributions are welcome — fork the repository and open a pull request.

## License

DFiles is licensed under the [GNU General Public License v3.0](LICENSE).
