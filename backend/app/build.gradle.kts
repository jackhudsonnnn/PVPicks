plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.p2picks"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val docsDir = rootProject.projectDir.parentFile.resolve("docs")
val docsOutput = docsDir.resolve("site/.vitepress/dist")
val prebuiltDocs = providers.gradleProperty("docsPrebuilt").map(String::toBoolean).getOrElse(false)

val buildDeveloperDocs = tasks.register<Exec>("buildDeveloperDocs") {
    workingDir(docsDir)
    commandLine("npm", "run", "build")
    inputs.files(fileTree(docsDir) {
        exclude("node_modules/**", "site/.vitepress/dist/**", "site/.vitepress/cache/**")
    })
    inputs.file(rootProject.file("api/openapi.yaml"))
    inputs.file(rootProject.file("README.md"))
    inputs.file(rootProject.projectDir.parentFile.resolve("client/roadmap.md"))
    outputs.dir(docsOutput)
    onlyIf { !prebuiltDocs }
}

tasks.processResources {
    dependsOn(buildDeveloperDocs)
    doFirst {
        if (!docsOutput.resolve("index.html").isFile) {
            throw GradleException("Documentation build missing. Run npm ci --prefix docs, then rebuild.")
        }
    }
    from(docsOutput) { into("static/docs") }
}

repositories {
    mavenCentral()
}

val modulithVersion: String by project

dependencies {
    implementation(project(":shared:kernel"))
    implementation("org.springframework.modulith:spring-modulith-api:$modulithVersion")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.security:spring-security-messaging")
    implementation("com.ibm.icu:icu4j:76.1")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    testImplementation("com.networknt:json-schema-validator:1.5.6")
    testImplementation("org.springframework.modulith:spring-modulith-core:$modulithVersion")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.processTestResources {
    from("../api/openapi.yaml")
    from("../api/realtime.schema.json")
    from("../dev/dev-seed.sql")
}
