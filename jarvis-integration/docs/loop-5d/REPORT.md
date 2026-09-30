# JADE Brain — Loop 5D

LOOP 5D: PASS

PURE JAVA: YES. EXTERNAL ML RUNTIME: NONE. PRETRAINED WEIGHTS: NO.

The tokenizer, transformer, causal attention, forward/backward, loss, gradients, AdamW, trainer, checkpoint codec and generation remain JADE implementations using Java/JDK APIs and FP64 double arrays. No dependencies were added. Existing application voice/native and remote-provider code is outside the Brain training path and was not modified for this loop. Regression runs use mocked providers; no real external model or API key was used.

Bounded phase-0 findings: the 5C experiment artifact matches 58,368 parameters, 4,467.699168 supervised positions/sec smoke, 37,429 bytes / 20 files, validation loss 0.801706600070 and test loss 0.826042642301. The working tree already contained partial 5D work: a 309,999-byte corpus, exact planner, 5.5M configuration ceiling, 160 MiB checkpoint ceiling, overlap/quality utilities and a benchmark that had failed at 1024 BPE with `Training work limit exceeded`. This differs from the last complete 5C state; that incomplete run was not accepted as a pass. The 5C code/docs/artifacts and unrelated working-tree changes were preserved.

The existing BPE fitting path shared the 32M encoding pair-scan budget even though fitting repeatedly scans the entire sample for hundreds of merges. Fitting now has a separate finite 128M pair-scan budget; input/vocabulary/merge ceilings and the 32M per-input encoding guard remain. Parameter planning matches each ladder model's actual tensor registry. The persistent-state estimate now includes gradients. The scale harness uses token IDs from the corresponding frozen tokenizer, rather than attaching 1024 vocabulary semantics to 512-generated IDs. Step timing reports a real two-window batch and does not add an extra diagnostic forward pass to training-step time.

Graphify was read but not executed: its Python workflow conflicts with the user's absolute no-Python rule. Inspection used bounded file reads and ripgrep. No unrelated JADE audit, UI launch or AnswerService integration was performed.

CORPUS:

- Accepted files: 11; skipped: 0.
- Categories: dialogue/social narrative; scientific explanation/cause and effect; autobiography/practical reasoning; descriptive/philosophical prose; dialogue/questions/fantasy; comparison/argument/explanation; historical narrative/factual prose; descriptive narrative/definitions; procedures/technical/QA/everyday English; independent detective dialogue; independent speculative explanation.
- Bytes: **304,963** (297.815 KiB).
- Code points: **301,209**.
- Provenance: 10 pre-existing known public-domain historical English excerpts, plus 8,530 bytes of independently written original JADE prose. This is curated historical/original prose, not natural web English and not a 250 KiB repeated-template fixture.
- Train bytes: **244,099** across 9 whole documents.
- Validation bytes: **30,632**, Doyle alone, independently authored.
- Test bytes: **30,232**, Wells alone, independently authored.
- Corpus digest: `1ee4db08e1af1b8f7540021801cc4c4774142bad20c67a76977d57174a41d6ba`.
- Relative paths, categories, byte/code-point counts, SHA-256 and provenance type: [manifest.tsv](manifest.tsv). Source URL, source digest, author/translator/editor death information and deterministic excerpt procedure: [provenance.tsv](provenance.tsv).

Whole-document sequential partitioning uses sorted paths and fractions 0.82 / 0.09 / remainder; the manifest fixes the exact documents. BPE fits a **65,369-byte balanced TRAIN-only sample**, not the full training partition. Holdouts use frozen BPE. Chunked token stores and lazy windows are separate for each partition; windows may cross documents within a partition, never train/validation/test boundaries. Test token counting and lexical auditing are preprocessing only; aggregate test loss is evaluated once after training is frozen, followed by the predeclared test-prefix diagnostics. Neither influences selection/training.

