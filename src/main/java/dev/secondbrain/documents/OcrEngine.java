package dev.secondbrain.documents;

import java.awt.image.BufferedImage;

/** Recognises the text on one page image. Implementations never log images or text. */
interface OcrEngine {
    String recognise(BufferedImage page) throws InterruptedException;

    /** The engine cannot run at all (not installed, language data missing): permanent. */
    final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unavailable(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }

    /** One page took too long or failed transiently: worth retrying later. */
    final class PageFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PageFailed(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }
}
