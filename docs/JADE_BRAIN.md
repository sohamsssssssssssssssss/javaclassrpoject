# JADE Brain V0 — first forward pass

JADE Brain is an isolated, experimental CPU transformer implemented in Java.
The model currently has randomly initialized parameters and therefore does not
possess useful learned language knowledge. Its logits and highest-scoring token
are numerical demonstrations, not answers. It does not replace AnswerService or
change the application, providers, UI, command execution, confirmation, or undo.

## Current pipeline

Text → existing `CommandTokenizer` → `BrainTokenizerAdapter` → fixed
`BrainVocabulary` IDs → token embeddings + learned positional embeddings →
pre-normalized causal attention / feed-forward residual blocks → final RMSNorm
→ independent bias-free language-model head → `[sequenceLength][vocabSize]` logits.

The existing tokenizer is a command lexer, **not a production subword tokenizer**.
It splits whitespace, preserves case and punctuation, treats quoted arguments as
single lexical tokens, and rejects malformed quotes. It has no vocabulary IDs,
encode/decode protocol, unknown-token mechanism, BOS or EOS. The narrow adapter
in `com.jade.core` calls that unchanged lexer; no Brain class imports core, UI,
execution services, networking, or providers.

The experimental vocabulary is supplied explicitly in a fixed order. ID 0 is
`<unk>`; supplied entries receive IDs 1 onward. Unknown lexical values map to 0.
There is no dynamic vocabulary insertion, automatic BOS/EOS, casing conversion,
or punctuation splitting. Quoted flags are discarded after lexical boundaries
are resolved. `token(id)` retrieves a vocabulary entry; there is no lossless text
decode. `decode(ids)` joins vocabulary entries with single spaces for display;
it does not reconstruct original quotes/whitespace. A future trained subword
tokenizer is separate work.

## Mathematics and parameters

- Primitive `double[][]` storage, immutable matrix shape and defensive copies.
  Doubles keep the first CPU implementation easy to inspect and test numerically;
  there is no GPU, JNI, native math library, or external inference engine.
- One shared seeded `java.util.Random`; each weight is Gaussian × 0.02. Same
  configuration and seed reproduce the same logits. Test seed: 26167.
- Learned positional table `[contextLength][embeddingDimension]`; sequences
  beyond the context window reject, rather than wrap or truncate.
- Unit-scale RMSNorm with epsilon 1e-6, no learned scale or bias. Each position
  normalizes independently. Scaled squared accumulation avoids overflow for
  very large finite inputs.
- Bias-free Q/K/V/output projections; heads use scaled dot products divided by
  `sqrt(headDimension)`. For position i, only scores/values 0..i are computed.
  This is an exact causal mask without infinity sentinels. Softmax subtracts
  the largest score before exponentiation.
- Bias-free `D → F → D` feed-forward projections, tanh-approximate GELU.
- Block: `x = x + attention(RMSNorm(x))`, then
  `x = x + feedForward(RMSNorm(x))`. No dropout.
- Independent, untied `[D][V]` LM head after final RMSNorm.

Parameter count is `2VD + CD + L(4D² + 2DF)`: token and positional tables,
Q/K/V/output matrices, two FFN matrices, and LM head. Norms have no parameters.
The demo vocabulary has 8 entries including unknown; C=32, D=32, H=4, L=1,
F=64: **9,728 parameters**. Input `hello jade` encodes as `[1, 2]`, producing
2×8 finite logits. The top ID is printed only by the development test.

V0 guards against incompatible/ragged/empty matrix shapes, non-finite values,
invalid IDs, invalid/divisibility dimensions, and overlong/empty model input.
Arithmetic overflow fails explicitly instead of returning invalid logits.
Deliberate small-model allocation ceilings: 2 million parameters/matrix elements,
1 million hidden activation elements, at most 64 blocks, 4096 context/dimension,
and 65,536 vocabulary entries. These are educational CPU limits, not promises
of practical performance for those largest configurations. Attention is O(T²D)
and matrix operations allocate copies; optimize only after measuring a real need.
The adapter caps input at 8192 characters. There are no downloads or side effects.

## Validation

`mvn -Dtest=BrainMathTest,BrainModelTest,BrainTokenizerTest,BrainGenerationTest,ByteBpeTokenizerTest test`

Tests include hand-calculated matrix/attention values, softmax stability,
normalization, embedding lookup, residual preservation, invalid input, deterministic
initialization, and text → logits. Causality is checked both on attention outputs
and on a two-block LM: replacing the fourth token leaves all first-three logits
bit-identical; appending the fourth token also leaves the prefix unchanged.

## Loop 2 — autoregressive generation

Prompt text → unchanged `BrainTokenizerAdapter.encode` → prompt IDs →
`JadeTextGenerator.generate(ids, maxNewTokens)` → repeated in-process
`JadeLanguageModel.nextTokenLogits(context)` → greedy argmax → append ID → repeat
→ temporary vocabulary decode.

`nextTokenLogits` uses the normal verified forward pass and returns its final
position's logits. There is no second inference path. `TokenSelector.greedy()`
chooses the highest finite logit; exact ties select the lowest ID. Empty or
non-finite logits reject. There is no softmax/sampling step in greedy decoding.

The generator clones the prompt and expands that prefix by one selected token
each iteration. It recomputes the full prefix without a KV cache. It remains
independent of the command lexer: callers encode text through the existing
adapter before invoking it. No application or AnswerService integration is added.

The prompt must be nonempty, contain valid IDs, and fit the context window.
An oversized prompt rejects without truncation. Generation stops before adding
a token beyond context capacity; a full-window prompt produces no new tokens.
`maxNewTokens` must be nonnegative. Zero requests no inference. No allocations
are based on an unbounded requested budget; context capacity bounds the loop.

Stop reasons are `MAX_NEW_TOKENS` and `CONTEXT_LIMIT`. If both limits are reached
simultaneously, `MAX_NEW_TOKENS` takes precedence. There is **no EOS protocol**;
even an entry spelled like a special token is ordinary vocabulary data.

`GenerationResult` contains immutable prompt IDs, generated IDs, generated text
(excluding the prompt), and typed stop reason. `allTokenIds()` returns their
immutable concatenation. `decode` reuses the validated `token(id)` mapping,
including explicit `<unk>`, and never changes the vocabulary. Space-joined
display text is not promised to re-encode losslessly for entries containing spaces
or quotes; individual ID-to-entry mapping remains exact.

The development demo is `BrainGenerationTest.firstAutoregressiveGenerationDemo`:
the existing 8-entry model, seed 26167, prompt `hello jade`, and 8 new tokens.
It prints every generated ID in order, the final sequence, exact generated text,
stop reason, and forward-pass count. Nothing is special-cased to improve output.
Weights remain random and untrained; generated text has no useful language ability.

