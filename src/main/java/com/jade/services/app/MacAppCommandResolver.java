package com.jade.services.app;

import com.jade.api.ConfiguredApp;
import com.jade.api.ErrorCode;
import com.jade.api.ServiceException;
import com.jade.api.StructuredError;

import java.util.List;
import java.util.Optional;

/**
 * macOS adapter. Logical apps map to platform-standard launchers invoked by
 * name, never to personal absolute paths and never through a shell.
 */
public final class MacAppCommandResolver implements AppCommandResolver {

    /** macOS launchers used by name (resolved via PATH), not hardcoded locations. */
    static final String OPEN = "open";
    static final String TEXTEDIT = "TextEdit";

    @Override
    public LaunchSpec resolve(ConfiguredApp app) throws ServiceException {
        return switch (app.id()) {
            case "calculator" -> new LaunchSpec("macOS Calculator via open", List.of(OPEN, "-a", "Calculator"));
            case "text-editor" -> new LaunchSpec("macOS TextEdit via open", List.of(OPEN, "-a", TEXTEDIT));
            case "file-manager" -> new LaunchSpec("macOS Finder via open", List.of(OPEN, "-a", "Finder"));
            default -> throw unknownApp(app);
        };
    }

    private ServiceException unknownApp(ConfiguredApp app) {
        return new ServiceException(new StructuredError(
                ErrorCode.UNKNOWN_APP,
                "No macOS launch mapping for configured app: " + app.id(),
                Optional.of("displayName=" + app.displayName())));
    }
}
