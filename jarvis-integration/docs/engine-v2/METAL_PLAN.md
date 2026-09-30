# Bounded Metal feasibility study — no implementation

Recommendation: **do not make Loop 6B a full Metal rewrite**. At the tested 5M/context32/batch2 workload, Accelerate already delivers about 2,596 positions/s and AdamW occupies about 57% of the measured step. CPU optimizer/gradient-reduction profiling is the next justified experiment. A later isolated Metal kernel feasibility measurement may be useful once CPU work is measured again; no GPU speedup is asserted without measurements.

| Operation | Suitability for later Metal experiment | Main issue |
|---|---|---|
| Projection/FFN/LM-head GEMM, including gradients | Strong arithmetic candidate; compare low-level MPS matrix multiplication to CPU BLAS | Matrices have only 32 rows; dispatch/setup overhead can dominate |
| AdamW and gradient clipping | Elementwise update plus reductions; currently dominant | Global norm reduction creates dependencies; preserve clipping, epsilon, bias correction and finite checks |
| Attention | QK and value products become attractive with longer contexts | At context32 the causal products are small; masking and FP32 reduction order need parity |
| Softmax | Custom stable row reduction is feasible | Short rows, synchronization overhead and numerical stability |
| RMSNorm / GELU / residual | Simple kernels or carefully justified fusion | Memory bandwidth and extra dispatches; fusion must preserve the math contract |

Apple silicon unified memory permits shared CPU/GPU buffers without an obligatory discrete-device staging copy. It does **not** remove memory bandwidth cost, cache effects, dispatch latency, ownership hazards or the need to wait for producers. Apple's [shared storage documentation](https://developer.apple.com/documentation/metal/mtlstoragemode/shared) explicitly requires writes to finish before the other processor accesses the resource. Keep an entire forward/backward/update sequence resident when possible, rather than synchronizing after every small operation. Checkpoints and CPU validation still require completion boundaries. [Metal fences](https://developer.apple.com/documentation/metal/synchronizing-passes-with-a-fence) describe ordering between GPU passes; synchronization must follow the actual API/version selected for a future prototype.

A future bounded trial should measure end-to-end command encoding, dispatch and completion as well as kernel duration; compare against warmed Accelerate on identical weights/tokens; repeat tiny forward/gradient/finite-difference checks with explicit FP32 tolerances; and preserve a CPU fallback. Low-level GPU matrix primitives would be disclosed exactly as BLAS is here. No existing model implementation or ML framework should enter the path. This document is feasibility analysis, not evidence that Metal is faster for this workload.
