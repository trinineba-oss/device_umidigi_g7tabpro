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
}
