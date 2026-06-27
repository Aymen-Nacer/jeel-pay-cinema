package com.jeelpay.cinema.domain;

import java.math.BigDecimal;

public class Movie {
    private Long id;
    private Long tmdbId;
    private String title;
    private String synopsis;
    private String posterUrl;
    private Integer runtimeMinutes;
    private String genres;
    private Integer releaseYear;
    private BigDecimal rating;

    public Movie() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTmdbId() { return tmdbId; }
    public void setTmdbId(Long tmdbId) { this.tmdbId = tmdbId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSynopsis() { return synopsis; }
    public void setSynopsis(String synopsis) { this.synopsis = synopsis; }
    public String getPosterUrl() { return posterUrl; }
    public void setPosterUrl(String posterUrl) { this.posterUrl = posterUrl; }
    public Integer getRuntimeMinutes() { return runtimeMinutes; }
    public void setRuntimeMinutes(Integer runtimeMinutes) { this.runtimeMinutes = runtimeMinutes; }
    public String getGenres() { return genres; }
    public void setGenres(String genres) { this.genres = genres; }
    public Integer getReleaseYear() { return releaseYear; }
    public void setReleaseYear(Integer releaseYear) { this.releaseYear = releaseYear; }
    public BigDecimal getRating() { return rating; }
    public void setRating(BigDecimal rating) { this.rating = rating; }
}
