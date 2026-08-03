plugins {
    id("com.google.protobuf").version("0.9.4")
    id("me.champeau.jmh").version("0.7.2")
}

// https://docs.gradle.org/current/userguide/java_testing.html#ex-disable-publishing-of-test-fixtures-variants
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }

dependencies {
    api("io.netty:netty-common")
    api("io.netty:netty-buffer")
    api("io.netty:netty-transport")
    implementation("io.netty:netty-handler")
    implementation("io.netty:netty-codec-http")
    implementation("io.netty:netty-codec-protobuf")
    implementation("io.netty:netty-transport-classes-epoll")
    implementation("io.netty.incubator:netty-incubator-codec-classes-quic:0.0.76.Final-SNAPSHOT")
    implementation("io.netty.incubator:netty-incubator-codec-native-quic:0.0.76.Final-SNAPSHOT")
    api("io.netty.incubator:netty-incubator-codec-native-quic:0.0.76.Final-SNAPSHOT:${nativeClassifier()}")
    implementation("io.netty:netty-tcnative-boringssl-static::linux-x86_64")
    implementation("io.netty:netty-tcnative-boringssl-static::linux-aarch_64")
    implementation("io.netty:netty-tcnative-boringssl-static::osx-x86_64")
    implementation("io.netty:netty-tcnative-boringssl-static::osx-aarch_64")
    implementation("io.netty:netty-tcnative-boringssl-static::windows-x86_64")

    api("com.google.protobuf:protobuf-java")

    implementation("com.github.multiformats:java-multibase")
    implementation("tech.pegasys:noise-java")

    implementation("org.bouncycastle:bcprov-jdk18on")
    implementation("org.bouncycastle:bcpkix-jdk18on")

    testImplementation(project(":tools:schedulers"))

    testFixturesApi("org.apache.logging.log4j:log4j-core")
    testFixturesImplementation(project(":tools:schedulers"))
    testFixturesImplementation("io.netty:netty-transport-classes-epoll")
    testFixturesImplementation("io.netty:netty-handler")
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api")

    jmhImplementation(project(":tools:schedulers"))
    jmhImplementation("org.openjdk.jmh:jmh-core")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess")
}

fun nativeClassifier(): String {
    val os = System.getProperty("os.name").lowercase()
    val archRaw = System.getProperty("os.arch").lowercase()
    val arch = when (archRaw) {
        "aarch64", "arm64" -> "aarch_64"
        "x86_64", "amd64" -> "x86_64"
        else -> archRaw
    }
    val osPart = when {
        os.contains("mac") || os.contains("darwin") -> "osx"
        os.contains("linux") -> "linux"
        os.contains("win") -> "windows"
        else -> error("Unsupported OS for incubator QUIC native classifier: $os")
    }
    return "$osPart-$arch"
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc"
    }

    tasks["clean"].doFirst { delete(generatedFilesBaseDir) }

    idea {
        module {
            sourceDirs.add(file("$generatedFilesBaseDir/main/java"))
        }
    }
}
