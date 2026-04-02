plugins {
    kotlin("jvm")
    application
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.example.transactiongenerator.TransactionGeneratorApplicationKt")
}

dependencies {
    implementation(kotlin("stdlib"))
}
