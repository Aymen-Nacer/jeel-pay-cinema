package com.jeelpay.cinema.web;

import com.jeelpay.cinema.domain.Booking;
import com.jeelpay.cinema.integration.moyasar.CardDetails;
import com.jeelpay.cinema.integration.moyasar.MoyasarException;
import com.jeelpay.cinema.repository.UserRepository;
import com.jeelpay.cinema.service.BookingService;
import com.jeelpay.cinema.service.BookingService.SeatUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
public class BookingController {

    private static final Logger log = LoggerFactory.getLogger(BookingController.class);

    private final BookingService bookingService;
    private final UserRepository userRepository;

    public BookingController(BookingService bookingService, UserRepository userRepository) {
        this.bookingService = bookingService;
        this.userRepository = userRepository;
    }

    @PostMapping("/showtimes/{showtimeId}/book")
    public String book(@PathVariable Long showtimeId,
                       @RequestParam List<String> seatNumbers,
                       @RequestParam("cardName") String cardName,
                       @RequestParam("cardNumber") String cardNumber,
                       @RequestParam("cardMonth") int cardMonth,
                       @RequestParam("cardYear") int cardYear,
                       @RequestParam("cardCvc") String cardCvc,
                       @AuthenticationPrincipal UserDetails principal,
                       RedirectAttributes redirectAttrs) {

        if (seatNumbers == null || seatNumbers.isEmpty()) {
            redirectAttrs.addFlashAttribute("error", "Please select at least one seat.");
            return "redirect:/showtimes/" + showtimeId;
        }

        var user = userRepository.findByEmail(principal.getUsername()).orElseThrow();

        Booking booking;
        try {
            booking = bookingService.createPendingBooking(showtimeId, seatNumbers, user.getId());
        } catch (SeatUnavailableException e) {
            redirectAttrs.addFlashAttribute("error", e.getMessage());
            return "redirect:/showtimes/" + showtimeId;
        }

        var card = new CardDetails(
                cardName.trim(),
                cardNumber.replaceAll("\\s+", ""),
                cardMonth, cardYear, cardCvc.trim());

        try {
            String paymentUrl = bookingService.initiatePayment(booking, card);
            return "redirect:" + paymentUrl;
        } catch (MoyasarException e) {
            log.error("Payment initiation failed for booking {}: {}", booking.getId(), e.getMessage());
            bookingService.releaseBooking(booking.getId());
            redirectAttrs.addFlashAttribute("error", "Payment initiation failed. Please try again.");
            return "redirect:/showtimes/" + showtimeId;
        }
    }

    @GetMapping("/bookings/{bookingId}/payment/callback")
    public String paymentCallback(@PathVariable String bookingId,
                                  @AuthenticationPrincipal UserDetails principal,
                                  RedirectAttributes redirectAttrs) {

        var user = userRepository.findByEmail(principal.getUsername()).orElseThrow();
        Booking booking = bookingService.findById(bookingId).orElse(null);
        if (booking == null || !booking.getUserId().equals(user.getId())) {
            redirectAttrs.addFlashAttribute("error", "Booking not found.");
            return "redirect:/my-bookings";
        }

        try {
            bookingService.confirmPayment(bookingId);
            redirectAttrs.addFlashAttribute("success", "Booking confirmed! Enjoy the show.");
        } catch (MoyasarException e) {
            log.error("Payment verification failed for booking {}: {}", bookingId, e.getMessage());
            redirectAttrs.addFlashAttribute("error", "Payment was not completed: " + e.getMessage());
        }

        return "redirect:/my-bookings";
    }

    @GetMapping("/my-bookings")
    public String myBookings(@AuthenticationPrincipal UserDetails principal, Model model) {
        var user = userRepository.findByEmail(principal.getUsername()).orElseThrow();
        List<Booking> bookings = bookingService.findByUserId(user.getId());
        model.addAttribute("bookings", bookings);
        return "my-bookings";
    }

    @GetMapping("/bookings/{id}")
    public String bookingDetail(@PathVariable String id,
                                @AuthenticationPrincipal UserDetails principal,
                                Model model) {
        var user = userRepository.findByEmail(principal.getUsername()).orElseThrow();
        Booking booking = bookingService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));

        if (!booking.getUserId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        model.addAttribute("booking", booking);
        return "booking-detail";
    }
}
