package org.netpreserve.jwarc.net;

import java.io.IOException;

/**
 * Thrown when a request cannot be replayed unambiguously.
 * <p>
 * Carries an HTTP status code and human-readable reason so the server can respond with an explicit failure rather
 * than silently falling back to a different capture (e.g. a GET response).
 */
class ReplayException extends IOException {
    private final int statusCode;

    ReplayException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    int statusCode() {
        return statusCode;
    }
}