Feedback is tested at the token-selection seam: every received logit vector must
equal inference over the independently expanded expected prefix, and later
vectors must differ from the original prompt's logits. Other tests cover decode
round trips, ties, deterministic independent runs, immutable results, unknowns,
invalid input/selector IDs, and context/budget stopping.

## Not implemented yet

Training, backpropagation, optimizer, checkpoint loading/format, sampling, KV cache,
instruction tuning, web grounding, large-corpus tokenizer training, and GPU
acceleration. There are no external model weights or inference runtimes involved
in this forward pass. Existing local/cloud answer integrations remain untouched.

## LOOP 3 — BYTE-LEVEL BPE TOKENIZER

### Why byte level

Every valid Unicode string can be UTF-8 encoded. All 256 byte values are present
as base tokens, with IDs 0–255 exactly equal to their unsigned byte values.
Consequently rare text can always fall back to bytes: **no normal-text unknown
token is required**. In this tokenizer ID 0 means byte 0x00, not `<unk>`.

BPE learns frequent adjacent sequences: `h e l l o` can become tokens such as
`he`, `ll`, or `hello`, depending on the corpus's frequencies. This reduces
model positions without losing the ability to represent unseen valid Unicode.
Representation is not understanding: the transformer still has random weights.

**Tokenizer training != neural network training.** Loop 3 learns only vocabulary
entries and merge rules. It does not train embeddings, attention, transformer
blocks, or the LM head. The tiny in-memory fixture corpus is test data, not JADE's
actual language-model training corpus. Nothing is downloaded or crawled.

### Vocabulary, merge table, and algorithm

`BpeToken` stores a validated ID and defensively copied byte sequence, with
content-based equality/hash code and hexadecimal diagnostics. `BpeVocabulary`
is immutable, includes all base bytes, and maps IDs ↔ byte sequences. No encode
or decode operation grows it. Token IDs are contiguous; duplicate byte sequences
are rejected. The trainer assigns new IDs in creation order starting at 256.

`BpeTokenizerModel` holds the vocabulary and immutable rank-sorted `BpeMerge`
rules. Construction rejects duplicate pairs/ranks, invalid or missing IDs,
unreachable merged tokens, and references to parents not established by earlier
ranks. Every result's bytes must equal left bytes concatenated with right bytes.
If alternate decompositions produce identical bytes, the trainer reuses the
existing token ID rather than inserting a duplicate; earlier rules are reapplied
if that reuse exposes them. Merge count therefore need not always equal V−256.

Training starts each corpus entry as an independent byte-ID sequence. It counts
adjacent pairs (including overlapping occurrences), chooses the greatest count,
then breaks ties by lowest left ID and lowest right ID. HashMap iteration order
does not choose the winner. Occurrences are replaced left-to-right without
overlap. Training stops at the target vocabulary size or when no pairs remain;
a resource-limit violation fails explicitly. Separate corpus entries never
merge across their boundaries. Entry order does not change learned rules.

Encoding starts with UTF-8 byte IDs. At each step it selects the lowest-ranked
currently applicable pair and replaces that pair's non-overlapping occurrences
left-to-right. It repeats to a fixed point. This is ranked BPE, **not longest
vocabulary matching**. For example, a high-priority `b+c` rule can prevent using
an existing `abc` token through a lower-priority `a+b` path; this is tested.

Decoding concatenates token bytes and strictly decodes UTF-8, without inserting
spaces, stripping punctuation, normalizing Unicode, trimming, or lowercasing.
Empty text encodes to an empty sequence; empty IDs decode to empty text.

### Unicode and resource policy

Valid Unicode Java strings round-trip exactly within the resource limits,
including NUL, combining marks, multilingual text, emoji, and exact whitespace.
Isolated UTF-16 surrogate code units reject explicitly using the Java UTF-8
encoder's REPORT policy. They are not silently replaced. The decoder also uses
REPORT: arbitrary token IDs may concatenate to invalid UTF-8 and then reject.
All byte values are representable; not every arbitrary byte sequence is valid
Unicode. Random BPE-model generation can therefore fail strict text decoding;
this loop does not add a generation repair policy or claim language competence.

Deliberate educational CPU bounds:

- Target vocabulary: 256–4096; individual token: at most 4096 bytes.
- Total vocabulary byte storage: at most 1 MiB; merge rules: at most 8192.
- Training corpus: at most 4096 independent entries and 64 KiB of UTF-8 bytes.
- Encode/decode: at most 1 MiB of UTF-8 bytes; decode also caps input ID count.
- Pair scans: at most 32 million per encode or complete training call, including
  rank reapplication during training. The bounded full-scan implementation is
  intended for small corpora; an incremental pair-count/adjacency implementation
  can replace it when measured needs justify that work.

There is no recursion, thread creation, networking, process execution, external
tokenizer library/runtime, checkpoint persistence, or downloaded vocabulary.

### Brain integration and fixture evidence

`BrainTokenizer` exposes `encode`, `decode`, and `vocabularySize`.
`ByteBpeTokenizer` implements it. The legacy `BrainTokenizerAdapter` also
implements it solely to preserve the temporary lexical fixtures. The command
tokenizer/parser remain unchanged; BPE is for Brain, not command routing.

`JadeTextGenerator` now accepts `BrainTokenizer` instead of `BrainVocabulary`,
checks its size against the model, and validates IDs without decoding individual
bytes (which may be incomplete UTF-8). Construct `BrainConfig.vocabSize` from
`tokenizer.vocabularySize()`. Compatibility currently checks size only, not a
checkpoint/tokenizer fingerprint; checkpoint persistence remains later work.
All Loop 1 mathematical tests and the exact Loop 2 random-generation demo remain.
No AnswerService or UI integration is added.

`ByteBpeTokenizerTest` trains on the specified tiny in-memory fixture, targeting
300 tokens. It learns 44 merges, compresses `hello` from five byte tokens to one,
and encodes `JADE 🤖 says: hello jade` from 26 bytes to 18 tokens. The demo prints
the first ten rules with canonical byte sequences in safe hexadecimal.
ASCII, punctuation, whitespace, Latin/CJK/Cyrillic/Arabic/Devanagari, emoji,
mixed text, and deterministic randomly sampled Unicode all round-trip exactly.

Still absent: useful learned language knowledge, neural training/backpropagation,
optimizer, checkpoint loading, sampling, KV cache, instruction tuning, web
grounding, and application answering integration.

## Language-model objective — Loop 4A

Causal next-token prediction shifts a complete sequence by one position:
`[A,B,C,D]` produces input `[A,B,C]` and targets `[B,C,D]`. There is no target
after the final token. `LanguageModelExample` defensively copies its arrays,
requires equal nonempty lengths, and rejects negative IDs. Its sequence factory
requires at least two tokens.

