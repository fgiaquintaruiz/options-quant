plugins {
    id("java")
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.owasp.dependency.check)
    id("com.google.protobuf") version "0.9.4" apply false
}

import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

group = "com.fgiaquinta.optionsquant"
version = "2.0.0-SNAPSHOT"

// Note: Protobuf code generation is handled separately via protoc command line
// The proto files are in src/main/proto/ and can be compiled manually if needed

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
    implementation(libs.spring.boot.starter.security)
    developmentOnly(libs.spring.boot.devtools)
    // IBKR TWS API (local JAR)
    implementation(files("libs/TwsApi.jar"))
    // TwsApi depends on protobuf
    implementation("com.google.protobuf:protobuf-java:4.34.1")
    // gRPC dependencies
    implementation("io.grpc:grpc-netty-shaded:1.59.0")
    implementation("io.grpc:grpc-protobuf:1.59.0")
    implementation("io.grpc:grpc-stub:1.59.0")
    // Protobuf code generation
    implementation("com.google.protobuf:protobuf-java-util:4.34.1")

    // Guava (for RateLimiter and caches)
    implementation("com.google.guava:guava:33.4.0-jre")

    // SQLite persistence layer
    implementation("org.xerial:sqlite-jdbc:3.47.0.0")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

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
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.playwright)
}

