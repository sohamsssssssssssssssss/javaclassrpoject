# JADE Brain reference mathematics (source contract)

Source of truth: `src/main/java/com/jade/brain/{math,model,training,config}` as inspected for Loop 6A. All matrices are row-major conceptually in Java's `double[][]`; C++ uses contiguous row-major FP32 and tolerance-based comparisons. There are no trainable biases or normalization scales.

## Shape and parameter order

For vocabulary `V`, context `C`, width `D`, heads `H`, layers `L`, FFN width `F`, and sequence length `T <= C`, each head has `d = D/H`. Parameter registry order is token embedding `E[V,D]`, position embedding `P[C,D]`, then for every block `Q,K,Vp,O[D,D]`, `U[D,F]`, `W[F,D]`, and finally untied head `A[D,V]`. Exact count is `(2V+C)D + L(4D²+2DF)`. At 1024/32/256/4/6/1024 it is **5,251,072**.

## Forward

For input IDs `x[0..T-1]`, `X[t,c] = E[x[t],c] + P[t,c]`. In each block:

1. `N = RMSNorm(X)`, independently per row, `N[t,c] = X[t,c] / sqrt(mean_k X[t,k]² + 1e-6)`. Java rescales by `max(1,max(abs(X[t,*])))` internally to protect extreme finite FP64 values; the algebraic result is the same in the normal range.
2. `Qm=NQ`, `Km=NK`, `Vm=NVp`, all `[T,D]`.
3. For head `h` and query position `i`, only `j <= i` exists: `s[h,i,j] = dot(Qm[i,h*d:(h+1)*d], Km[j,h*d:(h+1)*d]) / sqrt(d)`. This is an exact causal prefix; future probabilities are zero. `p[h,i,*] = softmax(s[h,i,0..i])` with max subtraction.
4. Joined attention `J[i,h*d+c] = sum_{j<=i} p[h,i,j] Vm[j,h*d+c]`; projected attention `R=JO`; first residual `Y=X+R`.
5. `M=RMSNorm(Y)`, `Z=MU`, `G=GELU(Z)`, `B=GW`, final block state `X'=Y+B`.

GELU is the **tanh approximation** `g(z)=0.5z[1+tanh(sqrt(2/pi)(z+0.044715z³))]`, not erf GELU. After `L` blocks, `Hout=RMSNorm(X_L)`, logits `Zout=Hout A` `[T,V]`. No dropout or tied weights.

The shifted language-model example predicts each `target[t]=tokens[t+1]` from `input[t]=tokens[t]`. Cross entropy is the **arithmetic mean** over `T` positions, in natural-log units: `loss = (1/T) sum_t (logsumexp(Zout[t,*]) - Zout[t,target[t]])`. `dZout[t,k]=(softmax(Zout[t,*])[k]-1[k=target[t]])/T`. The batch gradient is the position-count-weighted mean of example gradients; for equal context lengths this is the arithmetic mean across examples.

## Backward

For linear `Y=XW`, `dX=dY Wᵀ` and `dW=XᵀdY`. GELU uses the derivative of the exact tanh formula above. For each RMSNorm row `y=x/r`, `r=sqrt(mean(x²)+epsilon)`, `dx=(dy-y*mean(dy*y))/r`. Both residual branches pass upstream unchanged along the identity path and add the branch gradient.

Attention backward starts with `dJ=dR Oᵀ`, `dO=JᵀdR`. For each causal row/head: `dVm[j,c] += p[j]*dJ[i,c]`; `dp[j]=dot(dJ[i,*],Vm[j,*])`; `ds[j]=p[j]*(dp[j]-sum_k dp[k]p[k])/sqrt(d)`; `dQm[i,c]+=sum_j ds[j]Km[j,c]`; `dKm[j,c]+=ds[j]Qm[i,c]`. Then Q/K/V projection gradients and input gradients use the same linear rule and sum. Embedding gradients scatter-add by token ID; position gradients scatter-add by position.

## Optimizer, checkpoint, generation

Global L2 gradient clipping applies to the averaged full-batch gradient: if norm exceeds threshold, multiply every scalar by `threshold/norm`. Java accumulates norm through `Math.hypot`. AdamW step `t` uses `m=beta1*m+(1-beta1)*g`, `v=beta2*v+(1-beta2)*g²`, `w'=w-lr*(m/(1-beta1^t))/(sqrt(v/(1-beta2^t))+epsilon)-lr*decay*w` unless a parameter is excluded from decay. Loop 5D/5E settings: lr 0.001, betas 0.9/0.999, epsilon 1e-8, decay 0.01, clip 1.0, no exclusions.

Java training checkpoints are deterministic epoch-boundary snapshots with architecture, optimizer/configuration, corpus and tokenizer identities, parameters, moments, counters and SHA-256. C++ v1 checkpoint remains a separate explicitly versioned FP32 format. Generation recomputes the whole prefix, chooses the maximum last-row logit (lowest ID on a tie), appends until token budget or context limit; there is no KV cache, EOS, or sampling in the reference generator.

Java BPE starts from byte IDs 0–255, learns ranked pair merges from **train-only** text, encodes each line independently, and stores partition-local IDs in 4096-token chunks. Windows read `[context+1]` IDs lazily at fixed stride and never cross train/validation/test boundaries. Loop 6A may feed C++ the same already-tokenized integer fixtures; it does not replace Java BPE.
