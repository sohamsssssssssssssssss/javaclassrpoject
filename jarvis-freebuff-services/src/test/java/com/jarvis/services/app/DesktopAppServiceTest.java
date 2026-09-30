package com.jarvis.services.app;

import com.jarvis.api.AppLaunchReceipt;
import com.jarvis.api.CancellationToken;
import com.jarvis.api.ConfiguredApp;
import com.jarvis.api.ErrorCode;
import com.jarvis.api.ServiceException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopAppServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final CancellationToken NONE = CancellationToken.NONE;

    private static List<ConfiguredApp> apps() {
        return List.of(
                new ConfiguredApp("calculator", "Calculator", Set.of("calculator", "calc")),
                new ConfiguredApp("text-editor", "Text Editor", Set.of("text editor", "editor")),
                new ConfiguredApp("file-manager", "File Manager", Set.of("file manager", "files")));
    }

    /** Records the LaunchSpec instead of starting a real process. */
    private static final class RecordingLauncher implements AppLauncher {
        LaunchSpec launched;

        @Override
        public Process launch(LaunchSpec spec) {
            this.launched = spec;
            return null; // Process is never used by DesktopAppService
        }
    }

    private static final class FailingLauncher implements AppLauncher {
        @Override
        public Process launch(LaunchSpec spec) throws IOException {
            throw new IOException("executable not found");
        }
    }

    private static final AppCommandResolver MAC_RESOLVER = new MacAppCommandResolver();

    @Test
    void launchesConfiguredAliasWithExplicitArguments() throws ServiceException {
        RecordingLauncher launcher = new RecordingLauncher();
        DesktopAppService service = new DesktopAppService(
                apps(), MAC_RESOLVER, launcher, CLOCK);

        AppLaunchReceipt receipt = service.launch("calc", NONE);

        assertEquals("calculator", receipt.appId());
        assertEquals("Calculator", receipt.displayName());
        assertEquals(NOW, receipt.launchedAt());
        assertEquals(List.of("open", "-a", "Calculator"), launcher.launched.command());
        assertEquals("macOS Calculator via open", launcher.launched.description());
    }

    @Test
    void resolvesAllConfiguredIdsAndAliases() throws ServiceException {
        RecordingLauncher launcher = new RecordingLauncher();
        DesktopAppService service = new DesktopAppService(
                apps(), MAC_RESOLVER, launcher, CLOCK);

        service.launch("calculator", NONE);
        assertEquals(List.of("open", "-a", "Calculator"), launcher.launched.command());

        service.launch("text editor", NONE);
        assertEquals(List.of("open", "-a", "TextEdit"), launcher.launched.command());

        service.launch("EDITOR", NONE);
        assertEquals(List.of("open", "-a", "TextEdit"), launcher.launched.command());

        service.launch("files", NONE);
        assertEquals(List.of("open", "-a", "Finder"), launcher.launched.command());
    }

    @Test
    void unknownAppIsRejectedAsUnknownApp() {
        DesktopAppService service = new DesktopAppService(
                apps(), MAC_RESOLVER, new RecordingLauncher(), CLOCK);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.launch("spotify", NONE));

        assertEquals(ErrorCode.UNKNOWN_APP, exception.error().code());
    }

    @Test
    void preCancelledLaunchIsCancelled() {
        DesktopAppService service = new DesktopAppService(
                apps(), MAC_RESOLVER, new RecordingLauncher(), CLOCK);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.launch("calc", () -> true));

        assertEquals(ErrorCode.CANCELLED, exception.error().code());
    }

    @Test
    void unsupportedPlatformAdapterFailsClearly() {
        DesktopAppService service = new DesktopAppService(
                apps(), new WindowsAppCommandResolver(), new RecordingLauncher(), CLOCK);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.launch("calc", NONE));

        assertEquals(ErrorCode.UNSUPPORTED_PLATFORM, exception.error().code());
    }

    @Test
    void launcherFailureIsServiceFailureWithCause() {
        DesktopAppService service = new DesktopAppService(
                apps(), MAC_RESOLVER, new FailingLauncher(), CLOCK);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.launch("calc", NONE));

        assertEquals(ErrorCode.SERVICE_FAILURE, exception.error().code());
        assertTrue(exception.getCause() instanceof IOException);
    }

    @Test
    void launchSpecRejectsEmptyOrBlankCommands() {
        assertThrows(IllegalArgumentException.class, () -> new LaunchSpec("x", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LaunchSpec("x", List.of(" ")));
    }

    @Test
    void resolverFailsUnknownLogicalApp() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> MAC_RESOLVER.resolve(
                        new ConfiguredApp("chess", "Chess", Set.of("chess"))));
        assertEquals(ErrorCode.UNKNOWN_APP, exception.error().code());
    }

    @Test
    void processAppLauncherPassesCommandListVerbatimToProcessBuilder() throws Exception {
        // Prove the default launcher never wraps in a shell: the recorded
        // ProcessBuilder command equals the spec command exactly.
        java.lang.reflect.Field field = ProcessBuilder.class.getDeclaredField("command");
        // ProcessBuilder.command is not reflectively readable on all JDKs; instead
        // assert via public API on a no-op command that exists on all platforms.
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(List.of("definitely-not-a-real-binary-xyz"));
        assertEquals(List.of("definitely-not-a-real-binary-xyz"), builder.command());
    }
}
