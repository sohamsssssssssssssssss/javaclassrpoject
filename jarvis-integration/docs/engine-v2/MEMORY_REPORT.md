# Memory measurements and limits

All sizes below are bytes. Persistent training state is exactly `4 * P * 4`: FP32 parameters, gradients, AdamW m and v. Parameters and moments update in place. Temporary Tensor buffers are allocated once at engine construction and reused across both examples; batch 2 does not duplicate the cache. Tensor peak includes persistent state and reusable workspace, but excludes object metadata, allocator overhead, executable/library pages and checkpoint-load std::vectors.

| Model | Persistent state | Reusable workspace | Tracked Tensor peak | Measured training peak RSS | Measured save peak RSS |
|---|---:|---:|---:|---:|---:|
| 58K | 933,888 | 422,016 | 1,355,904 | 7,667,712 | 7,667,712 |
| 250K | 3,702,784 | 778,368 | 4,481,152 | 11,141,120 | 11,157,504 |
| 1M | 16,842,752 | 1,884,288 | 18,727,040 | 26,198,016 | 26,198,016 |
| 5M | 84,017,152 | 4,620,416 | 88,637,568 | 95,961,088 | 95,977,472 |

5M parameter bytes = 21,004,288; gradients = 21,004,288; optimizer moments = 42,008,576. Persistent total **84,017,152** (80.125 MiB); workspace **4,620,416** (4.406 MiB); tracked total **88,637,568** (84.531 MiB). RSS is `getrusage(RUSAGE_SELF).ru_maxrss`, bytes on macOS, a process high-water observation. Training peak **95,961,088** (91.516 MiB); checkpoint save peak **95,977,472**. No Tensor allocations occurred during any timed training step. The tracker does not claim to intercept every malloc inside the runtime or BLAS.

5D's **1,087,897,600 bytes** was observed JVM used heap, not peak RSS. The new warmed Java run observed **487,063,552 bytes** used heap after steps, with max heap 1,610,612,736. Both include garbage not yet collected and can miss transient peaks. Numerically C++ training RSS is 11.34x below the old observation (91.18% lower) and 5.08x below the warmed observation (80.30% lower), but those are **different metrics**, not an apples-to-apples RSS reduction claim. The exact FP64-to-FP32 persistent scalar reduction is 50%; additional savings come from avoiding copies and garbage. No forced-GC retained-heap or paired Java RSS study was performed.

Checkpoint save streams parameters/moments and does not materialize the whole file. Load temporarily uses another `3*P*4 = 63,012,864` bytes so invalid input cannot damage the current model. The resume harness retains benchmark, micro and resumed engines simultaneously; its measured peak **336,527,360** (320.938 MiB) must be reported alongside ordinary training RSS. No OOM was caught or hidden.

Config bounds dimensions and caps parameters at 5.5M. Tensor element/byte multiplication is overflow checked. The measured ladder has ample memory headroom on the tested machine; no claim is made that every extreme combination allowed by individual dimension bounds is affordable. A future 20M engineering test requires a new explicit memory/activation plan and a separately reviewed increase of the present ceiling. No such model was allocated here.
