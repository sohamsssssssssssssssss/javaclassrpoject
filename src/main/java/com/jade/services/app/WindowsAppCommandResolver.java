package com.jade.services.app;

import com.jade.api.ConfiguredApp;
import com.jade.api.ErrorCode;
import com.jade.api.ServiceException;
import com.jade.api.StructuredError;

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
