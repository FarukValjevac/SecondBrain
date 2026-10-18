package dev.secondbrain.documents;

import dev.secondbrain.documents.PdfExtractionException.Reason;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Per-call PDFBox state; parsing and extraction belong outside database transactions. */
final class PdfTextExtractor {
    private final Limits limits;
    private final Clock clock;

    PdfTextExtractor(long maximumInputBytes) {
        this(
                new Limits(maximumInputBytes, 200, 1_000_000, 1_000_000, Duration.ofSeconds(15)),
                Clock.systemUTC());
    }

    PdfTextExtractor(Limits limits, Clock clock) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Caller owns the input stream; all PDF library resources close before returning. */
    List<Page> extract(InputStream input) {
        Objects.requireNonNull(input, "input");
        var budget = new Budget(clock.instant().plus(limits.maximumDuration()));
        var bytes = readInput(input, budget);
        try (var source = new CheckedPdfSource(bytes, budget);
                var document = new PDFParser(source).parse(false)) {
            budget.check();
            // Also reject encryption that opens with an empty password.
            if (document.isEncrypted()) throw new PdfExtractionException(Reason.ENCRYPTED);
            int count = document.getNumberOfPages();
            if (count > limits.maximumPages()) throw new PdfExtractionException(Reason.PAGE_LIMIT);
            if (count == 0) throw new PdfExtractionException(Reason.NO_TEXT);
            var output = new PageWriter(count, budget);
            new BoundedStripper(output, budget).writeText(document, output);
            budget.check();
            var pages = output.pages();
            if (pages.stream().allMatch(page -> page.text().isBlank())) {
                throw new PdfExtractionException(Reason.NO_TEXT);
            }
            return pages;
        } catch (InvalidPasswordException encrypted) {
            throw new PdfExtractionException(Reason.ENCRYPTED, encrypted);
        } catch (PdfExtractionException classified) {
            throw classified;
        } catch (IOException | RuntimeException unreadable) {
            throw new PdfExtractionException(Reason.UNREADABLE, unreadable);
        }
    }

    private byte[] readInput(InputStream input, Budget budget) {
        var output = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        try {
            while (true) {
                budget.check();
                // One overflow byte distinguishes exact-limit EOF without buffering excess data.
                int allowed =
                        (int)
                                Math.min(
                                        buffer.length,
                                        limits.maximumInputBytes() - output.size() + 1);
                int count = input.read(buffer, 0, allowed);
                if (count < 0) return output.toByteArray();
                if ((long) output.size() + count > limits.maximumInputBytes()) {
                    throw new PdfExtractionException(Reason.INPUT_TOO_LARGE);
                }
                output.write(buffer, 0, count);
            }
        } catch (IOException unavailable) {
            throw new PdfExtractionException(Reason.SOURCE_UNAVAILABLE, unavailable);
        }
    }

    record Page(int number, String text) {
        Page {
            if (number < 1) throw new IllegalArgumentException("Page number must be positive");
            Objects.requireNonNull(text, "text");
        }
    }

    record Limits(
            long maximumInputBytes,
            int maximumPages,
            int maximumCharacters,
            int maximumOperators,
            Duration maximumDuration) {
        Limits {
            if (maximumInputBytes < 1
                    || maximumInputBytes > 32L * 1024 * 1024
                    || maximumPages < 1
                    || maximumPages > 1_000
                    || maximumCharacters < 1
                    || maximumCharacters > 10_000_000
                    || maximumOperators < 1
                    || maximumOperators > 10_000_000
                    || maximumDuration == null
                    || maximumDuration.isNegative()
                    || maximumDuration.isZero()
                    || maximumDuration.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new IllegalArgumentException("PDF extraction limits are out of range");
            }
        }
    }

    private final class Budget {
        private final Instant deadline;
        private int operators;
        private long glyphCharacters;

        private Budget(Instant deadline) {
            this.deadline = deadline;
        }

        void check() {
            if (Thread.currentThread().isInterrupted())
                throw new PdfExtractionException(Reason.CANCELLED);
            if (!clock.instant().isBefore(deadline))
                throw new PdfExtractionException(Reason.TIME_LIMIT);
        }

        void operator() {
            check();
            if (++operators > limits.maximumOperators())
                throw new PdfExtractionException(Reason.WORK_LIMIT);
        }

        void glyph(TextPosition position) {
            check();
            glyphCharacters += position.getUnicode().length();
            // Bound PDFBox's buffered text positions before it sorts or suppresses duplicates.
            if (glyphCharacters > limits.maximumCharacters())
                throw new PdfExtractionException(Reason.TEXT_LIMIT);
        }
    }

    private static final class CheckedPdfSource extends RandomAccessReadBuffer {
        private final Budget budget;

        private CheckedPdfSource(byte[] bytes, Budget budget) {
            super(bytes);
            this.budget = budget;
        }

        @Override
        public int read() throws IOException {
            budget.check();
            return super.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            budget.check();
            return super.read(buffer, offset, length);
        }

        @Override
        public void seek(long position) throws IOException {
            budget.check();
            super.seek(position);
        }
    }

    private static final class BoundedStripper extends PDFTextStripper {
        private final PageWriter output;
        private final Budget budget;

        private BoundedStripper(PageWriter output, Budget budget) {
            this.output = output;
            this.budget = budget;
            setSortByPosition(true);
            setShouldSeparateByBeads(false);
            setLineSeparator("\n");
            setPageEnd("");
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            budget.check();
            output.page = getCurrentPageNo() - 1;
            super.startPage(page);
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands)
                throws IOException {
            budget.operator();
            super.processOperator(operator, operands);
        }

        @Override
        protected void processTextPosition(TextPosition position) {
            budget.glyph(position);
            super.processTextPosition(position);
        }
    }

    private final class PageWriter extends Writer {
        private final List<StringBuilder> text = new ArrayList<>();
        private final Budget budget;
        private int page;
        private int characters;

        private PageWriter(int pages, Budget budget) {
            this.budget = budget;
            for (int index = 0; index < pages; index++) text.add(new StringBuilder());
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            budget.check();
            if (length > limits.maximumCharacters() - characters)
                throw new PdfExtractionException(Reason.TEXT_LIMIT);
            text.get(page).append(buffer, offset, length);
            characters += length;
        }

        List<Page> pages() {
            var pages = new ArrayList<Page>();
            for (int index = 0; index < text.size(); index++) {
                pages.add(new Page(index + 1, text.get(index).toString()));
            }
            return List.copyOf(pages);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