`LanguageModelObjective.loss(model, tokens)` validates every ID against the model
vocabulary, builds the example, calls the unchanged `forward(input)`, and computes
**mean categorical cross entropy over positions**, in natural-log units (nats).
The shifted input must fit the context window; the complete sequence can therefore
contain at most `contextLength + 1` tokens. Oversized sequences are rejected,
never silently truncated. Logit rows must match the configured vocabulary size.

For logits z and target y, with m = max(z), the implemented stable equation is:

`CE(z,y) = (m - z[y]) + log(sum_i(exp(z[i] - m)))`.

This equals `logSumExp(z) - z[y]`, but subtracting the target before adding the
logarithm avoids cancellation of very large shared offsets. Max subtraction
prevents exponential overflow. Sequence reduction uses an online arithmetic
mean to avoid overflowing a sum of large finite per-position losses. Empty or
ragged logits, invalid targets, and non-finite logits reject explicitly. If a
mathematical loss exceeds finite double range, it fails explicitly rather than
returning infinity or NaN.

Analytical tests cover uniform loss `ln(V)`, confident correct/wrong predictions,
translation invariance, extreme positive/negative logits, and a manual sequence
average. A random-model smoke verifies finite deterministic loss and unchanged
logits before/after evaluation. A finite loss does **not** mean JADE has learned
language. There are no gradients, backpropagation, optimizer, parameter updates,
training loop, dataset/batching infrastructure, or checkpointing in this loop.

Focused objective validation: `mvn -Dtest=LanguageModelObjectiveTest test`.

## Loop 4B — cross-entropy and LM-head backward

`CrossEntropyLoss.meanWithGradient` returns immutable `CrossEntropyResult`:
the same scalar mean loss plus `dLogits` shaped `[T,V]`. It reuses forward
validation and the existing max-subtracted softmax. For position t and class i:

`dLogits[t,i] = (softmax(logits[t])[i] - indicator(i == target[t])) / T`.

The division by T matches the mean forward objective. Each row sums to
approximately zero; target derivatives are nonpositive, others nonnegative.
Inputs remain unchanged; non-finite logits, malformed shapes, and invalid
targets reject according to the existing numerical policy.

The existing bias-free LM projection is now isolated as immutable `LmHead`,
without changing its random initialization or forward arithmetic:

- `logits = H * W`, H `[T,D]`, W `[D,V]`.
- `dW = H.transpose() * dLogits`, shape `[D,V]`.
- `dH = dLogits * W.transpose()`, shape `[T,D]`.

There is no bias and no bias gradient. `LmHeadBackwardResult` holds only the
immutable hidden and weight gradient matrices. `JadeLanguageModel.outputHidden`
exposes the post-final-RMSNorm states used by the unchanged forward computation;
`lmHead()` exposes the immutable projection for this explicit backward slice.

**Backpropagation stops at the input to the LM head / output hidden states.**
It does not propagate through final RMSNorm, transformer blocks, attention,
FFN, token embeddings, or positional embeddings. There is no global gradient
registry, autodiff graph, optimizer, learning rate, or parameter update.
No learning has occurred.

`LmHeadBackwardTest` checks centered finite differences with epsilon 1e-6 on
12 non-uniform logit elements, all 20 head weights of a tiny deterministic
model, and all 12 output-hidden elements. Perturbations use copies, so original
parameters never change. Absolute tolerance is 1e-8; measured maxima are printed
by the tests. Other tests verify mean scaling independently, gradient signs/sums,
extreme values, immutable results, shape rejection, deterministic backward,
unchanged parameters, and unchanged complete-model forward outputs.

Focused validation:
`mvn -Dtest=LmHeadBackwardTest,LanguageModelObjectiveTest,BrainModelTest,BrainGenerationTest test`.

## Loop 4C — RMSNorm, FFN, and residual backward

Forward equations remain unchanged, including initialization order. The existing
bias-free FFN is isolated as immutable `FeedForwardNetwork`:
`U = X Wup`, `A = GELU(U)`, `Y = A Wdown` (D → F → D).
`LinearBackward.calculate` now supplies the shared explicit projection derivative:
`dX = dY W.transpose()`, `dW = X.transpose() dY`. There are no bias gradients.
The LM head reuses this same helper.

GELU is the existing tanh approximation, not exact Gaussian GELU. Define
`a = sqrt(2/pi)`, `b = 0.044715`, `u = a(x + b*x^3)`, `q = tanh(u)`.
Its derivative is `0.5(1+q) + 0.5*x*(1-q*q)*a*(1+3*b*x*x)`.
`Matrix.geluBackward` multiplies this by upstream elementwise. Saturated tanh
uses its zero derivative directly, avoiding `0 * overflow` at extreme finite x.

RMSNorm remains unit-scale with no gamma, bias, or mean subtraction:
`r = sqrt(mean(x*x) + epsilon)`, `y = x/r`. For upstream g:
`dX = (g - y*mean(g*y))/r`, independently per token row.
`Matrix.rmsNormBackward` uses the same scaled squared accumulation as forward,
plus scaled upstream dot products to avoid overflow on huge finite input rows.
Epsilon is preserved inside the square root. Unrepresentable/non-finite results
reject under the existing finite-Matrix policy; no parameters are invented.

FFN backward explicitly applies Linear 2 → GELU → Linear 1, returning immutable
input, up-weight, and down-weight gradients. It recomputes local intermediates;
there is no computational graph or gradient registry.

For the existing block's second residual, `B = R + FFN(RMSNorm(R))`,
`backwardFfnResidual` sends upstream to both branches and returns
`dR = upstream + RMSNormBackward(R, FFNBackward(RMSNorm(R), upstream).dInput)`.
The result is deliberately named `dAttentionResidual`: it stops at the output of
the attention-side residual. It is **not** a complete block-input gradient and
does not bypass the missing attention derivative.

`JadeLanguageModel.transformerOutput` exposes the pre-final-norm output.
`backwardFinalNorm(tokens, dFinalHidden)` connects the actual LM-head hidden
gradient through final RMSNorm, returning the gradient entering the last
transformer block's output. No attention/body traversal is implied by that method.

Backward currently reaches cross entropy, LM head, final RMSNorm, and the
FFN-side block components through their explicit partial-backward API.
Backward does not yet cross self-attention, token embeddings, or positional
embeddings. No optimizer exists, no parameters are updated, and no training
has occurred.

`NormFfnBackwardTest` verifies centered finite differences (epsilon 1e-6,
absolute tolerance 3e-8) for linear input/weights, exact implemented GELU,
RMSNorm including non-negligible epsilon, complete FFN input and both projections,
residual+norm+FFN, and final norm+head+mean-loss compositions (including actual
model output states). It also checks zero/huge RMSNorm inputs, row independence,
residual addition, deterministic repeated backward, immutable parameters, and
malformed/non-finite rejection. Maximum observed errors are printed by tests.

