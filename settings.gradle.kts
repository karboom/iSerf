rootProject.name = "iSlogger"

dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
}

pluginManagement {
    repositories {

        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }

        gradlePluginPortal()
    }
}