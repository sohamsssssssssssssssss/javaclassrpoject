package com.jade.brain.training;

import java.util.ArrayList;
import java.util.List;

/** Indexed tokens without a single giant array; ranges are bounded independent copies. */
public interface TokenStore {
    long size();
    int get(long index);

    default int[] range(long start, int length) {
        if (start < 0 || length < 0 || length > 1_048_576 || start > size() - length)
            throw new IllegalArgumentException("Invalid or excessive token range");
        int[] result = new int[length];
        for (int i = 0; i < length; i++) result[i] = get(start + i);
        return result;
    }

    final class Chunked implements TokenStore {
        public static final int CHUNK_SIZE = 4096;
        public static final long MAX_TOKENS = 16_777_216;
        private final List<int[]> chunks;
        private final long count;
        private Chunked(List<int[]> chunks, long count) { this.chunks = List.copyOf(chunks); this.count = count; }
        public long size() { return count; }
        public int get(long index) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException("Token index: " + index);
            return chunks.get((int) (index / CHUNK_SIZE))[(int) (index % CHUNK_SIZE)];
        }

        public static final class Builder {
            private final long maximum;
            private final List<int[]> chunks = new ArrayList<>();
            private long count;
            private boolean built;
            public Builder(long maximum) {
                if (maximum < 1 || maximum > MAX_TOKENS) throw new IllegalArgumentException("Invalid token-store limit");
                this.maximum = maximum;
            }
            public void append(int[] ids) {
                if (built) throw new IllegalStateException("Token store already sealed");
                if (ids.length > maximum - count) throw new IllegalArgumentException("Token-store count limit exceeded");
                for (int id : ids) if (id < 0) throw new IllegalArgumentException("Negative token ID");
                for (int id : ids) {
                    if (count % CHUNK_SIZE == 0) chunks.add(new int[CHUNK_SIZE]);
                    chunks.get((int) (count / CHUNK_SIZE))[(int) (count % CHUNK_SIZE)] = id;
                    count++;
                }
            }
            public Chunked build() {
                if (built) throw new IllegalStateException("Token store already sealed");
                built = true; return new Chunked(chunks, count);
            }
        }
    }
}
