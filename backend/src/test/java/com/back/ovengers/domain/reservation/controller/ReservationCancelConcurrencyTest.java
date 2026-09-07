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

        willAnswer(invocation -> {
            Thread.sleep(300);
            return null;
        }).given(tossPaymentClient)
                .cancel(anyString(), anyString());
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    @DisplayName("같은 예약을 동시에 취소하면 Toss 취소는 한 번만 호출된다")
    void concurrentCancel_onlyOneSuccess() throws Exception {

        int threadCount = 2; // 동시에 2개의 요청 (동시에 스레드 2개 실행)

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        // 여러 스레드 관리하는 객체
        // 고정 크기 2개의 스레드를 가진 스레드 풀 생성. 각 스레드가 요청 하나씩 담당

        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        // 아직 준비 완료해야 할 스레드 수 (두 스레드가 모두 요청 직전까지 준비됐는지 확인)

        CountDownLatch startLatch = new CountDownLatch(1);
        // 동시 실행 횟수 1

        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        // 아직 끝나지 않은 작업 수 (두 요청이 모두 끝날 때까지 테스트 본문이 기다리게 함)

        AtomicInteger successCount = new AtomicInteger(); // 정상 취소 성공 개수

        AtomicInteger cancelInProgressCount = new AtomicInteger(); // PAYMENT_CANCEL_IN_PROGRESS로 막힌 요청 개수

        String url =
                "http://localhost:"
                        + port
                        + "/api/reservations/"
                        + reservation.getId()
                        + "/cancel";

        for (int i = 0; i < threadCount; i++) {

            executorService.submit(() -> { // 작업 등록 및 실행 (반복문에 의해 요청 두 번 등록)

                try {

                    readyLatch.countDown(); // 카운트 1 감소
                    startLatch.await(); // 현재 이 코드를 실행한 스레드를 대기시킴

                    ResponseEntity<String> response =
                            restClient.patch()
                                    .uri(url)
                                    .header("Cookie", "accessToken=" + accessToken)
                                    .retrieve()
                                    .toEntity(String.class);

                    System.out.println(
                            "SUCCESS: " +
                                    response.getStatusCode() +
                                    " / " +
                                    response.getBody()
                    );

                    if (response.getStatusCode().is2xxSuccessful()) {
                        successCount.incrementAndGet();
                    }

                } catch (HttpClientErrorException.Conflict e) {

                    System.out.println(
                            "CONFLICT: " +
                                    e.getResponseBodyAsString()
                    );

                    if (e.getResponseBodyAsString()
                            .contains("결제 취소가 이미 진행 중입니다.")) {

                        cancelInProgressCount.incrementAndGet();
                    }

                } catch (Exception e) {

                    System.out.println(
                            "OTHER ERROR: " +
                                    e.getClass().getName() +
                                    " / " +
                                    e.getMessage()
                    );

                } finally {

                    doneLatch.countDown(); // try 성공 여부와 상관없이 무조건 실행
                }
            });
        }

        /*
        * 동시성 제어의 핵심
        */
        readyLatch.await(); // 두 요청 준비 완료까지 대기

        startLatch.countDown(); // 두 요청 동시에 시작

        doneLatch.await(); // 두 요청 모두 종료될 때까지 대기

        executorService.shutdown();

        System.out.println("successCount = " + successCount.get());
        System.out.println("cancelInProgressCount = " + cancelInProgressCount.get());

        assertThat(successCount.get())
                .isEqualTo(1);

        assertThat(cancelInProgressCount.get())
                .isEqualTo(1);

        // Toss cancel 호출 횟수 = 1인지
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

        assertThat(updatedReservation.getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);

        assertThat(updatedPayment.getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
    }
}