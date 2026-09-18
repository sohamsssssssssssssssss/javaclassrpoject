package com.jarvis.services.app;

import java.io.IOException;
import java.util.Objects;

/**
 * Default launcher: hands the explicit command list directly to
 * {@link ProcessBuilder}. There is no shell involvement anywhere on this
 * path; the first element is the executable, the rest are arguments.
 */
public final class ProcessAppLauncher implements AppLauncher {

    @Override
    public Process launch(LaunchSpec spec) throws IOException {
        Objects.requireNonNull(spec, "spec");
        return new ProcessBuilder(spec.command()).start();
    }
}
