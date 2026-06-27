package com.jeelpay.cinema.web;

import com.jeelpay.cinema.config.AppProperties;
import com.jeelpay.cinema.domain.ShowtimeSeat;
import com.jeelpay.cinema.repository.ShowtimeSeatRepository;
import com.jeelpay.cinema.repository.ShowtimeRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
@RequestMapping("/showtimes")
public class ShowtimeController {

    private final ShowtimeRepository showtimeRepository;
    private final ShowtimeSeatRepository seatRepository;
    private final AppProperties appProperties;

    public ShowtimeController(ShowtimeRepository showtimeRepository,
                              ShowtimeSeatRepository seatRepository,
                              AppProperties appProperties) {
        this.showtimeRepository = showtimeRepository;
        this.seatRepository = seatRepository;
        this.appProperties = appProperties;
    }

    @GetMapping("/{id}")
    public String seatMap(@PathVariable Long id, Model model) {
        populateSeatModel(id, model);
        return "seat-map";
    }

    private void populateSeatModel(Long id, Model model) {
        var showtime = showtimeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Showtime not found"));

        List<ShowtimeSeat> seats = seatRepository.findByShowtimeId(id);

        Map<String, List<ShowtimeSeat>> seatsByRow = new LinkedHashMap<>();
        for (ShowtimeSeat seat : seats) {
            String row = seat.getRowLabel();
            seatsByRow.computeIfAbsent(row, k -> new java.util.ArrayList<>()).add(seat);
        }

        model.addAttribute("showtime", showtime);
        model.addAttribute("seatsByRow", seatsByRow);
        model.addAttribute("holdMinutes", appProperties.getBooking().seatHoldMinutes());
    }
}
