package com.aleksandarparipovic.marel_app.config;

import com.aleksandarparipovic.marel_app.auth.JwtAuthenticationFilter;
import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.RolePermissions;
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
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Locale;

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
                        .requestMatchers("/api/customers/**").access(permission(AppPermission.CUSTOMER_VIEW))

                        /*
                         * WHAT FOLLOWS MIRRORS THE SCREENS.
                         *
                         * Each block below is one area of the application, named
                         * by the capability its screens need. The client hides a
                         * destination somebody may not open; these rules are what
                         * actually refuse the request when the address is typed
                         * anyway, and they are the half that matters.
                         *
                         * Ordering is significant: Spring takes the FIRST matching
                         * rule, so a narrower path or a single method is always
                         * listed above the rule that would otherwise swallow it.
                         */

                        // Analytics. Wider than the records behind them — the
                        // production coordinator plans against these figures.
                        .requestMatchers("/api/analytics/**").access(permission(AppPermission.ANALYTICS_VIEW))

                        /*
                         * THE LOOKUP LIST OF WORK-CODE CATEGORIES, and only that.
                         *
                         * Listed BEFORE the work-record block below, which owns
                         * everything else under this path. The operations
                         * catalogue is open to the whole company — an operation's
                         * category is one of its columns — so the screen that
                         * lists operations has to be able to fill the category
                         * filter. Without this line the filter answered 403 to
                         * commercial staff on a page that is deliberately theirs
                         * to read, which is the same mistake `search-all` was.
                         *
                         * Reading the LIST is not reading anybody's work. The
                         * cards, the hours and the logs stay behind
                         * WORK_RECORD_VIEW immediately below.
                         */
                        .requestMatchers(HttpMethod.GET,
                                "/api/work-code-categories/active-work-code-categories")
                            .authenticated()

                        /*
                         * THE WORK RECORDS — cards, months, shifts, hours, logs.
                         *
                         * `/api/me/**` is deliberately NOT in this list. A worker
                         * reading their own payslips goes through there, and that
                         * must keep working for somebody who may not open anybody
                         * else's card.
                         */
                        .requestMatchers(
                                "/api/daily-reports/**",
                                "/api/daily-report-categories/**",
                                "/api/monthly-reports/**",
                                "/api/monthly-report-categories/**",
                                // Scrap counted at month end, entered from the
                                // monthly records screen — the same area, so the
                                // same door.
                                "/api/monthly-scraps/**",
                                "/api/employee-records/**",
                                "/api/work-logs/**",
                                "/api/work-shifts/**",
                                // Absences are recorded on a shift, by the same
                                // people and through the same screen as the work is.
                                "/api/absences/**",
                                "/api/shifts/**",
                                "/api/reports/summary/**",
                                "/api/work-code-categories/**",
                                "/api/work-code-category-mappings/**")
                            .access(permission(AppPermission.WORK_RECORD_VIEW))

                        /*
                         * PAYROLL. Opening the screens only — which lines of a
                         * payroll each role may READ is still decided per field by
                         * payroll_field_access underneath, and nothing here widens
                         * that.
                         *
                         * The heavier payroll steps keep their own method-level
                         * checks (PAYROLL_LOCK, PAYROLL_MAINTENANCE_RECALCULATE,
                         * PAYROLL_ACCESS_CONFIGURE). Both have to pass, so this
                         * rule can only narrow, never widen.
                         */
                        .requestMatchers(
                                "/api/payroll-runs/**",
                                "/api/payroll-run-items/**",
                                "/api/payroll-run-item-categories/**",
                                "/api/payroll-run-item-updates/**",
                                "/api/payroll-adjustments/**",
                                "/api/payroll-adjustment-categories/**",
                                "/api/payroll-maintenance/**",
                                /*
                                 * The requests to reopen a payroll. PAYROLL_VIEW
                                 * is the door — a request is about a month, and
                                 * whoever cannot open the month has no business
                                 * with requests about it. Raising and answering
                                 * one keep their own method-level checks; both
                                 * have to pass, so this can only narrow.
                                 */
                                "/api/payroll-change-requests/**")
                            .access(permission(AppPermission.PAYROLL_VIEW))

                        /*
                         * THE WORKERS, and everything hanging off one — employment
                         * periods, categories, bonuses, compensation, payroll
                         * values. A worker's page is not the user directory.
                         */
                        .requestMatchers(
                                "/api/employees/**",
                                "/api/compensation-schemes/**")
                            .access(permission(AppPermission.EMPLOYEE_VIEW))

                        /*
                         * MANUFACTURING TIMES — writing only.
                         *
                         * Reading one back stays open to everybody signed in, and
                         * that is deliberate: the person who ASKED for a
                         * manufacturing time downloads its report from the requests
                         * screen. `/my` returns only the caller's own records and
                         * `/from-requests` is shared by design, so neither leaks
                         * anything a requester may not already see.
                         */
                        .requestMatchers(HttpMethod.GET,
                                "/api/product-manufacturing-times/**",
                                "/api/product-manufacturing-time-operations/**")
                            .authenticated()
                        .requestMatchers(
                                "/api/product-manufacturing-times/**",
                                "/api/product-manufacturing-time-operations/**")
                            .access(permission(AppPermission.MANUFACTURING_TIME_MANAGE))

                        // The monthly bonus rules and the application parameters.
                        .requestMatchers(
                                "/api/bonus-categories/**",
                                "/api/bonus-eligibility-rules/**",
                                "/api/bonus-min-hours-rules/**")
                            .access(permission(AppPermission.BONUS_RULE_MANAGE))
                        .requestMatchers("/api/app-settings/**")
                            .access(permission(AppPermission.APP_SETTING_MANAGE))

                        /*
                         * THE WORK CALENDAR. Everybody signed in may see which days
                         * the factory works; entering and changing a period is the
                         * restricted half.
                         */
                        .requestMatchers(HttpMethod.GET, "/api/work-calendar/**").authenticated()
                        .requestMatchers("/api/work-calendar/**")
                            .access(permission(AppPermission.WORK_CALENDAR_MANAGE))

                        /*
                         * PRODUCTION ORDERS — read and write are two permissions.
                         *
                         * The supervisor reads every order and alters none. The
                         * recipients sub-resource is listed FIRST and left to its
                         * own @PreAuthorize: managing who gets told about an order
                         * is a grant the owner made to supervisors and commercial
                         * staff separately, and folding it into the write rule
                         * would silently take it away from supervisors.
                         */
                        .requestMatchers("/api/production-orders/*/recipients/**").authenticated()
                        /*
                         * `search-all` is a READ done with POST — it carries the
                         * paging and filter payload, and it is the ONLY call the
                         * order list screen makes. Listing it by name is not a
                         * nicety: without this line the whole screen answered 403
                         * to the supervisor, who is precisely the person the
                         * VIEW/MANAGE split was introduced for.
                         *
                         * The same shape exists on products and operations below,
                         * and was handled there first. This one was missed, which
                         * is why it is spelled out rather than left to be inferred
                         * from the pattern.
                         */
                        .requestMatchers(HttpMethod.POST, "/api/production-orders/search-all")
                            .access(permission(AppPermission.PRODUCTION_ORDER_VIEW))
                        .requestMatchers(HttpMethod.GET, "/api/production-orders/**")
                            .access(permission(AppPermission.PRODUCTION_ORDER_VIEW))
                        .requestMatchers("/api/production-orders/**")
                            .access(permission(AppPermission.PRODUCTION_ORDER_MANAGE))

                        /*
                         * SAMPLE ORDERS — the same three rules, for the same
                         * reasons, one line lower. The recipients sub-resource
                         * first and left to its own @PreAuthorize; `search-all`
                         * named explicitly because it is a READ done with POST
                         * and would otherwise fall to the write rule and answer
                         * 403 to the supervisor, who is exactly who the
                         * VIEW/MANAGE split exists for.
                         */
                        .requestMatchers("/api/sample-orders/*/recipients/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/sample-orders/search-all")
                            .access(permission(AppPermission.SAMPLE_ORDER_VIEW))
                        .requestMatchers(HttpMethod.GET, "/api/sample-orders/**")
                            .access(permission(AppPermission.SAMPLE_ORDER_VIEW))
                        .requestMatchers("/api/sample-orders/**")
                            .access(permission(AppPermission.SAMPLE_ORDER_MANAGE))

                        /*
                         * THE CATALOGUE — products, operations and the norms on
                         * them.
                         *
                         * Reading is open to everybody signed in: most of the
                         * company has to be able to look a product or an operation
                         * up. Writing is the shop floor's and the administration's,
                         * because an operation's norm is what a person is paid
                         * against.
                         *
                         * `search-all` is listed by name because it is a READ done
                         * with POST — it carries the paging and filter payload for
                         * the list screens. Without these two lines the catalogue
                         * would have vanished for most of the company, which is the
                         * opposite of the rule.
                         */
                        .requestMatchers(HttpMethod.GET, "/api/products/**", "/api/operations/**")
                            .authenticated()
                        .requestMatchers(HttpMethod.POST,
                                "/api/products/search-all",
                                "/api/operations/search-all")
                            .authenticated()
                        .requestMatchers("/api/products/**").access(permission(AppPermission.PRODUCT_MANAGE))
                        .requestMatchers("/api/operations/**").access(permission(AppPermission.OPERATION_MANAGE))

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

    /**
     * A URL rule that asks {@link RolePermissions} the same question
     * {@code @PreAuthorize("@perm.has(...)")} asks.
     *
     * <p>Takes the enum rather than its name, so a renamed or deleted permission
     * is a compile error here instead of a rule that silently stops matching.
     *
     * <p>Fails closed on every path: no authentication, no authorities, or a role
     * this application has no mapping for all answer "no".
     */
    private static AuthorizationManager<RequestAuthorizationContext> permission(AppPermission required) {
        return (authentication, context) -> {
            Authentication auth = authentication.get();
            if (auth == null || !auth.isAuthenticated()) {
                return new AuthorizationDecision(false);
            }
            boolean granted = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith("ROLE_"))
                    .map(a -> a.substring("ROLE_".length()).toLowerCase(Locale.ROOT))
                    .anyMatch(role -> RolePermissions.roleHas(role, required));
            return new AuthorizationDecision(granted);
        };
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