Removed 5,036 bytes of Machiavelli editorial front matter containing a Latin quotation and Italian bibliography; the preparation utility now reproduces that documented removal. No new source downloads were performed in this continuation. The explicit Java public-domain preparation utility has bounded HTTP retrieval and is never called by tests or training. English is the curated training language; alphabetic heuristics alone do not prove language identity.

OVERLAP AUDIT:

- Exact duplicate nonblank stripped lines across partitions: **0** distinct shared strings.
- Exact duplicate paragraphs across partitions: **0**.
- Normalized duplicate sentence strings across partitions: **5**: `mr`, `mrs`, `my dear mr`, `what`, `yes`.
- Other similarity findings: **0** suspicious paragraph pairs at five-word-shingle Jaccard >= 0.8, across **128,293** cross-partition pairs.

Normalization is NFKC, lowercase, punctuation-to-space; Java English BreakIterator can split abbreviations into fragments. These five low-information matches are reported, not suppressed; they are not copied passages. Similarity compares paragraphs of at least 20 words, ignores exact copies already counted, and requires size ratio >= 0.8. This is a bounded lexical screen, not semantic deduplication or a comprehensive contamination/memorization audit. One validation author and one test author remain narrow holdouts. Source digests and authorship independence provide stronger isolation than name/number substitutions.

SELECTED VOCAB: **1024**. Predeclared rule: at least 15% fewer validation tokens and fit time at most 4x 512. Observed reduction **19.6990%**, fit ratio **2.6186x**; independent sentence compression also improves. 2048 was omitted because 512/1024 answer the current decision without extending fitting work. The 58K control retains **512** to isolate data quality.

PARAMETER PLANNER:

Exact formula: `P = (2V + C)D + L(4D² + 2DF)`. Untied token embedding and LM head, learned position embeddings, bias-free Q/K/V/output and two FFN projections, fixed unit RMSNorm; no learned norm scales or biases. Heads affect attention layout, not parameter count. Arithmetic uses checked long operations. The deterministic search uses D in 64/96/128/160/192/224/256, L in 2/4/6/8, H=4, F=4D, selecting nearest exact target within its range; no architecture changes merely to round a count.

| Target | Architecture V/C/D/H/L/F | Exact parameters |
|---|---|---|
| 58K | 512/32/32/4/2/128 | 58,368 |
| 250K | 1024/32/64/4/2/256 | 231,424 |
| 1M | 1024/32/128/4/4/512 | 1,052,672 |
| 5M | 1024/32/256/4/6/1024 | 5,251,072 |

MEMORY ESTIMATES (bytes, FP64, batch 2):

| Model | Parameters | Gradients | First moments | Second moments | Persistent training state | Estimated peak working bytes |
|---|---|---|---|---|---|---|
| 58K | 466,944 | 466,944 | 466,944 | 466,944 | 1,867,776 | 74,842,112 |
| 250K | 1,851,392 | 1,851,392 | 1,851,392 | 1,851,392 | 7,405,568 | 96,993,280 |
| 1M | 8,421,376 | 8,421,376 | 8,421,376 | 8,421,376 | 33,685,504 | 202,113,024 |
| 5M | 42,008,576 | 42,008,576 | 42,008,576 | 42,008,576 | 168,034,304 | 739,508,224 |

| Model | Immutable/update copies | Activation caches | One logits tensor | One triangular attention probability cache | Batch token storage | Estimated checkpoint payload |
|---|---|---|---|---|---|---|
| 58K | 3,268,608 | 589,824 | 131,072 | 16,896 | 768 | 1,466,368 |
| 250K | 12,959,744 | 1,179,648 | 262,144 | 16,896 | 768 | 5,619,712 |
| 1M | 58,949,632 | 4,718,592 | 262,144 | 16,896 | 768 | 25,329,664 |
| 5M | 294,060,032 | 14,155,776 | 262,144 | 16,896 | 768 | 126,091,264 |

