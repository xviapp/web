import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Pinned to AndroidLibXrayLite ea96a7f9c33d6e18021386db96bf95680e853c93. The release AAR also ships
// full GeoIP/GeoSite data under assets/; the host packages libv2ray-core.aar without it, so the
// trimmed data from prepareGeoData is the only copy in the APK.
val prepareXray by tasks.registering {
    val target = layout.projectDirectory.file("libs/libv2ray.aar").asFile
    val expected = "cf71680b776b9ca583747ba652f816b047a655eab875d8951e6141636d88bbd6"
    val classes = layout.projectDirectory.file("libs/libv2ray.jar").asFile
    val core = layout.projectDirectory.file("libs/libv2ray-core.aar").asFile
    outputs.files(target, classes, core)
    fun digest(file: File) = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    outputs.upToDateWhen { target.exists() && classes.exists() && core.exists() && digest(target) == expected }
    doLast {
        target.parentFile.mkdirs()
        val temporary = File(target.parentFile, "libv2ray.download")
        try {
            if (!target.exists() || digest(target) != expected) {
                URI("https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.9.30/libv2ray.aar")
                    .toURL().openConnection().apply { connectTimeout = 30000; readTimeout = 120000 }
                    .getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(digest(temporary) == expected) { "Xray SHA-256 mismatch" }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            ZipFile(target).use { zip ->
                zip.getInputStream(zip.getEntry("classes.jar")).use { input -> classes.outputStream().use { input.copyTo(it) } }
                ZipOutputStream(core.outputStream()).use { output ->
                    for (entry in zip.entries()) {
                        if (entry.name.startsWith("assets/")) continue
                        output.putNextEntry(ZipEntry(entry.name))
                        if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
        } finally { temporary.delete() }
    }
}

// GeoIP/GeoSite: Loyalsoldier/v2ray-rules-dat 202610052207. The full files are cached in libs/geodata;
// the APK carries only the categories the bundled routing presets use and a few common ones (27 MB → 3 MB).
// A person can still download the full data from the configuration UI for custom rules.
val geoRelease = "202610052207"
val geoChecksums = mapOf(
    "geoip.dat" to "391b522361c52804e486a98b53d97f3d9c1d3e4e4217bb34954774a0b456b9fd",
    "geosite.dat" to "f574a55e779133682583ca8e8fd052f2a70d9d010431a5579cf1a6c93ef6e910"
)
val geoipKeep = listOf("CN", "PRIVATE", "TELEGRAM", "TWITTER", "NETFLIX", "GOOGLE", "FASTLY", "FACEBOOK", "CLOUDFLARE", "CLOUDFRONT")
val geositeKeep = listOf(
    "CN", "PRIVATE", "GOOGLE", "GFW", "GREATFIRE", "GEOLOCATION-CN", "GEOLOCATION-!CN",
    "APPLE", "MICROSOFT", "TLD-CN", "TLD-!CN"
)

// A .dat file is a protobuf list whose entries (field 1) start with their code (field 1).
// Copy whole entries whose code is kept; fail if a kept code disappeared upstream.
fun filterGeoData(source: File, target: File, keep: List<String>) {
    val bytes = source.readBytes()
    fun varint(position: IntArray): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = bytes[position[0]++].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }
    val output = ByteArrayOutputStream()
    val found = mutableSetOf<String>()
    val cursor = intArrayOf(0)
    while (cursor[0] < bytes.size) {
        val start = cursor[0]
        check(varint(cursor) == 10L) { "Unexpected field in ${source.name}" }
        val end = varint(cursor).toInt() + cursor[0]
        val inner = intArrayOf(cursor[0])
        check(varint(inner) == 10L) { "Entry without a code in ${source.name}" }
        val codeLength = varint(inner).toInt()
        val code = String(bytes, inner[0], codeLength, Charsets.UTF_8).uppercase()
        if (code in keep) {
            output.write(bytes, start, end - start)
            found += code
        }
        cursor[0] = end
    }
    check(found.containsAll(keep)) { "${source.name} lacks ${keep - found}" }
    target.writeBytes(output.toByteArray())
}

val prepareGeoData by tasks.registering {
    val cache = layout.projectDirectory.dir("libs/geodata").asFile
    val assets = layout.projectDirectory.dir("src/main/assets")
    inputs.property("release", geoRelease)
    inputs.property("checksums", geoChecksums)
    inputs.property("geoip", geoipKeep)
    inputs.property("geosite", geositeKeep)
    outputs.files(assets.file("geoip.dat"), assets.file("geosite.dat"), assets.file("geoip-only-cn-private.dat"))
    doLast {
        fun digest(file: File) = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        cache.mkdirs()
        geoChecksums.forEach { (name, expected) ->
            val target = File(cache, name)
            if (target.exists() && digest(target) == expected) return@forEach
            // Download beside the cache and move into place only once verified.
            val temporary = File(cache, "$name.download")
            try {
                URI("https://github.com/Loyalsoldier/v2ray-rules-dat/releases/download/$geoRelease/$name")
                    .toURL().openConnection().apply { connectTimeout = 30000; readTimeout = 120000 }
                    .getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(digest(temporary) == expected) { "Geodata SHA-256 mismatch: $name" }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
        assets.asFile.mkdirs()
        filterGeoData(File(cache, "geoip.dat"), assets.file("geoip.dat").asFile, geoipKeep)
        filterGeoData(File(cache, "geosite.dat"), assets.file("geosite.dat").asFile, geositeKeep)
        // Routing rewrites geoip:cn/private to this file (see CoreConfigManager.appendRoutingUserRule).
        filterGeoData(File(cache, "geoip.dat"), assets.file("geoip-only-cn-private.dat").asFile, listOf("CN", "PRIVATE"))
    }
}

// v2rayNG snapshot (see README.md); update both when refreshing the source.
val sourceVersion = "2.3.10"
val sourceCommit = "a4dd3d08de80958701247b00a8a979131192d1b4"

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "APPLICATION_ID", "\"app.xvi.web\"")
        // The upstream snapshot this module is built from (shown in the host's About page).
        buildConfigField("String", "SOURCE_VERSION", "\"$sourceVersion\"")
        buildConfigField("String", "SOURCE_COMMIT", "\"$sourceCommit\"")
        buildConfigField("String", "VERSION_NAME", "\"$sourceVersion-embedded\"")
        buildConfigField("String", "DISTRIBUTION", "\"Embedded\"")
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { buildConfig = true; compose = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Core Libraries
    compileOnly(files("libs/libv2ray.jar").builtBy(prepareXray))
    testImplementation(files("libs/libv2ray.jar").builtBy(prepareXray))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose Libraries
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Share QR codes (generation only)
    implementation(libs.core) // zxing core

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)

    // Reorderable list
    implementation(libs.reorderable)

    // Testing Libraries
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

tasks.named("preBuild") { dependsOn(prepareGeoData) }
