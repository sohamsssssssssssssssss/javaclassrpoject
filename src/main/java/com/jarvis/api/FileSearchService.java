package com.jarvis.api;

import java.util.function.LongConsumer;

public interface FileSearchService {
    FileSearchResult search(
            FileSearchQuery query,
            CancellationToken cancellation,
            LongConsumer visitedFileProgress) throws ServiceException;
}
