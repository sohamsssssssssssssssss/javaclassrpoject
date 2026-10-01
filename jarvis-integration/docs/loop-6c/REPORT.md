# Loop 6C — JADE-20M bounded scale smoke

**LOOP 6C: PASS.** The 20M C++ engine constructed, passed large-shape forward checks, completed one gated training step and a four-step trajectory, saved and reloaded its checkpoint, and reproduced the next optimizer step exactly. This was a bounded engine test on two frozen train-token windows, not a corpus training run or evidence of language ability. Java remains the mathematical reference. No model math, tokenizer, corpus, UI, voice or AnswerService code changed.

## JADE-20M

Architecture: **V=1024, C=32, D=448, H=8, L=8, F=1792**, head width 56. Exact trainable parameters: **20,199,424**. Source-derived count: token embedding **458,752**; position embedding **14,336**; per-layer attention **802,816**; per-layer FFN **1,605,632**; eight blocks **19,267,584**; untied LM head **458,752**; total **20,199,424**. [Shape proof](shape-preflight.txt). The model was initialized with the native engine's deterministic seed **26167**; no pretrained weights were loaded. Input IDs came from the existing Java train-only BPE corpus fixture; [corpus and encoding identities](fixture-identities.txt) match Loop 6A/6B.

## Memory

The new `EngineeringLimits` defaults to **25,000,000 parameters** and a **768 MiB** preflight budget. Callers can configure stricter limits, but cannot raise the parameter ceiling above 25M or memory budget above 1 GiB in this loop. `plan_memory` uses checked 64-bit arithmetic for exact parameters, four persistent FP32 arrays, reusable workspace and checkpoint; it includes all checkpoint-load validation arrays plus a 64 MiB engineering margin. The constructor checks this plan **before any large Tensor allocation**. A deliberately too-small budget is tested to reject before a single Tensor allocation. The 20M candidate's planned worst construction/load reservation is **642,826,368 bytes**, below the default budget **805,306,368 bytes**. Available memory was informational only: macOS `memory_pressure -Q` reported 16 GiB physical memory and about 50% free; it did not determine correctness or bypass the fixed budget. [Raw memory observation](memory-pressure-preflight.txt).

| Measure | Bytes |
|---|---:|
| Parameters | 80,797,696 |
| Gradients | 80,797,696 |
| AdamW first moments | 80,797,696 |
| AdamW second moments | 80,797,696 |
| Persistent total, predicted and actual buffer size | **323,190,784** |
| Reusable activation/workspace estimate and actual Tensor size | **10,133,632** |
| Loop 6B predicted ordinary RSS | **340,697,088** |
| Measured RSS before model | 5,931,008 |
| Measured RSS after parameter array | **86,769,664** |
| Measured RSS after optimizer arrays | **329,236,480** |
| Measured RSS after workspace/cache construction | **340,197,376** |
| Measured ordinary peak training RSS, warmed benchmark | **344,129,536** |
| Measured checkpoint-load process peak | **584,695,808** |

Actual ordinary peak exceeded the Loop 6B estimate by **3,432,448 bytes (+1.01%)**. Stage observations use Mach task resident size; peak observations use `getrusage` high water. Constructor made exactly **132** Tensor allocations: four training-state arrays, fifteen global workspace arrays, nine layer states and 104 per-layer cache arrays. Their total tracked bytes equal persistent plus workspace. Timed training made **zero new Tensor allocations** and **zero observed C++ `new`/`new[]` calls**; as in Loop 6B, this does not intercept internal C-library/BLAS `malloc`.

## Performance

Loop 6B predicted **75.583 ms/step**, about **846.75 supervised positions/s**. Loop 6C measured **69.414 ms median step**, **79.370 ms p95**, and **922.413 positions/s median** across 40 steps after five warmups, batch 2/context 32. The measured step is **6.169 ms (8.16%) faster**, and throughput **8.94% higher**, than the point estimate. Raw per-step samples and min/max are in [20M benchmark](bench-20M.txt) and [profile summary](profile-summary.tsv). All measurements use the same Accelerate GEMM backend and FP32 engine as Loop 6B. The 20M benchmark starts from the C++ deterministic random initializer because there is no Java 20M initialization fixture; it uses the same frozen training token windows and optimizer settings. Each process was externally bounded to 90 seconds.

| Warmed 20M component | p50 ms | p95 ms |
|---|---:|---:|
| Forward | 10.842 | 12.377 |
| Cross entropy | 0.096 | 0.103 |
| Backward excluding gradient GEMMs | 11.153 | 12.819 |
| Parameter-gradient accumulation | 5.357 | 5.814 |
| Global gradient norm | 6.419 | 7.173 |
| Clip decision | <0.001 | <0.001 |
| AdamW moment/parameter update | 34.483 | 38.898 |
| Gradient clear | 0.989 | 1.238 |
| Complete step | **69.399** | **79.370** |

