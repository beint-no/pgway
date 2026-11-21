plugins {
    `java-library`
}

dependencies {
    // Zero runtime dependencies. Callers supply pgjdbc and their DataSource/pool.
    testImplementation(project(":test-support"))
    testImplementation("org.postgresql:postgresql:42.7.14")
    testImplementation("com.zaxxer:HikariCP:7.1.0")
    testImplementation("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
