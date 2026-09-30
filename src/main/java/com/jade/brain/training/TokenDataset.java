package com.jade.brain.training;

import com.jade.brain.objective.LanguageModelExample;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.AbstractList;

/** Sequential split first, then independent fixed-width windows; incomplete final batches are retained. */
public record TokenDataset(int[] trainTokens, int[] validationTokens,
                           List<Window> train, List<Window> validation, int splitIndex) {
    private static final int MAX_WINDOWS_PER_PARTITION = 50_000;
    private static final long MAX_WINDOW_TOKEN_CELLS = 2_000_000;
    public record Window(int start, LanguageModelExample example) {
        public Window {
            if (start < 0 || example == null) throw new IllegalArgumentException("Invalid window");
        }
    }
    public record Batch(List<Window> windows) {
        public Batch {
            windows = List.copyOf(windows);
            if (windows.isEmpty()) throw new IllegalArgumentException("Empty batch");
            int length = windows.getFirst().example().inputTokenIds().length;
            for (var window : windows)
                if (window.example().inputTokenIds().length != length)
                    throw new IllegalArgumentException("Batch sequences must have equal length");
            Math.multiplyExact(windows.size(), length);
        }
        public int[][] inputs() {
            return windows.stream().map(w -> w.example().inputTokenIds()).toArray(int[][]::new);
        }
        public int[][] targets() {
            return windows.stream().map(w -> w.example().targetTokenIds()).toArray(int[][]::new);
        }
        public int supervisedTokens() {
            return windows.stream().mapToInt(w -> w.example().targetTokenIds().length).sum();
        }
    }
    public TokenDataset {
        trainTokens = trainTokens.clone(); validationTokens = validationTokens.clone();
        train = List.copyOf(train); validation = List.copyOf(validation);
        if (train.isEmpty() || validation.isEmpty() || splitIndex != trainTokens.length)
            throw new IllegalArgumentException("Both split partitions require at least one window");
    }
    @Override public int[] trainTokens() { return trainTokens.clone(); }
    @Override public int[] validationTokens() { return validationTokens.clone(); }

    public static TokenDataset split(int[] tokens, int contextLength, int stride, double trainFraction) {
        if (tokens == null || contextLength < 1 || stride < 1
                || !Double.isFinite(trainFraction) || trainFraction <= 0 || trainFraction >= 1)
            throw new IllegalArgumentException("Invalid dataset configuration");
        int cut = (int) Math.floor(tokens.length * trainFraction);
        int[] train = Arrays.copyOfRange(tokens, 0, cut);
        int[] validation = Arrays.copyOfRange(tokens, cut, tokens.length);
        return new TokenDataset(train, validation, windows(train, contextLength, stride),
                windows(validation, contextLength, stride), cut);
    }
    private static List<Window> windows(int[] ids, int contextLength, int stride) {
        long count = ids.length <= contextLength ? 0
                : 1L + (ids.length - contextLength - 1L) / stride;
        if (count > MAX_WINDOWS_PER_PARTITION || count * contextLength > MAX_WINDOW_TOKEN_CELLS)
            throw new IllegalArgumentException("Dataset window allocation limit exceeded");
        var windows = new ArrayList<Window>();
        for (long offset = 0; offset + contextLength < ids.length; offset += stride) {
            int start = (int) offset;
            windows.add(new Window(start, LanguageModelExample.fromTokens(
                    Arrays.copyOfRange(ids, start, start + contextLength + 1))));
        }
        return windows;
    }
    public static List<Batch> batches(List<Window> windows, int batchSize) {
        if (windows == null || batchSize < 1 || batchSize > 1024) throw new IllegalArgumentException("Invalid batch size");
        int count = (int) ((windows.size() + (long) batchSize - 1) / batchSize);
        return new AbstractList<>() {
            public int size() { return count; }
            public Batch get(int index) {
                if (index < 0 || index >= count) throw new IndexOutOfBoundsException("Batch index: " + index);
                int start = Math.toIntExact((long) index * batchSize);
                return new Batch(windows.subList(start, start + Math.min(batchSize, windows.size() - start)));
            }
        };
    }
}
