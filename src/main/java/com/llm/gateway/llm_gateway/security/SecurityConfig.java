package com.llm.gateway.llm_gateway.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * Authenticates every request as a Keycloak-issued OAuth2 JWT Bearer token (resource-server mode) —
 * Spring Boot auto-configures the {@code ReactiveJwtDecoder} from {@code
 * spring.security.oauth2.resourceserver.jwt.issuer-uri}. Keycloak puts realm roles under the {@code
 * realm_access.roles} claim rather than the standard {@code scope}/{@code scp} claim that {@link
 * JwtAuthenticationConverter} reads by default, so {@link #keycloakAuthoritiesConverter()} maps
 * that claim onto {@code ROLE_*} authorities instead.
 */
@Slf4j
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

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
          "SECURITY | OAuth2 authentication is DISABLED — set GATEWAY_AUTH_ENABLED=true in production");
      return http.authorizeExchange(a -> a.anyExchange().permitAll()).build();
    }

    log.info("SECURITY | OAuth2 (Keycloak) JWT authentication is ENABLED");

    return http.oauth2ResourceServer(
            oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(reactiveKeycloakJwtConverter())))
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
                    // Everything else requires a valid Keycloak-issued Bearer token
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

  private Converter<Jwt, Mono<AbstractAuthenticationToken>> reactiveKeycloakJwtConverter() {
    JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
    delegate.setJwtGrantedAuthoritiesConverter(this::keycloakAuthoritiesConverter);
    return new ReactiveJwtAuthenticationConverterAdapter(delegate);
  }

  /**
   * Reads Keycloak's {@code realm_access.roles} claim (e.g. {@code ["gateway-user"]}) and maps each
   * entry to a {@code ROLE_*} {@link GrantedAuthority}, e.g. {@code ROLE_GATEWAY-USER}.
   */
  @SuppressWarnings("unchecked")
  private Collection<GrantedAuthority> keycloakAuthoritiesConverter(Jwt jwt) {
    Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
    if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
      return List.of();
    }
    return roles.stream()
        .map(String::valueOf)
        .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
        .toList();
  }
}
