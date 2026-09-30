package com.jarvis.api;

public interface SystemInfoService {
    SystemSnapshot snapshot(CancellationToken cancellation) throws ServiceException;
}
