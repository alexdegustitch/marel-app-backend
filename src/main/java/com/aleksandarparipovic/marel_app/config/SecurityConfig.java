package com.aleksandarparipovic.marel_app.config;

import com.aleksandarparipovic.marel_app.auth.JwtAuthenticationFilter;
import com.aleksandarparipovic.marel_app.auth.ratelimit.AuthRateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@RequiredArgsConstructor
@EnableWebSecurity
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthRateLimitFilter authRateLimitFilter;

    @Value("${app.security.cors.allowed-origins:http://localhost:5123,http://localhost:5173,http://localhost:3000}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})   // 👈 enable CORS support
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        /*
                         * ACTUATOR IS NOT PUBLIC.
                         *
                         * /actuator/health stays open because that is what a
                         * monitor or a restart policy polls, and with
                         * show-details=never it answers nothing but UP or DOWN.
                         *
                         * Everything else — the endpoint index, info and metrics —
                         * is operational detail about the running server and is
                         * restricted to "developer", the internal engineering
                         * account. Deliberately NOT admin: admin is a business role
                         * held by factory staff, and running the machine is not
                         * part of running the company. The index path is listed
                         * separately so it never depends on whether "/**" also
                         * matches the bare prefix.
                         */
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator", "/actuator/**").hasRole("developer")
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/departments/**").permitAll()
                        .requestMatchers("/ws/**").permitAll()          // WebSocket endpoint
                        .requestMatchers("/api/admin/**").hasRole("admin")
                        .requestMatchers("/api/users/me").authenticated()
                        .requestMatchers("/api/users/active-users").permitAll()
                        /*
                         * THE DIRECTORY. Everyone signed in may look up a colleague
                         * — name, role, e-mail, telephone. That is what an internal
                         * directory is for, and the list carries nothing else: no
                         * password material, no payroll, no settings.
                         *
                         * Listed as GET on the collection ONLY. Everything else
                         * under /api/users — creating, editing, archiving, reading
                         * one account by name — falls through to the admin rule
                         * below, so relaxing the directory relaxes nothing else.
                         */
                        .requestMatchers(HttpMethod.GET, "/api/users").authenticated()
                        /*
                         * Linking an account to a worker. Authenticated here and
                         * decided by @PreAuthorize on the method, which asks for
                         * USER_EMPLOYEE_LINK — held by admins and supervisors.
                         * Kept OUT of the admin rule rather than widening that rule
                         * to supervisors, which would have handed them roles and
                         * passwords along with it.
                         */
                        .requestMatchers(HttpMethod.PUT, "/api/users/*/employee").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/users/*/employee").authenticated()
                        .requestMatchers("/api/users/**").hasRole("admin")
                        .requestMatchers("/api/roles/**").hasRole("admin")
                        /*
                         * CUSTOMERS. Everyone but the supervisor, whose work is
                         * the shop floor rather than the commercial relationship.
                         *
                         * Listed here rather than left to `anyRequest()` because
                         * the rail merely HIDES the page from a supervisor, and a
                         * hidden link is not a closed door — the address can be
                         * typed. Nothing else in the application needs a
                         * supervisor to read this: an order carries its customer's
                         * name in its own response.
                         */
                        .requestMatchers("/api/customers/**")
                            .hasAnyRole("admin", "developer", "commercial")
                        .anyRequest().authenticated()
                )
                /*
                 * 401 FOR "WHO ARE YOU", 403 FOR "NOT YOU".
                 *
                 * Spring's default entry point answers 403 to an unauthenticated
                 * request, so both cases arrived at the browser as the same
                 * status and the client could not tell them apart. It guessed,
                 * and guessed the expensive way: lacking ONE permission logged
                 * the user out of the whole application, because a refusal was
                 * indistinguishable from an expired session.
                 *
                 * With these two separated, the client's rule becomes the plain
                 * one — refresh the session on 401, show a refusal on 403.
                 */
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                        .accessDeniedHandler((request, response, deniedException) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class
                )
                // Ahead of everything else on the credential endpoints: a blocked
                // caller must be refused before any password is hashed.
                .addFilterBefore(authRateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    // 🔥 This is where we configure which origins are allowed
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
