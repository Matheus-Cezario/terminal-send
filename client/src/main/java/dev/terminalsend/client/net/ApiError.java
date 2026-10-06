package dev.terminalsend.client.net;

import dev.terminalsend.protocol.rest.ErrorCode;

/** A non-2xx answer from the server, or a failure to reach it ({@code status == 0}). */
public class ApiError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final ErrorCode code;

    public ApiError(int status, ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }

    public boolean is(ErrorCode expected) {
        return code == expected;
    }
}
