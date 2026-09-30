package com.jarvis.services.app;

import com.jarvis.api.ConfiguredApp;
import com.jarvis.api.ErrorCode;
import com.jarvis.api.ServiceException;
import com.jarvis.api.StructuredError;

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
