package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

class SearchContractsTest {
    private static final SearchResult.SourceLocation LOCATION =
            new SearchResult.SourceLocation(2, 0, 4, 10, OptionalInt.empty());

    @Test
    void normalizesOuterWhitespaceWithoutChangingQueryMeaningAndBoundsResultCount() {
        var query = new SearchQuery("  Café 📚\nStudy notes  ", SearchScope.all());
        assertThat(query.text()).isEqualTo("Café 📚\nStudy notes");
        assertThat(query.limit()).isEqualTo(5);
        assertThat(new SearchQuery("Notes", SearchScope.all(), 1).limit()).isEqualTo(1);
        assertThat(new SearchQuery("Notes", SearchScope.all(), 20).limit()).isEqualTo(20);
    }

    @Test
    void countsUnicodeCodePointsAndRejectsOversizedInputBeforeTrimming() {
        assertThat(new SearchQuery("📚".repeat(1200), SearchScope.all()).text())
                .isEqualTo("📚".repeat(1200));
        for (var text : List.of("x".repeat(1201), "📚".repeat(1201), " ".repeat(1200) + "x")) {
            assertThatThrownBy(() -> new SearchQuery(text, SearchScope.all()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\n", "private-sentinel\0", "\uD800", "\uDC00"})
    void rejectsBlankNulAndMalformedUnicodeWithoutEchoingTheQuery(String text) {
        assertThatThrownBy(() -> new SearchQuery(text, SearchScope.all()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private-sentinel");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 21})
    void rejectsUnboundedResultLimits(int limit) {
        assertThatThrownBy(() -> new SearchQuery("private-sentinel", SearchScope.all(), limit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private-sentinel");
    }

    @Test
    void preservesEmptySelectedScopeAndDefensivelyCopiesIdentifiers() {
        var all = SearchScope.all();
        var empty = SearchScope.selected(Set.of());
        assertThat(all).isInstanceOf(SearchScope.AllDocuments.class).isNotEqualTo(empty);
        assertThat(empty).isInstanceOf(SearchScope.SelectedDocuments.class);
        assertThat(((SearchScope.SelectedDocuments) empty).documentIds()).isEmpty();
        var id = UUID.randomUUID();
        var ids = new HashSet<>(Set.of(id));
        var selected = new SearchScope.SelectedDocuments(ids);
        ids.clear();
        assertThat(selected.documentIds()).containsExactly(id);
        assertThatThrownBy(() -> selected.documentIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void boundsExplicitSelectionsAndRejectsMissingScopeOrIdentifiers() {
        var ids =
                IntStream.range(0, 100)
                        .mapToObj(ignored -> UUID.randomUUID())
                        .collect(Collectors.toSet());
        assertThat(new SearchScope.SelectedDocuments(ids).documentIds()).hasSize(100);
        ids.add(UUID.randomUUID());
        assertThatThrownBy(() -> SearchScope.selected(ids))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchScope.selected(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                SearchScope.selected(
                                        new HashSet<>(java.util.Arrays.asList((UUID) null))))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SearchQuery(null, SearchScope.all()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SearchQuery("Notes", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void returnsAnImmutableBoundedSnapshotWithoutDuplicatePassages() {
        assertThat(new SearchResult(List.of()).matches()).isEmpty();
        var match = match(0.75);
        var mutable = new ArrayList<>(List.of(match));
        var result = new SearchResult(mutable);
        mutable.clear();
        assertThat(result.matches()).containsExactly(match);
        assertThatThrownBy(() -> result.matches().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new SearchResult(List.of(match, match)))
                .isInstanceOf(IllegalArgumentException.class);
        var matches = IntStream.range(0, 20).mapToObj(ignored -> match(0.75)).toList();
        assertThat(new SearchResult(matches).matches()).hasSize(20);
        var tooMany = new ArrayList<>(matches);
        tooMany.add(match(0.5));
        assertThatThrownBy(() -> new SearchResult(tooMany))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.01, 1.01})
    void rejectsNonfiniteOrOutOfRangeCosineScores(double score) {
        assertThatThrownBy(() -> match(score)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesLocatedPassageIdentityAndAllowsTheFullCosineRange() {
        for (double score : new double[] {-1, 0, 1}) {
            var match = match(score);
            assertThat(match.score()).isEqualTo(score);
            assertThat(match.indexingVersion()).isEqualTo(7);
            assertThat(match.location()).isEqualTo(LOCATION);
        }
        var pdf = new SearchResult.SourceLocation(8, 0, 8, 5, OptionalInt.of(3));
        assertThat(pdf.pageNumber()).hasValue(3);
        assertThatThrownBy(() -> new SearchResult.SourceLocation(1, 0, 2, 0, OptionalInt.of(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SearchResult.Match(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        0,
                                        1,
                                        "Notes",
                                        LOCATION,
                                        0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SearchResult.Match(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        7,
                                        1,
                                        "x".repeat(1201),
                                        LOCATION,
                                        0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"0,0,1,1", "2,0,1,1", "1,-1,1,2", "1,0,1,0", "1,4,1,3", "1,0,2,-1"})
    void rejectsInvalidSourceRanges(int startLine, int startColumn, int endLine, int endColumn) {
        assertThatThrownBy(
                        () ->
                                new SearchResult.SourceLocation(
                                        startLine,
                                        startColumn,
                                        endLine,
                                        endColumn,
                                        OptionalInt.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultDiagnosticsOmitQueryPassageAndDocumentIdentifiers() {
        var match = match(0.75);
        var scope = SearchScope.selected(Set.of(match.documentId()));
        var query = new SearchQuery("private-query-sentinel", scope);
        var result = new SearchResult(List.of(match));
        assertThat(query.toString() + scope + result + match)
                .doesNotContain(
                        "private-query-sentinel",
                        "private-passage-sentinel",
                        match.documentId().toString(),
                        match.passageId().toString());
    }

    private static SearchResult.Match match(double score) {
        return new SearchResult.Match(
                UUID.randomUUID(),
                UUID.randomUUID(),
                7,
                1,
                "private-passage-sentinel",
                LOCATION,
                score);
    }
}
