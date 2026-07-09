import org.jetbrains.gradle.ext.settings
import org.jetbrains.gradle.ext.taskTriggers

plugins {
    id("java-library")
    id("eclipse")
    alias(libs.plugins.idea.ext)
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.velocity.api)
    compileOnly(libs.luckperms.api)
    compileOnly(libs.proxysettings)

    implementation(libs.boosted.yaml)
    implementation(libs.caffeine)
    implementation(libs.gson)
    implementation(libs.craftkit.redis)
    implementation(libs.cloud.velocity)
    implementation(libs.cloud.minecraft.extras)

    testImplementation(libs.velocity.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    annotationProcessor(libs.velocity.api)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.shadowJar {
    destinationDirectory.set(layout.projectDirectory.dir("target"))
    archiveClassifier.set("")
    mergeServiceFiles()
    exclude("INFO_BIN", "INFO_SRC", "README")

    dependencies {
        exclude(dependency("org.slf4j:slf4j-api:.*"))
    }

    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    relocate("com.hera.craftkit", "com.stephanofer.partysystem.libs.craftkit")
    relocate("dev.dejvokep.boostedyaml", "com.stephanofer.partysystem.libs.boostedyaml")
    relocate("org.incendo.cloud", "com.stephanofer.partysystem.libs.cloud")
    relocate("io.leangen.geantyref", "com.stephanofer.partysystem.libs.geantyref")
    relocate("com.github.benmanes.caffeine", "com.stephanofer.partysystem.libs.caffeine")
    relocate("com.google.gson", "com.stephanofer.partysystem.libs.gson")
    relocate("io.lettuce", "com.stephanofer.partysystem.libs.lettuce")
    relocate("redis.clients.authentication", "com.stephanofer.partysystem.libs.redis_authx")
    relocate("reactor", "com.stephanofer.partysystem.libs.reactor")
    relocate("org.reactivestreams", "com.stephanofer.partysystem.libs.reactivestreams")
    relocate("io.netty", "com.stephanofer.partysystem.libs.netty")
}

tasks.jar {
    destinationDirectory.set(layout.projectDirectory.dir("target"))
}

tasks.test {
    useJUnitPlatform()
}

val templateSource = file("src/main/templates")
val templateDest = layout.buildDirectory.dir("generated/sources/templates")
val generateTemplates = tasks.register<Copy>("generateTemplates") {
    val props = mapOf("version" to project.version)
    inputs.properties(props)

    from(templateSource)
    into(templateDest)
    expand(props)
}

sourceSets.main.configure { java.srcDir(generateTemplates.map { it.outputs }) }

project.idea.project.settings.taskTriggers.afterSync(generateTemplates)
project.eclipse.synchronizationTasks(generateTemplates)
