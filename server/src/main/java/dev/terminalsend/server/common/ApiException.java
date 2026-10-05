package dev.terminalsend.server.common;

import dev.terminalsend.protocol.rest.ErrorCode;
import org.springframework.http.HttpStatus;

/** Domain failure mapped to an RFC 9457 problem response by {@link ApiExceptionHandler}. */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final ErrorCode code;

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }
}