Persistent state includes a full gradient set even when it is not retained between steps. Batch examples run sequentially; no batch-sized activation tensor is retained. Activation estimate is `8LC(12D+6F)`; one attention payload is `8HC(C+1)/2`. Training estimate adds seven full parameter-sized copy buffers, six logit buffers, three attention buffers and batch storage. Checkpoint estimate uses four serialized-payload buffers plus persistent state; the larger of the two gets a 64 MiB row-object/JVM allowance. Allocation gate requires estimate <= 75% child heap.

These are payload-based live-working estimates, **not measured heap or a hard upper bound on heap occupancy**. Retained benchmark comparison snapshots and uncollected garbage can exceed them. The 5M after-smoke observation is 1,087,897,600 bytes, above the 739,508,224 estimate, but below the 1,610,612,736-byte (`-Xmx1536m`) child limit. No OOM occurred. Use that measured observation when setting JVM headroom; do not treat the estimate as an RSS guarantee or extrapolate to larger models.

BENCHMARK METHODOLOGY:

JDK 25.0.2 running Java-21-compatible code; deterministic initialization seed 26167. Every scale has a separate JVM with `-Xmx1536m` and a **90-second process deadline**. An unsafe scale blocks larger scales. A measured operation sum >10 seconds fails the scale; smoke has a 30-second trainer budget and resume has 10 seconds, with the process deadline as the hard backstop. All scales passed. The control child has a **240-second process deadline**, plus a 230-second training budget.

Two representative training windows at one-third and two-thirds of each encoded partition are used. 250K/1M/5M use the same 1024-tokenizer data; 58K uses its original 512 vocabulary and corresponding corpus positions. This avoids mismatched token semantics but means the 58K-to-250K comparison also changes tokenization. Timings are short cold/partially warmed CPU measurements, not JMH or sustained throughput. Backward time includes its required forward pass and batch-gradient accumulation. Training step = backward-including-forward + clipped AdamW; the separately measured forward is diagnostic. Three-step smoke time includes train/validation evaluations, so its tokens/sec is conservative, not a pure optimizer-loop rate. Heap observations use `Runtime.totalMemory()-freeMemory()` without forcing GC; they include transient garbage and are not RSS or a measured peak.

JADE-5M CANDIDATE:

- Vocabulary: **1024**.
- Context: **32**.
- Embedding: **256**.
- Heads: **4**.
- Layers: **6**.
- FFN: **1024**.
- EXACT PARAMETERS: **5,251,072**, within 4.5–5.5M.

5M MICRO-TRAINING:

- Attempted: **YES**, after the finite measured step passed the scale safety gate.
- Steps: **4** in the reported training trajectory; **256 supervised positions**.
- Initial loss: **6.956243840336** on the two-window micro dataset.
- Final loss: **5.001416298672** on the same dataset.
- Wall time: **5.017853 seconds**, including initialization, diagnostic step, evaluations, save/load and resume comparison; fitting is outside this timing.
- End-to-end trajectory rate: **51.017829 positions/sec**.
- Checkpoint: **126,028,032 bytes**, after trajectory step 3 / 192 positions; filename/path in [scale-5M.txt](scale-5M.txt).
- Resume: **PASS**, fourth-step result bit-identical to uninterrupted continuation for weights and both moments, with matching optimizer/token counters.
- Result: **PASS**, finite forward/loss/gradients/AdamW/checkpoint/resume and moved loss.

A separate one-step diagnostic and a duplicate fourth step for the resume comparison execute two additional updates on independent branches: **six actual optimizer calls / 384 positions** total for this scale, not six steps in the reported training trajectory. The 3-step smoke and resumed fourth step are the micro experiment; there is no separate hidden larger training run. Micro loss reduction on two windows establishes engine operation, not generalization.

58K DIVERSE-CORPUS CONTROL:

Existing architecture 512/32/32/4/2/128, 58,368 parameters. AdamW lr 0.003, betas 0.9/0.999, epsilon 1e-8, decay 0.01, clip 1.0, batch 4, context/stride 32; initialization seed 26167, deterministic shuffle seed 42. Two epochs, **1,840 optimizer steps / 235,520 supervised positions**, **23.431330 seconds**, including evaluation/checkpoint/probes/generation. Epoch-one checkpoint **1,401,888 bytes**, loaded for epoch two. Original 5C template loss is not comparable evidence of broad ability: the diverse independent holdout is much harder.

