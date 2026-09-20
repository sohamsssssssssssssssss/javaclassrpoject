package com.jade.services.app;

import java.io.IOException;

/**
 * Seam for starting a process from an explicit command list so tests can
 * assert the command without launching real applications.
 */
public interface AppLauncher {
    Process launch(LaunchSpec spec) throws IOException;
}
