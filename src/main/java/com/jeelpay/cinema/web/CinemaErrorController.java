package com.jeelpay.cinema.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

@Controller
@RequestMapping("/error")
public class CinemaErrorController implements ErrorController {

    // Use @RequestMapping (any method) because Spring Security's AccessDeniedHandlerImpl
    // forwards the original request — preserving its HTTP method — to this error page.
    // A plain @GetMapping would return 405 when the original request was a POST (e.g. a
    // CSRF-rejected login attempt).
    @RequestMapping("/403")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String forbidden() {
        return "error/403";
    }

    @RequestMapping
    public String handleError(HttpServletRequest request, HttpServletResponse response) {
        Object statusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (statusCode != null) {
            int code = Integer.parseInt(statusCode.toString());
            response.setStatus(code);
            if (code == HttpStatus.NOT_FOUND.value()) {
                return "error/404";
            }
            if (code == HttpStatus.FORBIDDEN.value()) {
                return "error/403";
            }
        }
        return "error/404";
    }
}