tasks.test {
    useJUnitPlatform {
        // slow: full backtest HTTP; e2e: Playwright + Spring (update UI assertions in e2eTest)
        // tws-paper: real TWS/Gateway session (run: ./gradlew twsTest -DrunTwsTests=true)
        excludeTags("slow", "e2e", "tws-paper")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

/** Full backtest / heavy HTTP tests tagged with @Tag("slow"). Run: ./gradlew slowTest */
tasks.register<Test>("slowTest") {
    group = "verification"
    description = "Runs JUnit tests tagged @Tag(\"slow\") (e.g. full parallel backtest)"
    dependsOn("buildFrontend")
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform {
        includeTags("slow")
        excludeTags("tws-paper")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

/** Playwright + Spring boot tests (excludes @Tag(\"slow\") full backtest). Run: ./gradlew buildFrontend e2eTest */
tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "JUnit @Tag(\"e2e\") only; excludes @Tag(\"slow\"). For HTTP backtest stress use: ./gradlew slowTest"
    dependsOn("buildFrontend")
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform {
        includeTags("e2e")
        excludeTags("slow", "tws-paper")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

/** Real TWS / IB Gateway (paper): requires logged-in session. Run: ./gradlew twsTest -DrunTwsTests=true */
tasks.register<Test>("twsTest") {
    group = "verification"
    description = "JUnit @Tag(\"tws-paper\") only; fails fast if TWS not logged in. Requires -DrunTwsTests=true"
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform {
        includeTags("tws-paper")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    systemProperty("runTwsTests", "true")
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
//
// Full stack (Spring + React estático + FastAPI analytics + Streamlit, Windows):
//   ./gradlew startFullStack
// o: start-full-stack.bat  |  .\scripts\start-full-stack.ps1

tasks.register("startFullStack") {
    group = "application"
    description =
        "Builds frontend, then opens 3 terminals: Spring Boot :9090, Python analytics :8001, Streamlit :8501 (Windows PowerShell)."
    dependsOn(buildFrontend)
    doLast {
        val script = file("scripts/start-full-stack.ps1")
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            throw GradleException(
                "startFullStack solo abre ventanas en Windows. En Linux/macOS ejecutá manualmente: " +
                    "./gradlew bootRun; luego en python/ uvicorn y streamlit (ver comentarios en scripts/start-full-stack.ps1)."
            )
        }
        if (!script.isFile) {
            throw GradleException("No se encontró ${script.absolutePath}")
        }
        val exit = ProcessBuilder(
            listOf(
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.absolutePath,
                "-SkipFrontendBuild"
            )
        )
            .directory(project.rootDir)
            .inheritIO()
            .start()
            .waitFor()
        if (exit != 0) {
            throw GradleException("start-full-stack.ps1 terminó con código $exit")
        }
    }
}

dependencyCheck {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "JSON")
    analyzers.assemblyEnabled = false
}

tasks.withType<Test>().configureEach {
    val testTask = this
    configure<JacocoTaskExtension> {
        destinationFile = layout.buildDirectory.file("jacoco/${testTask.name}.exec").get().asFile
    }
}

/**
 * Classes excluded from JaCoCo HTML/XML totals: bootstrap, DTOs/records, interactive CLI,
 * IBKR plumbing, config property holders, and grid DTO records (logic stays in services).
 * See `docs/COVERAGE-CONFIG.md`.
 */
private val jacocoClassExcludes = listOf(
    "**/OptionsQuantApplication.class",
    "**/com/fgiaquinta/optionsquant/dto/**",
    "**/com/fgiaquinta/optionsquant/cli/**",
    "**/com/fgiaquinta/optionsquant/infrastructure/**",
    "**/com/fgiaquinta/optionsquant/config/IbkrProperties.class",
    "**/com/fgiaquinta/optionsquant/config/GridSearchProperties.class",
    "**/com/fgiaquinta/optionsquant/config/ScannerProperties.class",
    "**/com/fgiaquinta/optionsquant/backtest/grid/GridAxis.class",
    "**/com/fgiaquinta/optionsquant/backtest/grid/GridSearchRequest.class",
    "**/com/fgiaquinta/optionsquant/backtest/grid/GridSearchResult.class",
    "**/com/fgiaquinta/optionsquant/backtest/grid/GridCellResult.class",
    "**/com/fgiaquinta/optionsquant/backtest/grid/WalkForwardFoldResult.class",
    "**/com/fgiaquinta/optionsquant/backtest/grid/WalkForwardOosSummary.class",
    "**/com/fgiaquinta/optionsquant/backtest/grid/GridOptimizationSummary.class",
    "**/com/fgiaquinta/optionsquant/backtest/grid/PromoteRiskRequest.class",
    "**/com/fgiaquinta/optionsquant/backtest/grid/PromoteResult.class",
    // Service layer: IBKR, scanning, I/O — validated by e2e / manual; not a line-coverage target
    "**/com/fgiaquinta/optionsquant/service/**",
    // Backtest runtime engine + domain models (heavy; exercised by slow/e2e)
    "**/com/fgiaquinta/optionsquant/backtest/engine/**",
    "**/com/fgiaquinta/optionsquant/backtest/domain/**",
    "**/com/fgiaquinta/optionsquant/domain/**",
    "**/com/fgiaquinta/optionsquant/trading/**",
    "**/com/fgiaquinta/optionsquant/config/StartupInitializer.class",
    "**/com/fgiaquinta/optionsquant/config/ChartController.class",
    // WebConfig anonymous PathResourceResolver (SPA fallback) — covered by Playwright, not unit-tested
    "**/com/fgiaquinta/optionsquant/config/WebConfig$*.class",
    // REST controllers (WebMvc/e2e); unit JaCoCo often shows 0% — exclude from line targets
    "**/com/fgiaquinta/optionsquant/controller/**",
    "**/com/fgiaquinta/optionsquant/CandleDownloader.class",
)

/** Main bytecode tree for JaCoCo (same rules for merged + unit-only reports). */
fun Project.jacocoMainClassDirectories(): FileCollection =
    files(
        sourceSets.named("main").get().output.classesDirs.map { dir ->
            fileTree(dir) {
                exclude(jacocoClassExcludes)
                exclude {
                    val path = it.path.replace('\\', '/')
                    path.contains("com/fgiaquinta/optionsquant/strategy/") &&
                        !path.contains("strategy/utils/") &&
                        !path.contains("strategy/model/") &&
                        !path.contains("strategy/data/") &&
                        !path.contains("strategy/indicator/")
                }
            }
        }
    )

tasks.jacocoTestReport {
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) {
            include("*.exec")
        }
    )
    classDirectories.setFrom(project.jacocoMainClassDirectories())
    sourceDirectories.setFrom(sourceSets.named("main").get().allJava.sourceDirectories)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

/**
 * JaCoCo HTML/XML from **unit tests only** (`build/jacoco/test.exec`), without merging e2e/slow.
 * Output: `build/reports/jacoco-unit/html`, `build/reports/jacoco-unit/jacoco.xml`.
 */
tasks.register<JacocoReport>("jacocoUnitOnlyReport") {
    group = "verification"
    description = "JaCoCo report from :test only (no e2e/slow .exec merge)."
    dependsOn(tasks.test)
    executionData.setFrom(layout.buildDirectory.file("jacoco/test.exec"))
    classDirectories.setFrom(project.jacocoMainClassDirectories())
    sourceDirectories.setFrom(sourceSets.named("main").get().allJava.sourceDirectories)
    reports {
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco-unit/html"))
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco-unit/jacoco.xml"))
    }
}

/** Runs `:test` then `:jacocoUnitOnlyReport` (fast JVM coverage for local/CI). */
tasks.register("unitCoverageReport") {
    group = "verification"
    dependsOn(tasks.named("jacocoUnitOnlyReport"))
}

/**
 * Runs all JVM test suites (unit + e2e + slow), then merges JaCoCo data from every `*.exec` under `build/jacoco/`.
 * HTML/XML: `build/reports/jacoco/test/`. Use for CI nocturno; `./gradlew jacocoTestReport` alone solo fusiona lo ya generado.
 * GitHub Actions: `.github/workflows/nightly-coverage.yml` (schedule + manual).
 */
tasks.register("coverageReport") {
    group = "verification"
    dependsOn(tasks.test, tasks.named("e2eTest"), tasks.named("slowTest"))
    finalizedBy(tasks.named("jacocoTestReport"))
}

/** Playwright Java: download Chromium once per CI runner (before e2eTest / coverageReport). */
tasks.register<JavaExec>("installPlaywrightBrowsers") {
    group = "verification"
    description = "Runs com.microsoft.playwright.CLI install chromium (e2e browser binaries)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    args("install", "chromium")
}

private val isWindows: Boolean
    get() = System.getProperty("os.name").lowercase().contains("win")

/** Vitest + v8 coverage for `frontend/src` → `build/reports/coverage-frontend/`. */
val frontendCoverage by tasks.registering(Exec::class) {
    group = "verification"
    description = "npm run test:coverage (Vitest + v8) in frontend/"
    workingDir = file("frontend")
    dependsOn(npmInstall)
    commandLine = if (isWindows) {
        listOf("cmd", "/c", "npm", "run", "test:coverage")
    } else {
        listOf("npm", "run", "test:coverage")
    }
}

/** pytest-cov for `python/analytics_service` → `build/reports/coverage-python/html`. */
val pythonCoverage by tasks.registering(Exec::class) {
    group = "verification"
    description = "pytest --cov analytics_service (engine + FastAPI tests; see python/.coveragerc)"
    workingDir = file("python")
    commandLine = if (isWindows) {
        listOf(
            "cmd", "/c", "py", "-m", "pytest",
            "analytics_service",
            "--cov=analytics_service",
            "--cov-config=.coveragerc",
            "--cov-report=html:../build/reports/coverage-python/html",
            "--cov-report=term-missing",
        )
    } else {
        listOf(
            "python3", "-m", "pytest",
            "analytics_service",
            "--cov=analytics_service",
            "--cov-config=.coveragerc",
            "--cov-report=html:../build/reports/coverage-python/html",
            "--cov-report=term-missing",
        )
    }
}

/**
 * JVM (`unitCoverageReport`: unit tests + JaCoCo unit-only) + frontend Vitest + Python pytest-cov.
 * Reports: `build/reports/jacoco-unit/html`, `coverage-frontend`, `coverage-python/html`.
 * For merged JVM (e2e + slow + unit) use `./gradlew coverageReport` (long).
 * See `docs/COVERAGE-CONFIG.md`.
 */
tasks.register("fullStackCoverage") {
    group = "verification"
    description =
        "unitCoverageReport (Java unit + jacoco-unit) + frontendCoverage + pythonCoverage. Fast full-stack check."
    dependsOn(tasks.named("unitCoverageReport"), frontendCoverage, pythonCoverage)
}

/** JVM merged (test+e2e+slow) + Vitest + Python — puede tardar mucho (slow backtest masivo). */
tasks.register("fullStackCoverageMerged") {
    group = "verification"
    description =
        "coverageReport (merged JaCoCo) + frontendCoverage + pythonCoverage. Long-running."
    dependsOn(tasks.named("coverageReport"), frontendCoverage, pythonCoverage)
}
