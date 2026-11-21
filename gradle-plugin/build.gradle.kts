plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        create("pgway") {
            id = "no.beint.pgway"
            implementationClass = "no.beint.pgway.gradle.PgwayPlugin"
            displayName = "Pgway"
            description = "Verifies Pgway PostgreSQL 19+ SQL migrations at build time."
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("org.postgresql:postgresql:42.7.14")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
