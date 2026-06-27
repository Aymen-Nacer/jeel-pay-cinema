package com.jeelpay.cinema.web;

import com.jeelpay.cinema.domain.BookingStatus;
import com.jeelpay.cinema.repository.MovieRepository;
import com.jeelpay.cinema.service.BookingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final int PAGE_SIZE = 20;

    private final BookingService bookingService;
    private final MovieRepository movieRepository;

    public AdminController(BookingService bookingService, MovieRepository movieRepository) {
        this.bookingService = bookingService;
        this.movieRepository = movieRepository;
    }

    @GetMapping("/bookings")
    public String bookings(@RequestParam(required = false) String status,
                           @RequestParam(required = false) Long movieId,
                           @RequestParam(defaultValue = "0") int page,
                           Model model) {

        BookingStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try { statusEnum = BookingStatus.valueOf(status.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }

        long total = bookingService.countAll(statusEnum, movieId);
        int totalPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));

        // Clamp the requested page into [0, totalPages - 1] so manual URL tampering is safe.
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

        model.addAttribute("bookings", bookingService.findAll(statusEnum, movieId, page, PAGE_SIZE));
        model.addAttribute("movies", movieRepository.findAll());
        model.addAttribute("statuses", BookingStatus.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedMovieId", movieId);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalBookings", total);

        return "admin/bookings";
    }

    @PostMapping("/bookings/{id}/cancel")
    public String cancel(@PathVariable String id, RedirectAttributes redirectAttrs) {
        try {
            bookingService.cancelBooking(id);
            redirectAttrs.addFlashAttribute("success", "Booking #" + id + " has been cancelled and refunded.");
        } catch (IllegalStateException e) {
            redirectAttrs.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            log.error("Cancellation failed for booking {}: {}", id, e.getMessage());
            redirectAttrs.addFlashAttribute("error", "Cancellation failed: " + e.getMessage());
        }
        return "redirect:/admin/bookings";
    }
}
