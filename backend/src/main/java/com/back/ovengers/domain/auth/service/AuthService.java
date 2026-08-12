package com.back.ovengers.domain.auth.service;

import com.back.ovengers.domain.auth.dto.*;
import com.back.ovengers.domain.camping.entity.Camping;
import com.back.ovengers.domain.camping.entity.CampingStatus;
import com.back.ovengers.domain.camping.repository.CampingRepository;
import com.back.ovengers.domain.user.entity.Role;
import com.back.ovengers.domain.user.entity.Status;
import com.back.ovengers.domain.user.entity.User;
import com.back.ovengers.domain.user.repository.UserRepository;
import com.back.ovengers.global.exception.CustomException;
import com.back.ovengers.global.exception.ErrorCode;
import com.back.ovengers.global.security.JwtProvider;
import com.back.ovengers.global.util.CookieUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;              // JWT 토큰 생성 담당
    private final CookieUtil cookieUtil;
    private final RefreshTokenService refreshTokenService;
    private final CampingRepository campingRepository;

    @Transactional
    public SignUpResponse signUp(SignUpRequest request) {

        // 이메일 중복 체크 (탈퇴(삭제)되지 않은 유저 중에서만 검사)
        //    -> deletedAt이 null인 경우만 체크하므로, 탈퇴한 회원의 이메일은 재가입 가능
        if (userRepository.existsByEmailAndDeletedAtIsNull(request.email())) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }

        // 닉네임 중복 체크 (동일하게 삭제되지 않은 유저 기준)
        if (userRepository.existsByNicknameAndDeletedAtIsNull(request.nickname())) {
            throw new CustomException(ErrorCode.DUPLICATE_NICKNAME);
        }

        //  User 엔티티 생성
        //    - 비밀번호는 평문 저장 금지 -> passwordEncoder로 암호화 후 저장
        //    - role은 기본값 USER, status는 기본값 ACTIVE로 설정
        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .name(request.name())
                .nickname(request.nickname())
                .phone(request.phone())
                .role(request.role() != null ? request.role() : Role.USER)
                .status(Status.ACTIVE)
                .build();

        User savedUser = userRepository.save(user);


        // 응답 DTO로 변환하여 반환 (비밀번호 등 민감 정보는 제외)
        return SignUpResponse.builder()
                .id(savedUser.getId())
                .email(savedUser.getEmail())
                .nickname(savedUser.getNickname())
                .role(savedUser.getRole().name())
                .build();
    }


    @Transactional
    public SignUpResponse hostSignUp(HostSignUpRequest request) {

        if (userRepository.existsByEmailAndDeletedAtIsNull(request.email())) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }

        if (userRepository.existsByNicknameAndDeletedAtIsNull(request.nickname())) {
            throw new CustomException(ErrorCode.DUPLICATE_NICKNAME);
        }

        // User 저장 (Role.HOST)
        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .name(request.name())
                .nickname(request.nickname())
                .phone(request.phone())
                .role(Role.HOST)
                .status(Status.ACTIVE)
                .build();

        User savedUser = userRepository.save(user);

        String[] addressParts = request.address().trim().split("\\s+");

        // Camping 저장 — status는 PENDING (관리자 승인 후 APPROVED)
        Camping camping = Camping.builder()
                .host(savedUser)
                .businessNum(request.businessNum())
                .name(request.campingName())
                .address(request.address())
                .region(addressParts.length > 0 ? addressParts[0] : "")  // 시/도
                .city(addressParts.length > 1 ? addressParts[1] : "")    // 시/군/구
                .status(CampingStatus.PENDING)  // 관리자 승인 대기
                .build();

        campingRepository.save(camping);

        return SignUpResponse.builder()
                .id(savedUser.getId())
                .email(savedUser.getEmail())
                .nickname(savedUser.getNickname())
                .role(savedUser.getRole().name())
                .build();
    }

    @Transactional
    public LoginResponse login(LoginRequest request, HttpServletResponse response) {

        User user = userRepository.findByEmailAndDeletedAtIsNull(request.email())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_LOGIN_CREDENTIALS));

        if (user.getStatus() == Status.BANNED) {
            throw new CustomException(ErrorCode.BANNED_USER);
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_LOGIN_CREDENTIALS);
        }

        String role = user.getRole().name();

        //Role을 토큰에 포함
        String accessToken = jwtProvider.createAccessToken(user.getId(), role);
        String refreshToken = jwtProvider.createRefreshToken(user.getId(), role);

        cookieUtil.addAccessTokenCookie(response, accessToken);
        cookieUtil.addRefreshTokenCookie(response, refreshToken);

        refreshTokenService.save(user.getId(), refreshToken);

        return new LoginResponse(accessToken, user.getRole().name()); // 토큰은 쿠키에 있으므로 응답 바디에서 제거
    }

    public void reissue(HttpServletRequest request, HttpServletResponse response) {

        // 쿠키에서 Refresh Token 추출
        String refreshToken = cookieUtil.getRefreshToken(request)
                .orElseThrow(() -> new CustomException(ErrorCode.REFRESH_TOKEN_MISSING));

        // 서명 검증 + 만료 시간 체크
        jwtProvider.validateRefreshToken(refreshToken);

        Claims claims = jwtProvider.parseClaims(refreshToken);
        Long userId = jwtProvider.getUserId(claims);
        String role = jwtProvider.getRole(claims);

        // DB에서 토큰 일치 여부 + 만료 시간 확인
        refreshTokenService.validate(userId, refreshToken);

        // 새 Access Token 발급 후 쿠키에 저장
        String newAccessToken = jwtProvider.createAccessToken(userId, role);
        cookieUtil.addAccessTokenCookie(response, newAccessToken);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {

        // Refresh Token에서 userId 추출하여 DB 삭제
        cookieUtil.getRefreshToken(request).ifPresent(refreshToken -> {
            try {
                Claims claims = jwtProvider.parseClaims(refreshToken);
                Long userId = jwtProvider.getUserId(claims);
                refreshTokenService.delete(userId);
            } catch (Exception e) {
                // 토큰이 이미 만료되었거나 유효하지 않아도 쿠키는 삭제
            }
        });

        // 쿠키 삭제
        cookieUtil.deleteAccessTokenCookie(response);
        cookieUtil.deleteRefreshTokenCookie(response);
    }

    @Transactional(readOnly = true)
    public void checkEmail(String email) {
        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }
    }

    @Transactional(readOnly = true)
    public void checkNickname(String nickname) {
        if (userRepository.existsByNicknameAndDeletedAtIsNull(nickname)) {
            throw new CustomException(ErrorCode.DUPLICATE_NICKNAME);
        }
    }


}
