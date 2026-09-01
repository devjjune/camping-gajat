package com.back.ovengers.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    MISSING_REQUIRED_FIELD(
            HttpStatus.BAD_REQUEST,
            "필수값이 누락되었습니다."
    ),

    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "서버 오류가 발생했습니다."
    ),

    // 회원
    EMPTY_NICKNAME(
            HttpStatus.BAD_REQUEST,
            "닉네임은 공백일 수 없습니다."
    ),
    EMPTY_PHONE(
            HttpStatus.BAD_REQUEST,
            "전화번호는 공백일 수 없습니다."
    ),
    EMPTY_IMAGE_URL(
            HttpStatus.BAD_REQUEST,
            "프로필 이미지는 공백일 수 없습니다."
    ),

    INVALID_EMAIL_FORMAT(
            HttpStatus.BAD_REQUEST,
            "이메일 형식이 올바르지 않습니다."
    ),

    INVALID_PASSWORD_FORMAT(
            HttpStatus.BAD_REQUEST,
            "비밀번호는 8자 이상이어야 합니다."
    ),

    DUPLICATE_EMAIL(
            HttpStatus.CONFLICT,
            "이미 사용 중인 이메일입니다."
    ),

    DUPLICATE_NICKNAME(
            HttpStatus.CONFLICT,
            "이미 사용 중인 닉네임입니다."
    ),

    INVALID_LOGIN_CREDENTIALS(
            HttpStatus.UNAUTHORIZED,
            "이메일 또는 비밀번호가 올바르지 않습니다."
    ),

    SAME_PASSWORD(
            HttpStatus.BAD_REQUEST,
            "현재 비밀번호와 동일한 비밀번호로 변경할 수 없습니다."
    ),

    ALREADY_DELETED(
            HttpStatus.BAD_REQUEST,
            "탈퇴한 회원입니다."
    ),

    BANNED_USER(
            HttpStatus.FORBIDDEN,
            "이용이 정지된 계정입니다."
    ),

    // 인증
    INVALID_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "유효하지 않은 토큰입니다."
    ),

    LOGIN_REQUIRED(
            HttpStatus.UNAUTHORIZED,
            "로그인이 필요합니다."
    ),

    ACCESS_TOKEN_MISSING(
            HttpStatus.UNAUTHORIZED,
            "Access Token이 없습니다."
    ),

    ACCESS_TOKEN_EXPIRED(
            HttpStatus.UNAUTHORIZED,
            "Access Token이 만료되었습니다."
    ),

    REFRESH_TOKEN_MISSING(
            HttpStatus.UNAUTHORIZED,
            "Refresh Token이 없습니다."
    ),

    REFRESH_TOKEN_EXPIRED(
            HttpStatus.UNAUTHORIZED,
            "Refresh Token이 만료되었습니다."
    ),

    REFRESH_TOKEN_INVALID(
            HttpStatus.UNAUTHORIZED,
            "Refresh Token이 유효하지 않습니다."
    ),

    // 호스트
    HOST_REQUIRED(
            HttpStatus.FORBIDDEN,
            "호스트 권한이 필요합니다."
    ),

    NOT_CAMPING_OWNER(
            HttpStatus.FORBIDDEN,
            "해당 캠핑장에 대한 권한이 없습니다."
    ),

    CAMPING_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "존재하지 않는 캠핑장입니다."
    ),

    CONFIRMED_RESERVATION_EXISTS(
        HttpStatus.CONFLICT,
        "확정된 예약이 있어 삭제할 수 없습니다."
    ),

    CAMPING_IMAGE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "캠핑장 이미지를 찾을 수 없습니다."
    ),

    INVALID_TOUR_NUMBER(
            HttpStatus.BAD_REQUEST,
            "관광사업자 등록번호가 일치하지 않습니다."
    ),

    CAMPING_ALREADY_CLAIMED(
            HttpStatus.CONFLICT,
            "이미 다른 호스트가 등록한 캠핑장입니다."
    ),

    // 예약
    RESERVATION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "예약을 찾을 수 없습니다."
    ),
    RESERVATION_CONFLICT(
            HttpStatus.CONFLICT,
            "이미 예약된 날짜입니다."
    ),
    INVALID_RESERVATION_DATE(
            HttpStatus.BAD_REQUEST,
            "예약 날짜가 올바르지 않습니다."
    ),
    RESERVATION_CANNOT_BE_CANCELLED(
            HttpStatus.CONFLICT,
            "취소할 수 없는 예약 상태입니다."),

    // 구역
    SITE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "구역을 찾을 수 없습니다."
    ),

    USER_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "존재하지 않는 회원입니다."
    ),

    CAMPING_NOT_AVAILABLE(
            HttpStatus.BAD_REQUEST,
            "예약 불가능한 캠핑장입니다."
    ),

    GUEST_COUNT_EXCEEDED(
            HttpStatus.BAD_REQUEST,
            "예약 인원이 최대 인원을 초과했습니다."
    ),

    SITE_NOT_AVAILABLE(
            HttpStatus.CONFLICT,
            "해당 구역의 재고가 없습니다."
    ),

    INVALID_CAPACITY(
            HttpStatus.BAD_REQUEST,
            "기준 인원은 최대 인원보다 클 수 없습니다."
    ),

    DUPLICATE_SITE_NAME(
            HttpStatus.CONFLICT,
            "이미 존재하는 구역 이름입니다."
    ),

    // 권한
    FORBIDDEN(
            HttpStatus.FORBIDDEN,
        "접근 권한이 없습니다."
    ),
    // 리뷰
    REVIEW_NOT_FOUND(
            HttpStatus.NOT_FOUND,
        "리뷰를 찾을 수 없습니다."
    ),
    ALREADY_REVIEWED(
            HttpStatus.CONFLICT,
        "이미 리뷰를 작성한 예약입니다."
    ),
    RESERVATION_NOT_COMPLETED(
            HttpStatus.BAD_REQUEST,
        "이용 완료된 예약만 리뷰를 작성할 수 있습니다."
    ),
    REVIEW_ACCESS_DENIED(
            HttpStatus.FORBIDDEN,
        "리뷰 작성 권한이 없습니다."
    ),
    INVALID_PATH_VARIABLE(
            HttpStatus.BAD_REQUEST,
            "요청 값이 올바르지 않습니다."
    ),

    //결제
    RESERVATION_FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "본인 예약만 결제 가능합니다."),
    ALREADY_PAID(
            HttpStatus.CONFLICT,
            "이미 결제된 예약입니다."),
    NO_AVAILABLE_ROOM(
            HttpStatus.CONFLICT,
            "예약 가능한 자리가 없습니다."),
    INVALID_RESERVATION_STATUS(
            HttpStatus.BAD_REQUEST,
        "결제 가능한 예약 상태가 아닙니다."
    ),
    PAYMENT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
        "결제 정보를 찾을 수 없습니다."
    ),
    AMOUNT_MISMATCH(
            HttpStatus.BAD_REQUEST,
            "결제 금액이 일치하지 않습니다."
    ),
    TOSS_CONFIRM_FAIL(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "결제 승인에 실패했습니다."
    ),
    PAYMENT_CONFIRM_FAILED(
            HttpStatus.BAD_GATEWAY,
            "결제 승인에 실패했습니다. 잠시 후 다시 시도해주세요."
    ),
    INVALID_PAYMENT_STATUS(
            HttpStatus.CONFLICT,
            "현재 결제 상태에서는 요청을 처리할 수 없습니다."
    ),

    // 관리자
    INVALID_CAMPING_ID(
            HttpStatus.BAD_REQUEST,
            "캠핑장 ID가 올바르지 않습니다."
    ),
    CAMPING_NOT_PENDING(
            HttpStatus.BAD_REQUEST,
            "승인 대기 상태의 캠핑장만 처리할 수 있습니다."
    ),
    ADMIN_REQUIRED(
            HttpStatus.FORBIDDEN,
            "관리자 권한이 필요합니다."
    ),
    EMPTY_CAMPING_ID_LIST(
            HttpStatus.BAD_REQUEST,
            "승인할 캠핑장을 선택해주세요."
    ),
    REJECT_REASON_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "거절 사유는 필수입니다."
    ),

    // 알람 관련 에러코드
    NOTIFICATION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "존재하지 않는 알림입니다."
    ),
    NOTIFICATION_ACCESS_DENIED(
            HttpStatus.FORBIDDEN,
            "알림에 대한 권한이 없습니다."
    ),

    // 채팅방 관련 예외
    CHAT_ROOM_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "채팅방을 찾을 수 없습니다."
    ),
    CHAT_ROOM_ACCESS_DENIED(
            HttpStatus.FORBIDDEN,
            "채팅방에 접근할 수 없습니다."
    ),

    // 외부 API 통신 관련 예외
    GO_CAMPING_API_ERROR(
            HttpStatus.BAD_GATEWAY,
            "고캠핑 API 통신 중 오류가 발생했습니다."
    ),
    INITIAL_DATA_ALREADY_EXISTS(
            HttpStatus.CONFLICT,
            "초기 데이터가 이미 존재합니다."
    ),

    //타임딜
    TIME_DEAL_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "존재하지 않는 타임딜입니다."
    ),
    TIME_DEAL_INVALID_DATE_RANGE(
            HttpStatus.BAD_REQUEST,
            "체크아웃 날짜는 체크인 날짜 이후여야 합니다."
    ),
    TIME_DEAL_PAST_DATE(
            HttpStatus.BAD_REQUEST,
            "지난 날짜로는 타임딜을 등록할 수 없습니다."
    ),
    TIME_DEAL_INVALID_SALE_WINDOW(
            HttpStatus.BAD_REQUEST,
            "판매 기간이 올바르지 않습니다."
    ),
    TIME_DEAL_INVALID_PRICE(
            HttpStatus.BAD_REQUEST,
            "할인가는 정가보다 낮아야 합니다."
    ),
    TIME_DEAL_STOCK_EXCEEDED(
            HttpStatus.BAD_REQUEST,
            "등록 가능한 재고를 초과했습니다."
    ),
    TIME_DEAL_ALREADY_SOLD(
            HttpStatus.BAD_REQUEST,
            "이미 판매된 타임딜은 삭제할 수 없습니다."
    ),
    ACCESS_DENIED(
            HttpStatus.FORBIDDEN,
            "접근 권한이 없습니다."
    ),
    TIME_DEAL_SOLD_OUT(
            HttpStatus.CONFLICT,
            "해당 타임딜은 매진되었습니다."
    ),
    TIME_DEAL_NOT_ACTIVE(
            HttpStatus.BAD_REQUEST,
            "현재 판매 중인 타임딜이 아닙니다."
    ),
    TIME_DEAL_CONCURRENT_MODIFICATION(
            HttpStatus.CONFLICT,
            "다른 요청과 충돌이 발생했습니다. 다시 시도해주세요."
    ),
    OPTIMISTIC_LOCK_CONFLICT(
            HttpStatus.CONFLICT,
        "다른 요청과 충돌이 발생했습니다. 다시 시도해주세요."
    ),

    // 정산
    SETTLEMENT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "정산 정보를 찾을 수 없습니다."
    ),
    SETTLEMENT_ALREADY_EXISTS(
            HttpStatus.CONFLICT,
            "이미 생성된 정산입니다."
    ),
    SETTLEMENT_ALREADY_COMPLETED(
            HttpStatus.CONFLICT,
            "이미 완료된 정산입니다."
    ),
    INVALID_SETTLEMENT_DATE(
            HttpStatus.BAD_REQUEST,
            "잘못된 정산 기준일입니다."
    ),
    LOCK_TIMEOUT(
            HttpStatus.SERVICE_UNAVAILABLE,
            "현재 처리 중인 요청이 많습니다. 잠시 후 다시 시도해주세요."
    ),

    // S3 이미지
    FILE_UPLOAD_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "파일 업로드에 실패했습니다."
    ),

    IMAGE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "이미지를 찾을 수 없습니다."
    ),

    INVALID_FILE_TYPE(
            HttpStatus.BAD_REQUEST,
            "이미지 파일만 업로드할 수 있습니다."
    ),

    IMAGE_SIZE_EXCEEDED(
            HttpStatus.BAD_REQUEST,
            "이미지 파일은 5MB 이하만 업로드할 수 있습니다."
    );

    private final HttpStatus status;
    private final String message;
}
