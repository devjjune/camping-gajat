package com.back.ovengers.global.security;

import com.back.ovengers.domain.user.entity.User;
import com.back.ovengers.domain.user.repository.UserRepository;
import com.back.ovengers.global.exception.CustomException;
import com.back.ovengers.global.exception.ErrorCode;
import com.back.ovengers.global.util.CookieUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final CookieUtil cookieUtil;
    private final HandlerExceptionResolver handlerExceptionResolver;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();

        if (path.startsWith("/api/auth")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String token = cookieUtil.getAccessToken(request).orElse(null);
            if (token == null) {
                filterChain.doFilter(request, response);
                return;
            }

            Claims claims = jwtProvider.parseClaims(token);
            Long userId = jwtProvider.getUserId(claims);
            String role = jwtProvider.getRole(claims);

            // DB 조회 제거 — 토큰에 Role이 있으므로 불필요
            // 단, soft delete 체크는 DB 조회가 필요하므로 유지
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

            if (user.getDeletedAt() != null) {
                throw new CustomException(ErrorCode.ALREADY_DELETED);
            }

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );

            auth.setDetails(
                    new WebAuthenticationDetailsSource()
                            .buildDetails(request)
            );

            SecurityContextHolder
                    .getContext()
                    .setAuthentication(auth);

            filterChain.doFilter(request, response);

        } catch (
                Exception e) {
            // 필터에서 발생한 모든 예외를 GlobalExceptionHandler로 위임
            // CustomException → handleCustomException()
            // 그 외 예외 → handleException()
            handlerExceptionResolver.resolveException(request, response, null, e);
        }
    }
}