HELD-OUT TEST: loss **4.122185075752**, perplexity **61.693900938126**, 14,880 supervised positions. One aggregate evaluation after training was frozen. Test-prefix probes are separately reported final diagnostics, not selection feedback.

QUALITY METRICS:

| Aggregate output metric | Before | After |
|---|---|---|
| Printable code-point ratio | 0.836283 | 0.983471 |
| ISO control ratio | 0.035398 | 0.016529 |
| Word-like segment ratio | 0.260870 | 1.000000 |
| Mean per-prompt token reuse repetition | 0.033333 | 0.258333 |
| Immediate token repetition | 0.008403 | 0.000000 |
| Repeated word trigrams | 0.000000 | 0.169811 |

The aggregate concatenates prompts with spaces and includes cross-prompt n-gram/token boundaries; per-prompt results are primary. All ten baseline outputs failed strict UTF-8 decoding and were preserved with replacement markers; all ten trained outputs decode as UTF-8. The trained control characters are four newlines, with **zero other control characters**. Only `once upon ` has repeated word trigrams within its own output (0.2); mean across prompts is 0.02. A word-like score of 1.0 accepts invented strings such as `sovershe` and therefore does not establish real words, grammar, intelligence or QA ability. A token-reuse repetition rate (1 - distinct IDs / generated IDs) is additionally computed from the preserved IDs in `quality.tsv`; this complements immediate repetition and word trigrams. These are descriptive lexical metrics only.

Probe losses fall for training, independently authored validation/test and manually authored out-of-corpus prefixes. This is compatible with some transfer of local English token structure. Short generations are nevertheless degenerate and share repeated endings across prompts. Three prefixes per group and 32-position losses are not a comprehensive memorization audit, nor evidence of coherent explanations or question-answering competence. No claims of general intelligence, production language ability or ChatGPT-level ability are made.

Evidence: [experiment.txt](experiment.txt), [control-58K.txt](control-58K.txt), [probes.txt](probes.txt), [maven-package.log](maven-package.log), [surefire-summary.txt](surefire-summary.txt).

BPE measurements (bytes/token covers all three partitions):

| Vocab | Actual | Train tokens | Val tokens | Test tokens | Bytes/token | Fit seconds | Windows 32 (train/val/test) | Windows 64 |
|---|---|---|---|---|---|---|---|---|
| 512 | 512 | 117772 | 15082 | 14912 | 2.063824 | 0.528491 | [3680, 471, 465] | [1840, 235, 232] |
| 1024 | 1024 | 92507 | 12111 | 11980 | 2.615508 | 1.383902 | [2890, 378, 374] | [1445, 189, 187] |

Fixed held-out tokenizer sentence measurements:

| Vocabulary | Sentence | Bytes | Tokens | Bytes/token |
|---|---|---|---|---|
| 512 | `"A narrow footbridge connects the orchard to the railway platform."` | 65 | 35 | 1.857143 |
| 512 | `"Why does the kettle whistle before the water stops boiling?"` | 59 | 31 | 1.903226 |
| 512 | `"I can't attend on Thursday; could we meet at 09:45 instead?"` | 59 | 36 | 1.638889 |
| 512 | `"Save the document, close its window, and check the backup."` | 58 | 29 | 2.000000 |
| 512 | `"The sensor reports 18.7 degrees, although the room feels warmer."` | 64 | 37 | 1.729730 |
| 1024 | `"A narrow footbridge connects the orchard to the railway platform."` | 65 | 28 | 2.321429 |
| 1024 | `"Why does the kettle whistle before the water stops boiling?"` | 59 | 26 | 2.269231 |
| 1024 | `"I can't attend on Thursday; could we meet at 09:45 instead?"` | 59 | 30 | 1.966667 |
| 1024 | `"Save the document, close its window, and check the backup."` | 58 | 24 | 2.416667 |
| 1024 | `"The sensor reports 18.7 degrees, although the room feels warmer."` | 64 | 29 | 2.206897 |