Focused validation:
`mvn -Dtest=NormFfnBackwardTest,LmHeadBackwardTest,LanguageModelObjectiveTest,BrainModelTest,BrainGenerationTest test`.

## Autonomous foundation: attention and complete block backward

Attention now caches only projected Q/K/V, triangular probabilities and joined
heads locally. Its VJP reverses output projection, aggregation, row-softmax,
scaled scores and Q/K/V projections. Masked future scores have exactly zero
contribution. No bias or normalization scale exists in the current model.
Complete block backward reverses both residuals, adding identity gradients to
FFN/norm and attention/norm branches. Inference equations and seeded draw order
are unchanged. Deterministic central differences verify inputs and every
projection; backward does not update parameters.

## Complete model backward and parameter identity

The mean causal objective now traverses the independent LM head, final unit
RMSNorm, blocks in reverse order, and both learned embedding tables. Repeated
token IDs accumulate into the same embedding row; unused rows remain zero.
Position gradients follow absolute position IDs. Parameters have stable names
in an ordered immutable map; embeddings and LM head are not tied. There are no
normalization parameters or biases. A two-layer tiny model checks every element
of every trainable tensor against central finite differences. Loss gradients
retain the existing 1/T mean reduction. No parameter update is done by backward.

## Optimizer foundation (fixture use only)

Named parameter maps validate every gradient and moment shape. Global norm uses
iterated `Math.hypot`; optional clipping scales the whole gradient set without
modifying the model. AdamW maintains independent first/second moments per named
parameter, applies bias correction, and decouples decay from the gradient update.
`TrainingConfig.noDecayParameters` is an explicit exclusion policy. All current
parameters are weights; there are no bias/normalization-scale groups to invent.
There are no implicit hyperparameter defaults or retries.

Updates construct immutable replacement model/optimizer snapshots only after
validation. Missing/extra gradients, shape errors and numeric overflow cannot
partially modify the original state. Each backward returns fresh gradients;
there is no hidden accumulation buffer or zero-grad operation to forget.
Only deterministic mathematical fixtures exercise updates; no corpus or
production model has been trained.

## Single-example step and bounded learning proof

`TrainingExamples` reuses shifted input/target examples, rejecting invalid IDs
and inputs longer than model context. A complete sequence may have context+1
tokens because its shifted input has context tokens. No truncation, padding,
batching or implicit gradient accumulation is introduced. BPE text conversion
requires matching tokenizer/model vocabulary and at least two encoded tokens.

`TrainingStep.run` returns a replacement state plus pre-update mean loss,
predicted-token count, pre-clip global norm, clipping flag and optimizer step.
Configuration is explicit: learning rate, beta1/beta2, epsilon, decoupled decay,
clip threshold (zero disables), and named no-decay exclusions. Context comes
from model configuration, and initialization seed remains model metadata.

A deterministic three-token, four-dimensional synthetic fixture performs only
30 verification updates. Its mean loss drops from 1.0817910609486339 to
0.34194833890289716. This is a mathematical learning proof, not corpus training,
useful language ability or modification of the production JADE model.
Attention backward caches at most two million triangular probabilities;
inference retains no probability cache and preserves original computation.

## Local checkpoint v1 and current verified frontier

`BrainCheckpoint` uses a JADE-owned binary format: magic/version, architecture,
initialization seed metadata, explicit training configuration, complete BPE
byte vocabulary and ranked merges, named model tensors, AdamW step and moments.
Double values round-trip exactly. Ordered parameter identities and sorted
no-decay exclusions make serialization deterministic. A SHA-256 checksum detects
corruption; it is not authentication. Readers reject unsupported versions,
incompatible shapes/IDs, invalid numerical values, truncation and trailing data.
Files are capped at 64 MiB; architecture/tokenizer limits also apply before tensor
allocation. Save writes a sibling temporary file and atomically replaces the
destination; unsupported atomic moves fail rather than leaving a partial save.

The tiny checkpoint fixture (28,940 bytes) reconstructs exact architecture,
tokenizer, parameters, moments and step, then reproduces the next forward and
optimizer update exactly. Fixtures use temporary directories only.

**Implemented and verified:** full causal-attention/block/stack backward,
final norm/head and embedding gradients, named parameter registry, global
clipping, AdamW, transactional snapshot updates, BPE-to-example conversion,
single-example steps, bounded synthetic learning proof, checkpoint save/load.
RMSNorm remains parameter-free, all linear projections are bias-free, and the
head is independent of token embeddings. No model semantics were redesigned.

**Not yet authorized/implemented:** actual corpus training, corpus downloads,
long-running/background training, useful learned language ability, production
weight updates, AnswerService/UI integration, batching/padding/accumulation,
GPU/native acceleration or external model inference. Older Loop sections record
the frontier at that historical stage; this section is the current frontier.

Run deterministic Brain verification with
`mvn -Dtest='com.jade.brain.*Test' test`, then `mvn clean package`.
Numerical checks use central differences (epsilon 1e-6, absolute tolerance 3e-8)
and print both maximum absolute and relative error; relative errors near zero
must be interpreted alongside absolute error. The two-layer end-to-end check
covers every element of every trainable tensor (80 elements), not just shapes.

## Loop 4G — language-model output boundary

The existing output path is `stack output [T,D] → unit-scale final RMSNorm
→ independent bias-free LM head [D,V] → logits [T,V] → mean next-token cross
entropy`. Targets for `[t0,t1,t2,t3]` are `[t1,t2,t3]` at inputs `[t0,t1,t2]`.
There is no self-prediction or supervision after the last token.

`forwardOutput` retains the exact stack pass, normalized hidden states, logits,
and defensive-copy targets. `backwardOutput` returns immutable logits and gradients,
mean loss, supervised-token count, the reconstructed total loss (`mean × count`),
LM-head gradient, gradient into the stack output, per-layer stack gradients and
the gradient reaching stack input. The sum may be numerically unrepresentable
for extreme finite mean losses; `totalLoss()` rejects that case explicitly.
Loss and `dLogits = (softmax(logits) - oneHot(target)) / T` use the existing
stable cross-entropy implementation. LM-head and final-RMSNorm backward reuse
their verified implementations; the stack consumes its same-pass forward cache.
The output-specific API stops at stack input. Existing full-model behavior is
preserved; no parameters are updated by this output path.

## Loop 4H — complete token-to-loss gradient path

Token lookup reads the learned `[V,D]` embedding table. Absolute positions read
a separate learned `[contextLength,D]` table; their row values are added before
the transformer stack. The LM head is independent, not weight-tied. IDs outside
`[0,V)` reject. The byte-level BPE tokenizer represents normal Unicode text
without an unknown token; the temporary command-lexer adapter remains only a
small historical fixture.

