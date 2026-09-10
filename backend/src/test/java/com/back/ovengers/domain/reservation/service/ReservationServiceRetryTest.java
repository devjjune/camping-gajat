package com.back.ovengers.domain.reservation.service;

import com.back.ovengers.domain.chat.service.ChatService;
import com.back.ovengers.domain.payment.repository.PaymentRepository;
import com.back.ovengers.domain.payment.service.PaymentCancelService;
import com.back.ovengers.domain.reservation.dto.ReservationCancelPrepareResult;
import com.back.ovengers.domain.reservation.dto.ReservationCancelResponse;
import com.back.ovengers.domain.reservation.repository.ReservationRepository;
import com.back.ovengers.domain.site.repository.SiteRepository;
import com.back.ovengers.domain.timedeal.repository.TimeDealRepository;
import com.back.ovengers.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class ReservationServiceRetryTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private TimeDealRepository timeDealRepository;

    @Mock
    private ReservationCancelTransactionService reservationCancelTransactionService;

    @Mock
    private PaymentCancelService paymentCancelService;

    @Mock
    private ChatService chatService;

    @InjectMocks
    private ReservationService reservationService;


    @Test
    @DisplayName("Toss 취소 성공 후 DB 반영이 1회 실패하면 재시도 후 성공한다")
    void cancelReservation_completeCancelRetrySuccess() {

        // given
        Long reservationId = 1L;
        Long userId = 1L;
        Long paymentId = 10L;
        String paymentKey = "test_payment_key";

        ReservationCancelPrepareResult prepared =
                new ReservationCancelPrepareResult(
                        paymentId,
                        paymentKey
                );

        ReservationCancelResponse expectedResponse =
                mock(ReservationCancelResponse.class);

        given(
                reservationCancelTransactionService.prepareCancel(
                        reservationId,
                        userId
                )
        ).willReturn(prepared);

        /*
         * Toss 취소는 정상 성공
         */
        willDoNothing()
                .given(paymentCancelService)
                .cancel(paymentKey);

        /*
         * completeCancel()
         *
         * 1번째 호출 → 실패
         * 2번째 호출 → 성공
         */
        given(
                reservationCancelTransactionService.completeCancel(
                        reservationId,
                        paymentId
                )
        )
                .willThrow(new RuntimeException("DB 반영 실패"))
                .willReturn(expectedResponse);


        // when
        ReservationCancelResponse result =
                reservationService.cancelReservation(
                        reservationId,
                        userId
                );


        // then
        assertThat(result).isSameAs(expectedResponse);

        /*
         * 첫 번째 실패 후 두 번째 재시도에서 성공했으므로
         * completeCancel은 총 2번 호출되어야 한다.
         */
        then(reservationCancelTransactionService)
                .should(times(2))
                .completeCancel(
                        reservationId,
                        paymentId
                );

        /*
         * Toss는 재시도하지 않는다.
         * Toss 취소는 이미 성공했기 때문에 정확히 1회만 호출되어야 한다.
         */
        then(paymentCancelService)
                .should(times(1))
                .cancel(paymentKey);

        /*
         * Toss 성공 이후 completeCancel 실패이므로
         * releaseCancel은 호출하면 안 된다.
         */
        then(reservationCancelTransactionService)
                .should(never())
                .releaseCancel(anyLong());
    }


    @Test
    @DisplayName("Toss 취소 성공 후 DB 반영이 모두 실패하면 취소 선점 상태를 유지한다")
    void cancelReservation_completeCancelRetryAllFail() {

        // given
        Long reservationId = 1L;
        Long userId = 1L;
        Long paymentId = 10L;
        String paymentKey = "test_payment_key";

        ReservationCancelPrepareResult prepared =
                new ReservationCancelPrepareResult(
                        paymentId,
                        paymentKey
                );

        given(
                reservationCancelTransactionService.prepareCancel(
                        reservationId,
                        userId
                )
        ).willReturn(prepared);

        /*
         * Toss 취소는 정상 성공
         */
        willDoNothing()
                .given(paymentCancelService)
                .cancel(paymentKey);

        /*
         * completeCancel은 모든 재시도에서 실패
         */
        given(
                reservationCancelTransactionService.completeCancel(
                        reservationId,
                        paymentId
                )
        ).willThrow(new RuntimeException("DB 반영 실패"));


        // when & then
        assertThatThrownBy(() ->
                reservationService.cancelReservation(
                        reservationId,
                        userId
                )
        )
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB 반영 실패");


        /*
         * 최대 재시도 횟수가 3회이므로
         * completeCancel은 총 3번 호출된다.
         */
        then(reservationCancelTransactionService)
                .should(times(3))
                .completeCancel(
                        reservationId,
                        paymentId
                );

        /*
         * Toss 취소 자체를 다시 호출하면 안 된다.
         */
        then(paymentCancelService)
                .should(times(1))
                .cancel(paymentKey);

        /*
         * Toss는 이미 성공했으므로
         * Payment를 DONE으로 되돌리는 releaseCancel은 호출하면 안 된다.
         *
         * 따라서 실제 DB에서는 CANCEL_IN_PROGRESS가 유지된다.
         */
        then(reservationCancelTransactionService)
                .should(never())
                .releaseCancel(anyLong());
    }
}
