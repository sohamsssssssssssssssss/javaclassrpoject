# Loop 7A — pretraining pipeline v1 pilot

**Engineering pilot: PASS. Language quality: poor.** JADE-50M retained the exact 1024/32/640/8/10/2560 architecture and 50,483,200 parameters. This run started from **fresh random seed 26167**, not the Loop 6D step-4 smoke checkpoint. It used the Java-verified 1024-byte BPE artifact, C++ FP32 Transformer/AdamW and Apple Accelerate GEMM; no external model, pretrained weights or network training was used.

The only suitable local English pilot source was Loop 5D's 11-document, 304,963-byte curated public-domain/original corpus. Whole-document split: 9 train documents / 244,099 bytes / **92,507 tokens**, 1 validation document / 30,632 bytes / **12,111 tokens**, and 1 sealed test document / 30,232 bytes / **11,980 tokens**. No documents were rejected. Exact duplicate documents, cross-partition stripped lines and cross-partition paragraphs: **0 each**. These lexical checks do not prove zero semantic overlap. [Pilot manifest](../../data/processed/pilot-v1/manifest.tsv) and [processed identities](../../data/processed/pilot-v1/identity.txt). No test loss was evaluated or used for selection.

The frozen tokenizer artifact is [jade-bpe-1024.v1](../../data/tokenizer/jade-bpe-1024.v1), digest `73ca92067dccc1dc24198a4c8596ae8b95b62e2c0393fca1f02ea5d9aaa094a7`. Java refitting on the original train-only BPE sample reproduced all 768 merges. C++ matched Java's token IDs and round-trips on twelve golden cases, including Unicode, punctuation, numbers, apostrophes and newlines. Dataset identity: `b86265662039d5f35c670cd6ed20d95ed52bec73abbe017d206cc87633bdf9d7`.

Training used context 32, stride 32, batch 2 and one deterministic shuffled pass over **2,885 document-local windows**. It completed **1,443 optimizer steps**, **92,320 supervised positions**, in **289.571 s** wall time, below the 600-second deadline. Optimizer-only time was 233.075 s: **396.095 positions/s** inside steps and **318.817 positions/s** including validation, probes, checkpoints and reload. Nominal AdamW β₁=.9, β₂=.999, ε=1e-8, decay=.01, global clip=1. Peak LR **1e-4** followed 100-step linear warmup and cosine decay to **1e-5** at step 1,443. [Exact versioned config](../../data/checkpoints/pilot-7a-fresh-26167/training-config.txt).

| Measure | Before optimization | After one pass |
|---|---:|---:|
| Train loss, same canonical first 128 windows | 7.046921 | **5.642761** |
| Full validation loss, all 378 document-local windows | 7.018067 | **5.826955** |
| Validation perplexity | 1,116.627 | **339.324** |
| Gradient norm | 10.743180 (first step) | 5.503422 (last step) |

Train loss fell **1.404160** and validation loss fell **1.191113**. All losses, gradients, parameters and moments remained finite. Clipping triggered on all 1,443 steps; that is reported, not tuned away. Frequent validation used the first 128 canonical windows every 200 steps; full validation ran before the first update and after the last. Validation uses forward-only calls, and a tiny-model regression checks parameter, gradient, moment and timestep immutability. No second epoch or test evaluation occurred.

The final [checkpoint](../../data/checkpoints/pilot-7a-fresh-26167/final.jade) is **605,798,468 bytes**, with [metadata](../../data/checkpoints/pilot-7a-fresh-26167/final.jade.meta) binding config/tokenizer/dataset/checkpoint digests and counters. Core CRC and metadata SHA-256 verified; a fresh model loaded it, restored step 1,443 / 92,320 positions and reproduced all ten greedy baseline-prompt continuations. Only this run's two newest periodic checkpoints (steps 800 and 1,200) were retained. The Loop 6D checkpoint was untouched. Observed process peak RSS including transactional reload: **1,441,153,024 bytes**; ordinary training RSS in telemetry was about **835 MB**.

Every scheduled [generation probe](../../data/checkpoints/pilot-7a-fresh-26167/generation-probes.tsv) is retained with step, losses, greedy and fixed-seed sampled output. [All ten before/after chat prompts](CHAT_COMPARISON.md) remain visibly poor: the old `for the` collapse changed into `eiiii…`, `eyyy…`, repeated quotation marks and other fragments. Falling held-out loss on one historical author does **not** imply conversational competence or robust English transfer. No output was filtered or selected.

Machine-readable [telemetry](../../data/checkpoints/pilot-7a-fresh-26167/telemetry.tsv), [raw console](pilot-console.txt) and [summary](../../data/checkpoints/pilot-7a-fresh-26167/summary.txt) are retained. C++ Debug, Release and ASan/UBSan CTests each passed 2/2. Java `mvn -o clean package` passed **511 tests** with zero failures/errors/skips. `git diff --check` passed. The safety cap remains 60M; no 100M model, Metal implementation, full 50M run, commit or push was made.
