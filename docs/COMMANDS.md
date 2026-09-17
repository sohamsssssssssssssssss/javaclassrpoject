# Sprint 1 Commands

Keywords and aliases are matched with `Locale.ROOT` case folding; quoted argument contents and the original request text are preserved.

| Canonical command | Accepted forms |
| --- | --- |
| Open configured app | `open <app>`; app may be quoted |
| Find PDFs | `find pdfs`, `find pdf files` |
| Find large PDFs | `find pdfs larger than <positive integer> mb` |
| System status | `system status`, `status` |
| History | `show history`, `history` |

Configured logical apps and aliases:

- `calculator`: `calculator`, `calc`
- `text-editor`: `text editor`, `editor`
- `file-manager`: `file manager`, `files`

Matching is exact after trimming/collapsing whitespace and keyword case folding. Unknown apps, extra tokens, malformed/zero/negative sizes, unsupported platforms and missing executables are structured failures. No input is interpreted as a shell command or filesystem target.
