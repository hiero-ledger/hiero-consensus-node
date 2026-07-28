package org.hiero.consensus.exception;

import edu.umd.cs.findbugs.annotations.NonNull;

public class InvalidFreezeTimeException extends Exception {

    public InvalidFreezeTimeException(@NonNull final String message) {
        super(message);
    }
}
