plugins {
    id("java")
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.owasp.dependency.check)
    id("com.google.protobuf") version "0.9.4" apply false
}

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

tasks.test {
    useJUnitPlatform {
        // slow: full backtest HTTP; e2e: Playwright + Spring (update UI assertions in e2eTest)
        excludeTags("slow", "e2e")
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
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform {
        includeTags("slow")
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
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform {
        includeTags("e2e")
        excludeTags("slow")
    }
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

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
