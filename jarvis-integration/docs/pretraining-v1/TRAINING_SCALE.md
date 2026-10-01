# JADE-50M training scale estimate

The fresh pilot completed **92,320 supervised positions** in **1,443 optimizer steps** (batch 2, context 32), taking **233.075 s inside training steps** and **289.571 s end to end**, including validation, probes, checkpoints and reload. Measured rates were **396.095 positions/s optimizer-only** and **318.817 positions/s end to end**. The earlier 6D warmed smoke benchmark was ~388 positions/s; all are CPU measurements on this machine, not throughput guarantees. The current train split has **92,507 tokens**, producing **2,885 document-local stride-32 windows**; one pass supervises 92,320 positions. Each full window reads 33 token IDs for 32 supervised positions, so token accesses are approximately 1.03125 times positions; these are not unique source tokens.

| Supervised positions | Approx. token accesses | Optimizer steps at batch 2 | Passes over present tiny train split | Optimizer-only lower bound | Pilot-cadence end-to-end estimate |
|---:|---:|---:|---:|---:|---:|
| 1 million | 1.031 million | 15,625 | 10.8 | 42 min | **52 min** |
| 10 million | 10.313 million | 156,250 | 108 | 7.0 h | **8.7 h** |
| 100 million | 103.125 million | 1,562,500 | 1,083 | 70.1 h | **3.63 d** |
| 500 million | 515.625 million | 7,812,500 | 5,416 | 14.6 d | **18.2 d** |
| 1 billion | 1.03125 billion | 15,625,000 | 10,832 | 29.2 d | **36.3 d** |

These pass counts demonstrate why repeating the 305 KB corpus is inappropriate. Timing is a linear estimate at the pilot's cadence and current machine; larger local corpora, checkpoint policy, thermal behavior, disk throughput and future engine changes could alter it. Parameter count (50.48M) is distinct from source tokens, token accesses and supervised positions. No row represents an authorized run.

**Recommended next data gate:** acquire at least **50 million unique train BPE tokens** of provenance-checked, diverse English (roughly 131 MB at this pilot's 2.615 bytes/token), plus at least **0.5 million tokens each** of independent validation and test documents. Use the existing ingestion and overlap reports, and improve the 2-million-entry lexical-audit ceiling before accepting much larger text. The first long JADE-50M pretraining run should be bounded to **10 million supervised positions** (approximately 156,250 optimizer steps and 8.7 h at this pilot's end-to-end rate), with scheduled validation/probes and checkpoints; assess validation and throughput before any larger budget. This is a data and training recommendation, not a run started in Loop 7A. The current pilot cannot establish a final learning-rate choice or language competence.
