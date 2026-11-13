val modulithVersion: String by project
val junitVersion: String by project
val assertjVersion: String by project

// Plain Java libraries: the domain modules and the shared kernel.
configure(subprojects.filter { it.parent?.path == ":modules" || it.path == ":shared:kernel" }) {
    apply(plugin = "java-library")

    group = "com.p2picks"
    version = "0.1.0-SNAPSHOT"

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(21)
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        add("testImplementation", platform("org.junit:junit-bom:$junitVersion"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testImplementation", "org.assertj:assertj-core:$assertjVersion")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

configure(subprojects.filter { it.parent?.path == ":modules" }) {
    dependencies {
        add("api", project(":shared:kernel"))
        add("implementation", "org.springframework.modulith:spring-modulith-api:$modulithVersion")
    }
}
