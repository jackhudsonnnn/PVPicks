subprojects {
    if (parent?.path == ":modules") {
        apply(plugin = "java-library")

        group = "com.p2picks"
        version = "0.1.0-SNAPSHOT"

        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(21)
        }

        repositories {
            mavenCentral()
        }

        val modulithVersion: String by project
        dependencies {
            add("api", project(":shared:kernel"))
            add("implementation", "org.springframework.modulith:spring-modulith-api:$modulithVersion")
        }
    }
}
