package com.back.ovengers.global.security;

import com.back.ovengers.global.exception.CustomException;
import com.back.ovengers.global.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtProvider {

    private final SecretKey key;
    private final long accessExpiration;
    private final long refreshExpiration;

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-expiration}") long accessExpiration,
            @Value("${jwt.refresh-expiration}") long refreshExpiration
    ) {
        // secret 문자열 → UTF-8 바이트 배열 → HMAC-SHA SecretKey 객체로 변환
        // Keys.hmacShaKeyFor()는 키 길이에 따라 자동으로 HS256/384/512 결정
        // HS256 기준 최소 32바이트(256bit) 이상이어야 하며, 짧으면 WeakKeyException 발생
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpiration = accessExpiration;
        this.refreshExpiration = refreshExpiration;
    }

    private SecretKey getSigningKey() {
        return key;
    }

    public String createAccessToken(Long userId, String role) {
        return createToken(userId, role, accessExpiration);
    }

    public String createRefreshToken(Long userId, String role) {
        return createToken(userId, role, refreshExpiration);
    }

    private String createToken(Long userId, String role, long expirationMs) {
        Date now = new Date();

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 토큰을 파싱해서 Claims를 반환 (서명 검증 + 만료 검증 동시 수행)
     * - 만료 시 ACCESS_TOKEN_EXPIRED, 그 외 실패 시 INVALID_TOKEN 예외를 던짐
     * - 검증/추출 로직을 여기 한 곳으로 모아, 호출부에서 파싱을 반복하지 않도록 함
     */
    public Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

        } catch (ExpiredJwtException e) {
            throw new CustomException(ErrorCode.ACCESS_TOKEN_EXPIRED);

        } catch (JwtException | IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
    }

    public Long getUserId(Claims claims) {
        return Long.parseLong(claims.getSubject());
    }

    public String getRole(Claims claims) {
        return claims.get("role", String.class);
    }

    public void validateRefreshToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);

        } catch (ExpiredJwtException e) {
            throw new CustomException(ErrorCode.REFRESH_TOKEN_EXPIRED);

        } catch (JwtException | IllegalArgumentException e) {
            throw new CustomException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }
}
