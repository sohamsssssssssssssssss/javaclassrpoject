# Commands (Sprints 1–3)

Keywords and aliases are matched with `Locale.ROOT` case folding; quoted argument contents and the original request text are preserved.

## Voice (sprint 3, optional)

Voice is available only when a Vosk STT model is installed (see docs/AUDIO_FEASIBILITY.md). Commands are **spoken** using exactly the same grammar as the table below — there are no voice-only commands and no voice-specific parsing.

1. Say **"hello Jarvis"** — JARVIS greets you ("Good morning, Master Soham…" when recognized; otherwise it introduces itself and asks your name).
2. After the greeting, speak one command, e.g. **"system status"** or **"open calculator"**.
3. JARVIS executes it through the same pipeline as typed input and speaks the summary (SUCCEEDED/REJECTED/FAILED).

The voice strip shows the current state (IDLE, LISTENING, PROCESSING, EXECUTING, SPEAKING, ERROR), the recognized transcript and the identified speaker. Speaker identification (Soham/Ved/Unknown) personalizes greetings only; it never grants permissions. Each wake session handles exactly one command.

| Canonical command | Accepted forms |
| --- | --- |
| Open configured app | `open <app>`; app may be quoted |
| Find by type | `find pdfs`, `find pdf files`, `find <ext> files`, `find <ext>s` (known extensions: pdf, doc, docx, txt, md, ppt, pptx, xls, xlsx, png, jpg, jpeg, gif, svg, mp3, wav, mp4, mov, avi, mkv, zip, tar, gz, rar, 7z, java, py, ts, js, html, css, json, xml, csv, jar) |
| Size filter | `… larger|bigger than <n> <unit>`, `… smaller than <n> <unit>`; units kb/kib, mb/mib, gb/gib; n is a positive whole number |
| Date filter | `… from|after|since today|yesterday|this week|this month|<weekday>`, `… before <phrase>`; "yesterday"/weekday mean exactly that calendar day, "today"/"this week"/"this month" are lower bounds |
| Create folder | `create folder called <name>` |
| Move selection | `move these [files] to <folder>` (acts on the last search of the session; creates the destination folder after confirmation if missing) |
| Copy selection | `copy these [files] to <folder>` |
| Rename selection | `rename the newest to <name>`, `rename the oldest to <name>` (extension preserved when omitted) |
| Refine last search | `only larger|bigger than <n> <unit>`, `only smaller than <n> <unit>`, `only from today|yesterday|this week|<weekday>`, `only before <phrase>`, `only <ext>[s] [files]`; clauses combine — every constraint not restated is inherited from the previous search of this session (a restated dimension replaces the old one; the previous sentence is never re-parsed) |
| Select + open | `open the newest`, `open the oldest` (deterministic: latest/earliest modification time, ties broken by highest absolute path string; the file is selected and opened) |
| Open selection | `open it`, `open the file` (opens the single contextual file referent) |
| Move/copy referent | `move|copy the newest|the oldest|it|that [file] to <folder>` — `it`/`that file` need exactly one valid referent (the explicit selection, or the sole file of the current result set) and fail honestly otherwise |
| Confirm pending | `confirm`, `cancel` (handler-less runs: a mutation first returns its exact preview with no filesystem effect; `confirm` executes precisely the previewed typed operations; `cancel` discards; a second `confirm` rejects) |
| Undo | `undo`, `undo that` (reverses the latest journalled move/rename command, row by row, never overwriting) |
| List scope files | `list files` (top-level regular files of the configured scope; acts like a search for `move these files …` selection) |
| File info | `file info <name>`; also `what is <name>` (case-insensitive name lookup inside the scope) |
| Content search | `find <ext> [files] containing "text"` (extracts text from the files of the last search of this session — TXT, PDF, DOCX and other Tika-supported formats — and matches the quoted phrase case-insensitively; at most 100 documents per search; unreadable documents are reported, not silently skipped) |
| System status | `system status`, `status` |
| History | `show history`, `history` |

Configured logical apps and aliases:

- `calculator`: `calculator`, `calc`
- `text-editor`: `text editor`, `editor`
- `file-manager`: `file manager`, `files`

Matching is exact after trimming/collapsing whitespace and keyword case folding. Unknown apps, extra tokens, malformed/zero/negative sizes, unsupported platforms and missing executables are structured failures. No input is interpreted as a shell command or filesystem target.
