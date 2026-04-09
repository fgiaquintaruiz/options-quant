plugins {
    id("java")
    alias(libs.plugins.spring.boot)
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
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.4"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)

    // IBKR TWS API (local JAR)
    implementation(files("libs/TwsApi.jar"))
    // TwsApi depends on protobuf
    implementation("com.google.protobuf:protobuf-java:4.34.1")

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
    // Exclude legacy code from compilation (missing TA4J etc. dependencies)
    exclude("com/fgiaquinta/optionsquant/legacy/**")
}

tasks.bootJar {
    mainClass = "com.fgiaquinta.optionsquant.OptionsQuantApplication"
}
