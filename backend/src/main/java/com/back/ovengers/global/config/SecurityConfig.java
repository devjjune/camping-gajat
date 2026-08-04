package com.back.ovengers.global.config;


import com.back.ovengers.global.exception.ErrorCode;
import com.back.ovengers.global.response.ApiResponse;
import com.back.ovengers.global.security.JwtFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final ObjectMapper objectMapper;

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                );

        configureExceptionHandling(http);
        configureAuthorization(http);

        http.addFilterBefore(
                jwtFilter,
                UsernamePasswordAuthenticationFilter.class
        );

        return http.build();
    }

    private void configureExceptionHandling(HttpSecurity http) throws Exception {
        http.exceptionHandling(exception -> exception
                // 인증 실패(미로그인, 토큰 없음 등) → 401 Unauthorized
                .authenticationEntryPoint((request, response, authException) ->
                        writeErrorResponse(response, ErrorCode.ACCESS_TOKEN_MISSING)
                )
                // 인가 실패(권한 부족) → 403 Forbidden
                .accessDeniedHandler((request, response, accessDeniedException) ->
                        writeErrorResponse(response, ErrorCode.FORBIDDEN)
                )
        );
    }

    private void configureAuthorization(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                // ===== 로그인 필요 =====
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/images/upload")
                .authenticated()
                .requestMatchers(
                        "/api/notifications/**", // 알람 관련 API는 로그인 사용자만 접근 가능
                        "/api/users/me/**"  // 내 정보 조회/수정/탈퇴 및 하위 API는 로그인 사용자만 접근 가능
                ).authenticated()

                // ===== 역할 권한 필요 =====
                .requestMatchers(
                        "/api/reservations/**",
                        "/api/payments/**"
                ).hasRole("USER")
                .requestMatchers(
                        "/api/admin/**"
                ).hasRole("ADMIN")
                .requestMatchers(
                        "/api/host/**",
                        "/api/timedeals/host/**"
                ).hasRole("HOST")

                // ===== 비회원 공개 (인증 불필요) =====
                .requestMatchers(
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/actuator/health",
                        "/actuator/prometheus"

                ).permitAll()
                .requestMatchers(
                        "/api/auth/signup",
                        "/api/auth/signup/host",
                        "/api/auth/login",
                        "/api/auth/logout",
                        "/api/auth/refresh",
                        "/api/auth/check/email",
                        "/api/auth/check/nickname"
                ).permitAll()
                .requestMatchers(
                        "/api/campings/search" // 캠핑장 검색
                ).permitAll()
                .requestMatchers(
                        HttpMethod.GET,
                        "/api/campings/*/reviews"  // 리뷰 목록 조회 비인증 허용
                ).permitAll()
                .requestMatchers(
                        HttpMethod.GET,
                        "/api/timedeals/**" // 타임딜
                ).permitAll()
                .requestMatchers(
                        "/api/users/**",
                        "/api/campings/**", // 리뷰 작성, 삭제 권한 확인 필요
                        "/ws/**",
                        "/chat-test.html"
                ).permitAll()

                .anyRequest().authenticated()
        );
    }

    private void writeErrorResponse(
            HttpServletResponse response,
            ErrorCode errorCode
    ) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(
                response.getWriter(),
                new ApiResponse<>(errorCode.name(), errorCode.getMessage())
        );
    }

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins); // 허용할 출처
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")); // 허용할 HTTP 메서드
        configuration.setAllowedHeaders(List.of("*")); // 모든 요청 헤더 허용
        configuration.setAllowCredentials(true);  // 쿠키/인증 정보 포함 요청 허용

        // 위 CORS 설정을 적용할 경로
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
