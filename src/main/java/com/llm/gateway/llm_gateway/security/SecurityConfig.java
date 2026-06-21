package com.llm.gateway.llm_gateway.security;

import java.util.Arrays;
import java.util.List;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

@Slf4j
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final ApiKeyService apiKeyService;

  @Value("${gateway.auth.enabled:true}")
  private boolean authEnabled;

  @Value("${gateway.cors.allowed-origins:}")
  private List<String> allowedOrigins;

  @Bean
  public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
    http.csrf(c -> c.disable())
        .httpBasic(b -> b.disable())
        .formLogin(f -> f.disable())
        .logout(l -> l.disable())
        .cors(c -> c.configurationSource(corsConfigurationSource()));

    if (!authEnabled) {
      log.warn(
          "SECURITY | API key authentication is DISABLED — set GATEWAY_AUTH_ENABLED=true in production");
      return http.authorizeExchange(a -> a.anyExchange().permitAll()).build();
    }

    log.info("SECURITY | API key authentication is ENABLED");

    AuthenticationWebFilter apiKeyFilter = buildApiKeyFilter();

    return http.addFilterAt(apiKeyFilter, SecurityWebFiltersOrder.AUTHENTICATION)
        .authorizeExchange(
            a ->
                a
                    // Infrastructure — always public
                    .pathMatchers("/actuator/**")
                    .permitAll()
                    // Public gateway info endpoints (base-path /llm/v1 is prepended by webflux)
                    .pathMatchers("/llm/v1/health", "/llm/v1/providers", "/llm/v1/models")
                    .permitAll()
                    // Swagger UI / OpenAPI docs are public
                    .pathMatchers(
                        "/llm/v1/swagger-ui.html", "/llm/v1/swagger-ui/**", "/llm/v1/api-docs/**")
                    .permitAll()
                    // Everything else requires a valid API key
                    .anyExchange()
                    .authenticated())
        .build();
  }

  /**
   * Wires {@code gateway.cors.allowed-origins} (already present in application.yaml) into an actual
   * CORS policy — without this bean the property was inert and browser clients on disallowed
   * origins would be blocked by the browser with no server-side CORS headers at all.
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    List<String> origins = allowedOrigins.stream().filter(o -> !o.isBlank()).toList();

    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(origins);
    config.setAllowedMethods(Arrays.asList("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setExposedHeaders(List.of("X-Request-ID"));
    config.setAllowCredentials(false);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  private AuthenticationWebFilter buildApiKeyFilter() {
    ReactiveAuthenticationManager authManager =
        token -> {
          String rawKey = (String) token.getCredentials();
          return apiKeyService
              .isValid(rawKey)
              .flatMap(
                  valid -> {
                    if (!valid) {
                      log.warn("SECURITY | rejected invalid API key");
                      return Mono.error(new BadCredentialsException("Invalid or expired API key"));
                    }
                    // Fire-and-forget last_used stamp
                    apiKeyService.touchLastUsed(rawKey).subscribe();
                    return Mono.just(
                        (org.springframework.security.core.Authentication)
                            new UsernamePasswordAuthenticationToken(
                                "api-client",
                                rawKey,
                                List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"))));
                  });
        };

    AuthenticationWebFilter filter = new AuthenticationWebFilter(authManager);

    filter.setServerAuthenticationConverter(
        exchange -> {
          String key = exchange.getRequest().getHeaders().getFirst("X-API-Key");
          if (key == null || key.isBlank()) return Mono.empty();
          return Mono.just(new UsernamePasswordAuthenticationToken(null, key.trim()));
        });

    filter.setAuthenticationFailureHandler(
        new ServerAuthenticationEntryPointFailureHandler(
            new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)));

    return filter;
  }
}