The existing embedding VJP accumulates upstream rows for every repeated token
ID and leaves unused rows exactly zero. Position rows accumulate analogously.
`backwardTokens(completeTokens)` now exposes a single immutable result containing
mean next-token loss, supervised count, logits, stack gradients, gradient into
stack input, the two embedding-table gradients and named gradients for every
trainable parameter. A complete `[t0,t1,t2,t3]` sequence supervises input
positions `[t0,t1,t2]` against targets `[t1,t2,t3]`; the target-only final token
has no input embedding contribution.

For the deterministic two-layer fixture `(V=4, context=4, D=2, heads=1,
layers=2, FF=3)`, all 15 parameter tensors and all 80 trainable scalar values
have finite, shape-matched gradients. These are token, position, 4 attention
projections and 2 FFN projections per layer, and the independent LM head.
Unit-scale RMSNorm has no trainable parameter. Central differences check
repeated/once/unused embedding rows plus first/last block and LM head. Forward
and greedy-generation semantics remain unchanged. This computes gradients only;
no optimizer or parameter update is introduced by Loop 4H.

## Loop 4I — first vanilla SGD update

`Sgd.step` applies `newWeight = oldWeight - learningRate * gradient` to every
named trainable tensor. It validates a finite positive learning rate and the
complete gradient identity/shape set. Each result is a replacement model built
from immutable, finite matrices, so a rejected update leaves the original model
unchanged. Backward itself still never updates parameters. No momentum, decay,
clipping, scheduler, or optimizer state is part of this SGD path.

`Sgd.trainStep` uses the real shifted next-token cross-entropy and returns
before/after mean losses, supervised-token count, learning rate, and updated
tensor/scalar counts. Deterministic fixtures verify the negative-gradient
direction, the wrong-direction control, exact parameter deltas, untouched
unused-token rows, and a bounded 30-step micro-overfit of one tiny sequence.
That fixture demonstrates learning mechanics only; JADE has no trained
production language model or useful learned language ability. Mini-batches,
corpus training, epochs and production training remain outside this loop.

## Loop 5A — bounded local text training

`TextCorpus` reads explicitly supplied `.txt` files in sorted path order,
strictly decodes UTF-8, concatenates their exact text, and tokenizes with the
existing JADE BPE tokenizer. Configurable byte and token limits are capped at
the tokenizer's one-megabyte input limit. No files are discovered, downloaded,
or sent over a network. Metadata records file count, UTF-8 bytes, Unicode
code points and resulting token count.

`TokenDataset` splits the continuous token stream sequentially before creating
fixed-context, configurable-stride windows in either partition. Each window's
targets are its inputs shifted by one position. Batches retain the final
incomplete group, and no padding or shuffle occurs. Window count and total
window cells are bounded before allocation. This split prevents windows from
crossing the train/validation boundary; repeated text can still occur on both
sides of a tiny fixture.

`SgdTrainer` averages losses and gradients by supervised-token count across
each batch. It applies the Loop 4I immutable replacement-model SGD step once
per batch, in deterministic order, for an explicit number of epochs. At each
epoch end it evaluates train and validation losses forward-only; validation
never computes gradients or updates parameters. Perplexity is `exp(meanLoss)`;
values beyond finite double range are reported as positive infinity. Epoch
metrics include losses, perplexities, supervised-token counts, and per-epoch
optimizer steps; the result and step history also retain cumulative steps.
The local `src/test/resources/brain/tiny-corpus.txt` fixture proves batch equivalence, determinism,
validation immutability and learning from a local natural-language `.txt` file.

This is a bounded single-threaded in-memory trainer, not large-corpus
streaming or useful general language ability. No production JADE model weights
are trained. Existing AdamW/checkpoint code predates this loop and is not used
by `SgdTrainer`; Loop 5A uses vanilla SGD only.

Loop 5A verification: two local copies of the 114-byte fixture yield 228 bytes,
228 code points and 228 base-BPE tokens. Explicit configuration is context=4,
stride=4, batch=4, epochs=3, learningRate=0.02, trainFraction=0.9,
maxInputBytes=4096, maxTokens=4096; model dimensions are vocabulary=256,
context=4, embedding=4, heads=2, layers=1, FFN=8, initialization seed=26167.
The zero-based split boundary is 205: training tokens occupy [0,205),
validation tokens [205,228). There are 51 training and 5 validation windows,
with 13 retained-tail training batches per epoch. Training windows cover
source positions 0–204; validation windows cover 205–225. Positions 226–227
are unused because no complete next stride window fits. Repeated text occurs
on both sides; source positions never cross the split.

Initial train/validation loss: 5.550088489256743 / 5.552746955890505.

| Epoch | Train loss | Validation loss | Train perplexity | Validation perplexity | Steps |
| --- | --- | --- | --- | --- | --- |
| 1 | 5.464174322120360 | 5.461443607653566 | 236.08084787946086 | 235.43705789618505 | 13 |
| 2 | 5.373009719093946 | 5.367836291445315 | 215.51051871476068 | 214.39846967157592 | 13 |
| 3 | 5.286137571500380 | 5.279726389107782 | 197.57881568612055 | 196.31615376473937 | 13 |

39 SGD steps supervise 612 training token positions (204 per epoch); validation
evaluates 20 token positions per epoch. With prompt `ja` and two greedy new
tokens, generated continuations are before `\u0011\u0005` (escaped control
characters, token IDs 17 and 5) and after `rs`. These are continuations only,
not useful general language ability. Batch-loss and every scalar gradient
match independent example means within 1e-15; parameter comparisons during
validation use raw double bits. Tests additionally cover actual BPE merges,
Unicode, unreadable/missing/malformed/oversized files, invalid configurations,
incomplete batches, and non-finite/overflow rejection without partial updates.

This loop does not add AdamW integration, shuffled batches, checkpoint
persistence/resume integration, streaming, production-scale training,
pretrained weights, or useful general language ability to this SGD pipeline.

Verification commands: `mvn -o -Dtest=CorpusTrainingTest,SgdTest test` passed
10 tests; `mvn -o clean package` passed all 473 tests in 60 Surefire suites
(zero failures, errors or skips). The first sandboxed full run could not bind
the pre-existing `RemoteProviderTest` loopback HTTP server; the full offline
rerun with sandbox escalation passed. Corpus/training use no network; that
existing regression uses only local HTTP. `git diff --check` passed. The dirty
working tree was preserved, with no staging, commit or push.

## Loop 5B — AdamW, deterministic shuffle and exact epoch resume

