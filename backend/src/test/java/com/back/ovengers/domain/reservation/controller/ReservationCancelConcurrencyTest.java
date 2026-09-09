package com.back.ovengers.domain.reservation.controller;

import com.back.ovengers.domain.camping.entity.Camping;
import com.back.ovengers.domain.camping.entity.CampingStatus;
import com.back.ovengers.domain.camping.repository.CampingRepository;
import com.back.ovengers.domain.chat.entity.ChatRoom;
import com.back.ovengers.domain.chat.enums.ChatRoomStatus;
import com.back.ovengers.domain.chat.enums.ChatRoomType;
import com.back.ovengers.domain.chat.repository.ChatRoomRepository;
import com.back.ovengers.domain.payment.client.TossPaymentClient;
import com.back.ovengers.domain.payment.entity.Payment;
import com.back.ovengers.domain.payment.entity.PaymentStatus;
import com.back.ovengers.domain.payment.repository.PaymentRepository;
import com.back.ovengers.domain.reservation.entity.Reservation;
import com.back.ovengers.domain.reservation.entity.ReservationStatus;
import com.back.ovengers.domain.reservation.repository.ReservationRepository;
import com.back.ovengers.domain.site.entity.Site;
import com.back.ovengers.domain.site.repository.SiteRepository;
import com.back.ovengers.domain.user.entity.User;
import com.back.ovengers.domain.user.repository.UserRepository;
import com.back.ovengers.fixture.UserFixture;
import com.back.ovengers.global.security.JwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.times;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReservationCancelConcurrencyTest {

    private final RestClient restClient = RestClient.create();

    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SiteRepository siteRepository;
    @Autowired private CampingRepository campingRepository;
    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private DataSource dataSource;

    @MockitoBean private TossPaymentClient tossPaymentClient;

    @LocalServerPort private int port;

    private User user;
    private Reservation reservation;
    private Payment payment;
    private String accessToken;

    private void cleanDatabase() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {

            stmt.execute("SET FOREIGN_KEY_CHECKS = 0"); // 외래키 검사 잠시 종료

            stmt.execute("TRUNCATE TABLE chat_room");
            stmt.execute("TRUNCATE TABLE payment");
            stmt.execute("TRUNCATE TABLE reservation");
            stmt.execute("TRUNCATE TABLE site");
            stmt.execute("TRUNCATE TABLE camping");
            stmt.execute("TRUNCATE TABLE refresh_tokens");
            stmt.execute("TRUNCATE TABLE users");

            stmt.execute("SET FOREIGN_KEY_CHECKS = 1");

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {

        cleanDatabase();

        user = userRepository.save(
                UserFixture.user()
                        .email("user" + System.nanoTime() + "@test.com")
                        .nickname("유저" + System.nanoTime())
                        .build()
        );

        User host = userRepository.save(
                UserFixture.host()
                        .email("host" + System.nanoTime() + "@test.com")
                        .nickname("호스트" + System.nanoTime())
                        .build()
        );

        Camping camping = campingRepository.save(
                Camping.builder()
                        .host(host)
                        .name("테스트 캠핑장")
                        .region("서울")
                        .city("강남구")
                        .address("서울시 강남구 테스트로 123")
                        .status(CampingStatus.APPROVED)
                        .build()
        );

        Site site = siteRepository.save(
                Site.builder()
                        .camping(camping)
                        .name("A구역")
                        .baseCapacity(2)
                        .maxCapacity(4)
                        .totalAmount(5)
                        .price(50000)
                        .build()
        );

        reservation = reservationRepository.save(
                Reservation.builder()
                        .user(user)
                        .site(site)
                        .rsvNum("RSV-" + UUID.randomUUID())
                        .rsvName("홍길동")
                        .rsvPhone("010-1234-5678")
                        .checkIn(LocalDate.now().plusDays(3))
                        .checkOut(LocalDate.now().plusDays(5))
                        .guestCount(2)
                        .rsvPrice(100000)
                        .status(ReservationStatus.CONFIRMED)
                        .build()
        );

        payment = paymentRepository.save(
                Payment.builder()
                        .reservation(reservation)
                        .orderId("ORD-" + UUID.randomUUID())
                        .paymentKey("test_payment_key")
                        .paidPrice(100000)
                        .status(PaymentStatus.DONE)
                        .build()
        );

        chatRoomRepository.save(
                ChatRoom.builder()
                        .reservationId(reservation.getId())
                        .name(camping.getName() + " 채팅방")
                        .type(ChatRoomType.DIRECT)
                        .status(ChatRoomStatus.ACTIVE)
                        .build()
        );

        accessToken =
                jwtProvider.createRefreshToken(
                        user.getId(),
                        user.getRole().name()
                );
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    @DisplayName("취소 진행 중 같은 예약의 중복 취소 요청은 차단된다")
    void concurrentCancel_onlyOneSuccess() throws Exception {

        /*
         * =========================================================
         * [1. 테스트 준비]
         * 실행 주체: 메인 테스트 스레드
         * =========================================================
         */

        // 요청 2개를 각각 별도 스레드에서 실행하기 위한 스레드풀
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        // 첫 번째 요청이 정상 취소까지 완료됐는지 카운트
        AtomicInteger successCount = new AtomicInteger();

        // 두 번째 요청이 PAYMENT_CANCEL_IN_PROGRESS로 차단됐는지 카운트
        AtomicInteger cancelInProgressCount = new AtomicInteger();


        /*
         * =========================================================
         * [2. 스레드 간 실행 순서를 제어하기 위한 Latch 준비]
         * 실행 주체: 메인 테스트 스레드
         * =========================================================
         */

        // 첫 번째 요청이 Toss 호출 지점까지 도착했음을 알리기 위한 Latch
        CountDownLatch tossEnteredLatch = new CountDownLatch(1);

        // 첫 번째 요청을 Toss 호출 지점에서 대기시키기 위한 Latch
        CountDownLatch releaseTossLatch = new CountDownLatch(1);

        // 첫 번째 요청이 완전히 끝났는지 확인
        CountDownLatch firstDoneLatch = new CountDownLatch(1);

        // 두 번째 요청이 완전히 끝났는지 확인
        CountDownLatch secondDoneLatch = new CountDownLatch(1);

        // Toss 호출 횟수 구분용
        AtomicInteger tossCallCount = new AtomicInteger();


        /*
         * =========================================================
         * [3. Toss Mock 동작 정의]
         * 설정: 메인 테스트 스레드
         * 실제 실행: tossPaymentClient.cancel()을 호출한 요청 스레드
         * =========================================================
         */

        willAnswer(invocation -> {

            int callCount = tossCallCount.incrementAndGet();

            /*
             * 첫 번째 Toss 호출만 여기서 멈춘다.
             *
             * 정상 흐름에서는 Toss가 한 번만 호출되어야 하지만,
             * 동시성 제어가 깨져 두 번째 Toss 호출까지 발생하더라도
             * 두 번째 호출까지 같이 멈추지 않도록 구분한다.
             */
            if (callCount == 1) {

                // [첫 번째 요청 스레드]
                // "prepareCancel을 끝내고 Toss 호출까지 도착했다"고
                // 메인 테스트 스레드에게 알림
                tossEnteredLatch.countDown();

                // [첫 번째 요청 스레드]
                // 메인 테스트 스레드가 풀어줄 때까지 여기서 대기
                releaseTossLatch.await();
            }

            return null;

        }).given(tossPaymentClient)
                .cancel(anyString(), anyString());


        /*
         * =========================================================
         * [4. 테스트할 예약 취소 API URL 준비]
         * 실행 주체: 메인 테스트 스레드
         * =========================================================
         */

        String url =
                "http://localhost:"
                        + port
                        + "/api/reservations/"
                        + reservation.getId()
                        + "/cancel";


        /*
         * =========================================================
         * [5. 첫 번째 취소 요청 시작]
         * submit 호출: 메인 테스트 스레드
         * submit 내부 실행: ExecutorService의 첫 번째 요청 스레드
         * =========================================================
         */

        executorService.submit(() -> {

            try {

                // [첫 번째 요청 스레드]
                // 실제 예약 취소 API 호출
                ResponseEntity<String> response =
                        restClient.patch()
                                .uri(url)
                                .header(
                                        "Cookie",
                                        "accessToken=" + accessToken
                                )
                                .retrieve()
                                .toEntity(String.class);

                // [첫 번째 요청 스레드]
                if (response.getStatusCode().is2xxSuccessful()) {
                    successCount.incrementAndGet();
                }

            } catch (Exception e) {

                System.out.println(
                        "첫 번째 요청 실패: " + e.getMessage()
                );

            } finally {

                // [첫 번째 요청 스레드]
                // 첫 번째 요청이 성공/실패 여부와 상관없이 끝났음을 알림
                firstDoneLatch.countDown();
            }
        });


        /*
         * =========================================================
         * [6. 첫 번째 요청이 Toss까지 도착할 때까지 기다림]
         * 실행 주체: 메인 테스트 스레드
         * =========================================================
         */

        boolean tossEntered =
                tossEnteredLatch.await(
                        3,
                        TimeUnit.SECONDS
                );

        assertThat(tossEntered).isTrue();

        /*
         * 여기까지 왔다는 뜻:
         *
         * 첫 번째 요청:
         * prepareCancel()
         * DONE → CANCEL_IN_PROGRESS
         * ↓
         * Toss mock까지 도착
         * ↓
         * releaseTossLatch.await()에서 대기 중
         *
         * 따라서 의도한 DB 상태는:
         * Reservation = CONFIRMED
         * Payment = CANCEL_IN_PROGRESS
         */


        /*
         * =========================================================
         * [7. 첫 번째 요청이 Toss에서 멈춰 있는 동안
         *     두 번째 취소 요청 시작]
         *
         * submit 호출: 메인 테스트 스레드
         * submit 내부 실행: ExecutorService의 두 번째 요청 스레드
         * =========================================================
         */

        executorService.submit(() -> {

            try {

                // [두 번째 요청 스레드]
                restClient.patch()
                        .uri(url)
                        .header(
                                "Cookie",
                                "accessToken=" + accessToken
                        )
                        .retrieve()
                        .toEntity(String.class);

            } catch (HttpClientErrorException.Conflict e) {

                // [두 번째 요청 스레드]
                // 첫 번째 요청이 이미 취소 권한을 선점했기 때문에
                // PAYMENT_CANCEL_IN_PROGRESS가 발생하는지 확인
                if (e.getResponseBodyAsString()
                        .contains(
                                "결제 취소가 이미 진행 중입니다."
                        )) {

                    cancelInProgressCount.incrementAndGet();
                }

            } catch (Exception e) {

                System.out.println(
                        "두 번째 요청 실패: " + e.getMessage()
                );

            } finally {

                // [두 번째 요청 스레드]
                secondDoneLatch.countDown();
            }
        });


        /*
         * =========================================================
         * [8. 두 번째 요청이 끝날 때까지 기다림]
         * 실행 주체: 메인 테스트 스레드
         * =========================================================
         */

        boolean secondFinished =
                secondDoneLatch.await(
                        3,
                        TimeUnit.SECONDS
                );

        assertThat(secondFinished).isTrue();


        /*
         * =========================================================
         * [9. 두 번째 요청이 CANCEL_IN_PROGRESS 때문에
         *     실제로 차단됐는지 검증]
         * 실행 주체: 메인 테스트 스레드
         * =========================================================
         */

        assertThat(cancelInProgressCount.get())
                .isEqualTo(1);


        /*
         * =========================================================
         * [10. 첫 번째 요청의 Toss 대기 해제]
         * 실행 주체: 메인 테스트 스레드
         * =========================================================
         */

        releaseTossLatch.countDown();

        /*
         * 이 신호를 받은 첫 번째 요청 스레드는
         *
         * releaseTossLatch.await()
         * ↓
         * Toss mock 종료
         * ↓
         * completeCancel()
         * ↓
         * Reservation = CANCELLED
         * Payment = CANCELLED
         *
         * 순서로 계속 실행된다.
         */


        /*
         * =========================================================
         * [11. 첫 번째 요청까지 완전히 끝날 때까지 기다림]
         * 실행 주체: 메인 테스트 스레드
         * =========================================================
         */

        boolean firstFinished =
                firstDoneLatch.await(
                        3,
                        TimeUnit.SECONDS
                );

        assertThat(firstFinished).isTrue();

        executorService.shutdown();


        /*
         * =========================================================
         * [12. 최종 결과 검증]
         * 실행 주체: 메인 테스트 스레드
         * =========================================================
         */

        // 정상 취소 성공 요청은 1개
        assertThat(successCount.get())
                .isEqualTo(1);

        // 실제 Toss 취소 호출도 1번만 발생
        then(tossPaymentClient)
                .should(times(1))
                .cancel(anyString(), anyString());

        Reservation updatedReservation =
                reservationRepository
                        .findById(reservation.getId())
                        .orElseThrow();

        Payment updatedPayment =
                paymentRepository
                        .findById(payment.getId())
                        .orElseThrow();

        // 최종 예약 상태
        assertThat(updatedReservation.getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);

        // 최종 결제 상태
        assertThat(updatedPayment.getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
    }
}