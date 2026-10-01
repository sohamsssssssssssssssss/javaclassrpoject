# Loop 6B — native CPU optimization gate

**PASS.** The existing JADE-5M architecture remains 1024/32/256/4/6/1024 with exactly 5,251,072 parameters. Java still supplies the mathematical reference and BPE/corpus fixtures. No model mathematics, tokenizer, corpus, UI, voice, AnswerService, checkpoint format, Metal code, larger model, external ML runtime or pretrained weights changed.

## Profile before and after

Release Apple clang 21.0.0, arm64, `-O3`, Accelerate GEMM, batch 2, context 32, same two train-token windows and Java seed-26167 weight export. Each profile used five warm updates and **40 timed optimizer steps**. `p95` is the 38th sorted sample of 40. The table is milliseconds; raw samples and all min/p50/p95/max values are in [profile before](profile-before.txt), [profile after](profile-after2.txt) and [profile summary](profile-summary.tsv). Forward and loss are measured inside the backward call; the separate benchmark diagnostic forward is excluded from complete-step accounting. “Backward other” excludes the timed parameter-gradient accumulation. The seven-sample ladder below is a separate comparison against Loop 6A.

| Component | Before p50 / p95 | After p50 / p95 |
|---|---:|---:|
| Forward | 4.095 / 4.329 | 4.161 / 4.655 |
| Cross entropy loss | 0.102 / 0.113 | 0.102 / 0.121 |
| Backward other | 4.213 / 4.410 | 4.269 / 4.618 |
| Parameter-gradient accumulation | 1.466 / 1.530 | 1.451 / 1.524 |
| Gradient norm and clipping | **4.245 / 4.566** | **1.611 / 1.677** |
| AdamW moment and parameter update | **9.719 / 10.064** | **8.659 / 9.132** |
| Gradient clear | 0.253 / 0.289 | 0.254 / 0.277 |
| Whole step | **24.151 / 25.372** | **20.534 / 22.279** |

The unlisted timer overhead/other time is below 0.001 ms median. AdamW norm plus update fell from **13.964 ms / 57.8%** of the timed step to **10.270 ms / 50.0%**. The measured issue was a serial FP64 sum of 5.25 million squared gradients plus divisions inside the norm and update loops. Four independent double-precision norm accumulators remove the long dependency chain; one reciprocal per batch/bias factor replaces repeated scalar division. The formulas, global clipping, timestep, decay and update order remain the same. The changed reduction order and reciprocal multiplication may alter the last floating-point bits; existing Java parity tolerances were **not changed**, and the 5M four-step micro-training final loss remained 5.001392365 to the recorded precision. No relaxed compiler math flags were added.

## Allocation and copy audit

Parameters, gradients and m/v are four contiguous FP32 arrays. The forward cache and backward scratch are allocated at model construction and reused for the two examples. Parameter-gradient GEMMs accumulate directly into the persistent gradient array. AdamW updates moments and parameters in place. No full-model parameter, gradient, moment or activation copy occurs during a timed step; checkpoint load is deliberately a separate transactional path. The global norm must be known before any clipped update, so norm and update retain two contiguous passes. Gradient clearing is one additional pass and only 0.25 ms; fusing it into update would change the observable post-update gradient state.

The instrumented benchmark reports **0 Tensor allocations and 0 ordinary C++ `new`/`new[]` calls per step** both before and after warm-up, and fails in profile mode if an observed `new` call appears. This counter does not intercept `malloc` inside Accelerate or the C library, so zero *all-runtime* allocations is not claimed. Source inspection found `std::vector` growth only during construction, generation, trace creation or checkpoint load, outside the training step. No hot-loop storage resize or parameter traversal by scattered registry entries occurs.