Scale benchmark (seconds, batch 2, context 32, 64 supervised positions per step):

| Model | Parameters | Forward | Backward including forward | AdamW | Training step | Tokens/sec | 3-step smoke tokens/sec | Checkpoint bytes |
|---|---|---|---|---|---|---|---|---|
| 58K | 58368 | 0.015520 | 0.019581 | 0.013519 | 0.033100 | 1933.529836 | 4402.302386 | 1401888 |
| 250K | 231424 | 0.016760 | 0.034659 | 0.019764 | 0.054423 | 1175.983290 | 1108.859004 | 5555232 |
| 1M | 1052672 | 0.036314 | 0.140813 | 0.065333 | 0.206146 | 310.460017 | 463.256743 | 25265808 |
| 5M | 5251072 | 0.115861 | 0.442896 | 0.149858 | 0.592754 | 107.970538 | 92.875285 | 126028032 |

Measured JVM used-heap observations, without forcing GC (bytes):

| Model | Before initialization | After measured step | After smoke/resume comparison |
|---|---|---|---|
| 58K | 75776720 | 95699664 | 56891688 |
| 250K | 97452256 | 155123936 | 49943000 |
| 1M | 97397864 | 89331688 | 236368704 |
| 5M | 97501904 | 342360064 | 1087897600 |

58K diverse-corpus learning curve:

| Epoch | Train loss | Val loss | Train PPL | Val PPL | Supervised tokens |
|---|---|---|---|---|---|
| 0 | 6.246387750178 | 6.247328806465 | 516.14500847 | 516.630958597809 | 0 |
| 1 | 4.200525373926 | 4.207852992700 | 66.721375505381 | 67.212079961089 | 117760 |
| 2 | 4.003866543821 | 4.055421457558 | 54.809664824857 | 57.709479968025 | 117760 |

Generation: ALL predefined prompts, exactly 12 new tokens each, greedy, same seed/model initialization. Only generated text is shown. Escapes preserve invalid UTF-8 replacement characters and control characters; `\u{000a}` means a newline. No outputs discarded.

| Prompt | Before | After |
|---|---|---|
| `"the "` | `"\u{fffd}\u{fffd}osal $j, and wa\u{fffd}ong\u{fffd}out"` | `"sover\u{000a}she cancan been"` |
| `"this "` | `"idin \u{fffd}\u{fffd}irshsemore qup/\u{fffd}"` | `"sovershe most me to be m"` |
| `"what "` | `"seore \u{001f}\u{fffd}irshishout\u{001d}\u{fffd}, \u{fffd}"` | `"to dover\u{000a}she most mare "` |
| `"why "` | `"\u{cd89}\u{fffd}irshsemore qup/\u{fffd}"` | `"so\u{000a}she cancan beend"` |
| `"how "` | `"me\u{001f}\u{201d}forandse\u{0013}\u{fffd}\u{fffd}out\u{fffd}\u{0003}"` | `"to get to do\u{000a}she mar"` |
| `"jade "` | `"\u{001b}ess \u{fffd}irshsemore tr\u{fffd}out\u{fffd}\u{0003}"` | `"to be may be mare to remar"` |
| `"the computer "` | `"=untt!Th\u{fffd}outffou\u{fffd}akup"` | `"to be mare to be maright "` |
| `"once upon "` | `"vkuntt!Th\u{fffd}\u{fffd}ffou\u{fffd}ak"` | `"her to be mare to be mare "` |
| `"because "` | `"\u{fffd}\u{fffd}irshsemore qup/\u{fffd}\u{fffd}ak"` | `"to remare to be mare to be "` |
| `"if the "` | `"tr a \u{0016}trfetrseqtr\u{fffd}out\u{fffd}"` | `"she crocancer, where "` |

