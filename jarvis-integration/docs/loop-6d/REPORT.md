# Loop 6D — JADE-50M bounded scale gate

**PASS.** This was an engineering smoke on two fixed, frozen training-token windows. It was not a corpus training run or a language-quality evaluation. The Java engine remains the reference; the C++ FP32 engine, Accelerate GEMM, model mathematics, tokenizer and corpus were unchanged. No external model, ML framework, pretrained weights or network training was used.

## Baseline and architecture

The [preflight](PREFLIGHT.md) records the Loop 6C source/tests/Git state and calculations made before 50M allocation. The source architecture formula is `(2V+C)D + L(4D²+2DF)`. The candidate is **V=1024, C=32, D=640, H=8, L=10, F=2560**, with 80 channels per head and a conventional 4D FFN. It has **50,483,200 exact parameters**: token embedding 655,360; position embedding 20,480; each attention block 1,638,400; each FFN 3,276,800; all ten blocks 49,152,000; LM head 655,360. A bounded 60M parameter / 2 GiB engineering budget replaced the 25M / 768 MiB guard. Over-cap, invalid-head, malformed-dimension and pre-allocation memory rejection tests pass. No 100M model was constructed.

## Memory

| Measure | Predicted bytes | Actual bytes |
|---|---:|---:|
| Parameters, gradients, AdamW m, AdamW v (each) | 201,932,800 | 201,932,800 |
| Persistent state | 807,731,200 | 807,731,200 |
| Reusable workspace | 17,219,712 | 17,219,712 |
| Ordinary process peak RSS | **835,756,032** | **832,684,032** |
| Transactional reload process peak RSS | **1,439,727,616** | **1,437,581,312** |
| Checkpoint file | **605,798,468** | **605,798,468** |

RSS was 5,914,624 before model construction, 207,880,192 after the parameter array, 813,727,744 after optimizer arrays and 831,045,632 after workspace construction. The 160 Tensor allocations occurred at construction; the warmed hot path reported zero new Tensor or C++ `new` calls. Ordinary RSS was 3,072,000 bytes below prediction; reload RSS was 2,146,304 below prediction. The reusable workspace includes width activations 9,093,120 bytes, FFN activations 7,208,960, logits 262,144, attention buffers 655,360 and 128 miscellaneous bytes. The exact checked preflight including three reload arrays and 64 MiB margin was 1,497,858,176 bytes, below its 2 GiB ceiling. Checkpoint disk space was prechecked. [Smoke log](smoke50m.txt).

## Numerical and four-step smoke

Random seed **26167** initialized all weights. Every model weight, gradient, moment and forward intermediate checked finite. The large-shape check exercised all ten blocks, all eight heads, causal masking and attention row sums, last-head indexing, embedding, attention projection, FFN up/down and GELU, LM head and independently recomputed cross entropy. The single-step gate used context 32 and batch 2. It took 167.689 ms: forward 23.277, loss 0.104, backward other 23.435, gradient accumulation 15.819, gradient norm 15.767, AdamW update 86.697 and gradient reset 2.590 ms. The averaged-gradient norm was 10.941638, so clipping to 1.0 used factor 0.091394. Nonzero gradients numbered 49,867,520; parameters and moments changed and remained finite.

| Step | Mean fixed-fixture loss |
|---:|---:|
| 0 | 7.008337975 |
| 1 | 5.359203339 |
| 2 | 4.912725449 |
| 3 | 3.939012766 |
| 4 | 3.587532282 |

The loss reduction was **3.420805693 (48.81%)** over exactly four optimizer steps and 256 supervised positions. A fifth step was run twice solely to verify resume equivalence; it was not part of the four-step smoke.

## Checkpoint and performance

The ignored [step-4 checkpoint](checkpoints/50M-step4.jade) has the predicted 605,798,468 bytes. A fresh engine loaded the exact architecture, full parameter/moment payload, timestep 4 and 256-position counter. The next diagnostic step matched the original state by exact payload comparison and full-state hash; the temporary step-5 checkpoint was removed. Existing corruption/truncation tests passed. Peak reload RSS was 1,437,581,312 bytes.

The performance preflight predicted about 167.8 ms/step and 381 positions/s from separate GEMM and optimizer extrapolations. After five warmups, a 40-step Accelerate benchmark measured **165.076 ms p50**, **166.850 ms p95**, **387.894 positions/s**, with 24.654 ms p50 forward, 66.059 ms p50 backward including forward, 15.741 ms p50 gradient norm and 98.919 ms p50 optimizer. Ordinary benchmark RSS was 832,684,032 bytes. See [raw samples](bench50m.txt). These timings include the same batch 2/context 32 workload as Loop 6C; they do not imply language ability.

The 20M regression [benchmark](bench20m-regression.txt) measured **947.810 positions/s** over a matched five-warmup/40-step profile, versus Loop 6C's **922.413**. No clear slowdown from the raised ceiling was observed.

## Regression and 100M arithmetic-only planner

C++ Debug, Release and ASan/UBSan CTests each passed 1/1. The existing small Java/C++ golden parity fixture remained green with unchanged tolerances. Offline `mvn -o clean package` passed **508 tests, zero failures/errors/skips**. An initial sandboxed Maven attempt could not open the local socket used by an unrelated HTTP transport test; the same offline command passed with loopback access. `git diff --check` passed. No files were staged, committed or pushed. The large checkpoint is ignored by Git.

For planning only, a conventional **V=1024, C=32, D=768, H=12, L=14, F=3072** shape has **100,687,872 exact parameters** and 64 channels/head. Its four persistent FP32 arrays total **1,611,005,952 bytes**, reusable workspace **28,278,912**, estimated ordinary RSS **1,647,017,984**, estimated transactional reload RSS **2,854,371,328**, and checkpoint **1,208,254,532 bytes**. Extrapolating the measured projection and optimizer components gives roughly **333 ms/step**, or **192 positions/s**, with substantial bandwidth/shape uncertainty. These values are estimates; the 60M guard still rejects this configuration. A separately budgeted 100M bounded smoke is reasonable to plan, but cannot be inferred safe without a new memory/disk gate. No 100M allocation, 50M corpus training or Metal work occurred.
