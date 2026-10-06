package dev.terminalsend.server.auth;

import dev.terminalsend.server.config.TerminalSendProperties;
import dev.terminalsend.server.config.TerminalSendProperties.Limit;
import dev.terminalsend.server.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimitFilterTest {

    private final MutableClock clock = new MutableClock();
    private final AuthRateLimitFilter filter = new AuthRateLimitFilter(new TerminalSendProperties(null, null, null,
            null, new TerminalSendProperties.RateLimits(new Limit(5, Duration.ofMinutes(10)),
            new Limit(2, Duration.ofHours(1)), new Limit(50, Duration.ofHours(1)))), clock);

    @Test
    void emailSendingEndpointsHaveTheirOwnStricterLimit() throws Exception {
        assertThat(call("/api/v1/auth/register", "1.1.1.1")).isEqualTo(200);
        assertThat(call("/api/v1/auth/verify/resend", "1.1.1.1")).isEqualTo(200);
        assertThat(call("/api/v1/auth/register", "1.1.1.1")).isEqualTo(429);

        assertThat(call("/api/v1/auth/login", "1.1.1.1")).as("other auth calls still allowed").isEqualTo(200);
        assertThat(call("/api/v1/auth/register", "2.2.2.2")).as("other IPs unaffected").isEqualTo(200);
    }

    @Test
    void generalAuthLimitAppliesPerIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(call("/api/v1/auth/login", "1.1.1.1")).isEqualTo(200);
        }
        MockHttpServletResponse blocked = response("/api/v1/auth/login", "1.1.1.1");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");

        clock.advance(Duration.ofMinutes(10));
        assertThat(call("/api/v1/auth/login", "1.1.1.1")).isEqualTo(200);
    }

    @Test
    void ignoresNonAuthPaths() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(call("/api/v1/connections", "1.1.1.1")).isEqualTo(200);
        }
    }

    private int call(String path, String ip) throws Exception {
        return response(path, ip).getStatus();
    }

    private MockHttpServletResponse response(String path, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
