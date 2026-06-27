package com.jeelpay.cinema;

import com.jeelpay.cinema.domain.BookingStatus;
import com.jeelpay.cinema.repository.BookingRepository;
import com.jeelpay.cinema.service.BookingService;
import com.jeelpay.cinema.service.BookingService.SeatUnavailableException;
import com.jeelpay.cinema.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SeatConcurrencyTest extends AbstractIntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookingRepository;
    @Autowired UserService userService;
    @Autowired TestDataFactory testDataFactory;

    @BeforeEach
    void stubEmails() {
        WireMockStubs.stubResendEmail();
    }

    @Test
    void twoSimultaneousBookings_onlyOneSucceeds() throws InterruptedException {
        var ctx = testDataFactory.createShowtimeWithSeats(5, 10);
        long showtimeId = ctx.showtimeId();
        String contestedSeat = ctx.firstSeat();

        var user1 = userService.register("race-user1@test.com", "password123");
        var user2 = userService.register("race-user2@test.com", "password123");

        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger expectedFailures = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();
        List<String>    bookingIds = new CopyOnWriteArrayList<>();

        Long[] userIds = {user1.getId(), user2.getId()};

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final Long userId = userIds[i];
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    var booking = bookingService.createPendingBooking(
                            showtimeId, List.of(contestedSeat), userId);
                    bookingIds.add(booking.getId());
                    successes.incrementAndGet();
                } catch (SeatUnavailableException e) {
                    expectedFailures.incrementAndGet();
                } catch (Exception e) {
                    unexpectedErrors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        boolean allFinished = done.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(allFinished)
                .as("Both threads should complete within 10 s")
                .isTrue();

        assertThat(unexpectedErrors)
                .as("Unexpected exceptions from worker threads: %s".formatted(unexpectedErrors))
                .isEmpty();

        assertThat(successes.get())
                .as("Exactly one thread should succeed in booking the seat")
                .isEqualTo(1);
        assertThat(expectedFailures.get())
                .as("The other thread must receive SeatUnavailableException")
                .isEqualTo(1);

        assertThat(bookingIds).hasSize(1);
        var booking = bookingRepository.findById(bookingIds.get(0)).orElseThrow();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
    }
}
