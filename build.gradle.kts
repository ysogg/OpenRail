plugins {
    java
}

group = "dev.openrail"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.bluecolored.de/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    // Optional at runtime: only used when BlueMap is installed.
    compileOnly("de.bluecolored:bluemap-api:2.8.1")
}

java {
    // Paper 26.1+ requires Java 25.
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

// The jar is compiled against Paper API 26.1.2 above; plugin.yml's api-version '26.1' lets every 26.1.x and newer
// server load it. (Paper never released a plain 26.1; its first 26.1 builds are 26.1.1.) Each other supported API
// is checked by compiling the same sources against it, as part of `./gradlew build` (or run one alone, e.g.
// `./gradlew compileAgainstPaper26_3`).
val otherPaperApis = mapOf(
    "26.1.1" to "26.1.1.build.+",
    "26.2" to "26.2.build.+",
    "26.3" to "26.3.build.+",
)

otherPaperApis.forEach { (name, version) ->
    val id = name.replace('.', '_')
    val apiClasspath = configurations.create("paperApi$id") {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }
    dependencies {
        add(apiClasspath.name, "io.papermc.paper:paper-api:$version")
        add(apiClasspath.name, "de.bluecolored:bluemap-api:2.8.1")
    }
    val compileCheck = tasks.register<JavaCompile>("compileAgainstPaper$id") {
        description = "Compiles the plugin against the Paper $name API."
        group = "verification"
        source = sourceSets.main.get().java
        classpath = apiClasspath
        destinationDirectory = layout.buildDirectory.dir("paper-api-check/$name")
        javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }
        options.compilerArgs.add("-Xlint:deprecation")
    }
    tasks.check { dependsOn(compileCheck) }
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}
