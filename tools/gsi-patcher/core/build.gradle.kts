plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }

dependencies {
    // Pure-Java LZMA2/xz. Neither the JDK nor Android ships xz support, and
    // GSIs are commonly distributed as .img.xz. Exposed as `api` so the Android
    // module resolves it on its compile classpath as well as at runtime.
    api("org.tukaani:xz:1.10")
    // 7z container reading (real extraction, not just a "not supported"
    // message) and bzip2 decompression (needed for payload.bin's REPLACE_BZ
    // operation type). Pure Java, no native code -- fine on Android.
    api("org.apache.commons:commons-compress:1.26.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.test {
    useJUnitPlatform()
    // Until these existed, every regression was found by flashing. The golden
    // tests need things that do not belong in the repository -- a
    // multi-gigabyte GSI, this device's vendor policy, a dumped expdb -- so each
    // is opt-in through an environment variable and skipped without it.
    maxHeapSize = "2g"
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
