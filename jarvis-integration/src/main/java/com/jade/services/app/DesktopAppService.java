package com.jade.services.app;

import com.jade.api.AppLaunchReceipt;
import com.jade.api.AppService;
import com.jade.api.CancellationToken;
import com.jade.api.ConfiguredApp;
import com.jade.api.ErrorCode;
import com.jade.api.ServiceException;
import com.jade.api.StructuredError;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Launches only configured aliases through an OS adapter that produces an
 * explicit executable/argument list. There is deliberately no API that
 * accepts a raw command string: {@code sh -c}, {@code bash -c},
 * {@code cmd /c} and arbitrary user-derived command lines are impossible by
 * construction.
 *
 * <p>The receipt reports that the launch request was accepted (process
 * started, or {@code open} accepted the request); it never claims the
 * application's window was verified open.</p>
 */
public final class DesktopAppService implements AppService {

    private static final Set<String> SPRINT_IDS = Set.of("calculator", "text-editor", "file-manager");

    private final List<ConfiguredApp> apps;
    private final AppCommandResolver resolver;
    private final AppLauncher launcher;
    private final Clock clock;

    /** Contract composition: platform adapter resolved from the running OS. */
    public DesktopAppService(List<ConfiguredApp> apps) {
        this(apps, AppCommandResolver.forCurrentPlatform(), new ProcessAppLauncher(), Clock.systemUTC());
    }

    /** Full injection constructor used by tests and custom compositions. */
    public DesktopAppService(
            List<ConfiguredApp> apps,
            AppCommandResolver resolver,
            AppLauncher launcher,
            Clock clock) {
        if (apps == null || apps.isEmpty()) {
            throw new IllegalArgumentException("at least one configured app is required");
        }
        this.apps = List.copyOf(apps);
        this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
        this.launcher = java.util.Objects.requireNonNull(launcher, "launcher");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<ConfiguredApp> configuredApps() {
        return apps;
    }

    @Override
    public AppLaunchReceipt launch(String appId, CancellationToken cancellation) throws ServiceException {
        java.util.Objects.requireNonNull(appId, "appId");
        java.util.Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancellationRequested()) {
            throw new ServiceException(error(
                    ErrorCode.CANCELLED,
                    "Launch cancelled before start",
                    "appId=" + appId));
        }
        ConfiguredApp app = findApp(appId);
        // Sprint 1 ships only the three configured logical apps.
        if (!SPRINT_IDS.contains(app.id())) {
            throw new ServiceException(error(
                    ErrorCode.UNSUPPORTED_PLATFORM,
                    "Configured app is not supported in sprint 1: " + app.id(),
                    null));
        }
        LaunchSpec spec = resolver.resolve(app);
        try {
            launcher.launch(spec);
        } catch (IOException e) {
            throw new ServiceException(error(
                    ErrorCode.SERVICE_FAILURE,
                    "Failed to start " + app.displayName(),
                    spec.description() + ": " + rootMessage(e)), e);
        } catch (RuntimeException e) {
            throw new ServiceException(error(
                    ErrorCode.SERVICE_FAILURE,
                    "Failed to start " + app.displayName(),
                    spec.description() + ": " + rootMessage(e)), e);
        }
        // Accurate reporting: the request was accepted; the window was NOT verified.
        return new AppLaunchReceipt(app.id(), app.displayName(), Instant.now(clock));
    }

    private ConfiguredApp findApp(String appId) throws ServiceException {
        String wanted = asciiLower(appId.strip());
        for (ConfiguredApp app : apps) {
            if (asciiLower(app.id()).equals(wanted)) {
                return app;
            }
            for (String alias : app.aliases()) {
                if (asciiLower(alias).equals(wanted)) {
                    return app;
                }
            }
        }
        throw new ServiceException(new StructuredError(
                ErrorCode.UNKNOWN_APP,
                "Unknown application: " + appId,
                Optional.empty()));
    }

    private static String asciiLower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String rootMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    private StructuredError error(ErrorCode code, String message, String detail) {
        return new StructuredError(code, message, Optional.ofNullable(detail));
    }
}
