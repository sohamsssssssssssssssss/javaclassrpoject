package com.jade.api;

import java.nio.file.Path;

import com.jade.api.CancellationToken;

/**
 * Seam for opening one concrete file with the host platform's default
 * handler. Kept in {@code com.jade.api} so the gateway can depend on it
 * while tests inject fakes; production wires the platform implementation.
 *
 * <p>Implementations must confine themselves to the exact path they are
 * given, must never interpret file content, and report failure as a
 * structured {@link ServiceException} instead of throwing raw exceptions.</p>
 */
@FunctionalInterface
public interface FileOpener {

    /**
     * Opens the file at {@code path} (for example the platform "open" or
     * "xdg-open" action on a concrete file). Never deletes, moves or rewrites
     * anything.
     */
    void open(Path path, CancellationToken cancellation) throws ServiceException;
}
