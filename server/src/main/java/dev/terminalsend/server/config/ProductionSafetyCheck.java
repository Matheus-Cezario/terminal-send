package dev.terminalsend.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Refuses to start in production with the development JWT secret from application.yml. */
@Configuration
@Profile("prod")
class ProductionSafetyCheck {

    ProductionSafetyCheck(TerminalSendProperties props) {
        if (props.jwt().secret().startsWith(TerminalSendProperties.DEV_SECRET_PREFIX)) {
            throw new IllegalStateException("TS_JWT_SECRET must be set to a private value in production");
        }
    }
}
