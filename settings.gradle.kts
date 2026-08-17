plugins {
    // Lets Gradle download a JDK when the requested toolchain isn't installed.
    // Matters for the Daytona sandbox image: without it, every image needs the
    // exact JDK preinstalled or the build fails before it starts.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "spring-context-mcp"