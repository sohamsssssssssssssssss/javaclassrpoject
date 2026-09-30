package com.jarvis.services.app;

import com.jarvis.api.ConfiguredApp;
import com.jarvis.api.ServiceException;

/**
 * OS adapter: maps a configured logical app to the platform's explicit
 * executable and argument list. Implementations never accept or build a
 * shell string and never embed personal machine paths; they use the
 * platform-standard launcher for the logical app id.
 */
public interface AppCommandResolver {

    /** Adapter for the platform we are currently running on. */
    static AppCommandResolver forCurrentPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (osName.contains("mac") || osName.contains("darwin")) {
            return new MacAppCommandResolver();
        }
        if (osName.contains("win")) {
            return new WindowsAppCommandResolver();
        }
        if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            return new LinuxAppCommandResolver();
        }
        throw new IllegalStateException(
                "Unsupported platform for application launching: " + osName);
    }

    /**
     * Resolves the configured app to a launchable command list, or fails
     * with a structured {@link ServiceException} (unsupported platform,
     * unknown logical app, or missing executable).
     */
    LaunchSpec resolve(ConfiguredApp app) throws ServiceException;
}
