plugins {
    id("java")
}

group = "me.karboom.java"
version = "1.0-SNAPSHOT"


dependencies {
    implementation("com.openai:openai-java:4.6.1")

    implementation("io.projectreactor:reactor-core:3.8.0-RC1")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")
//
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.projectreactor:reactor-test:3.8.0-RC1")
}

tasks.test {
    useJUnitPlatform()
}