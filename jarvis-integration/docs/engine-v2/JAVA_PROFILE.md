# Java reference profile before C++ implementation

Platform: Apple arm64, OpenJDK 25.0.2, Java-21-compatible JADE classes, `-Xmx1536m`. Model 1024/32/256/4/6/1024, batch 2. JFR `profile` recorded an 8-second existing 5M smoke including tokenizer fitting, initialization, updates and checkpointing; [the raw recording](java-5m.jfr) and [standalone component samples](java-components.tsv) are retained. A separate unchanged-engine operation harness warmed twice, then measured seven samples per component and reports minimum/median/maximum. Component calls use representative 32-position matrices and are **not additive step accounting**: backward calls include internal forward recomputation and GEMM. JFR samples are statistical, not exact percentages of elapsed wall time.

The existing smoke measured a two-window training step at **0.617038 s / 103.72 supervised positions/s**; backward including forward was 0.462553 s, AdamW 0.154486 s, and separate forward diagnostic 0.124654 s. The result agrees in scale with the 5D baseline of 0.592754 s / 107.97 positions/s. JFR collected 660 execution samples, 2,259 allocation samples, and 81 garbage-collection events across the whole 8-second recording.

| Rank | Component | Measured median | Allocation/copy observation |
|---|---|---:|---|
| 1 | AdamW plus global clip, entire 5M model | 120.349 ms | Copies every parameter and both moment matrices; creates replacement matrices and model. Gradient norm scans all scalars with `Math.hypot`. |
| 2 | FFN backward, **one** layer/example | 12.423 ms | Recomputes up projection and GELU; transpose/multiply and new matrices. Six layers and two examples amplify this. |
| 3 | Attention backward, **one** layer/example | 6.727 ms | Recomputes Q/K/V, probabilities and output projection; allocates gradients and transpose products. |
| 4 | FFN forward up + down, **one** layer/example | 2.180 + 2.374 ms | Two large `Matrix.multiply` calls, each creates `double[][]` then copies into an immutable `Matrix`. |
| 5 | Q/K/V + output projections, **one** layer/example | 0.607 + 0.588 + 0.586 + 0.580 ms | Four additional matrix products per block. |
| 6 | LM head, **one** example | 2.178 ms | `[32,256] x [256,1024]`; logits are later copied by `toArray()`. |
| 7 | Full parameter `toArray()` pass | 2.583 ms | Copies all 5,251,072 FP64 scalars once; AdamW does more than one such pass. |
| 8 | Two-example gradient accumulation copy/add | 3.197 ms | Allocates dense sums for every parameter and materializes a new gradient map. |
| 9 | One 126 MB checkpoint save | 168.789 ms | `ByteArrayOutputStream` plus final copy/hash and atomic file write; measured separately. |

Other medians: embedding lookup 0.0045 ms, RMSNorm 0.0129 ms, causal score loops 0.0736 ms, 32-way softmax 0.00034 ms, attention value aggregation 0.0768 ms, GELU 0.3135 ms, cross entropy including copied logits 0.0817 ms, embedding backward scatter 0.1729 ms. Full min/median/max values are in the TSV. Attention's softmax and score math are minor at context 32 compared with its projections and backward GEMMs.

JFR top leaf frame was `Matrix.multiply` in **248/660 samples**; tokenizer fitting/encoding occupied many startup samples, so 248/660 is not a training-only CPU percentage. The largest allocation-sample leaf was `Matrix.<init>` (688), followed by `Matrix.multiply` (192), `Matrix.row` (138), and `Matrix.transpose` (124). JFR allocation weights are sampled extrapolations and should not be mistaken for retained heap. The 5D smoke observed about 1.09 GB of used JVM heap; this recording observed 1.058 GB after smoke/resume. Source inspection explains the pressure: `Matrix.multiply`, `add`, norm, GELU and transpose allocate fresh `double[][]`, then `Matrix` copies it; AdamW duplicates parameters/moments every step; batch accumulation allocates another dense gradient set; checkpoint serialization holds large byte buffers.

No Java model, math, optimizer, tokenizer, or training code was changed for profiling. The harness lives in this documentation directory and is excluded from production Maven sources.
