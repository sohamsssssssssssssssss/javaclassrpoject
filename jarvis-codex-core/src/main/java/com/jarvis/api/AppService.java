package com.jarvis.api;

import java.util.List;

public interface AppService {
    List<ConfiguredApp> configuredApps();
    AppLaunchReceipt launch(String appId, CancellationToken cancellation) throws ServiceException;
}
