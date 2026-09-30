# Loop 6A measured performance

PASS: same architecture, context 32, batch 2, 64 supervised positions per optimizer step. Production-like Java JIT (`-Xmx1536m`, OpenJDK 25.0.2) versus native arm64 C++ Release, Apple clang 21.0.0, `-O3 -DNDEBUG -std=gnu++17`, warnings enabled, no fast-math. [Build evidence](build-platform.txt). Both use two warmup updates and seven timed updates, same Java seed-26167 random weights (rounded to FP32 for C++), same two 5D train windows and AdamW settings. Each process was bounded to 90 seconds, run serially, and completed. Tiny parity passed before the scale ladder.

A complete step is backward (including the required forward) plus AdamW. The separately measured forward diagnostic is **not added twice**. Throughput excludes checkpointing, initial tokenization and diagnostics. Java recomputes forward work within component backwards; C++ uses retained activations, without changing gradients. Seven samples characterize this short run, not a sustained thermal benchmark. Java timings include any observed GC/JIT interruptions; none were filtered out. Full min/median/max for every timing and throughput are retained in `java-bench-*.txt` and `bench-*.txt`.

| Model | Exact parameters | Warm Java pos/s | C++ naive pos/s | C++ Accelerate pos/s | Accelerate training peak RSS bytes |
|---|---:|---:|---:|---:|---:|
| 58K | 58368 | 5663.11 | 22811.66 | 99604.38 | 7,667,712 |
| 250K | 231424 | 2013.81 | 8500.32 | 41855.12 | 11,141,120 |
| 1M | 1052672 | 751.65 | 1642.51 | 9929.92 | 26,198,016 |
| 5M | 5251072 | 139.53 | 277.22 | 2595.61 | 95,961,088 |

| Model | Forward median ms | Backward incl. forward median ms | AdamW median ms | Step min / median / max ms | Checkpoint bytes |
|---|---:|---:|---:|---|---:|
| 58K | 0.209 | 0.460 | 0.186 | 0.639 / 0.643 / 0.737 | 700,484 |
| 250K | 0.430 | 0.922 | 0.607 | 1.507 / 1.529 / 1.560 | 2,777,156 |
| 1M | 1.542 | 3.350 | 3.055 | 6.062 / 6.445 / 13.408 | 12,632,132 |
| 5M | 4.387 | 10.374 | 14.087 | 24.379 / 24.657 / 25.218 | 63,012,932 |

Architectures in V/C/D/H/L/F order: 58K=512/32/32/4/2/128; 250K=1024/32/64/4/2/256; 1M=1024/32/128/4/4/512; 5M=1024/32/256/4/6/1024. No parameter scale increase.

5M Accelerate median **2,595.61 positions/s**, minimum 2,537.88, maximum 2,625.24. Warm Java median **139.53 positions/s**, minimum 134.24, maximum 148.66: **18.60x** median speedup. Against the reported 5D short-smoke 107.97 positions/s the ratio is **24.04x**, but that older warmup is not matched; use 18.60x as the primary comparison. Naive C++ delivers 277.22 positions/s; changing only the GEMM backend to Accelerate delivers **9.36x** end-to-end improvement. This is explicitly a BLAS benefit, not evidence that C++ syntax alone produces that speedup.

FP64 to FP32 halves scalar storage exactly. C++ also avoids immutable full-model updates, repeated transpose copies, per-operation matrix allocation and component-forward recomputation. The matched naive comparison measures these effects jointly (about 1.99x at 5M); it cannot separately attribute speed to precision, layout, caching or allocation. No unsupported attribution is made.

## Micro-training and threading

From Java random initialization, the same two 5D windows and default AdamW: four trajectory steps / 256 positions, loss **6.956243515 -> 5.001392365**, elapsed **0.45691275 s**, end-to-end 560.28 positions/s. This wall time includes checkpoint save/load, duplicate fourth-step continuation, model construction and final evaluation; there are **five actual optimizer calls**, not four. The saved step-3 checkpoint is 63,012,932 bytes. Resumed step-4 parameters/m/v and counters match exactly on this backend. Whole harness peak RSS **336,527,360 bytes**, including three simultaneous engines and transactional load arrays; this is not the ordinary one-engine training peak.

Java 5D micro: 6.956243840336 -> 5.001416298672, four trajectory steps, 256 positions, 5.017853 s, about 51.02 end-to-end positions/s. Final-loss difference is about 2.39e-5. Its harness included six actual optimizer calls and different diagnostic overhead, so the end-to-end ratio is descriptive rather than a matched kernel speedup.

Accelerate default thread policy was left unchanged; no JADE thread pool. Process CPU/wall ratio was 0.997. A separate fresh process with `VECLIB_MAXIMUM_THREADS=1` measured 2,595.74 positions/s and CPU/wall 0.998, essentially unchanged. This suggests no material multi-core speedup for these skinny context-32 matrices; it does not prove a fixed internal BLAS thread count. Accelerate is allowed to choose execution resources. [Apple threading options](https://developer.apple.com/documentation/accelerate/blas_threading_max_options) describe framework-selected versus single-thread execution.

The new dominant measured component is **AdamW**, 14.087 ms of a 24.657 ms median step (approximately 57%; component medians need not add exactly). Backward including forward is 10.374 ms. Next work should profile/vectorize the optimizer and gradient reductions before adding custom threads or a whole Metal backend. No full epoch, 20M model, GPU implementation or application integration ran.
