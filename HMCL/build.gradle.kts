import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jackhuang.hmcl.gradle.TerracottaConfigUpgradeTask
import org.jackhuang.hmcl.gradle.addon.FormatJsonFile
import org.jackhuang.hmcl.gradle.addon.UpdateAddonProjectInfos
import org.jackhuang.hmcl.gradle.ci.GitHubActionUtils
import org.jackhuang.hmcl.gradle.ci.JenkinsUtils
import org.jackhuang.hmcl.gradle.l10n.CheckTranslations
import org.jackhuang.hmcl.gradle.l10n.CreateLanguageList
import org.jackhuang.hmcl.gradle.l10n.CreateLocaleNamesResourceBundle
import org.jackhuang.hmcl.gradle.l10n.SyncTranslations
import org.jackhuang.hmcl.gradle.l10n.UpsideDownTranslate
import org.jackhuang.hmcl.gradle.pack.CreateDeb
import org.jackhuang.hmcl.gradle.pack.ReleaseType
import org.jackhuang.hmcl.gradle.utils.PropertiesUtils
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.shadow)
}

val projectConfig = PropertiesUtils.load(rootProject.file("config/project.properties").toPath())

val isOfficial = JenkinsUtils.IS_ON_CI || GitHubActionUtils.IS_ON_OFFICIAL_REPO

val versionType = "stable"
val saoBeta = providers.gradleProperty("saoBeta").map { it.toBooleanStrict() }.getOrElse(false)
val launcherArtifactName = if (saoBeta) "NerveGear-Launcher-Beta" else "NerveGear-Launcher"
val microsoftAuthId = System.getenv("MICROSOFT_AUTH_ID") ?: ""
val curseForgeApiKey = System.getenv("CURSEFORGE_API_KEY") ?: ""
val launcherExe = System.getenv("HMCL_LAUNCHER_EXE") ?: ""
version = providers.gradleProperty("saoVersion").getOrElse("0.1.3.1").also {
    // The launcher compares versions numerically (self-update, launcher.min); a suffix such as -rc1 would break that.
    require(it.matches(Regex("[0-9]+(\\.[0-9]+)*"))) { "saoVersion must be numeric, like 0.3.0" }
}

val embedResources = configurations.register("embedResources")

dependencies {
    implementation(project(":HMCLCore"))
    implementation(project(":HMCLBoot"))
    implementation("libs:JFoenix")
    implementation(libs.jwebp)
    implementation(libs.fxsvgimage)
    implementation(libs.java.info)
    implementation(libs.monet.fx)
    implementation(libs.nayuki.qrcodegen)
    implementation(libs.uuid.tools)
    implementation("org.jcraft:jorbis:0.0.17") // SAO title music (Ogg Vorbis), LGPL

    testImplementation(libs.jimfs)

    if (launcherExe.isBlank()) {
        implementation(libs.hmclauncher)
    }

    embedResources(libs.authlib.injector)
    embedResources(libs.lwjgl.unsafe.agent)
}

fun digest(algorithm: String, bytes: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).digest(bytes)