`TrainingOptimizer` selects the existing `Sgd.step` or `AdamW.step` through
immutable optimizer/model snapshots. `CorpusTrainer` reuses Loop 5A's loader,
split, windows, batches, batch gradients, evaluation and epoch metrics. The
original `SgdTrainer` remains unchanged. Without shuffling, the new SGD adapter
matches its losses, step history and all parameter bits exactly.

AdamW uses first/second moments `m = beta1*m + (1-beta1)*g` and
`v = beta2*v + (1-beta2)*g*g`, with bias correction by `1-beta1^t` and
`1-beta2^t`. The update is
`theta - learningRate*mHat/(sqrt(vHat)+epsilon) - learningRate*weightDecay*theta`.
All hyperparameters are explicit and validated. Existing optional clipping
and named decay exclusions are retained; the Loop 5B experiment disables
clipping and applies decay to every trainable parameter. SGD continues to
use its original unclipped learning-rate update.

Optional training shuffling copies canonical windows and performs explicit
Fisher-Yates with Java `Random`, seeded by
`shuffleSeed + 0x9E3779B97F4A7C15L * absoluteEpoch` (intentional long overflow).
Validation always uses canonical ordering. Each absolute epoch regenerates
its permutation independently; no mutable RNG cursor needs persistence.

`BrainCheckpoint.saveTraining/loadTraining` use explicit binary format **2**:
magic/version, six architecture dimensions, optimizer name, full corpus and
optimizer configuration, shuffle flag/seed, completed epochs, optimizer steps,
supervised training tokens, initial losses, corpus/tokenizer SHA-256 identities,
then named parameter tensors and AdamW moment tensors in model registry order.
The AdamW timestep equals optimizer steps and is validated as such. Corpus
identity hashes the ordered token stream; tokenizer identity hashes every
vocabulary token's exact bytes and every ranked merge rule. No Java object
serialization is used. The separate version-1 snapshot API remains supported.

Loading verifies the SHA-256 body checksum, version, bounded architecture,
expected architecture/configuration/tokenizer/corpus, tensor identities/shapes,
finite numbers, nonnegative moments and epoch-boundary counters before returning
a new state. Dimensions are bounded by `BrainConfig`; checkpoint files are
capped at 64 MiB and tensor payload lengths are checked before allocation.
Failed loads never mutate existing objects. SHA-256 detects corruption; the
format is not an authenticated source of trusted model weights.

Saving writes a sibling temporary file, closes and forces it to disk, then
atomically replaces the target where supported. On filesystems without atomic
move, NIO first keeps a backup of the previous file, moves the complete new
file, and restores the backup on failure. If restoration fails, the backup is
retained and its location is reported. That fallback is not crash-atomic;
power-loss recovery and directory fsync are outside this loop.

Resume occurs **only at completed epoch boundaries**. `Config.data.epochs`
means the total target epoch count, not additional epochs. That target may
change, but cannot precede completed epochs. Byte/token ingestion ceilings may
also change if the same corpus fits. Architecture, token stream, tokenizer,
context, stride, split fraction, batch size, optimizer type/hyperparameters,
clipping/exclusions, shuffle flag and shuffle seed must remain compatible.
Checkpoint histories are not persisted; counters and initial losses are, and
each resumed result reports newly processed epoch/step metrics.

The existing 228-token natural-text fixture uses AdamW learningRate=0.005,
beta1=0.9, beta2=0.999, epsilon=1e-8, weightDecay=0.01, shuffleSeed=42; model
seed/dimensions and corpus-window settings match Loop 5A. Four continuous
epochs equal two epochs plus save/load plus two resumed epochs in **raw double
bits** for every parameter and both moments, timestep, counters, final losses
and generated token IDs. This guarantee applies to the same Java runtime and
arithmetic environment with the exact compatible inputs. The epoch-2 checkpoint
is 53,352 bytes; the full run has 52 updates and 816 supervised token positions.

| Epoch | Train loss | Validation loss | Train perplexity | Validation perplexity |
| --- | --- | --- | --- | --- |
| 1 | 5.197265680182764 | 5.189469955984224 | 180.77726261606867 | 179.37345190542243 |
| 2 | 4.788888699801158 | 4.774975838543949 | 120.16775198617026 | 118.50745127355648 |
| 3 | 4.378299376087896 | 4.360257876450497 | 79.70237432161632 | 78.27731769741906 |
| 4 | 4.0026004942958755 | 3.9802337510159704 | 54.74031698292394 | 53.52954535080049 |

Prompt `ja` generates before-training IDs `[17,5]`, escaped text
`\u0011\u0005`; after-training and resumed IDs are both `[115,115]`, text `ss`.
No useful general language ability is claimed. Training remains bounded,
single-threaded and in memory. Mid-epoch resume, streaming/token storage,
production-scale training, accelerators and pretrained weights remain outside
this loop. The next frontier is larger bounded real text and training-quality
evaluation; corpus loading/training/checkpoints require no network or model
runtime.

Loop 5B focused verification: 40 tests passed across the new resume/fallback
tests and existing optimizer/checkpoint/training/SGD/corpus tests. Full
`mvn -o clean package`: 484 tests in 62 Surefire suites, zero failures, errors
or skips. The existing unrelated loopback HTTP regression ran with sandbox
escalation; training used no network. `git diff --check` passed. No staging,
commit or push was performed.

## Loop 5C — bounded English corpus experiment

**CURRENT TRAINING TARGET: ENGLISH ONLY.** This is a corpus selection and
benchmark policy, not a restriction in transformer mathematics or UTF-8 BPE.
The model is trained from random initialization. No pretrained weights,
embeddings, borrowed checkpoints, external model runtime, APIs, Python or
network dataset access are used. Brain remains disconnected from AnswerService;
UI and voice behavior are unchanged by this loop.

### Corpus contract and bounded ingestion

The experiment uses 20 repository-local original synthetic English prose files
in `src/test/resources/brain/english`, reproducible with the Java
`OriginalEnglishFixture` utility. Total: **37,429 bytes/code points**, roughly
164 times the old 228-byte fixture. These stories share a deliberately repeated
template; they are **not representative of real-world English**. No books,
articles or downloaded dataset are included.

`EnglishCorpus.scan` accepts a local directory of UTF-8 `.txt` files, including
nested directories up to seven levels. Paths use normalized relative `/`
separators, sorted lexically. It skips unsupported files, symlinks, unreadable
files, invalid UTF-8, empty files and garbage, with explicit reasons. Resource
ceilings fail clearly rather than silently dropping oversized data. The
lightweight heuristic requires at least 35% ASCII alphabetic code points and
4–60% whitespace, permits normal punctuation/numbers/dialogue, and rejects ISO
controls except tab/CR/LF and lines above the configured character limit.
This filter rejects obvious garbage; it is **not language identification**.
English source selection remains the operator's responsibility. Unicode
letters and symbols remain valid and lossless; they are not prohibited.

