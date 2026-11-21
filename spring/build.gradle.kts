plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:4.2.0")
    compileOnly("org.springframework.boot:spring-boot-sql:4.2.0")
    compileOnly("org.springframework.boot:spring-boot-jdbc:4.2.0")
    compileOnly("org.slf4j:slf4j-api:2.0.17")

    testImplementation(project(":test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc:4.2.0")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa:4.2.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test:4.2.0")
    testImplementation("org.postgresql:postgresql:42.7.14")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
