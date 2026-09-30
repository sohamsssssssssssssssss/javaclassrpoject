package com.jade.api;

import java.util.UUID;

public interface CommandSubscription extends AutoCloseable {
    UUID requestId();
    void cancel();
    boolean isCancellationRequested();

    @Override
    void close();
}
