#!/bin/bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -f "$DIR/jarvis-integration/jade-engine/build/jade_chat" ]; then
    EXEC="$DIR/jarvis-integration/jade-engine/build/jade_chat"
    CKPT="$DIR/jarvis-integration/docs/loop-6d/checkpoints/50M-step4.jade"
    MERGES="$DIR/jarvis-integration/jade-engine/data/merges-1024.txt"
elif [ -f "$DIR/jade-engine/build/jade_chat" ]; then
    EXEC="$DIR/jade-engine/build/jade_chat"
    CKPT="$DIR/docs/loop-6d/checkpoints/50M-step4.jade"
    MERGES="$DIR/jade-engine/data/merges-1024.txt"
elif [ -f "$DIR/jarvis-integration/jarvis-integration/jade-engine/build/jade_chat" ]; then
    EXEC="$DIR/jarvis-integration/jarvis-integration/jade-engine/build/jade_chat"
    CKPT="$DIR/jarvis-integration/jarvis-integration/docs/loop-6d/checkpoints/50M-step4.jade"
    MERGES="$DIR/jarvis-integration/jarvis-integration/jade-engine/data/merges-1024.txt"
else
    if [ -d "$DIR/jarvis-integration/jade-engine" ]; then
        (cd "$DIR/jarvis-integration/jade-engine" && cmake -B build && cmake --build build)
        EXEC="$DIR/jarvis-integration/jade-engine/build/jade_chat"
        CKPT="$DIR/jarvis-integration/docs/loop-6d/checkpoints/50M-step4.jade"
        MERGES="$DIR/jarvis-integration/jade-engine/data/merges-1024.txt"
    elif [ -d "$DIR/jade-engine" ]; then
        (cd "$DIR/jade-engine" && cmake -B build && cmake --build build)
        EXEC="$DIR/jade-engine/build/jade_chat"
        CKPT="$DIR/docs/loop-6d/checkpoints/50M-step4.jade"
        MERGES="$DIR/jade-engine/data/merges-1024.txt"
    fi
fi

exec "$EXEC" --checkpoint "$CKPT" --merges "$MERGES" "$@"
