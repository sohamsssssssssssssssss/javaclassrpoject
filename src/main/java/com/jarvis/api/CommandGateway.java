package com.jarvis.api;

import java.util.function.Consumer;

public interface CommandGateway extends AutoCloseable {
    CommandSubscription submit(
            CommandRequest request,
            Consumer<ProgressEvent> onProgress,
            Consumer<CommandOutcome> onComplete);

    @Override
    void close();
}
