plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }

dependencies {
    // Pure-Java LZMA2/xz. Neither the JDK nor Android ships xz support, and
    // GSIs are commonly distributed as .img.xz. Exposed as `api` so the Android
    // module resolves it on its compile classpath as well as at runtime.
    api("org.tukaani:xz:1.10")
}
