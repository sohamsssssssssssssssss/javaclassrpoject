# JADE-50M Baseline Chat Report

This is JADE-50M after only the Loop 6D micro-training smoke.
It is NOT a trained conversational model.

## Model Configuration
- Architecture: V=1024, C=32, D=640, H=8, L=10, F=2560
- Parameter count: 50,483,200
- Checkpoint: ../docs/loop-6d/checkpoints/50M-step4.jade
- Checkpoint step: 4
- Supervised positions: 256

## Diagnostic Prompt ("hello jade")
- Input text: `hello jade`
- Input token IDs: `[556, 284, 344, 106, 388, 101]`
- Generated token IDs: `[700, 700, 700, 700, 700, 700, 700, 700, 700, 700, 700, 700, 700, 700, 700, 700, 700, 776, 776, 776, 776, 776, 776, 776]`
- Generated text: `for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the time time time time time time time `

## Inference Performance
- Checkpoint load time: 3423.47 ms
- Prompt tokenization time: 19.5626 us
- First-token latency: 9.16137 ms
- Generation throughput: 49.7699 tokens/sec
- Peak RSS: 1365 MB (1431781376 bytes)

## Greedy Outputs

```
hello
->
for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the time time time time time time time

hello jade
->
for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the time time time time time time time

my name is
->
for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the time time time time time time ddd

what is a computer
->
for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the time time time time time time

what is java
->
for the for the for the for the for the for the for the for the for the for the for the for the for the for the time time time time time time dddd

the sky is
->
for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the for the time

once upon a time
->
for the for the for the for the for the for the for the for the for the for the time time time time time grdgrdddddd

User: hello
Assistant:
->
for the for the for the for the for the for the for the for the time time time time ddddddd

User: what is a computer?
Assistant:
->
for the for the for the time ddddddddddd

User: who are you?
Assistant:
->
for the for the for the for the time dddddddddddd

```

## Sampled Outputs (T=0.8, top-k=40, seed=26167)

```
hello
->
ationfor the s incbydworgrworight dation. Ba da  this ntime out. Bfor the tihave

hello jade
->
sefor the s blwords ight grnow
e , or have
 themfs doutdtime
have

my name is
->
linfor the s ywortiI this grti. Be , or grfor the wor mndoutdbyIhave

what is a computer
->
sefor the time yI ms ight have ight
e ight . Bby, or  thems doutdyyhave

what is java
->
worfor the time  themI mhave ti. Bight for the e worhave time ight for the s doutdbyIhave

the sky is
->
y for the s yworfIds ight wor them, or d. Bworanda . Bfor the
time

once upon a time
->
time for the s 'have outti. Bfor the  themfor the cenow

time wore doutda
ti

User: hello
Assistant:
->
dfor the worationwora. Bight . B, or
se, or have I, or fs d

User: what is a computer?
Assistant:
->
, or for the have e I themIaanowIse, or ti


User: who are you?
Assistant:
->

for the have e tiworInowI, or time ationnowtiy, or f

```
