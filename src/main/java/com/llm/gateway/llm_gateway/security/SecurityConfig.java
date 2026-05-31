package com.llm.gateway.llm_gateway.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ApiKeyService apiKeyService;

    @Value("${gateway.auth.enabled:false}")
    private boolean authEnabled;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        // Disable all mechanisms we don't use
        http.csrf(c -> c.disable())
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable());

        if (!authEnabled) {
            log.warn("SECURITY | API key authentication is DISABLED (gateway.auth.enabled=false)");
            return http.authorizeExchange(a -> a.anyExchange().permitAll()).build();
        }

        log.info("SECURITY | API key authentication is ENABLED");

        AuthenticationWebFilter apiKeyFilter = buildApiKeyFilter();

        return http
                .addFilterAt(apiKeyFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(a -> a
                        .pathMatchers("/actuator/**").permitAll()
                        .anyExchange().authenticated()
                )
                .build();
    }

    private AuthenticationWebFilter buildApiKeyFilter() {
        ReactiveAuthenticationManager authManager = token -> {
            String rawKey = (String) token.getCredentials();
            return apiKeyService.isValid(rawKey)
                    .flatMap(valid -> {
                        if (!valid) {
                            log.warn("SECURITY | rejected invalid API key");
                            return Mono.error(new BadCredentialsException("Invalid or expired API key"));
                        }
                        // Fire-and-forget last_used stamp
                        apiKeyService.touchLastUsed(rawKey).subscribe();
                        return Mono.just((org.springframework.security.core.Authentication)
                                new UsernamePasswordAuthenticationToken(
                                        "api-client", rawKey,
                                        List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"))));
                    });
        };

        AuthenticationWebFilter filter = new AuthenticationWebFilter(authManager);

        // Extract X-API-Key header and wrap it as an unauthenticated token
        filter.setServerAuthenticationConverter(exchange -> {
            String key = exchange.getRequest().getHeaders().getFirst("X-API-Key");
            if (key == null || key.isBlank()) return Mono.empty();
            return Mono.just(new UsernamePasswordAuthenticationToken(null, key.trim()));
        });

        // On auth failure → 401 JSON (not redirect)
        filter.setAuthenticationFailureHandler(
                new ServerAuthenticationEntryPointFailureHandler(
                        new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)));

        return filter;
    }
}