Generalization probes: fixed before training, prefix loss over at most 32 supervised tokens; generation uses the first 20 code points and 12 new tokens. Test probe losses were deferred until final evaluation. All individual results follow.

| Partition | Prefix | Loss before | Loss after | Generation before | Generation after |
|---|---|---|---|---|---|
| train | `"_The Bingleys and the Gardiners and the Lucases, Miss Darcy and Miss de Bourgh, "` | 6.246314378892 | 4.233567860940 | `", and wa\u{fffd}out\u{fffd}\u{0003} saet\u{fffd}\u{fffd}ich\u{fffd}"` | `"ere she can to be marilly "` |
| train | `"When we look to the individuals of the same variety or sub-variety of our older "` | 6.260854754153 | 3.994953897305 | `"\u{fffd}, and\u{fffd}ffou\u{fffd}akup/pl a \u{fffd}"` | `"she crovery. He to be "` |
| train | `"Begin with a question that has a measurable answer. Instead of writing that a pr"` | 6.280808251044 | 4.499528025480 | `"owome ffou\u{fffd}akupthur a \u{fffd}o"` | `"very. He to be mariller "` |
| validation | `"It was close upon four before the door opened, and a drunken-looking groom, ill-"` | 6.272934314351 | 4.045608208195 | `"ow\u{201c}ffou\u{fffd}ose upthur a \u{fffd} that "` | `"ver\u{000a}she mare to be mare "` |
| validation | `"\u{201c}It\u{2019}s quite too funny. I am sure you could never guess how I employed my morning"` | 6.274129230077 | 3.866775097170 | `"\u{fffd}\u{fffd}Nfor\u{fffd}ir tr\u{fffd}olve \u{0005}\u{fffd}"` | `"erepet to be mare to d"` |
| validation | `"\u{201c}Quite so; but the sequel was rather unusual. I will tell you, however. I left t"` | 6.251129930830 | 4.000391295009 | `"tr\u{fffd}id, and se\u{0013}\u{0006}\u{fffd} a \u{0016}ve \u{0005}"` | `"he most most she most she m"` |
| test | `"\u{201c}It seems a pity to let the dinner spoil,\u{201d} said the Editor of a well-known daily"` | 6.270904442117 | 4.227240035186 | `"oulffou\u{fffd}akup/pl\u{fffd}\u{fffd}ou\u{201d}"` | `"be most sococancanc"` |
| test | `"He was in an amazing plight. His coat was dusty and dirty, and smeared with gree"` | 6.264247095238 | 4.343967795214 | `"\u{fffd}\u{fffd}ffou\u{fffd}akup/pl a \u{fffd}\u{fffd}"` | `"\u{000a}she crocrocan be"` |
| test | `"He looked across at the Editor, who was a rare visitor, and hoped he was all rig"` | 6.221442419141 | 3.970092488109 | `"=ab*wall =\u{fffd}\u{fffd} a \u{0016}trve "` | `"to be might to be may be m"` |
| out-of-corpus | `"A patient astronomer checks the lens before measuring a distant star."` | 6.192420279667 | 4.214731229876 | `"ir \u{fffd}ichevfor\u{fffd}ir tr\u{fffd}\u{fffd}\u{fffd}is"` | `", where she cul her to d"` |
| out-of-corpus | `"The bus arrived late because a fallen branch blocked the hill road."` | 6.240825875884 | 3.780769094613 | `"K\u{fffd}ffou\u{fffd}akupthur a \u{fffd}\u{fffd}"` | `"t to be mare to be might "` |
| out-of-corpus | `"If a sensor disagrees with a reference, record both values before adjusting it."` | 6.234883403147 | 4.816797401148 | `"K\u{fffd}ffou\u{fffd}akupthur a \u{fffd} that "` | `"pepep her to be mar"` |

FULL REGRESSION: 508 tests; failures 0; errors 0; skipped 0. Brain: 206 tests, no failures/errors/skips.

