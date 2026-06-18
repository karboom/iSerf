plugins {
    id("java-library")
    id("com.vanniktech.maven.publish") version "0.36.0"
    id("org.graalvm.buildtools.native") version "0.11.4"
    id("com.gradleup.shadow") version "9.4.1"
    id ("io.freefair.lombok") version "9.5.0"
}

group = "io.github.karboom"
version = "0.75.23-alpha"


dependencies {

    api("io.projectreactor:reactor-core:3.8.0-RC1")

    api("tools.jackson.core:jackson-databind:3.0.4")
    api("tools.jackson.dataformat:jackson-dataformat-cbor:3.0.4")
    api("tools.jackson.dataformat:jackson-dataformat-yaml:3.0.4")
    // Source: https://mvnrepository.com/artifact/tools.jackson.module/jackson-module-blackbird
    api("tools.jackson.module:jackson-module-blackbird:3.0.0")

    api("com.squareup.okhttp3:okhttp:5.3.2")
    api("com.squareup.okhttp3:okhttp-sse:5.3.2")


    api("com.github.victools:jsonschema-generator:4.38.0")
    api("com.github.victools:jsonschema-module-jackson:4.38.0")


    api("com.openai:openai-java:4.6.1")
    api("io.modelcontextprotocol.sdk:mcp:0.14.1")
    api("io.modelcontextprotocol.sdk:mcp-spring-webflux:0.14.1")
    api("io.modelcontextprotocol.sdk:mcp-spring-webmvc:0.14.1")

    api("com.corundumstudio.socketio:netty-socketio:2.0.13")
    api("io.socket:socket.io-client:2.1.1")

    api("com.hivemq:hivemq-mqtt-client:1.3.3")
    api("com.hivemq:hivemq-community-edition-embedded:2025.5")

    api("io.vertx:vertx-core:5.0.4")
    api("io.vertx:vertx-web:5.0.4")

    api("com.cronutils:cron-utils:9.2.1")

    api("com.knuddels:jtokkit:1.1.0")

    // Apache PDFBox for PDF processing
    api("org.apache.pdfbox:pdfbox:3.0.4")

    api("io.lettuce:lettuce-core:7.4.0.RELEASE")

    api("org.apache.pulsar:pulsar-client:4.1.3")

    api("cn.hutool:hutool-all:5.8.43")


    api("io.milvus:milvus-sdk-java:2.6.6")

    api("org.apache.commons:commons-math3:3.6.1")

    api("net.bramp.ffmpeg:ffmpeg:0.8.0")

    api("org.jooq:jooq:3.20.0")
    api("org.postgresql:postgresql:42.7.5")

    api("org.openjdk.jol:jol-core:0.17")

    api("com.github.oshi:oshi-core:6.6.5")


//    implementation("org.projectlombok:lombok:1.18.42")
//    annotationProcessor("org.projectlombok:lombok:1.18.42")


//    testCompileOnly("org.projectlombok:lombok:1.18.42")
//    testAnnotationProcessor("org.projectlombok:lombok:1.18.42")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.projectreactor:reactor-test:3.8.0-RC1")
    testImplementation("tech.tablesaw:tablesaw-core:0.44.4")

    testImplementation("ch.qos.logback:logback-classic:1.4.14")
}


mavenPublishing {
//    publishToMavenCentral(SonatypeHost.DEFAULT)
//    // or when publishing to https://s01.oss.sonatype.org
//    publishToMavenCentral(SonatypeHost.S01)
//    // or when publishing to https://central.sonatype.com/
    publishToMavenCentral(automaticRelease = true)

//    configure(JavaLibrary(
//        // configures the -javadoc artifact, possible values:
//        // - `JavadocJar.None()` don't publish this artifact
//        // - `JavadocJar.Empty()` publish an emprt jar
//        // - `JavadocJar.Javadoc()` to publish standard javadocs
//        javadocJar = JavadocJar.Javadoc(),
//        // whether to publish a sources jar
//        sourcesJar = true,
//    ))

    signAllPublications()

    coordinates("io.github.karboom", "iSerf", version.toString())

    pom {
        name.set("iSerf")
        description.set("iSerf 是一个基于事件流构建的智能Agent框架，采用响应式编程模型，支持创建高交互性的应用系统。该框架设计用于支持大规模Agent协作，并利用Reactor模式提供高性能的数据处理能力。")
        inceptionYear.set("2026")
        url.set("https://github.com/karboom/iSerf")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("karboom")
                name.set("karboom")
                url.set("https://github.com/karboom/")
            }
        }
        scm {
            url.set("https://github.com/karboom/iSerf")
            connection.set("scm:git:git://github.com/karboom/iSerf.git")
            developerConnection.set("scm:git:ssh://git@github.com/karboom/iSerf.git")
        }
    }
}

tasks.shadowJar {
    isZip64 = true
}

tasks.withType<Javadoc> {
    exclude("**/*.json")
    enabled = false
}

tasks.test {
    useJUnitPlatform()

    jvmArgs(
        "-Xmx6g",
        "-XX:MaxDirectMemorySize=4g"
    )
}
