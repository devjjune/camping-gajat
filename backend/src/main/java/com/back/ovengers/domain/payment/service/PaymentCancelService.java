package com.back.ovengers.domain.payment.service;

import com.back.ovengers.domain.payment.client.TossPaymentClient;
import com.back.ovengers.global.exception.CustomException;
import com.back.ovengers.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentCancelService {

    private final TossPaymentClient tossPaymentClient;

    /**
     * Toss 외부 API 호출 중에는 현재 DB 트랜잭션을 중단한다.
     *
     * ReservationService와 별도의 Bean으로 분리하여
     * Spring Proxy를 거치도록 한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void cancel(String paymentKey) {

        try {

            tossPaymentClient.cancel(
                    paymentKey,
                    "사용자 예약 취소"
            );

        } catch (Exception e) {

            log.error(
                    "토스 결제 취소 실패 - paymentKey: {}, error: {}",
                    paymentKey,
                    e.getMessage(),
                    e
            );

            throw new CustomException(
                    ErrorCode.INTERNAL_SERVER_ERROR
            );
        }
    }
}
