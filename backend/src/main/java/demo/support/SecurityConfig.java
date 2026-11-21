package demo.support;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import static jakarta.servlet.http.HttpServletResponse.*;

@Configuration
class SecurityConfig {
    @Bean
    PasswordEncoder passwords() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService users(JdbcClient db) {
        return username -> db.sql("SELECT password_hash, role FROM accounts WHERE username = ?")
                .param(username)
                .query((row, index) -> User.withUsername(username)
                        .password(row.getString("password_hash"))
                        .roles(row.getString("role")).build())
                .optional().orElseThrow(() -> new UsernameNotFoundException("Cuenta desconocida"));
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/style.css", "/api/csrf", "/swagger-ui/**",
                                "/swagger-ui.html", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/register", "/api/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/tickets").hasRole("USER")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, ex) -> res.setStatus(SC_UNAUTHORIZED)))
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.loginProcessingUrl("/api/login")
                        .successHandler((req, res, auth) -> res.setStatus(SC_NO_CONTENT))
                        .failureHandler((req, res, ex) -> res.setStatus(SC_UNAUTHORIZED)))
                .logout(logout -> logout.logoutUrl("/api/logout").deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(SC_NO_CONTENT)))
                .build();
    }

    @Bean
    ApplicationRunner demoAgent(JdbcClient db, PasswordEncoder encoder) {
        return args -> {
            String password = System.getenv("SUPPORT_AGENT_PASSWORD");
            if (password == null || db.sql("SELECT COUNT(*) FROM accounts WHERE username = 'agente'")
                    .query(Integer.class).single() > 0) {
                return;
            }
            if (!SupportApi.validPassword(password)) {
                throw new IllegalArgumentException("SUPPORT_AGENT_PASSWORD requiere 12 caracteres o más y hasta 72 bytes UTF-8");
            }
            try {
                db.sql("INSERT INTO accounts VALUES ('agente', 'Agente de soporte', ?, 'AGENT')")
                        .param(encoder.encode(password)).update();
            } catch (DuplicateKeyException ignored) {
                // Another instance already initialized the account.
            }
        };
    }
}
