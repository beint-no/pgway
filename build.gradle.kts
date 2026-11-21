import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    base
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

allprojects {
    group = "no.beint.pgway"
    version = providers.gradleProperty("pgwayVersion").get()
}

tasks.register("printReleaseVersion") {
    val releaseVersion = version.toString()
    doLast { println(releaseVersion) }
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(27)
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release = 27
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed", "skipped")
                showStandardStreams = false
            }
            if (providers.environmentVariable("CI").map(String::toBoolean).getOrElse(false)) {
                outputs.cacheIf("Verify against the PostgreSQL 19 server installed on this runner") { false }
                outputs.upToDateWhen { false }
            }
        }
        tasks.withType<Jar>().configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            from(rootProject.file("LICENSE")) { into("META-INF") }
        }
        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).apply {
                addStringOption("Xdoclint:all,-missing", "-quiet")
                addBooleanOption("notimestamp", true)
            }
        }
    }

    if (name in setOf("core", "spring")) {
        plugins.withId("java-library") {
            apply(plugin = "com.vanniktech.maven.publish")
            extensions.configure<MavenPublishBaseExtension> {
                publishToMavenCentral()
                if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                    signAllPublications()
                }
                configure(
                    JavaLibrary(
                        javadocJar = JavadocJar.Javadoc(),
                        sourcesJar = SourcesJar.Sources(),
                    ),
                )
                pom {
                    name.set("Pgway ${project.name}")
                    description.set(
                        when (project.name) {
                            "core" -> "Zero-dependency PostgreSQL 19+ schema migration engine for JDK 27."
                            "spring" -> "Spring Boot 4 auto-configuration for Pgway PostgreSQL 19+ migrations."
                            else -> "Modern PostgreSQL 19+ schema migrations for JDK 27."
                        },
                    )
                    inceptionYear.set("2026")
                    url.set("https://github.com/beint-no/pgway")
                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("beint-no")
                            name.set("Beint")
                            url.set("https://github.com/beint-no")
                        }
                    }
                    scm {
                        url.set("https://github.com/beint-no/pgway")
                        connection.set("scm:git:https://github.com/beint-no/pgway.git")
                        developerConnection.set("scm:git:ssh://git@github.com/beint-no/pgway.git")
                    }
                }
            }
            plugins.withId("maven-publish") {
                extensions.configure<PublishingExtension> {
                    repositories {
                        maven {
                            name = "release"
                            url = uri(rootProject.layout.buildDirectory.dir("repository"))
                        }
                    }
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
}

tasks.register<Zip>("releaseBundle") {
    dependsOn(
        ":core:publishAllPublicationsToReleaseRepository",
        ":spring:publishAllPublicationsToReleaseRepository",
    )
    from(layout.buildDirectory.dir("repository"))
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("pgway-${project.version}-maven.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
