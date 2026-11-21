package dev.secondbrain.questions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Optional;

class CitationMarkersTest {
    @Test
    void keepsOnlyMarkersThatPointToAnAcceptedCitation() {
        assertThat(
                        CitationMarkers.retainValid(
                                "ArrayList keeps order [1]. HashSet does not [2][2]. Ignore [0] and"
                                        + " [3].",
                                2))
                .isEqualTo("ArrayList keeps order [1]. HashSet does not [2]. Ignore  and .");
    }

    @Test
    void indexesInCodeAndSeparatedRepeatsStayAsWritten() {
        var code = "Use a[0] or grid[0][1], not names_[1]. A [1]. B [1]. Unknown [x].";
        assertThat(CitationMarkers.retainValid(code, 1)).isEqualTo(code);
        assertThat(CitationMarkers.segments(code, 1))
                .filteredOn(segment -> segment.marker().isPresent())
                .extracting(CitationMarkers.Segment::text)
                .containsExactly("[1]", "[1]");
    }

    @Test
    void splitsAcceptedTextIntoPlainRunsAndNumberedMarkers() {
        assertThat(CitationMarkers.segments("First <b>[1]</b> then [2]", 2))
                .containsExactly(
                        new CitationMarkers.Segment("First <b>", Optional.empty()),
                        new CitationMarkers.Segment("[1]", Optional.of(1)),
                        new CitationMarkers.Segment("</b> then ", Optional.empty()),
                        new CitationMarkers.Segment("[2]", Optional.of(2)));
        // Without citations nothing becomes a link, even text that looks like a marker.
        assertThat(CitationMarkers.segments("Text [1]", 0))
                .containsExactly(new CitationMarkers.Segment("Text [1]", Optional.empty()));
    }
}
