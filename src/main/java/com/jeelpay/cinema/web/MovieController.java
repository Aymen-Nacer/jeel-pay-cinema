package com.jeelpay.cinema.web;

import com.jeelpay.cinema.repository.MovieRepository;
import com.jeelpay.cinema.repository.ShowtimeRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
@RequestMapping("/movies")
public class MovieController {

    private final MovieRepository movieRepository;
    private final ShowtimeRepository showtimeRepository;

    public MovieController(MovieRepository movieRepository, ShowtimeRepository showtimeRepository) {
        this.movieRepository = movieRepository;
        this.showtimeRepository = showtimeRepository;
    }

    @GetMapping("/{id}")
    public String movieDetail(@PathVariable Long id, Model model) {
        var movie = movieRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Movie not found"));
        var showtimes = showtimeRepository.findByMovieId(id);
        model.addAttribute("movie", movie);
        model.addAttribute("showtimes", showtimes);
        return "movie-detail";
    }
}