fun digestStream(algorithm: String, input: java.io.InputStream): ByteArray = input.use {
    val digest = MessageDigest.getInstance(algorithm)
    val buffer = ByteArray(65536)
    while (true) {
        val count = it.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest()
}

fun createChecksum(file: File) {
    val algorithms = linkedMapOf(
        "SHA-1" to "sha1",
        "SHA-256" to "sha256",
        "SHA-512" to "sha512"
    )

    algorithms.forEach { (algorithm, ext) ->
        File(file.parentFile, "${file.name}.$ext").writeText(
            digestStream(algorithm, file.inputStream()).joinToString(separator = "", postfix = "\n") { "%02x".format(it) }
        )
    }
}

fun attachSignature(jar: File) {
    val keyLocation = System.getenv("HMCL_SIGNATURE_KEY")
    if (keyLocation == null) {
        logger.warn("Missing signature key")
        return
    }

    val privatekey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(File(keyLocation).readBytes()))
    val signer = Signature.getInstance("SHA512withRSA")
    signer.initSign(privatekey)
    ZipFile(jar).use { zip ->
        zip.stream()
            .sorted(Comparator.comparing { it.name })
            .filter { it.name != "META-INF/hmcl_signature" }
            .forEach {
                signer.update(digest("SHA-512", it.name.toByteArray()))
                signer.update(digestStream("SHA-512", zip.getInputStream(it)))
            }
    }
    val signature = signer.sign()
    FileSystems.newFileSystem(URI.create("jar:" + jar.toURI()), emptyMap<String, Any>()).use { zipfs ->
        Files.newOutputStream(zipfs.getPath("META-INF/hmcl_signature")).use { it.write(signature) }
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.checkstyleMain {
    // Third-party code is not checked
    exclude("**/org/jackhuang/hmcl/ui/image/apng/**")
}

val addOpens = listOf(
    "java.base/java.lang",
    "java.base/java.lang.reflect",
    "java.base/jdk.internal.loader",
    "javafx.base/com.sun.javafx.binding",
    "javafx.base/com.sun.javafx.event",
    "javafx.base/com.sun.javafx.runtime",
    "javafx.base/javafx.beans.property",
    "javafx.graphics/javafx.css",
    "javafx.graphics/javafx.stage",
    "javafx.graphics/javafx.scene",
    "javafx.graphics/com.sun.glass.ui",
    "javafx.graphics/com.sun.javafx.stage",
    "javafx.graphics/com.sun.javafx.util",
    "javafx.graphics/com.sun.prism",
    "javafx.graphics/com.sun.javafx.font",
    "javafx.controls/com.sun.javafx.scene.control",
    "javafx.controls/com.sun.javafx.scene.control.behavior",
    "javafx.graphics/com.sun.javafx.tk.quantum",
    "javafx.controls/javafx.scene.control",
    "javafx.controls/javafx.scene.control.skin",
    "jdk.attach/sun.tools.attach",
)

tasks.compileJava {
    options.compilerArgs.addAll(addOpens.map { "--add-exports=$it=ALL-UNNAMED" })
}

// Existing UI probes inspect scene peers; compile them without starting a JavaFX toolkit.
tasks.compileTestJava {
    options.compilerArgs.addAll(listOf(
        "--add-exports=javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED",
        "--add-exports=javafx.graphics/com.sun.javafx.sg.prism=ALL-UNNAMED",
    ))
}

val hmclProperties = buildList {
    add("hmcl.version" to project.version.toString())
    add("hmcl.sao.beta" to saoBeta.toString())
    add("hmcl.add-opens" to addOpens.joinToString(" "))
    System.getenv("GITHUB_SHA")?.let {
        add("hmcl.version.hash" to it)
    }
    add("hmcl.version.type" to versionType)
    add("hmcl.microsoft.auth.id" to microsoftAuthId)
    add("hmcl.curseforge.apikey" to curseForgeApiKey)
    add("hmcl.authlib-injector.version" to libs.authlib.injector.get().version!!)
    add("hmcl.lwjgl-unsafe-agent.version" to libs.lwjgl.unsafe.agent.get().version!!)
}

val hmclPropertiesFile = layout.buildDirectory.file("hmcl.properties")
val createPropertiesFile = tasks.register("createPropertiesFile") {
    outputs.file(hmclPropertiesFile)
    hmclProperties.forEach { (k, v) -> inputs.property(k, v) }

    doLast {
        val targetFile = hmclPropertiesFile.get().asFile
        targetFile.parentFile.mkdir()
        targetFile.bufferedWriter().use {
            for ((k, v) in hmclProperties) {
                it.write("$k=$v\n")
            }
        }
    }
}

tasks.jar {
    archiveBaseName.set(launcherArtifactName)
    enabled = false
    dependsOn(tasks["shadowJar"])
}

val jarPath = tasks.jar.get().archiveFile.get().asFile

tasks.shadowJar {
    dependsOn(createPropertiesFile)

    archiveClassifier.set(null as String?)
    archiveBaseName.set(launcherArtifactName)

    exclude("**/package-info.class")
    exclude("META-INF/maven/**")

    exclude("META-INF/services/javax.imageio.spi.ImageReaderSpi")
    exclude("META-INF/services/javax.imageio.spi.ImageInputStreamSpi")

    listOf(
        "aix-*", "sunos-*", "openbsd-*", "dragonflybsd-*", "freebsd-*", "linux-*",
        "*-ppc", "*-ppc64le", "*-s390x", "*-armel",
    ).forEach { exclude("com/sun/jna/$it/**") }

    minimize {
        exclude(dependency("com.google.code.gson:.*:.*"))
        exclude(dependency("net.java.dev.jna:jna:.*"))
        exclude(dependency("libs:JFoenix:.*"))
        exclude(project(":HMCLBoot"))
    }

    manifest.attributes(
        "Created-By" to "Copyright(c) 2013-2025 huangyuhui.",
        "Implementation-Version" to project.version.toString(),
        "Main-Class" to "org.jackhuang.hmcl.Main",
        "Multi-Release" to "true",
        "Add-Opens" to addOpens.joinToString(" "),
        "Enable-Native-Access" to "ALL-UNNAMED",
        "Enable-Final-Field-Mutation" to "ALL-UNNAMED",
    )

    if (launcherExe.isNotBlank()) {
        into("assets") {
            from(file(launcherExe))
        }
    }

    doLast {
        attachSignature(jarPath)
        createChecksum(jarPath)
    }
}

// Distribution pack preparation is opt-in. Development builds contain no fabricated ready pack.
val saoPack = providers.gradleProperty("saoPack")
val saoPackOutput = layout.buildDirectory.dir("generated/saoPack")
val prepareSaoPack = tasks.register("prepareSaoPack") {
    group = "sao"
    description = "Hashes overrides and embeds all external pack dependencies, verified by SHA-1 and size."
    onlyIf { saoPack.isPresent }
    if (saoPack.isPresent) inputs.file(file(saoPack.get()))
    inputs.property("packOffline", providers.gradleProperty("saoPackOffline").getOrElse("false"))
    inputs.property("packVersion", providers.gradleProperty("saoPackVersion").getOrElse("auto"))
    inputs.property("newsUrl", providers.gradleProperty("saoNewsUrl").getOrElse(""))
    inputs.property("accountApi", providers.gradleProperty("saoAccountApi").getOrElse(""))
    inputs.property("serverNodes", providers.gradleProperty("saoServerNodes").getOrElse(""))
    inputs.property("server", providers.gradleProperty("saoServer").getOrElse(""))
    providers.gradleProperty("saoPackBaseline").orNull?.let { inputs.file(file(it).let { source -> if (source.isDirectory) source.resolve("oath08-receipt.json") else source }) }
    outputs.dir(saoPackOutput)
    doLast {
        val output = saoPackOutput.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val packFile = file(saoPack.get())
        val baselineFiles = mutableMapOf<String, File>()
        providers.gradleProperty("saoPackBaseline").orNull?.let { location ->
            val source = file(location)
            val receipt = if (source.isDirectory) source.resolve("oath08-receipt.json") else source
            val json = receipt.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
            json.getAsJsonArray("files").forEach { element ->
                val item = element.asJsonObject
                val besideReceipt = receipt.parentFile.resolve(item["path"].asString)
                baselineFiles[item.getAsJsonObject("hashes")["sha1"].asString] =
                    if (besideReceipt.isFile) besideReceipt else File(item["source"].asString)
            }
        }
        fun sha1(source: java.io.InputStream): String = source.use { input ->
            val digest = MessageDigest.getInstance("SHA-1")
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun matches(source: File, hash: String, size: Long) = source.isFile && source.length() == size && sha1(source.inputStream()) == hash
        ZipFile(packFile).use { archive ->
            val explicit = archive.getEntry("sao-manifest.json")
            val manifest: JsonObject
            if (explicit != null) {
                manifest = archive.getInputStream(explicit).reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
            } else {
                val indexEntry = archive.getEntry("modrinth.index.json") ?: throw GradleException("Missing Modrinth index")
                val index = archive.getInputStream(indexEntry).reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
                val dependencies = index.getAsJsonObject("dependencies")
                if (dependencies["minecraft"]?.asString != "1.20.1" || dependencies["neoforge"]?.asString != "47.1.106" || dependencies.has("forge"))
                    throw GradleException("Only Minecraft 1.20.1 + NeoForge 47.1.106 is supported")
                val files = sortedMapOf<String, JsonObject>()
                index.getAsJsonArray("files").forEach { element ->
                    val source = element.asJsonObject
                    if (source.has("env") && source.getAsJsonObject("env")["client"]?.asString == "unsupported") return@forEach
                    val item = JsonObject()
                    item.addProperty("path", source["path"].asString)
                    item.addProperty("sha1", source.getAsJsonObject("hashes")["sha1"].asString)
                    item.addProperty("size", source["fileSize"].asLong)
                    item.add("downloads", source.getAsJsonArray("downloads").deepCopy())
                    files[item["path"].asString] = item
                }
                archive.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("overrides/") }.forEach { entry ->
                    val path = entry.name.removePrefix("overrides/")
                    val item = JsonObject()
                    item.addProperty("path", path)
                    item.addProperty("sha1", sha1(archive.getInputStream(entry)))
                    item.addProperty("size", entry.size)
                    item.addProperty("entry", entry.name)
                    item.add("downloads", JsonArray())
                    files[path] = item
                }
                manifest = JsonObject()
                val namedVersion = Regex("""(?i)OATH\s*(\d+(?:\.\d+)+)""").find(index["name"].asString)?.groupValues?.get(1)
                manifest.addProperty("version", providers.gradleProperty("saoPackVersion").orNull ?: namedVersion ?: index["versionId"].asString)
                manifest.addProperty("minecraft", "1.20.1")
                manifest.addProperty("neoforge", "47.1.106")
                manifest.addProperty("newsUrl", providers.gradleProperty("saoNewsUrl").getOrElse(""))
                manifest.addProperty("server", providers.gradleProperty("saoServer").getOrElse(""))
                manifest.addProperty("accountApi", providers.gradleProperty("saoAccountApi").getOrElse(""))
                manifest.add("serverNodes", JsonArray().apply {
                    providers.gradleProperty("saoServerNodes").getOrElse("").split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
                })
                manifest.add("files", JsonArray().apply { files.values.forEach { add(it) } })
                manifest.add("removals", JsonArray())
            }
            if (manifest["minecraft"]?.asString != "1.20.1" || manifest["neoforge"]?.asString != "47.1.106")
                throw GradleException("Pack manifest dependencies are incompatible")
            val external = output.resolve("files").apply { mkdirs() }
            var count = 0
            manifest.getAsJsonArray("files").forEach { element ->
                val item = element.asJsonObject
                if (item.has("entry") && !item["entry"].isJsonNull) return@forEach
                val hash = item["sha1"].asString
                val size = item["size"].asLong
                if (!hash.matches(Regex("[0-9a-f]{40}")) || size < 0 || size > 2147483648L) throw GradleException("Invalid pack file identity")
                val destination = external.resolve(hash)
                val baseline = baselineFiles[hash]
                if (baseline != null && matches(baseline, hash, size)) baseline.copyTo(destination, overwrite = true)
                else {
                    if (providers.gradleProperty("saoPackOffline").getOrElse("false").toBoolean())
                        throw GradleException("Offline pack build requires verified baseline payload: " + item["path"].asString)
                    var obtained = false
                    item.getAsJsonArray("downloads").forEach download@{ url ->
                        if (obtained) return@download
                        try {
                            val uri = URI(url.asString)
                            if (uri.scheme != "https") throw GradleException("Pack downloads must use HTTPS")
                            val connection = uri.toURL().openConnection().apply { connectTimeout = 15000; readTimeout = 60000 }
                            connection.getInputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
                            obtained = matches(destination, hash, size)
                        } catch (failure: Exception) { logger.info("Trying next pack file source: {}", failure.javaClass.simpleName) }
                    }
                    if (!obtained) throw GradleException("Cannot obtain verified pack payload: " + item["path"].asString)
                }
                if (!matches(destination, hash, size)) throw GradleException("Pack checksum mismatch: " + item["path"].asString)
                count++
            }
            output.resolve("sao-manifest.json").writeText(manifest.toString(), Charsets.UTF_8)
            logger.lifecycle("Prepared complete SAO pack: {} managed files, {} external payloads, version {}", manifest.getAsJsonArray("files").size(), count, manifest["version"].asString)
        }
    }
}

tasks.processResources {
    inputs.property("saoPackEmbedded", saoPack.isPresent)
    doFirst { delete(destinationDir.resolve("assets/sao")) }
    if (saoPack.isPresent) {
        dependsOn(prepareSaoPack)
        into("assets/sao") {
            from(saoPackOutput)
            from(file(saoPack.get())) { rename { "pack.zip" } }
        }
    }
    dependsOn(createPropertiesFile)
    dependsOn(upsideDownTranslate)
    dependsOn(createLocaleNamesResourceBundle)
    dependsOn(createLanguageList)

    into("assets/") {
        from(hmclPropertiesFile)
        from(embedResources)
    }

    into("assets/lang") {
        from(createLanguageList.map { it.outputFile })
        from(upsideDownTranslate.map { it.outputFile })
        from(createLocaleNamesResourceBundle.map { it.outputDirectory })
    }

    inputs.property("terracotta_version", libs.versions.terracotta)
    doLast {
        upgradeTerracottaConfig.get().checkValid()
    }
}

fun artifactFile(ext: String) = jarPath.resolveSibling(jarPath.nameWithoutExtension + '.' + ext)

val makeExecutables = tasks.register("makeExecutables") {
    val extensions = listOf("exe", "sh")

    dependsOn(tasks.jar)

    inputs.file(jarPath)
    outputs.files(extensions.map { artifactFile(it) })

    doLast {
        val jarContent = jarPath.readBytes()

        ZipFile(jarPath).use { zipFile ->
            for (extension in extensions) {
                val output = artifactFile(extension)
                val entry = zipFile.getEntry("assets/HMCLauncher.$extension")
                    ?: throw GradleException("HMCLauncher.$extension not found")

                output.outputStream().use { outputStream ->
                    zipFile.getInputStream(entry).use { it.copyTo(outputStream) }
                    outputStream.write(jarContent)
                }

                createChecksum(output)
            }
        }
    }
}

val makeDeb = tasks.register("makeDeb", CreateDeb::class) {
    dependsOn(makeExecutables)

    val debFile = layout.file(provider { artifactFile("deb") })

    val debChannel = when (versionType) {
        "stable" -> ReleaseType.STABLE
        "dev" -> ReleaseType.DEVELOPMENT
        else -> ReleaseType.NIGHTLY
    }

    version.set(project.version.toString())
    releaseType.set(debChannel)
    launcherClassName.set("org.jackhuang.hmcl.Launcher")
    appShFile.set(layout.file(provider { artifactFile("sh") }))
    iconFile.set(layout.projectDirectory.file("image/hmcl.png"))
    outputFile.set(debFile)

    doLast {
        createChecksum(debFile.get().asFile)
    }
}

tasks.build {
    dependsOn(makeExecutables)
    dependsOn(makeDeb)
}

fun parseToolOptions(options: String?): MutableList<String> {
    if (options == null)
        return mutableListOf()

    val builder = StringBuilder()
    val result = mutableListOf<String>()

    var offset = 0

    loop@ while (offset < options.length) {
        val ch = options[offset]
        if (Character.isWhitespace(ch)) {
            if (builder.isNotEmpty()) {
                result += builder.toString()
                builder.clear()
            }

            while (offset < options.length && Character.isWhitespace(options[offset])) {
                offset++
            }

            continue@loop
        }

        if (ch == '\'' || ch == '"') {
            offset++

            while (offset < options.length) {
                val ch2 = options[offset++]
                if (ch2 != ch) {
                    builder.append(ch2)
                } else {
                    continue@loop
                }
            }

            throw GradleException("Unmatched quote in $options")
        }

        builder.append(ch)
        offset++
    }

    if (builder.isNotEmpty()) {
        result += builder.toString()
    }

    return result
}

// For IntelliJ IDEA
tasks.withType<JavaExec> {
    if (name != "run") {
        jvmArgs(addOpens.map { "--add-opens=$it=ALL-UNNAMED" })
//        if (javaVersion >= JavaVersion.VERSION_24) {
//            jvmArgs("--enable-native-access=ALL-UNNAMED")
//        }
    }
}

tasks.register<JavaExec>("run") {
    dependsOn(tasks.jar)

    group = "application"

    classpath = files(jarPath)
    workingDir = rootProject.rootDir

    val vmOptions = parseToolOptions(System.getenv("HMCL_JAVA_OPTS") ?: "-Xmx1g")
    if (vmOptions.none { it.startsWith("-Dhmcl.offline.auth.restricted=") })
        vmOptions += "-Dhmcl.offline.auth.restricted=false"

    jvmArgs(vmOptions)

    val hmclJavaHome = System.getenv("HMCL_JAVA_HOME")
    if (hmclJavaHome != null) {
        this.executable(
            file(hmclJavaHome).resolve("bin")
                .resolve(if (System.getProperty("os.name").lowercase().startsWith("windows")) "java.exe" else "java")
        )
    }

    doFirst {
        logger.quiet("HMCL_JAVA_OPTS: {}", vmOptions)
        logger.quiet("HMCL_JAVA_HOME: {}", hmclJavaHome ?: System.getProperty("java.home"))
    }
}

// terracotta

val upgradeTerracottaConfig = tasks.register<TerracottaConfigUpgradeTask>("upgradeTerracottaConfig") {
    val destination = layout.projectDirectory.file("src/main/resources/assets/terracotta.json")
    val source = layout.projectDirectory.file("terracotta-template.json");

    classifiers.set(
        listOf(
            "windows-x86_64", "windows-arm64",
            "macos-x86_64", "macos-arm64",
            "linux-x86_64", "linux-arm64", "linux-loongarch64", "linux-riscv64",
            "freebsd-x86_64"
        )
    )

    version.set(libs.versions.terracotta)
    downloadURL.set($$"https://github.com/burningtnt/Terracotta/releases/download/v${version}/terracotta-${version}-${classifier}-pkg.tar.gz")

    templateFile.set(source)
    outputFile.set(destination)
}

// Check Translations

tasks.register<CheckTranslations>("checkTranslations") {
    val dir = layout.projectDirectory.dir("src/main/resources/assets/lang")

    englishFile.set(dir.file("I18N.properties"))
    simplifiedChineseFile.set(dir.file("I18N_zh_Hans.properties"))
    traditionalChineseFile.set(dir.file("I18N_zh_Hant.properties"))
    classicalChineseFile.set(dir.file("I18N_lzh.properties"))
}

// l10n

tasks.register<SyncTranslations>("syncTranslations") {
    group = "localization"
    description = "Synchronizes translation keys and blank lines with I18N.properties."

    val dir = layout.projectDirectory.dir("src/main/resources/assets/lang")
    sourceFile.set(dir.file("I18N.properties"))
    translationFiles.from(fileTree(dir) { include("I18N_*.properties") })
}

val generatedDir = layout.buildDirectory.dir("generated")

val upsideDownTranslate = tasks.register<UpsideDownTranslate>("upsideDownTranslate") {
    inputFile.set(layout.projectDirectory.file("src/main/resources/assets/lang/I18N.properties"))
    outputFile.set(generatedDir.map { it.file("generated/i18n/I18N_en_Qabs.properties") })
}

val createLanguageList = tasks.register<CreateLanguageList>("createLanguageList") {
    resourceBundleDir.set(layout.projectDirectory.dir("src/main/resources/assets/lang"))
    resourceBundleBaseName.set("I18N")
    additionalLanguages.set(listOf("en-Qabs"))
    outputFile.set(generatedDir.map { it.file("languages.json") })
}

val createLocaleNamesResourceBundle =
    tasks.register<CreateLocaleNamesResourceBundle>("createLocaleNamesResourceBundle") {
        dependsOn(createLanguageList)

        languagesFile.set(createLanguageList.flatMap { it.outputFile })
        outputDirectory.set(generatedDir.map { it.dir("generated/LocaleNames") })
    }

// mcmod data

tasks.register<FormatJsonFile>("formatModJson") {
    file.set(layout.projectDirectory.file("mod.json"))
}

tasks.register<FormatJsonFile>("formatModpackJson") {
    file.set(layout.projectDirectory.file("modpack.json"))
}

tasks.register<UpdateAddonProjectInfos>("updateModProjectInfos") {
    inputFile.set(layout.projectDirectory.file("mod.json"))
    outputFile.set(layout.projectDirectory.file("src/main/resources/assets/addon/mod.csv"))
    projectType.set(UpdateAddonProjectInfos.AddonProjectType.MOD)
}

tasks.register<UpdateAddonProjectInfos>("updateModpackProjectInfos") {
    inputFile.set(layout.projectDirectory.file("modpack.json"))
    outputFile.set(layout.projectDirectory.file("src/main/resources/assets/addon/modpack.csv"))
    projectType.set(UpdateAddonProjectInfos.AddonProjectType.MODPACK)
}

// SAO screen: renders the screen off-screen to PNGs (no window, no focus, no sound).
tasks.register<JavaExec>("saoShots") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.jackhuang.hmcl.ui.sao.SaoScreenShotTool")
    args(layout.buildDirectory.dir("sao-shots").get().asFile.absolutePath)
    jvmArgs("-Dglass.taskbarApplication=false", "-Dsao.mute=true", "-Djava.awt.headless=true")
}

// JavaFX startup is process-global; isolate GUI fixtures from synchronous headless backend fixtures.
tasks.withType<Test>().configureEach {
    forkEvery = 1
}

// macOS aliases /var to /private/var; fixtures must not look like linked player directories.
tasks.withType<Test>().configureEach {
    val fixtureTmp = rootProject.projectDir.parentFile.resolve(rootProject.projectDir.name + "-test-fixtures").canonicalFile
    doFirst { fixtureTmp.mkdirs() }
    systemProperty("java.io.tmpdir", fixtureTmp.absolutePath)
    systemProperty("sao.test.fixtureRoot", fixtureTmp.absolutePath)
}
