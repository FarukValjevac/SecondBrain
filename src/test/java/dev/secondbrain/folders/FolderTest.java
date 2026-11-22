package dev.secondbrain.folders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class FolderTest {
    @Test
    void trimsNamesAndKeepsUnicode() {
        assertThat(Folder.name("  Psychologie & Sport  ")).isEqualTo("Psychologie & Sport");
        assertThat(Folder.name("é".repeat(80))).hasSize(80);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "tab\tinside", "line\nbreak"})
    void rejectsBlankOrControlCharacterNames(String name) {
        assertThatThrownBy(() -> Folder.name(name))
                .isInstanceOfSatisfying(
                        FolderException.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(FolderException.Reason.INVALID_NAME));
    }

    @Test
    void rejectsNamesLongerThanEightyCharacters() {
        assertThatThrownBy(() -> Folder.name("x".repeat(81))).isInstanceOf(FolderException.class);
    }

    @Test
    void depthMustMatchWhetherThereIsAParent() {
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> new Folder(id, Optional.empty(), "A", FolderColor.BLUE, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Folder(id, Optional.of(id), "A", FolderColor.BLUE, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        new Folder(id, Optional.of(UUID.randomUUID()), "A", FolderColor.BLUE, 3)
                                .canHaveChildren())
                .isFalse();
    }

    @Test
    void colorsParseOnlyFromTheirTokens() {
        assertThat(FolderColor.parse("teal")).isEqualTo(FolderColor.TEAL);
        assertThat(FolderColor.TEAL.label()).isEqualTo("Teal");
        assertThatThrownBy(() -> FolderColor.parse("TEAL")).isInstanceOf(FolderException.class);
        assertThatThrownBy(() -> FolderColor.parse(null)).isInstanceOf(FolderException.class);
    }

    @Test
    void treeNavigatesPathsLabelsOrderAndCounts() {
        var sport = new Folder(UUID.randomUUID(), Optional.empty(), "Sport", FolderColor.GREEN, 1);
        var art = new Folder(UUID.randomUUID(), Optional.empty(), "Art", FolderColor.ROSE, 1);
        var running =
                new Folder(
                        UUID.randomUUID(),
                        Optional.of(sport.folderId()),
                        "Running",
                        FolderColor.GREEN,
                        2);
        var tree =
                new FolderService.FolderTree(
                        List.of(sport, art, running),
                        Map.of(sport.folderId(), 2, running.folderId(), 3));
        assertThat(tree.path(running.folderId())).containsExactly(sport, running);
        assertThat(tree.label(running)).isEqualTo("Sport › Running");
        assertThat(tree.ordered()).containsExactly(sport, running, art);
        assertThat(tree.documentsIncludingSubfolders(sport)).isEqualTo(5);
        assertThat(tree.documentsIncludingSubfolders(art)).isZero();
        assertThat(tree.childrenOf(sport)).containsExactly(running);
        assertThat(tree.topLevel()).containsExactly(sport, art);
    }

    @Test
    void neverLogsTheFolderName() {
        var folder =
                new Folder(
                        UUID.randomUUID(), Optional.empty(), "private-name", FolderColor.SLATE, 1);
        assertThat(folder.toString()).doesNotContain("private-name");
    }
}