`mvn -o clean package`: PASS. The first sandboxed attempt had 508 tests / 0 failures / 1 error / 0 skips: an existing unrelated loopback HTTP fixture could not bind a socket. The exact offline command passed with sandbox escalation, without code changes or external API requests.

`git diff --check`: PASS. No staging, committing or pushing. Working-tree status (includes pre-existing changes):

```text
 M README.md
 M pom.xml
 M src/main/java/com/jade/api/CommandResult.java
 M src/main/java/com/jade/app/JadeApplication.java
 M src/main/java/com/jade/core/CommandParser.java
 M src/main/java/com/jade/core/CommandPlan.java
 M src/main/java/com/jade/core/DefaultCommandGateway.java
 M src/main/java/com/jade/ui/CommandUI.java
 M src/test/java/com/jade/core/CommandParserProjectIntelligenceTest.java
 M src/test/java/com/jade/services/audio/VoiceTranscriptFlowTest.java
?? docs/JADE_BRAIN.md
?? docs/RESEARCH_V1.md
?? docs/loop-5c/
?? docs/loop-5d/
?? src/main/java/com/jade/api/AnswerMode.java
?? src/main/java/com/jade/api/AnswerRequest.java
?? src/main/java/com/jade/api/AnswerResult.java
?? src/main/java/com/jade/api/AnswerService.java
?? src/main/java/com/jade/api/AnswerStatus.java
?? src/main/java/com/jade/api/GeneralQuestion.java
?? src/main/java/com/jade/api/KnowledgeAnswerProvider.java
?? src/main/java/com/jade/api/LocalLanguageModel.java
?? src/main/java/com/jade/api/WebSearchProvider.java
?? src/main/java/com/jade/brain/
?? src/main/java/com/jade/core/BrainTokenizerAdapter.java
?? src/main/java/com/jade/services/answer/
?? src/test/java/com/jade/brain/
?? src/test/java/com/jade/core/GeneralQuestionGatewayTest.java
?? src/test/java/com/jade/core/GeneralQuestionRoutingTest.java
?? src/test/java/com/jade/services/answer/
?? src/test/resources/
```

PYTHON USED: NO

OLLAMA USED: NO

PYTORCH USED: NO

TENSORFLOW USED: NO

EXTERNAL MODEL USED: NO

API KEY USED: NO (unrelated regression uses fake test credentials only)

NETWORK USED BY TRAINING: NO

PRETRAINED WEIGHTS USED: NO

TRAINED FROM RANDOM INITIALIZATION: YES

CURRENT TRAINING LANGUAGE: ENGLISH

5M FULL TRAINING READY: YES, for the bounded first run below; not for broad English competence.

Recommendation only, NOT STARTED: use the existing 304,963-byte curated corpus (244,099 train / 30,632 validation / 30,232 test), train-only balanced BPE sample of 65,369 bytes, frozen 1024 vocabulary; JADE-5M CANDIDATE 1024/32/256/4/6/1024; FP64; batch 2; context/stride 32; seed 26167; deterministic shuffle seed 42; AdamW learning rate 0.001, beta1 0.9, beta2 0.999, epsilon 1e-8, decay 0.01, global gradient clip 1.0, no no-decay exclusions. ONE epoch = 92,480 supervised positions = 1,445 optimizer steps. Use `-Xmx1536m`; estimate 20–25 minutes including initial/final train and validation evaluation (rough extrapolation from a very short CPU smoke, not a sustained benchmark); impose a 30-minute child-JVM hard deadline. Save an initial checkpoint and a final checkpoint at the supported epoch boundary, interval 92,480 positions / 1,445 steps. Do not promise intermediate step resume: current corpus checkpoints validate epoch boundaries. Test remains sealed until the final chosen evaluation; no tuning on that test. This corpus is adequate for a first bounded engineering trial, far too small to establish broad language ability. Expand independent English data before claiming useful generalization.

STOP: no full 5M run was started.
