package com.p2picks.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Each {@code com.p2picks.app.<feature>} package is a Spring Modulith module (see its package-info).
 * Feature modules may only use other modules' public types and their declared allowed dependencies;
 * the open {@code common} and {@code security} modules are shared infrastructure.
 */
class ModuleArchitectureTest {
    private static final Set<String> FEATURE_MODULES = Set.of(
            "users", "social", "tables", "chat", "bets", "settlement", "feedback", "admin", "config");
    private static final Set<String> SHARED_MODULES = Set.of("common", "security");
    private static final String[] FEATURE_PACKAGES = FEATURE_MODULES.stream()
            .map(name -> "com.p2picks.app." + name + "..")
            .toArray(String[]::new);

    private static final ApplicationModules MODULES = ApplicationModules.of(P2PicksApplication.class);
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.p2picks");

    @Test
    void discoversEveryFeatureAndSharedModule() {
        Set<String> names = MODULES.stream()
                .map(module -> module.getIdentifier().toString())
                .collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrderElementsOf(
                Stream.concat(FEATURE_MODULES.stream(), SHARED_MODULES.stream()).toList());
    }

    @Test
    void modulesAreAcyclicAndRespectTheirDeclaredDependencies() {
        MODULES.verify();
    }

    @Test
    void sharedModulesDoNotDependOnFeatureModules() {
        noClasses().that().resideInAnyPackage("com.p2picks.app.common..", "com.p2picks.app.security..")
                .should().dependOnClassesThat().resideInAnyPackage(FEATURE_PACKAGES)
                .check(PRODUCTION);
    }

    @Test
    void sharedKernelDoesNotDependOnTheApplication() {
        noClasses().that().resideInAPackage("com.p2picks.shared..")
                .should().dependOnClassesThat().resideInAPackage("com.p2picks.app..")
                .check(PRODUCTION);
    }

    @Test
    void calculatorsArePureJava() {
        classes().that().haveSimpleNameStartingWith("PayoutCalculator")
                .or().haveSimpleNameStartingWith("U2PickSettler")
                .or().haveSimpleNameStartingWith("DebtCalculator")
                .or().haveSimpleNameStartingWith("TableSettlementCalculator")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "java..", "com.p2picks.shared..", "com.p2picks.app.bets", "com.p2picks.app.settlement")
                .check(PRODUCTION);
    }

    @Test
    void outboxAndChatMessagesHaveOneWriterEach() throws IOException {
        Path sources = Path.of("src/main/java/com/p2picks/app");
        List<String> violations;
        try (var files = Files.walk(sources)) {
            violations = files.filter(file -> file.toString().endsWith(".java"))
                    .flatMap(file -> {
                        String path = sources.relativize(file).toString().replace('\\', '/');
                        String code = read(file);
                        return Stream.of(
                                code.contains("INSERT INTO outbox") && !path.equals("common/Outbox.java")
                                        ? path + " writes the outbox; use common.Outbox" : null,
                                code.contains("INSERT INTO chat_messages") && !path.startsWith("chat/")
                                        ? path + " writes chat_messages; use chat.ChatMessageWriter" : null);
                    })
                    .filter(violation -> violation != null)
                    .toList();
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void modulithRejectsAccessToAnotherModulesInternals() {
        ApplicationModules fixture = ApplicationModules.of("com.p2picks.archfixture.modules", location -> true);
        var source = fixture.getModuleByName("source").orElseThrow();
        assertThat(source.isExposed(com.p2picks.archfixture.modules.source.api.Exposed.class)).isTrue();
        assertThat(source.isExposed(com.p2picks.archfixture.modules.source.internal.Hidden.class)).isFalse();
        assertThat(fixture.detectViolations().toString()).contains("source.internal.Hidden");
    }

    @Test
    void modulithRejectsModuleCycles() {
        ApplicationModules fixture = ApplicationModules.of("com.p2picks.archfixture.cycle", location -> true);
        assertThat(fixture.detectViolations().toString()).containsIgnoringCase("cycle detected");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
