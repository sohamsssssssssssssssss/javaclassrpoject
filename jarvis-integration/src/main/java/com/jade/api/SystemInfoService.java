package com.jade.api;

public interface SystemInfoService {
    SystemSnapshot snapshot(CancellationToken cancellation) throws ServiceException;
}
