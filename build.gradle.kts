plugins {
    java
    application
    alias(libs.plugins.shadow)
}

group = "org.projects"
version = "0.0.1-SNAPSHOT"
description = "spring-context-mcp"

repositories {
    mavenCentral()
}

dependencies {
    // Static analysis of the target Spring codebase
    implementation(libs.javaparser.symbol.solver)

    // JSON-RPC envelopes and tool payloads. The MCP stdio transport is implemented
    // directly in ServerMain rather than via an SDK.
    implementation(libs.jackson.databind)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "org.projects.springcontextmcp.ServerMain"
}

tasks.test {
    useJUnitPlatform()
    systemProperty("petclinic.path", providers.gradleProperty("target").orNull ?: "")
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.shadowJar {
    // The MCP server is launched by an agent host as a subprocess over stdio,
    // so it has to be a single self-contained jar with a stable name.
    archiveBaseName = "spring-context-mcp"
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()
}

// `gradle build` should produce the runnable artifact, not just the thin jar.
tasks.build {
    dependsOn(tasks.shadowJar)
}

// Convenience: index a project and print stats without going through MCP.
// Sanity-check the scanner before wiring any protocol on top of it.
//
// The property is `target`, not `project` - the latter collides with Gradle's
// own Project object and silently resolves to it instead of your -P value.
tasks.register<JavaExec>("indexStats") {
    group = "verification"
    description = "Build the index for a Spring project and print counts. -Ptarget=/path/to/repo"
    mainClass = "org.projects.springcontextmcp.IndexCli"
    classpath = sourceSets["main"].runtimeClasspath

    val target = providers.gradleProperty("target")
    val path = providers.gradleProperty("path")
    argumentProviders.add(CommandLineArgumentProvider {
        listOfNotNull(
            target.orNull ?: throw GradleException("missing -Ptarget=/abs/path/to/spring-project"),
            path.orNull
        )
    })
}