package com.example.drive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class Security {
    @Bean
    SecurityFilterChain chain(HttpSecurity h, @Value("${drive.local}") boolean local, @Value("${drive.local-password}") String password, @Value("${drive.issuer}") String issuer, @Value("${drive.audience}") String audience) throws Exception {
        h.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/", "/index.html", "/app.css", "/app.js", "/actuator/health/**", "/api/v1/public/**").permitAll().anyRequest().authenticated());
        if (local) {
            Rules.require(password.length() >= 12, 500, "LOCAL_PASSWORD must have at least 12 characters");
            h.userDetailsService(new InMemoryUserDetailsManager(User.withUsername("alice").password("{noop}" + password).roles("USER").build(), User.withUsername("bob").password("{noop}" + password).roles("USER").build(), User.withUsername("charlie").password("{noop}" + password).roles("USER").build(), User.withUsername("admin").password("{noop}" + password).roles("ADMIN").build())).httpBasic(b -> {
            });
        } else {
            var decoder = JwtDecoders.<NimbusJwtDecoder>fromIssuerLocation(issuer);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), token -> token.getAudience().contains(audience) && token.getClaimAsString("tenant") != null ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
            h.oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder)));
        }
        return h.build();
    }
}