A bounded synthetic 5.25M-element optimizer trial compared scalar update with `vDSP_vsmul` plus `vvsqrtf`: median **2.196 ms scalar versus 3.559 ms vDSP path**, 21,004,288 extra scratch bytes, worst parameter difference 7.45e-9. This isolated kernel is not an end-to-end model benchmark. Because it was slower and adds a full intermediate pass, the engine retains its scalar fused update. [Experiment source](vdsp-experiment.cpp), [measurement](vdsp-experiment.txt), [Apple vector-scalar multiply](https://developer.apple.com/documentation/accelerate/vdsp_vsmul), [Apple vector sqrt](https://developer.apple.com/documentation/accelerate/vvsqrtf%28_%3A_%3A_%3A%29).

## Scale ladder

Same Loop 6A architectures and workload; C++ Accelerate Release, two warmups, seven samples per scale. Timing columns are **medians in milliseconds**. Backward includes its required forward; step is backward plus AdamW. Full minimum/median/maximum, RSS and allocations are in [benchmark summary](benchmark-summary.tsv) and the individual `bench-*-after.txt` files.

| Model | Parameters | Forward | Backward | AdamW | Step | Loop 6A pos/s | Loop 6B pos/s | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 58K | 58,368 | 0.218 | 0.476 | 0.121 | 0.598 | 99,604 | 107,083 | 9,699,328 |
| 250K | 231,424 | 0.433 | 0.918 | 0.453 | 1.364 | 41,855 | 46,909 | 11,190,272 |
| 1M | 1,052,672 | 1.499 | 3.222 | 2.048 | 5.270 | 9,930 | 12,144 | 25,903,104 |
| **5M** | **5,251,072** | **4.401** | **10.309** | **10.272** | **20.610** | **2,596** | **3,105** | **96,010,240** |

5M measured throughput improved **1.196× / 19.6%** over Loop 6A. Within this short CPU workload, about **3.1k supervised positions/sec** is the measured current level; it is not a theoretical hardware ceiling or a sustained thermal test. AdamW remains the largest component at **49.8%** of the seven-sample median step. RSS changed from **95,961,088** to **96,010,240 bytes** (a negligible 49,152-byte process high-water difference); persistent training state remains **84,017,152 bytes**. Each scale reports zero observed hot `new` calls. The 5M micro/resume smoke still completed four trajectory steps, ended at loss 5.001392365, and restored moments/counters with an identical resumed continuation. No full epoch ran.

## Exact 20M planner — arithmetic only

Candidate: **V=1024, C=32, D=448, H=8, L=8, F=1792**, using the existing bias-free architecture and conventional F=4D. `D` is divisible by `H`. Java's existing overflow-safe `BrainConfig.exactParameterCount` calculates **20,199,424** parameters without instantiating `BrainConfig` or a model; [planner source](Planner20M.java) checks its C++ workspace formula against the known 5M workspace. The current **5.5M configuration guard remains active** and is tested.

| Planned item | Bytes |
|---|---:|
| FP32 parameters | 80,797,696 |
| FP32 gradients | 80,797,696 |
| AdamW first moments | 80,797,696 |
| AdamW second moments | 80,797,696 |
| Persistent state | **323,190,784** |
| Per-layer cache and states | 8,839,168 |
| Global activations/logits/backward scratch | 1,294,464 |
| Total reusable workspace | **10,133,632** |
| Checkpoint `68 + 12P` | **242,393,156** |
| Estimated ordinary training peak RSS | **340,697,088** |
| Estimated checkpoint-load peak RSS with three validation arrays | **583,090,176** |

RSS estimates add the measured 5M non-Tensor process overhead (7,372,672 bytes) to exact planned arrays. They are **estimates**, not measured 20M memory; allocator and BLAS behavior at 20M may change. A 25% planning margin gives roughly 426 MB ordinary training and 729 MB during load. Disk had about 147.6 GB free at planning time, sufficient for a 242 MB checkpoint, but the guard still prevents allocation.

For the unchanged context/batch, projection weights evaluated per position grow from 917,504 at 1M and 4,980,736 at 5M to **19,726,336** at this candidate. Interpolating/extrapolating the **measured 1M and 5M backward-with-forward times against projection work** gives 36.03 ms; extrapolating their optimizer times against persistent parameter count gives 39.55 ms. The combined point estimate is **75.58 ms per batch-2 step, about 847 positions/sec**. [Calculation](step-estimate.txt). This separates GEMM work from optimizer scans and accounts for architecture, but extrapolation beyond 5M is uncertain. A practical planning range is **60–110 ms/step** (about 580–1,070 positions/sec), pending measurement. A **future bounded 20M smoke appears memory-feasible** only after a separate cap review, allocation limit and parity gate. No 20M model was created or trained in Loop 6B.

## Regression and boundary

- Tiny Java/C++ fixture: forward, loss, every analytical gradient, all finite differences, three AdamW steps and controlled loss decrease **PASS** with unchanged Loop 6A tolerances. New batch-2 gradient averaging/moment check **PASS**. Checkpoint corruption rejection, round trip, counters, deterministic generation and exact resume **PASS**.
- C++ Debug, Release and AddressSanitizer/UndefinedBehaviorSanitizer CTests: **1/1 passed** in each build. No sanitizer findings.
- Java `mvn -o clean package`: **PASS**, Surefire **508 tests, 0 failures, 0 errors, 0 skipped**; Brain subset **206 tests**. [Offline Maven log](maven-package.txt).
- `git diff --check`: **PASS**. [Final status](git-status-final.txt). Existing unrelated and outer-directory untracked work was preserved. No stage, commit, push or Git cleanup was performed.

The next loop, if requested, can separately gate a 20M smoke. Loop 6B stops here.

To reproduce the native profile from this Maven-project directory after a clean build, compile the already documented Java fixture exporter, regenerate `target/engine-v2/fixtures`, then run `jade_engine_bench 5M accelerate target/engine-v2/fixtures target/engine-v2/checkpoints profile`. The exact build and fixture commands are in [Loop 6A's reproduction instructions](../engine-v2/CPP_ARCHITECTURE.md). Profile mode runs 5 warmups and 40 measured steps, emits each timing component and rejects observed hot `new` allocations. The ordinary four-scale benchmark uses its standard 2 warmups and 7 samples. All benchmark checkpoint outputs stay under ignored `target/`.
