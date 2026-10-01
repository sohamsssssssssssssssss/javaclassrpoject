# JADE — Archive State & Reproduction Guide

> **Date**: October 1, 2026
> **Repository**: [https://github.com/sohamsssssssssssssssss/javaclassrpoject.git](https://github.com/sohamsssssssssssssssss/javaclassrpoject.git)
> **Branch**: `jade-main-integration`
> **Status**: Archived on GitHub (Safe for local clean/re-clone)
> **Latest Completed Milestone**: **Loop 7A** (Pretraining Pipeline v1 Pilot & Verified 50M Engine)
> **Next Planned Milestone**: Larger Real English Corpus Pretraining (Multi-Epoch Corpus Scaling)

---

## 1. Executive Summary

This document captures the complete architectural, algorithmic, operational, and reproduction state of the **JADE (Java/C++ Adaptive Development Engine) 50M Brain**.
Every component in this repository is built strictly from scratch without external ML frameworks (no PyTorch, TensorFlow, Ollama, external model APIs, or pretrained weights).

The system consists of:
1. **Java Reference Implementation**: A mathematically verified reference implementation (`src/main/java/com/jade/brain/` and `jarvis-integration/src/main/java/com/jade/brain/`) containing BPE tokenizer training/serialization, transformer forward/backward layers, SGD/AdamW optimizers, and comprehensive golden unit tests (512 passing tests).
2. **C++ Native FP32 Engine (`jade-engine`)**: A high-performance, single-header/modular native engine (`jarvis-integration/jade-engine/`) leveraging Apple Accelerate framework for hardware-accelerated BLAS GEMMs, zero-hot-allocation forward/backward passes, causal attention, GELU, RMSNorm, AdamW optimization, memory-mapped/buffered dataset streaming, and transactional CRC32-validated checkpointing.
3. **Persisted Tokenizer Artifact (`data/tokenizer/jade-bpe-1024.v1`)**: A deterministic 1024-token Byte-BPE vocabulary and merge table trained on a curated public-domain diverse English corpus, validated between Java and C++.
4. **Interactive Chat CLI (`jade_chat` & `run-chat.sh`)**: Direct transformer text generation supporting greedy decoding and deterministic top-$k$/temperature sampling.

---

## 2. Latest Model Architecture (JADE-50M)

The primary candidate architecture is the **50M Transformer** designed and validated in Loops 6D & 7A:

- **Formula**: `Parameters = (2V + C)D + L * (4D² + 2DF)`
- **Configuration**:
  - Vocabulary Size ($V$): **1024** tokens
  - Context Window ($C$): **32** tokens
  - Model Dimension ($D$): **640**
  - Attention Heads ($H$): **8** (Head width $D/H = 80$)
  - Transformer Layers ($L$): **10**
  - Feed-Forward Dimension ($F$): **2560** ($4D$)
  - **Exact Trainable Parameters**: **50,483,200**

### Parameter Breakdown
| Component | Dimensions | Parameter Count |
|---|---|---:|
| Token Embeddings | $V \times D = 1024 \times 640$ | 655,360 |
| Position Embeddings | $C \times D = 32 \times 640$ | 20,480 |
| Attention per block ($Q, K, V, Out$) | $4 \times D^2 = 4 \times 640^2$ | 1,638,400 |
| FFN per block ($Up, Down$) | $2 \times D \times F = 2 \times 640 \times 2560$ | 3,276,800 |
| Total per Transformer Block | Attention + FFN | 4,915,200 |
| Stack of 10 Blocks | $10 \times 4,915,200$ | 49,152,000 |
| Un-tied LM Head Projection | $D \times V = 640 \times 1024$ | 655,360 |
| **Total Model Parameters** | | **50,483,200** |

### Memory Footprint (FP32)
- Parameters (weights): **201,932,800 bytes** (~192.6 MB)
- Gradients: **201,932,800 bytes**
- AdamW First Moments ($m$): **201,932,800 bytes**
- AdamW Second Moments ($v$): **201,932,800 bytes**
- **Persistent State Total**: **807,731,200 bytes** (~770.3 MB)
- **Reusable Forward/Backward Workspace**: **17,219,712 bytes** (~16.4 MB)
- **Checkpoint File Size**: **605,798,468 bytes** (Header 68 bytes + $3 \times 50,483,200 \times 4$ payload + 4 CRC32)

---

## 3. Tokenizer Configuration

- **Type**: Strict UTF-8 Byte BPE (`ByteBpeTokenizer` in Java, `jade::Tokenizer` in C++)
- **Base Vocabulary**: 256 byte-level tokens (IDs 0–255)
- **Learned Merges**: 768 merges (IDs 256–1023), each assigned a unique integer rank (0–767)
- **Total Vocabulary Size**: 1024 tokens
- **Persistence Artifact**: `jarvis-integration/data/tokenizer/jade-bpe-1024.v1`
  - SHA-256 Digest: `73ca92067dccc1dc24198a4c8596ae8b95b62e2c0393fca1f02ea5d9aaa094a7`
  - Size: 9,216 bytes (9.0 KB)
  - Committed in Git repository
- **Golden Parity**: Tested across 12 strict edge-cases (Unicode, numbers, punctuation, dialogues, contractions) with zero discrepancies between Java and C++.

---

## 4. Latest Verified Training State

### Loop 6D — 50M Bounded Scale Gate
- Ran 4-step training smoke on two frozen 32-token windows
- Initial step loss: **7.0083** $\rightarrow$ Step 4 loss: **3.5875** (48.81% reduction)
- Verified checkpoint resume identity and transactional reload peak RSS (1,437 MB)

### Loop 7A — Pretraining Pipeline v1 Pilot
- **Run**: `pilot-7a-fresh-26167`
- **Initial Weight Seed**: Deterministic `26167` (fresh initialization)
- **Dataset**: Curated 11-document diverse English corpus (244 KB training text, 92,507 tokens, 2,885 sequence windows)
- **Total Steps**: **1,443 optimizer steps** (92,320 supervised positions) in 289.5 seconds wall-time
- **Loss Progression**:
  - Initial train loss: **7.0469** $\rightarrow$ Final train loss: **5.6428**
  - Initial validation loss: **7.0181** $\rightarrow$ Final validation loss: **5.8270**
  - Validation perplexity dropped from **1,116.6** to **339.3**
- **Inference Latency & Throughput**:
  - Time-to-first-token: **9.16 ms**
  - Generation throughput: **49.77 tokens/sec**
  - Checkpoint load time: **3.42 seconds**

---

## 5. Checkpoint Inventory & Git Storage Policy

### Why Large Checkpoints Are Not in Git
Each 50M parameter checkpoint stores weights, AdamW first moments, and second moments in full FP32 precision with a CRC32 integrity trailer. The resulting files are **605.8 MB each**. Storing binary checkpoint files of this magnitude in GitHub would exceed file/repository limits and bloat repository history. They are therefore explicitly ignored via `.gitignore`.

### Summary of Local Checkpoints (Not Stored on GitHub)
| Checkpoint Path | Milestone | Steps / Positions | File Size |
|---|---|---|---:|
| `jarvis-integration/data/checkpoints/pilot-7a-fresh-26167/final.jade` | Loop 7A Pilot | Step 1,443 / 92,320 pos | 605,798,468 bytes (~578 MB) |
| `jarvis-integration/data/checkpoints/pilot-7a-fresh-26167/periodic-step1200.jade` | Loop 7A Pilot | Step 1,200 / 76,800 pos | 605,798,468 bytes (~578 MB) |
| `jarvis-integration/data/checkpoints/pilot-7a-fresh-26167/periodic-step800.jade` | Loop 7A Pilot | Step 800 / 51,200 pos | 605,798,468 bytes (~578 MB) |
| `jarvis-integration/docs/loop-6d/checkpoints/50M-step4.jade` | Loop 6D Smoke | Step 4 / 256 pos | 605,798,468 bytes (~578 MB) |
| `jarvis-integration/docs/loop-6c/checkpoints/20M-step4.jade` | Loop 6C Smoke | Step 4 / 256 pos | 242,393,156 bytes (~231 MB) |
| `jarvis-integration/docs/engine-v2/checkpoints/5M-micro-step3.jade` | Loop 6A | Step 3 / 192 pos | 63,013,188 bytes (~60 MB) |
| `docs/loop-5e/checkpoints/jade-5m-epoch-1.jade` | Loop 5E | Epoch 1 | 126,025,972 bytes (~120 MB) |
| `docs/loop-5e/checkpoints/jade-5m-initial.jade` | Loop 5E | Initial | 126,025,972 bytes (~120 MB) |

### Re-creating Any Checkpoint From Scratch
Because training is 100% deterministic:
- Any checkpoint can be reproduced identically by running the pipeline with seed `26167` on the specified training data.
- Loop 6D step 4 is reproduced by `jade_engine_50m_smoke`.
- Loop 7A step 1,443 is reproduced by `jade_pretrain` using `jarvis-integration/data/processed/pilot-v1/train.jtok`.

---

## 6. How to Rebuild and Test the Project

### System Prerequisites
- **macOS** on Apple Silicon (arm64) or x86_64
- **Apple Accelerate Framework** (standard on macOS)
- **CMake** 3.20+
- **Apple Clang** or LLVM supporting C++17
- **Java JDK** 21+ (Java 25 verified)
- **Maven** 3.9+

### Rebuilding C++ Engine & CLI
```bash
cd jarvis-integration/jade-engine
mkdir -p build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build . -j4
```

### Running C++ Tests
```bash
cd jarvis-integration/jade-engine/build
ctest --output-on-failure
```
Expected output:
```
1/2 Test #1: jade_engine_tests ................   Passed
2/2 Test #2: jade_pretraining_tests ...........   Passed
100% tests passed, 0 tests failed out of 2
```

### Running Java Tests & Building Uber-JAR
```bash
cd jarvis-integration
mvn clean package
```
Expected output:
```
Tests run: 512, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## 7. How to Launch and Use `jade_chat`

The repository provides a unified launch script `run-chat.sh` at the root of `jarvis-integration`:

```bash
# From repository root:
./run-chat.sh
```

Or execute the C++ binary directly:
```bash
./jarvis-integration/jade-engine/build/jade_chat \
  --checkpoint jarvis-integration/docs/loop-6d/checkpoints/50M-step4.jade \
  --merges jarvis-integration/jade-engine/data/merges-1024.txt
```

### Interactive Commands
- Type your prompt and press Enter to generate text.
- `/info`: Displays current model architecture, parameter count, checkpoint path, and step counters.
- `/clear`: Clears the terminal screen.
- `/exit`: Exits the interactive CLI session.

---

## 8. How to Resume the Project Later

When returning to JADE:
1. **Clone the repository**:
   ```bash
   git clone -b jade-main-integration https://github.com/sohamsssssssssssssssss/javaclassrpoject.git
   cd javaclassrpoject/jarvis-integration
   ```
2. **Verify tests immediately**:
   ```bash
   (cd jade-engine && cmake -B build && cmake --build build && ctest --test-dir build)
   mvn test
   ```
3. **Run Pretraining on New Data**:
   - Prepare a new raw text corpus in a directory (e.g., `data/raw/english-corpus/`).
   - Tokenize the corpus using `com.jade.brain.training.PretrainingDataset` or `jade_pretrain --prep`.
   - Run training with `jade_pretrain` specifying epochs, batch size, learning rate schedule, and checkpoint directories.
4. **Scale Model Capacity**:
   - The engine architecture is parameter-governed by `Config{V, C, D, H, L, F}`.
   - Engineering bounds allow scaling up to 60,000,000 parameters and 2 GiB peak memory under the verified `EngineeringLimits` safety harness.

---

## 9. Verification & Integrity Checklist

- [x] Java test suite passes: 512 tests, 0 errors, 0 failures.
- [x] C++ test suite passes: 2/2 tests green.
- [x] No API keys, credentials, or proprietary weights present.
- [x] All heavy binary `.jade` models excluded via `.gitignore`.
- [x] Small tokenizer artifact `jade-bpe-1024.v1` preserved.
- [x] Complete provenance, baseline chats, and engineering reports preserved in `docs/`.
