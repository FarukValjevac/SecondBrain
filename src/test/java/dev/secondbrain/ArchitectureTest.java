package dev.secondbrain;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The feature boundaries of .task/architecture.md, checked on the compiled application. */
class ArchitectureTest {
    private static JavaClasses application;

    @BeforeAll
    static void load() {
        application =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("dev.secondbrain");
    }

    @Test
    void onlyTheWebLayerKnowsAboutHttpPages() {
        noClasses()
                .that()
                .resideOutsideOfPackage("dev.secondbrain.web..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("dev.secondbrain.web..")
                .check(application);
    }

    @Test
    void controllersUseServicesNotRepositories() {
        noClasses()
                .that()
                .resideInAPackage("dev.secondbrain.web..")
                .should()
                .dependOnClassesThat()
                .haveSimpleNameStartingWith("Jdbc")
                .check(application);
    }

    @Test
    void durableEventsAndProvidersKnowNoFeature() {
        noClasses()
                .that()
                .resideInAnyPackage("dev.secondbrain.events..", "dev.secondbrain.embeddings..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "dev.secondbrain.documents..",
                        "dev.secondbrain.search..",
                        "dev.secondbrain.questions..",
                        "dev.secondbrain.conversations..",
                        "dev.secondbrain.folders..",
                        "dev.secondbrain.notes..",
                        "dev.secondbrain.summaries..",
                        "dev.secondbrain.imports..")
                .check(application);
    }

    @Test
    void featurePackagesHaveNoCycles() {
        slices().matching("dev.secondbrain.(*)..").should().beFreeOfCycles().check(application);
    }
}
