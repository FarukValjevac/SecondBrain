package dev.secondbrain.web;

import dev.secondbrain.documents.UploadRejectedException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Global multipart handling also covers parser failures before a controller has been selected. */
@RestControllerAdvice
final class DocumentUploadErrors {
    @ExceptionHandler(UploadRejectedException.class)
    ResponseEntity<ErrorResponse> rejected(UploadRejectedException failure) {
        var reason = failure.reason();
        var status =
                reason == UploadRejectedException.Reason.FILE_TOO_LARGE
                        ? HttpStatus.CONTENT_TOO_LARGE
                        : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(new ErrorResponse(reason.name(), reason.message()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge() {
        return rejected(new UploadRejectedException(UploadRejectedException.Reason.FILE_TOO_LARGE));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ErrorResponse> missingFile() {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("MISSING_FILE", "Choose one file using the file field."));
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ErrorResponse> invalidMultipart() {
        return ResponseEntity.badRequest()
                .body(
                        new ErrorResponse(
                                "INVALID_UPLOAD", "The file upload request could not be read."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> unsupportedContentType() {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(
                        new ErrorResponse(
                                "UNSUPPORTED_CONTENT_TYPE", "Use a multipart form file upload."));
    }

    record ErrorResponse(String code, String message) {}
}
