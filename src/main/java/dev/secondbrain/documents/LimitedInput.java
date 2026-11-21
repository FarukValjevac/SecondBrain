package dev.secondbrain.documents;

import dev.secondbrain.documents.TextExtractionException.Reason;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Fails with INPUT_TOO_LARGE once more than the byte budget is read. It reads at most one byte past
 * the budget, so input of exactly the budget still ends normally. Closing it leaves the underlying
 * stream open, because extractors never own their input.
 */
final class LimitedInput extends FilterInputStream {
    private long remaining;

    LimitedInput(InputStream input, long maximumBytes) {
        super(input);
        remaining = maximumBytes;
    }

    @Override
    public int read() throws IOException {
        var value = in.read();
        if (value >= 0) consume(1);
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        var count = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
        if (count > 0) consume(count);
        return count;
    }

    private void consume(int count) {
        if (count > remaining) throw new TextExtractionException(Reason.INPUT_TOO_LARGE);
        remaining -= count;
    }

    @Override
    public void close() {
        // The caller owns the underlying stream.
    }
}
