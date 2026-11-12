package com.p2picks.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.p2picks.archfixture.consumer.ExternalConsumer;
import com.p2picks.archfixture.modules.client.internal.AllowedConsumer;
import com.p2picks.archfixture.modules.client.internal.ForbiddenConsumer;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleArchitectureTest {
    private static final String MODULE_ROOT = "com.p2picks.modules.";
    private static final Set<String> DOMAIN_MODULES = Set.of(
            "identity", "social", "tables", "chat", "bets", "settlement",
            "quickpicks", "notifications", "admin", "sportsdata", "audit");

    @Test
    void discoversAndVerifiesAllDomainModules() {
        ApplicationModules modules = ApplicationModules.of(P2PicksApplication.class);
        Set<String> names = modules.stream()
                .map(module -> module.getIdentifier().toString())
                .collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrderElementsOf(DOMAIN_MODULES);
        modules.forEach(module -> assertThat(module.getNamedInterfaces().getByName("api"))
                .as("%s API", module.getIdentifier()).isPresent());
        modules.verify();
    }

    @Test
    void productionDependenciesUseModuleApisAndDoNotPointIntoTheApp() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.p2picks");
        Set<String> importedModules = classes.stream()
                .map(type -> moduleName(type.getPackageName(), MODULE_ROOT))
                .filter(name -> name != null)
                .collect(Collectors.toSet());
        assertThat(importedModules).containsExactlyInAnyOrderElementsOf(DOMAIN_MODULES);
        apiOnlyRule(MODULE_ROOT).check(classes);
        noClasses().that().resideInAPackage("com.p2picks.modules..")
                .should().dependOnClassesThat().resideInAPackage("com.p2picks.app..")
                .check(classes);
        noClasses().that().resideInAPackage("com.p2picks.shared..")
                .should().dependOnClassesThat().resideInAnyPackage("com.p2picks.app..", "com.p2picks.modules..")
                .check(classes);
    }

    @Test
    void boundaryRuleAllowsApisAndRejectsInternalAndRootAccess() {
        ArchRule rule = apiOnlyRule("com.p2picks.archfixture.modules.");
        JavaClasses allowed = new ClassFileImporter().importClasses(AllowedConsumer.class);
        assertThat(rule.evaluate(allowed).hasViolation()).isFalse();
        JavaClasses forbidden = new ClassFileImporter().importClasses(ForbiddenConsumer.class, ExternalConsumer.class);
        var violations = rule.evaluate(forbidden).getFailureReport().getDetails();
        assertThat(violations).anyMatch(message -> message.contains("source.internal.Hidden"));
        assertThat(violations).anyMatch(message -> message.contains("source.RootType"));
        assertThat(violations).anyMatch(message -> message.contains("consumer.ExternalConsumer"));
    }

    @Test
    void modulithExposesNamedApiAndRejectsInternalDependencies() {
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

    private static ArchRule apiOnlyRule(String moduleRoot) {
        return classes().should(new ArchCondition<>("access other modules only through their api package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String originModule = moduleName(origin.getPackageName(), moduleRoot);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    String targetModule = moduleName(targetPackage, moduleRoot);
                    if (targetModule == null || targetModule.equals(originModule)) {
                        continue;
                    }
                    String apiPackage = moduleRoot + targetModule + ".api";
                    if (!targetPackage.equals(apiPackage) && !targetPackage.startsWith(apiPackage + ".")) {
                        events.add(SimpleConditionEvent.violated(origin, dependency.getDescription()));
                    }
                }
            }
        });
    }

    private static String moduleName(String packageName, String moduleRoot) {
        if (!packageName.startsWith(moduleRoot)) {
            return null;
        }
        String suffix = packageName.substring(moduleRoot.length());
        int separator = suffix.indexOf('.');
        return separator < 0 ? suffix : suffix.substring(0, separator);
    }
}