A strict UTF-8 decoder reads incrementally through a byte-counting stream and
SHA-256 digest. Only one bounded line String is emitted at a time. BPE fitting
retains a bounded prefix sample of complete **training** lines, at most 65,536
UTF-8 bytes / 4,096 entries; excessive individual lines fail to fit explicitly.
Encoding resets BPE rank application at line boundaries, retaining the exact
newline bytes. This is lossless but can differ from encoding one concatenated
String because merges cannot span lines. Adjacent files are concatenated
without inserting extra text; provide newline endings when separation matters.

Experiment ceilings: 1 MiB/file, 8 MiB scanned corpus, 4,096 UTF-16 characters/line,
128 files, 65,536 fitting bytes, 2,000,000 tokens/partition. Configurable hard
ceilings are 16 MiB/file, 64 MiB/corpus, 16,384 characters/line, 512 files and
16,777,216 tokens/partition. BPE still uses full sequence recount/replacement,
and encoding uses repeated full rank scans: these can approach quadratic work
on pathological sequences. The existing 32-million pair-scan guard remains.
We did not rewrite verified tokenizer semantics or transformer/backward/AdamW.

### Manifest, split and token storage

The [manifest](loop-5c/manifest.tsv) records every accepted path, byte count,
code-point count and SHA-256 content digest; skipped records include reasons.
The corpus digest hashes each ordered path, counts and content digest:
`d5310d31954ee0a5b9f8591d7b70b24517d7f35ce30dcf8e5b3739210fd0a1f9`.
Accepted: 20; skipped: 0. Subsequent fitting/encoding recomputes each file's
content digest and rejects changed files.

Split before tokenizer fitting: sorted **whole-file** boundaries, floor(90%
file count) train, floor(5%) validation, remainder test, clamped to preserve
one file per partition. At least three accepted files are required; a partition
must then encode to at least context+1 tokens to yield windows. The split
ratios apply to file counts, not exact byte/token ratios. Here train is files
01–18 (33,687 bytes), validation file 19 (1,870 bytes), test file 20 (1,872 bytes).
There is no cross-partition window or BPE fitting leakage. However, the
synthetic template repeats across partitions: this is **content overlap** that
makes the measured holdout much easier than independently authored prose.

The train-only BPE tokenizer freezes before validation/test encoding. Its
vocabulary and merge list are immutable. `TokenStore.Chunked` stores tokens in
4,096-int chunks with long token counts and checked indexed/range access;
all encoded tokens remain in RAM, but there is no giant flat array. Its sealed
builder owns storage and does not duplicate it on publication. `StoredDataset`
uses an `AbstractList`: for context C, stride S, N tokens, window count is
`1 + (N-C-1)/S` when N>C. Each indexed request copies only C+1 tokens and derives
inputs/targets; no example matrix is retained. One bounded int permutation
(up to 1,000,000 indices) gives deterministic epoch shuffling. Validation and
test use canonical order. Incomplete final batches are retained.

`CorpusTrainer.Data` consumes these lazy views; its identity combines the
manifest, split policy/membership and exact partition token streams. The
version-2 checkpoint overload checks this identity and the existing tokenizer
identity, architecture, optimizer/configuration and epoch counters. The legacy
`TextCorpus`/eager `TokenDataset` APIs remain for verified small proof tests;
the scaled path does not use them. Model allocation checks now bound vocabulary-logit cells alongside existing parameter/FFN limits. The verified backward path already limits actual attention-cache cells. Training calls reject more than one million retained step-history entries before updates.

### Measurements

Full raw evidence, all token IDs and escaped generation outputs are in
[experiment.txt](loop-5c/experiment.txt). Times are one wall-clock observation,
not a statistical performance study; Java 25.0.2, Java sources targeted to 21,
CPU only, experiment heap cap 512 MiB.

| Target vocabulary | Actual | Train tokens | Validation tokens | Test tokens | Bytes/token | Fit seconds |
| --- | --- | --- | --- | --- | --- | --- |
| 512 | 512 | 12,222 | 682 | 678 | 2.755780 | 0.122849 |
| 1024 | 1024 | 3,006 | 170 | 160 | 11.219724 | 0.158261 |
| 2048 | 1659 | 360 | 61 | 57 | 78.303347 | 0.144803 |

Every fixture line round-tripped. Larger vocabularies learn long template
fragments; the 2048 target exhausts available pairs at 1659 tokens. Selected
**512**, the only candidate leaving at least eight context-32/stride-32 windows
in each holdout (selection maximizes measured compression among candidates
meeting that minimum).

| Fixed non-verbatim English sentence | UTF-8 bytes | Tokens | Bytes/token | Lossless |
| --- | --- | --- | --- | --- |
| JADE learns language from examples. | 35 | 18 | 1.944444 | PASS |
| Where did the spacecraft land? | 30 | 20 | 1.500000 | PASS |
| I don't think that's the correct answer. | 40 | 16 | 2.500000 | PASS |
| The temperature is 27.5 degrees. | 32 | 21 | 1.523810 | PASS |
| Can a small model learn useful patterns? | 40 | 19 | 2.105263 | PASS |

Model: vocabulary 512, context 32, embedding 32, four heads, two layers,
FFN 128, seed 26167. Exact untied/bias-free parameter count:
`2*512*32 + 32*32 + 2*(4*32*32 + 2*32*128) = 58,368`.
RMSNorm scales are unit constants, not additional learned parameters.
Double payloads: parameters **466,944 bytes**, AdamW first+second moments
**933,888 bytes**, one parameter gradient set **466,944 bytes**. One context
logit matrix is 131,072 bytes; attention score payload per layer is 32,768 bytes.
Cached activations and backward tensors add several such arrays. Batch examples
are processed sequentially, so activation memory does not scale like a fully
parallel batch. Immutable parameter/optimizer updates retain temporary copies;
a conservative rough live working allowance is **16–32 MiB**, excluding JVM
baseline and corpus storage, not a measured peak. Chunked corpus payload is
about 80 KiB for this experiment; fitting text/sequence arrays add bounded RAM.

Smoke: batch 4, context 32, 512 supervised tokens, four optimizer steps,
0.114600 seconds, **4,467.70 tokens/second**. Smoke updates use an independent
snapshot, never the experiment model. Estimated six-epoch compute had to fit
180 seconds; the actual experiment also had a 240-second update budget.

Training: AdamW, learning rate .003, beta1 .9, beta2 .999, epsilon 1e-8,
weight decay .01, clip threshold 1, no decay exclusions, batch 4, stride 32,
six epochs, deterministic Fisher-Yates seed 42. 381 train windows,
21 validation windows, 21 test windows; the last train batch has one example.
Each epoch performs 96 steps and supervises 12,192 positions. Train losses
below are forward evaluation of the final epoch snapshot, not average update loss.

