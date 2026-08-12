package com.back.ovengers.global.websocket;

import com.back.ovengers.domain.user.entity.User;
import com.back.ovengers.domain.user.repository.UserRepository;
import com.back.ovengers.global.exception.CustomException;
import com.back.ovengers.global.exception.ErrorCode;
import com.back.ovengers.global.security.JwtProvider;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtChannelInterceptor implements ChannelInterceptor {

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    @Override
    public Message<?> preSend(
            Message<?> message,
            MessageChannel channel
    ) {

        StompHeaderAccessor accessor =
                StompHeaderAccessor.wrap(message);

        // CONNECT 할 때만 JWT 검증
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {

            String authHeader =
                    accessor.getFirstNativeHeader("Authorization");

            if (authHeader == null ||
                    !authHeader.startsWith("Bearer ")) {
                throw new CustomException(ErrorCode.INVALID_TOKEN);
            }

            String token = authHeader.substring(7);

            // 토큰 검증 + userId 추출
            Claims claims = jwtProvider.parseClaims(token);
            Long userId = jwtProvider.getUserId(claims);

            User user = userRepository.findById(userId)
                    .orElseThrow(() ->
                            new CustomException(ErrorCode.USER_NOT_FOUND));

            Authentication authentication =
                    new UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            List.of()
                    );

            // WebSocket 세션에 저장
            accessor.setUser(authentication);
        }

        return message;
    }
}
