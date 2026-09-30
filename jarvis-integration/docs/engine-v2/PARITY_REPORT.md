# Numerical parity

PASS on Apple arm64 in Debug, Release and AddressSanitizer + UndefinedBehaviorSanitizer builds. One CTest executable contains **14,355 checked conditions** per normal build, not 14,355 separately registered tests. No warning suppression or fast-math flags. Raw results: [Release](cpp-tests-release.txt), [Debug](cpp-tests-debug.txt), [sanitizers](cpp-tests-sanitizers.txt).

Tiny architecture V=32,C=4,D=8,H=2,L=1,F=16 has exactly 1,056 parameters. `ParityFixture.java` exports Java random weights, input [1,2,3,4], targets [2,3,4,5], 17 major forward arrays, every analytical parameter gradient and three-step AdamW p/m/v references. Java components reconstruct the trace without modifying production code; loss and gradients come from the production model. C++ loads those same values rounded to FP32. Causal score entries and their masked matrix are checked together; future entries are diagnostic -1e9 sentinels and never participate in softmax.

| Quantity | Naive FP32 | Accelerate FP32 |
|---|---:|---:|
| Worst forward absolute error | 2.02617486122e-7 | 2.02617486122e-7 |
| Worst forward relative error, denominator floor 1e-6 | 3.51293813908e-5 | 3.51293813908e-5 |
| Java loss | 3.43300611462 | 3.43300611462 |
| C++ loss | 3.43300604820 | 3.43300604820 |
| Absolute loss difference | 6.642e-8 | 6.642e-8 |
| Worst gradient absolute error | 5.67965446896e-8 | 5.28636426855e-8 |
| Worst gradient relative error, denominator floor 1e-6 | 3.91959648512e-5 | 3.91959648512e-5 |
| Finite-difference worst absolute error | 1.01238489151e-4 | 1.01223587990e-4 |
| Finite-difference worst relative error, denominator floor 1e-3 | 0.084014784079 | 0.084014784079 |
| Three-step AdamW worst absolute p/m/v error | 1.86854472173e-8 | 1.86854472173e-8 |

Forward acceptance: `abs <= 2e-6 + 2e-5*abs(reference)`. Analytical gradient acceptance: `abs <= 3e-6 + 3e-4*abs(reference)`. Central finite differences perturb **all 1,056 parameters**, epsilon=0.001, acceptance `abs < 0.002 + 0.03*abs(analytical)`. Float loss subtraction limits this check; relative error with a floor is reported explicitly rather than hiding tiny-gradient instability. AdamW tolerance is 3e-6 over three repeated fixed-gradient steps; loss subsequently decreases.

Tests additionally cover shape/indexing/overflow, known non-square signed GEMM, zeros and beta=0 overwrite, seeded random rectangular products in all transpose combinations against double accumulation, Accelerate parity, RMSNorm derivatives and large finite inputs, tanh-GELU derivatives/saturation, stable softmax normalization, causal invariance, cross entropy, deterministic token generation, parameter updates, checkpoint round trip, counters, resumed updates, corruption/truncation/architecture rejection without mutation, and nonfinite optimizer settings. The 5M micro-test independently compares parameters **and both moments** after resume.

FP32 is stable in these bounded fixtures and the 5M four-step experiment. This establishes the implemented contract at tested scales; it does not prove arbitrary long-run convergence or language quality. Full Java checkpoint interoperability, variable-length batching, tokenization in C++, and full-corpus C++ training are outside this engine loop.
