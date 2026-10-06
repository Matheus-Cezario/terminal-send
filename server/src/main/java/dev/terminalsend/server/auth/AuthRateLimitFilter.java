package dev.terminalsend.server.auth;

import dev.terminalsend.protocol.rest.ErrorCode;
import dev.terminalsend.server.common.FixedWindowLimiter;
import dev.terminalsend.server.config.TerminalSendProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Set;

/**
 * Per-IP limits on the unauthenticated auth API: a general cap, and a stricter one on the endpoints that send
 * email (sign-up and code resend), so the server can't be used to spam inboxes. Behind a proxy, the client IP
 * comes from forwarded headers only when {@code server.forward-headers-strategy} is enabled (prod profile).
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    static final String AUTH_PREFIX = "/api/v1/auth/";
    static final Set<String> EMAIL_SENDING = Set.of("/api/v1/auth/register", "/api/v1/auth/verify/resend");

    private final FixedWindowLimiter authPerIp;
    private final FixedWindowLimiter emailsPerIp;

    public AuthRateLimitFilter(TerminalSendProperties props, Clock clock) {
        TerminalSendProperties.RateLimits limits = props.rateLimits();
        this.authPerIp = new FixedWindowLimiter(limits.authPerIp().max(), limits.authPerIp().window(), clock);
        this.emailsPerIp = new FixedWindowLimiter(limits.emailsPerIp().max(), limits.emailsPerIp().window(), clock);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(AUTH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        boolean allowed = authPerIp.tryAcquire(ip)
                && (!EMAIL_SENDING.contains(request.getRequestURI()) || emailsPerIp.tryAcquire(ip));
        if (!allowed) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"status\":429,\"title\":\"Too Many Requests\",\"code\":\""
                    + ErrorCode.RATE_LIMITED + "\",\"detail\":\"Too many requests from this address\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