| Epoch | Train loss | Validation loss | Train PPL | Validation PPL | Steps | Tokens | Seconds |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 3.878833 | 3.896399 | 48.367722 | 49.224860 | 96 | 12,192 | 1.151530 |
| 2 | 2.431323 | 2.478315 | 11.373921 | 11.921163 | 96 | 12,192 | 1.141263 |
| 3 | 1.610421 | 1.691385 | 5.004919 | 5.426992 | 96 | 12,192 | 1.120117 |
| 4 | 1.112826 | 1.230805 | 3.042946 | 3.423986 | 96 | 12,192 | 1.113760 |
| 5 | 0.786740 | 0.947267 | 2.196224 | 2.578651 | 96 | 12,192 | 1.182512 |
| 6 | 0.610923 | 0.801707 | 1.842131 | 2.229342 | 96 | 12,192 | 1.160465 |

Initial validation loss/PPL: **6.247628764318 / 516.785949355087**.
Final: **0.801706600070 / 2.229342279567**; reductions
**5.445922164248 / 514.556607075520**.
Initial test loss/PPL: **6.247410168779 / 516.672994597646**.
The frozen final model's sole test evaluation: **0.826042642301 /
2.284261191492**, 672 supervised positions, reductions **5.421367526478 /
514.388733406154**. No parameter updates follow that evaluation. Test data
never enters tokenizer fitting or optimizer updates. Total: 576 updates,
73,152 training positions, 6.951687 seconds including resume/probes/generation.

An epoch-2 checkpoint was saved (1,401,888 bytes), loaded with the larger
architecture/tokenizer/corpus and compared against the saved parameters and
step counter, then used for epochs 3–6. The existing exact bit-resume tests
remain unchanged. The experiment checkpoint is stored with the local report.

### Generations, probes and limitations

All six predeclared prompts use greedy decoding, 12 new tokens maximum and
the existing context ceiling. The report preserves **every** before/after ID
and escaped output. All six random-model outputs contain invalid UTF-8;
the benchmark explicitly labels replacement decoding and retains exact IDs.
The production generator's strict UTF-8 behavior is unchanged. All six trained
outputs decode correctly. After-training continuations:

| Prompt | Escaped continuation (excluding prompt) |
| --- | --- |
| `the ` | `visitor asked three questions. the first was eas` |
| `this ` | `was the name of their experimen` |
| `what ` | `nearest every answer," bird landed ou` |
| `jade ` | `was the name of their easier for visitors to` |
| `the computer ` | `displayed a list of ped a list of ` |
| `once upon ` | `monday morning.\nthe visitor find infor` |

The common-word probe forms a fixed set of leading BPE tokens from training
suffixes following literal `the `; this is a simple continuation proxy, not
linguistic judgment. Probability mass rises from **0.090312 to 0.795253**.
Across all generated outputs, printable code points excluding controls and
replacement characters rise **84.1772% → 99.5690%**; whitespace is
**16.4557% → 16.3793%**. Disallowed controls (ISO control excluding whitespace)
fall **2.5316% → 0%**. The remaining trained newline is an allowed control;
total ISO control ratio is **0.4310%** after training, so "0% controls" without
that distinction would be inaccurate. Replacement decoding limits the meaning
of untrained character statistics; exact token IDs remain the primary evidence.

Training-prefix next-token loss (`the garden was quiet when anna arrived on
monday morning.\n`) falls **6.207154 → 1.808212**. Held-out-prefix loss
(`the greenhouse was quiet when tom arrived on monday morning.\n`) falls
**6.246187 → 3.464100**. This measures at most context+1 tokens and is a narrow
memorization check, not an audit. Lower held-out loss and cleaner output show
learned predictive structure on the template; fragments are repetitive,
occasionally incoherent and unfinished. **No useful general English ability
is claimed.** No assistant quality gate is established.

The next bounded experiment should prioritize 150–250 KiB of independently
written English text, distinct held-out topics/templates, then remeasure
512/1024 vocabularies. Keep context 32 and this 58k-parameter model initially;
only test context 64 or embedding 64 after isolating corpus gains. At the
measured smoke rate, retain a 2–4 minute ceiling and roughly 100k–300k supervised
positions rather than choosing a large epoch count blindly. Larger corpus,
more diverse prose and statistical independence are the main bottlenecks;
full-scan BPE and immutable double-array allocation become future compute
limits. No next-loop increase is implemented.

Reproduce explicitly after `mvn -o test-compile`:

```text
java -Xmx512m -cp target/test-classes:target/classes com.jade.brain.EnglishLearningBenchmark src/test/resources/brain/english /tmp/jade-loop-5c
```

The >1 MiB scale test proves streaming ingestion, bounded fitting, multiple
files, stable manifest/encoding identity, chunk access and partition-local
lazy windows without training a large model. Focused tests additionally cover
malformed input, punctuation/Unicode, mutation after manifesting, forged splits,
train-only BPE, immutable held-out encoding, bounds/overflow, deterministic
shuffle, final batches, scaled forward/backward, bounded training/evaluation,
checkpoint compatibility and deterministic generation. This runner is explicit;
the six-epoch benchmark does not run automatically with unit tests.

Final verification: **82 focused tests in six suites**, all passed, including
20 new corpus/scale tests and unchanged exact-resume tests. Required
`mvn -o clean package`: **504 tests in 63 Surefire suites**, zero failures,
errors or skipped tests; BUILD SUCCESS. An initial context-level attention
ceiling conflicted with a verified backward-cache test; that redundant ceiling
was removed, retaining the existing actual-sequence cache guard. The existing
unrelated HTTP transport test required loopback socket permission for the final
offline build; corpus processing and training used no network. `git diff --check`
passed. Existing unrelated working-tree changes were preserved. No staging,
commit or push. Loop 5C stops here.

## Loop 5D — diverse English and 5M readiness

See [the complete Loop 5D report](loop-5d/REPORT.md) for provenance, overlap,
BPE compression, exact FP64 memory/parameter planning, the bounded scale ladder,
all fixed generations/probes, and aggregate regression results. The corpus is
304,963 bytes of curated public-domain historical and original English. The
58,368-parameter control trained for 235,520 positions; its independently authored
final test loss is 4.122185075752 (perplexity 61.693900938126), not the template-heavy
5C result. The selected 1024/32/256/4/6/1024 candidate has exactly 5,251,072
parameters and passed a four-step training trajectory plus checkpoint/resume.
The 5.5M total-parameter and 160 MiB checkpoint ceilings support this gate;
per-matrix elements remain capped at two million. No full 5M training was started.
This establishes a bounded CPU engineering path, not broad English competence.
