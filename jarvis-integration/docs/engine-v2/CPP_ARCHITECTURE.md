# C++ performance engine boundary

The Java Brain remains the reference implementation and still owns BPE, corpus provenance, partitioning and application integration. No Java production code changed in Loop 6A. `jade-engine/` is a separate C++17 library, a focused numerical test executable and a bounded benchmark executable. No UI, voice, parser or AnswerService changes were made by this loop; preexisting working-tree changes were preserved.

`include/jade/engine.hpp` defines move-only contiguous row-major Tensor storage, validated Config, parameter registry, forward/backward engine and AdamW/checkpoint API. `src/gemm.cpp` contains storage and numerical primitives; `src/engine.cpp` contains the exact reference transformer, analytical reverse pass, in-place optimizer and greedy token-ID generation; `src/checkpoint.cpp` contains persistence. Small related operations share modules rather than a separate file per operation.

All learned arrays, gradients, moments, activations and GEMM operands are FP32. Selected scalar reductions (RMSNorm squares/dot, softmax sums, gradient norm, bias correction and loss accumulation) use double for stability; this is not a pure FP32 arithmetic claim. There is no FP16, BF16, quantization, autodiff library or external model. Java FP64 and C++ FP32 are compared with explicit tolerances, not bit identity.

Both NAIVE and ACCELERATE GEMM remain available. Accelerate implements only `cblas_sgemm` for projection and reverse-pass matrix products. JADE owns attention, masking, normalization, GELU, loss, reverse pass, optimizer, checkpoint and generation. No pretrained parameters are used. Benchmark initialization is exported directly from Java seed 26167 and rounded to float. The standalone C++ initializer uses `mt19937_64` and normal(0,0.02); it is deterministic within this toolchain, but is not claimed to reproduce Java RNG or to be portable bit-for-bit across standard libraries.

Four persistent arrays hold parameters, gradient sum, first and second moments. A reusable context-sized cache and backward scratch serve both examples sequentially. `backward` adds one example's mean gradient; `adamw(settings, examples)` averages it and applies global clipping. This loop's batch API requires equal-length examples (32 positions here); it is not a variable-length weighted-batch trainer. Call `zero_grad()` at the batch boundary. The benchmark verifies zero Tensor allocations in timed steps. Registry/trace/log vectors and platform allocations are outside Tensor accounting. Tensor bounds assert in Debug, shape multiplication is overflow checked, ownership is RAII and copying is disabled. No custom allocator or thread pool was introduced.

## Checkpoint v1

Separate from Java checkpoint v2; there is no Java checkpoint converter. On this little-endian IEEE-754 Apple arm64 target: 8-byte `JADEFP32` magic, uint32 version=1, uint32 numeric type=1, six uint32 architecture values, uint64 parameter count, uint64 step and position counters, then FP32 parameters/m/v in registry order and CRC32. Total size is `68 + 12P` bytes. Big-endian interchange is not supported. Gradients/caches are reconstructed. Optimizer hyperparameters and corpus/tokenizer identities are external run metadata in these reports/fixture identities, not fields in this small engine checkpoint; callers must use the same settings on resume. This is an engine micro-test format, not a replacement for the identity-rich Java corpus trainer.

Save streams to a same-directory temporary file, flushes/closes, fsyncs and renames; a failed save removes the temporary file. CRC32 detects accidental corruption, not malicious tampering. Load validates size, magic/version/type, architecture/count, checksum and finite parameters/moments before changing the engine. Three temporary load arrays intentionally preserve the prior state on rejection. The same-process destination is not designed for concurrent writers. Power-loss directory-entry durability beyond fsync+rename is not claimed. Nonfinite optimizer inputs/gradients and invalid configuration are rejected. A nonfinite update aborts; optimizer steps are not transactional rollback operations.

The retained 5M checkpoint is `docs/engine-v2/checkpoints/5M-micro-step3.jade`, 63,012,932 bytes, after three trajectory steps / 192 positions. It was loaded and continued to step 4 in parallel with the in-memory trajectory with identical FP32 parameters and both moments. `git check-ignore` confirms it is ignored. Intermediate benchmark checkpoints and random initialization exports were under ignored `target/` and removed by the required Maven clean. The exporters below reproduce them.

## Reproduce from repository root

```sh
mvn -o test-compile
javac -cp target/classes:target/test-classes -d target/test-classes docs/engine-v2/ParityFixture.java docs/engine-v2/BenchmarkFixtures.java docs/engine-v2/JavaStepBenchmark.java docs/engine-v2/JavaProfileHarness.java
java -Xmx1536m -cp target/classes:target/test-classes com.jade.brain.ParityFixture > /tmp/jade-tiny-parity.txt
java -Xmx1536m -cp target/classes:target/test-classes com.jade.brain.BenchmarkFixtures src/test/resources/brain/diverse-english target/engine-v2/fixtures
cmake -S jade-engine -B target/jade-engine-release -DCMAKE_BUILD_TYPE=Release
cmake --build target/jade-engine-release -j 4
ctest --test-dir target/jade-engine-release --output-on-failure
# Each benchmark process was externally bounded to 90 seconds.
target/jade-engine-release/jade_engine_bench 5M accelerate target/engine-v2/fixtures target/engine-v2/checkpoints
java -Xmx1536m -cp target/classes:target/test-classes com.jade.brain.JavaStepBenchmark 5M src/test/resources/brain/diverse-english
```

Use `-DCMAKE_BUILD_TYPE=Debug` in a separate build directory for assertions. The sanitizer build additionally used `-DCMAKE_CXX_FLAGS='-fsanitize=address,undefined -fno-omit-frame-pointer'`. No build dependencies were downloaded. No network is used by training. Config enforces the Loop 6A ceiling of 5.5M parameters; no larger model was allocated.
