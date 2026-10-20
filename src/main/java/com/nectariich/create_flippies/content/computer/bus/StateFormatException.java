package com.nectariich.create_flippies.content.computer.bus;

import java.io.IOException;

/**
 * A saved state that cannot be loaded (spec 1.5): an unknown format version, a different machine
 * configuration (CPU model, clock, RAM size, boot ROM, attached disk), corrupt or truncated data.
 * The message says which, for the game's log; the game then cold-boots the PC.
 */
public final class StateFormatException extends IOException {

    public StateFormatException(String message) {
        super(message);
    }

    public StateFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
