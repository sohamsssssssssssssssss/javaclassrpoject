package com.jade.services.app;

import com.jade.api.ConfiguredApp;
import com.jade.api.ErrorCode;
import com.jade.api.ServiceException;
import com.jade.api.StructuredError;

import java.util.Optional;

/**
 * Linux adapter. Fails clearly with UNSUPPORTED_PLATFORM until the GNOME/KDE
 * mappings are implemented; never guesses executables.
 */
public final class LinuxAppCommandResolver implements AppCommandResolver {

    @Override
    public LaunchSpec resolve(ConfiguredApp app) throws ServiceException {
        throw new ServiceException(new StructuredError(
                ErrorCode.UNSUPPORTED_PLATFORM,
                "Linux application launching is not implemented in sprint 1",
                Optional.of("appId=" + app.id())));
    }
}
