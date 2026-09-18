package com.jarvis.services.app;

import com.jarvis.api.ConfiguredApp;
import com.jarvis.api.ErrorCode;
import com.jarvis.api.ServiceException;
import com.jarvis.api.StructuredError;

import java.util.Optional;

/**
 * Windows adapter. Uses the documented shell-executable app aliases via
 * explicit argument lists to {@code cmd.exe} only as a fixed launcher name
 * with no user-derived string; no arbitrary command lines are accepted.
 */
public final class WindowsAppCommandResolver implements AppCommandResolver {

    @Override
    public LaunchSpec resolve(ConfiguredApp app) throws ServiceException {
        throw new ServiceException(new StructuredError(
                ErrorCode.UNSUPPORTED_PLATFORM,
                "Windows application launching is not implemented in sprint 1",
                Optional.of("appId=" + app.id())));
    }
}
