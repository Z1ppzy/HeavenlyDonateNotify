plugins {
    java
    id("com.gradleup.shadow") version "9.4.1"
}

group = "org.heavenlyweiner"
version = "1.0.0"

// Рабочий прокси: Velocity 4.2.x на Java 25 (velocity-api 4.2.0 собран под class file 69).
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val velocityApi = "com.velocitypowered:velocity-api:4.2.0"
val luckPermsApi = "net.luckperms:api:5.4"
val adventure = "4.26.1"

dependencies {
    compileOnly(velocityApi)
    annotationProcessor(velocityApi)
    compileOnly(luckPermsApi)
    compileOnly("net.kyori:adventure-text-minimessage:$adventure")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0") { exclude(group = "org.slf4j") }
    implementation("org.yaml:snakeyaml:2.7")

    testImplementation(velocityApi)
    testImplementation(luckPermsApi)
    testImplementation("net.kyori:adventure-text-minimessage:$adventure")
    testImplementation("net.kyori:adventure-text-serializer-plain:$adventure")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-this-escape"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.yaml.snakeyaml", "org.heavenlyweiner.donatenotify.libs.snakeyaml")
    mergeServiceFiles()
    exclude("META-INF/maven/**", "META-INF/versions/9/module-info.class", "module-info.class")
}
tasks.build { dependsOn(tasks.shadowJar) }

extra["rollingJarTask"] = "shadowJar"
extra["rollingJarName"] = "HeavenlyDonateNotify.jar"
apply(from = "gradle/rolling-jar.gradle")
