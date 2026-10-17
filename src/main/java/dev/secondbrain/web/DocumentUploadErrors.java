package dev.secondbrain.web;

import dev.secondbrain.documents.UploadRejectedException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.io.IOException;
import java.net.URI;

/** Also handles multipart parsing failures before a controller has been selected. */
@RestControllerAdvice
final class DocumentUploadErrors {
    @ExceptionHandler(UploadRejectedException.class)
    ResponseEntity<ErrorResponse> rejected(
            UploadRejectedException failure,
            HttpServletRequest request,
            HttpServletResponse response) {
        var reason = failure.reason();
        var status =
                reason == UploadRejectedException.Reason.FILE_TOO_LARGE
                        ? HttpStatus.CONTENT_TOO_LARGE
                        : HttpStatus.BAD_REQUEST;
        return failure(status, reason.name(), reason.message(), request, response);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(
            HttpServletRequest request, HttpServletResponse response) {
        return rejected(
                new UploadRejectedException(UploadRejectedException.Reason.FILE_TOO_LARGE),
                request,
                response);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ErrorResponse> missingFile(
            HttpServletRequest request, HttpServletResponse response) {
        return failure(
                HttpStatus.BAD_REQUEST,
                "MISSING_FILE",
                "Choose one file using the file field.",
                request,
                response);
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ErrorResponse> invalidMultipart(
            HttpServletRequest request, HttpServletResponse response) {
        return failure(
                HttpStatus.BAD_REQUEST,
                "INVALID_UPLOAD",
                "The file upload request could not be read.",
                request,
                response);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> unsupportedContentType(
            HttpServletRequest request, HttpServletResponse response) {
        return failure(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_CONTENT_TYPE",
                "Use a multipart form file upload.",
                request,
                response);
    }

    @ExceptionHandler({IOException.class, DataAccessException.class, TransactionException.class})
    ResponseEntity<ErrorResponse> unavailable(
            HttpServletRequest request, HttpServletResponse response) {
        return failure(
                HttpStatus.SERVICE_UNAVAILABLE,
                "UPLOAD_UNAVAILABLE",
                "The upload could not be completed. Try again later.",
                request,
                response);
    }

    private ResponseEntity<ErrorResponse> failure(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (request.getRequestURI().equals(request.getContextPath() + "/documents/upload")) {
            var target = request.getContextPath() + "/";
            // Parsing may fail before handler selection; save only the safe message for the GET.
            RequestContextUtils.getOutputFlashMap(request).put("uploadError", message);
            RequestContextUtils.saveOutputFlashMap(target, request, response);
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(target)).build();
        }
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }

    record ErrorResponse(String code, String message) {}
}
