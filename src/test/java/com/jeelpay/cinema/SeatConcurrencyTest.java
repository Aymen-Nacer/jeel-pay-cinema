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

/**
 * Concurrency test: two threads race to book the same seat.
 * Exactly one must succeed; the other must receive {@link SeatUnavailableException}.
 *
 * <h3>Why separate unexpected-exception tracking matters</h3>
 * The original code incremented the same {@code failures} counter for both
 * {@code SeatUnavailableException} and any other {@code Exception}. This meant
 * the test would pass even if both threads threw a {@code NullPointerException}
 * due to a bug — 0 successes + 2 failures still satisfies "failures == 1" is
 * false, but 1 success + 1 NPE gives the same counter values as the correct path.
 *
 * The fix: track unexpected exceptions in a separate list and fail the test
 * immediately if any are present. The test can only pass when there is exactly
 * one {@code SeatUnavailableException} (intentional) and zero unexpected errors.
 *
 * <h3>Concurrency correctness</h3>
 * {@link CountDownLatch} with {@code start.await()} ensures all threads are
 * scheduled and waiting before the starting gate is released. This creates a
 * microsecond-level race that reliably exercises the DB-level {@code FOR UPDATE NOWAIT}
 * lock in {@link com.jeelpay.cinema.repository.ShowtimeSeatRepository}.
 */
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
        // Create an isolated showtime (5 rows × 10 seats) — no reliance on seeded IDs.
        var ctx = testDataFactory.createShowtimeWithSeats(5, 10);
        long showtimeId = ctx.showtimeId();
        String contestedSeat = ctx.firstSeat();   // "A1"

        var user1 = userService.register("race-user1@test.com", "password123");
        var user2 = userService.register("race-user2@test.com", "password123");

        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger expectedFailures = new AtomicInteger(0);      // SeatUnavailableException
        List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>(); // anything else
        List<String>    bookingIds = new CopyOnWriteArrayList<>();

        Long[] userIds = {user1.getId(), user2.getId()};

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final Long userId = userIds[i];
            pool.submit(() -> {
                ready.countDown();          // signal: this thread is ready
                try {
                    start.await();          // wait for the starting gun
                    var booking = bookingService.createPendingBooking(
                            showtimeId, List.of(contestedSeat), userId);
                    bookingIds.add(booking.getId());
                    successes.incrementAndGet();
                } catch (SeatUnavailableException e) {
                    // Intentional: the DB lock correctly rejected the second thread.
                    expectedFailures.incrementAndGet();
                } catch (Exception e) {
                    // Unexpected: a bug produced an error we did not anticipate.
                    // Collect rather than swallow so we can assert on it below.
                    unexpectedErrors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();          // wait until both threads are at the starting gate
        start.countDown();      // release both threads simultaneously
        boolean allFinished = done.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(allFinished)
                .as("Both threads should complete within 10 s")
                .isTrue();

        // Unexpected exceptions must surface the bug, not be hidden behind a counter.
        assertThat(unexpectedErrors)
                .as("Unexpected exceptions from worker threads: %s".formatted(unexpectedErrors))
                .isEmpty();

        assertThat(successes.get())
                .as("Exactly one thread should succeed in booking the seat")
                .isEqualTo(1);
        assertThat(expectedFailures.get())
                .as("The other thread must receive SeatUnavailableException")
                .isEqualTo(1);

        // The winning booking must be PENDING (not double-inserted).
        assertThat(bookingIds).hasSize(1);
        var booking = bookingRepository.findById(bookingIds.get(0)).orElseThrow();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
    }
}
