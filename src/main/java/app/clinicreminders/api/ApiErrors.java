package app.clinicreminders.api;

import java.util.Map;

import app.clinicreminders.balance.BalanceProblem;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors as the React app reads them: a message key it can show. Records that don't exist, or
 * belong to another clinic, are 404 already ({@code NotFoundException}).
 */
@RestControllerAdvice(basePackageClasses = ApiErrors.class)
class ApiErrors {

    /** An action that no longer applies, e.g. on an appointment cancelled meanwhile. */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> notAllowed() {
        return Map.of("error", "error.not-allowed");
    }

    /** A money entry that can't be made (a negative fee, lira without a rate...): its text key. */
    @ExceptionHandler(BalanceProblem.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> balance(BalanceProblem problem) {
        return Map.of("error", problem.getMessage());
    }
}
