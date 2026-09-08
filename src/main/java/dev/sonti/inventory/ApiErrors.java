package dev.sonti.inventory;

import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status()), error.getMessage());
    }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ProblemDetail invalid(Exception error) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), "Invalid request. Check required fields, types and documented limits.");
    }
}
