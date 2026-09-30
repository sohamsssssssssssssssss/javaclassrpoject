package com.jade.api;

import java.util.List;

public interface HistoryRepository extends AutoCloseable {
    void save(HistoryEntry entry) throws ServiceException;
    List<HistoryEntry> recent(int limit, CancellationToken cancellation) throws ServiceException;

    @Override
    void close() throws ServiceException;
}