The main favorable difference from the estimate is projection throughput at the wider 448-dimensional shape: backward including forward measured **28.433 ms** against the projection-work estimate **36.031 ms**. AdamW measured **40.998 ms** against **39.552 ms** estimated. The two component medians need not sum exactly to the step median. Ordinary process RSS was close to the estimate.

## Training smoke

The first 20M optimizer step was a hard gate. It completed in **68.661 ms**, with forward **10.681 ms**, cross entropy **0.095 ms**, backward other **11.063 ms**, gradient accumulation **5.461 ms**, norm **6.516 ms**, AdamW update **33.875 ms**, and gradient clearing **0.970 ms**. The clip-decision timer was below clock resolution. Global averaged-gradient norm was **8.322303774**, so clipping at 1.0 was active with factor **0.1201590361**. **19,768,448** gradients were nonzero; all gradients, parameters and moments were finite. The LM-head parameter changed, timestep became 1, supervised positions became 64, and no Tensor allocation occurred in the step.

The gated first step was **step 1 of the required four-step trajectory**. Loss on the same deterministic two-window fixture was:

| Step | Mean loss |
|---:|---:|
| 0 | 7.066051960 |
| 1 | 5.752046585 |
| 2 | 5.623816013 |
| 3 | 4.677310944 |
| 4 | 4.092441559 |

Final reduction: **2.973610401**, about **42.08%**. The four-step trajectory contains exactly **256 supervised positions**. For the separately required resume-equivalence test, step 5 was computed once from the in-memory step-4 state and once after loading the step-4 checkpoint; those two diagnostic calls are not included in the four-step smoke trajectory. No full epoch or larger-model training occurred. [Smoke and resume log](smoke20m.txt).

## Checkpoint

Predicted and measured size both **242,393,156 bytes**. The retained [step-4 checkpoint](checkpoints/20M-step4.jade) is ignored by Git (`**/checkpoints/`); its SHA-1 matches the final run original checkpoint. The original model was destroyed before constructing the reload model. Reload verified architecture, exact parameter and m/v payload bytes, timestep **4**, supervised positions **256**, and identical step-4 loss. A diagnostic next step produced the **same parameter/moment hash, exact parameter and m/v payload bytes, and loss**, with timestep **5** and positions **320**. Peak RSS during transactional checkpoint load was **584,695,808 bytes**. Existing corruption/truncation rejection tests remained green.

## Numerical health and parity

Native large-shape checks covered every layer's finite intermediate tensors, all eight heads' causal probabilities and row sums, the last head's score indexing, embedding lookup, each block's attention-output/FFN up/FFN down projection samples, GELU, LM head and independently recomputed cross entropy. All passed. [Golden Java/C++ parity](parity-release.txt) also passed unchanged: worst forward absolute error **2.026e-7**, worst naive/Accelerate gradient absolute errors **5.680e-8 / 5.286e-8**, and three-step AdamW worst absolute difference **1.869e-8**. The fixture includes finite differences. No tolerance was loosened.

## 5M regression

Loop 6B baseline: **3,105.238 positions/s**. The first 5M seven-sample process following 20M benchmark measured **2,554.865**. Three independent identical repeats measured **3,098.055**, **3,086.687**, and **2,686.348** positions/s. A matched 40-step profile measured **3,055.239** versus Loop 6B's 40-step profile at roughly **3,116** positions/s (about **1.9%** lower). The mixed seven-sample results show process-level performance variability; no benchmark settings were changed or runs discarded. Both recovered runs and the longer matched profile support no clear 5M engine regression from the cap change. All runs used the same Java-exported 5M random weights and windows, produced finite losses, and reported zero observed hot allocations. [All 5M logs](bench-5M.txt) and `bench-5M-repeat*.txt`, `bench-5M-profile40.txt` are retained.

## Regression and boundaries

C++ Debug, Release and ASan/UBSan CTests: **1/1 passed** each. The test suite checked 5M and 20M exact counts, >25M and malformed/head-invalid rejection, overflow-safe arithmetic, a low-memory-budget rejection before any Tensor allocation, golden parity, AdamW, checkpoint round trip, corruption/truncation and deterministic resume. Java `mvn -o clean package`: **508 tests, zero failures/errors/skips**, including **206 Brain tests**, and build **PASS**. [Maven log](maven-package.txt). `git diff --check`: **PASS**. [Git status](git-status-final.txt). Unrelated and pre-existing untracked work was preserved. Nothing was staged, committed or pushed.

External ML dependencies/models/pretrained weights: **none**. No PyTorch, TensorFlow, Ollama, API model or network training path was used. Accelerate supplies only CPU GEMM. Metal was not implemented. The engine's new upper engineering cap is **25M**; 50M/100M/500M models remain rejected and were not constructed.

**Next frontier:** arithmetic-only planning for 50M is justified by the finite 20M run, exact checkpoint recovery, measured 922 positions/s, and modest 344 MB ordinary RSS. A 50M smoke or training run is **not authorized by this evidence**: it needs its own preflight memory budget, disk/checkpoint plan, parity checks and measured throughput gate. Loop 6C stops here.
