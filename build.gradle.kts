plugins {
    id("java")
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.owasp.dependency.check)
}

group = "com.fgiaquinta.optionsquant"
version = "2.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.spring.io/release")
    flatDir {
        dirs("libs")
    }
}

dependencies {
    // Spring Boot 4
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.5"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    developmentOnly(libs.spring.boot.devtools)
    // IBKR TWS API (local JAR)
    implementation(files("libs/TwsApi.jar"))
    // TwsApi depends on protobuf
    implementation("com.google.protobuf:protobuf-java:4.34.1")

    // Guava (for RateLimiter)
    implementation("com.google.guava:guava:33.4.8-jre")

    // Jackson for JSON serialization (used by BacktestCli)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
    implementation(libs.jackson.jsr310)

    // Lombok
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // SLF4J (provided by spring-boot-starter)
    implementation(libs.slf4j.api)

    // TA4J (Technical Analysis for Java - used by strategies)
    implementation("org.ta4j:ta4j-core:0.16")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.playwright)
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-g"))
    // Exclude legacy-reference code from compilation (preserved for migration reference only)
    exclude("com/fgiaquinta/optionsquant/legacy-reference/**")
}

tasks.bootJar {
    mainClass = "com.fgiaquinta.optionsquant.OptionsQuantApplication"
}

// ===== Frontend Build Tasks =====

val npmInstall by tasks.registering(Exec::class) {
    workingDir = file("frontend")
    commandLine = if (System.getProperty("os.name").lowercase().contains("win")) {
        listOf("cmd", "/c", "npm", "install")
    } else {
        listOf("npm", "install")
    }
    // Only run if node_modules doesn't exist
    onlyIf { !file("frontend/node_modules").exists() }
}

// Build frontend task (manual execution or via buildJar)
val buildFrontend by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the React frontend and copies it to static resources"
    workingDir = file("frontend")
    commandLine = if (System.getProperty("os.name").lowercase().contains("win")) {
        listOf("cmd", "/c", "npm", "run", "build")
    } else {
        listOf("npm", "run", "build")
    }
    dependsOn(npmInstall)
}

// Ensure frontend is built when creating a JAR for production
tasks.bootJar {
    dependsOn(buildFrontend)
}

// NOTE: To run separately during development:
// 1. Backend: ./gradlew bootRun
// 2. Frontend: cd frontend && npm run dev

dependencyCheck {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "JSON")
    analyzers.assemblyEnabled = false
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
