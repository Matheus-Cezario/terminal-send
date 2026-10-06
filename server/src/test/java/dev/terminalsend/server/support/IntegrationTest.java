package dev.terminalsend.server.support;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import dev.terminalsend.protocol.rest.AuthDtos.RegisterRequest;
import dev.terminalsend.protocol.rest.AuthDtos.TokenPair;
import dev.terminalsend.protocol.rest.AuthDtos.VerifyRequest;
import dev.terminalsend.server.TestcontainersConfig;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full application against Testcontainers Postgres and an in-process GreenMail SMTP server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.mail.host=localhost", "spring.mail.port=3025",
                // Every test registers users from 127.0.0.1; limits get their own focused tests.
                "terminal-send.rate-limits.auth-per-ip.max=100000",
                "terminal-send.rate-limits.emails-per-ip.max=100000"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IntegrationTest.ClockOverride.class})
public abstract class IntegrationTest {

    protected static final String PASSWORD = "correct horse battery";

    private static final Pattern CODE = Pattern.compile("(\\d{6})$");

    @RegisterExtension
    protected static final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected MutableClock clock;

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockOverride {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    protected ResultActions postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected <T> T read(ResultActions result, Class<T> type) throws Exception {
        return json.readValue(result.andReturn().getResponse().getContentAsString(), type);
    }

    /** Code from the most recent email sent to {@code email}; the subject ends with it. */
    protected String latestCodeFor(String email) {
        MimeMessage last = Arrays.stream(greenMail.getReceivedMessagesForDomain(email))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No email sent to " + email));
        try {
            Matcher matcher = CODE.matcher(last.getSubject());
            if (!matcher.find()) {
                throw new AssertionError("No code in subject: " + last.getSubject());
            }
            return matcher.group(1);
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        }
    }

    protected String register() throws Exception {
        String email = uniqueEmail();
        postJson("/api/v1/auth/register", new RegisterRequest(email, PASSWORD)).andExpect(status().isCreated());
        return email;
    }

    protected TokenPair registerAndVerify() throws Exception {
        String email = register();
        return read(postJson("/api/v1/auth/verify", new VerifyRequest(email, PASSWORD, latestCodeFor(email)))
                .andExpect(status().isOk()), TokenPair.class);
    }

    protected static String bearer(TokenPair tokens) {
        return "Bearer " + tokens.accessToken();
    }

    protected int emailsSentTo(String email) {
        return greenMail.getReceivedMessagesForDomain(email).length;
    }
}
