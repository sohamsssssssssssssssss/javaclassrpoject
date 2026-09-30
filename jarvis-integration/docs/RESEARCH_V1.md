# Local-first JADE intelligence

JADE's tokenizer and deterministic parser remain authoritative for actions.
General questions fall back to local text generation. Model output and retrieved
page content never re-enter the parser or invoke tools, processes, mutations,
confirmation, or undo.

## Local setup on macOS

1. Install Ollama from https://ollama.com/download/mac.
2. Start its runtime on loopback, with cloud features disabled:
   `OLLAMA_HOST=127.0.0.1:11434 OLLAMA_NO_CLOUD=1 ollama serve`
3. Choose an existing local model (`ollama list`). If none exists, manually obtain
   a modest model, for example `ollama pull llama3.2` (approximately 2 GB).
   JADE does not download or bundle models.
4. Launch JADE from a terminal:

```sh
export JADE_ANSWER_PROVIDER=local
export JADE_LOCAL_MODEL=llama3.2:latest
./run-jade.command
```

The lightweight default is llama3.2:latest (the installed 2 GB model verified here).
JADE_LOCAL_MODEL overrides it for another installed model. A fresh Mac must obtain
that model itself; a missing default model yields unavailable, never an automatic
pull or cloud fallback.
JADE_LOCAL_MODEL_ENDPOINT defaults to http://127.0.0.1:11434. Only literal
loopback endpoints (127.0.0.1 / ::1) are accepted; hostname aliases, remote hosts,
cloud model tags, and endpoints with credentials/paths are rejected. Local model
metadata is checked before each question; remote aliases or missing local
architecture metadata fail closed. Use a local
model and keep Ollama cloud features disabled. Runtime configuration is the
operator's responsibility; JADE never starts a runtime or pulls models itself.
Equivalent Java properties are jade.local.model, jade.local.model.endpoint, and
jade.answer.provider. Missing runtime/model/configuration is reported honestly;
there is no automatic cloud fallback. Finder may not inherit terminal exports.

OFFLINE: deterministic commands and installed local-model knowledge.
ONLINE: public web retrieval, followed by local synthesis. No paid LLM account or
API key is required for the canonical configuration.

## Current information and sources

The no-key provider retrieves a small official-source set, not broad search:
Java release/version questions use jdk.java.net and Oracle's support roadmap;
Maven release/version questions use maven.apache.org. `look up https://jdk.java.net/`
performs direct retrieval. Direct URLs are restricted to HTTPS official hosts:
jdk.java.net, openjdk.org, maven.apache.org, www.oracle.com, docs.oracle.com.
Private/loopback/reserved addresses are rejected after DNS resolution. No
redirects, arbitrary-host retrieval, crawling, page scripts, or browser automation.

Broad queries such as yesterday's sports results are unavailable without a
separate search provider. Optional Brave search uses JADE_SEARCH_PROVIDER=brave
and JADE_SEARCH_API_KEY; public evidence is still sent only to the local model.
JADE never scrapes Google HTML or fabricates results when retrieval fails.

Only the informational query goes to search. Project paths, selected files,
search scope, history, documents, usernames, and environment are not appended.
Public evidence receives source IDs [S1], [S2], etc. Actual retrieved URL/title
metadata supplies the source list; generated URLs cannot become verified sources.
The local model is instructed to ignore evidence instructions and admit when
retrieved evidence is insufficient. HTTP/HTTPS citation links use HostServices.

## Bounds and optional legacy code

Questions: 2,048 characters / parser 64 tokens. Search queries: 600 characters.
At most five pages/sources; titles 200, URLs 2,048, excerpt 1,000 characters each;
external evidence 12,000 characters; local prompt 20,000 characters. HTTP bodies
are capped at 256 KiB; connect timeout 5 seconds, each page timeout 10 seconds,
model metadata timeout 10 seconds, generation timeout 60 seconds. No redirects, system HTTP proxies, or automatic retries.
Local generation uses up to 768 output tokens / 8,192 context tokens; displayed
answers are capped at 12,000 characters and truthfully marked when truncated.
Cancellation uses the existing token; network work runs off the JavaFX thread.
There is no conversation memory, embeddings, local-file upload, or model tool API.

The OpenAI adapter is retained behind its existing seam for explicit
JADE_ANSWER_PROVIDER=openai opt-in only. It needs OPENAI_API_KEY, answers knowledge
only in application configuration, rejects supplied retrieval evidence, and is
never a fallback. Its legacy web-response parser remains tested but is not wired
into canonical application research. Default JADE neither reads nor demands that
key. Streaming remains deferred.

Deterministic tests use injected LocalLanguageModel implementations, fake
transports, and localhost fixtures. No model download or real internet is needed.
Protocol/setup references: https://docs.ollama.com/api/generate and
https://docs.ollama.com/faq.
