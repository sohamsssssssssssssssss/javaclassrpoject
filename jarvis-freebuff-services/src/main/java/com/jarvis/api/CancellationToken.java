package com.jarvis.api;

@FunctionalInterface
public interface CancellationToken {
    CancellationToken NONE = () -> false;

    boolean isCancellationRequested();
}
