import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.github.arjenvanharen"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.2.3")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        changeNotes = changeNotes(project.version.toString())
        ideaVersion {
            sinceBuild = "261"
        }
    }
}

/** The CHANGELOG.md section of [version] as HTML, for the plugin's change notes. */
fun changeNotes(version: String): Provider<String> =
    providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText.map { text ->
        val section = text.substringAfter("## [$version]", "").substringAfter('\n').substringBefore("\n## [")
        // Fold indented continuation lines into the bullet above, then render headings and bullet lists.
        val blocks = ArrayList<String>()
        for (line in section.lines()) {
            when {
                line.isBlank() -> {}
                line.startsWith(" ") && blocks.isNotEmpty() -> blocks[blocks.size - 1] += " " + line.trim()
                else -> blocks += line.trim()
            }
        }
        val html = StringBuilder()
        for ((i, block) in blocks.withIndex()) {
            val bullet = block.startsWith("- ")
            if (bullet && blocks.getOrNull(i - 1)?.startsWith("- ") != true) html.append("<ul>")
            html.append(
                when {
                    block.startsWith("### ") -> "<h3>${block.removePrefix("### ")}</h3>"
                    bullet -> "<li>${block.removePrefix("- ")}</li>"
                    else -> "<p>$block</p>"
                },
            )
            if (bullet && blocks.getOrNull(i + 1)?.startsWith("- ") != true) html.append("</ul>")
        }
        html.toString().replace(Regex("`([^`]*)`"), "<code>$1</code>")
    }

// ./gradlew bumpVersion -Ppart=major|minor|patch (default patch): raises pluginVersion in gradle.properties.
val bumpVersion by tasks.registering {
    group = "versioning"
    description = "Raises pluginVersion in gradle.properties (-Ppart=major|minor|patch)."
    val propsFile = layout.projectDirectory.file("gradle.properties").asFile
    val part = providers.gradleProperty("part").orElse("patch")
    doLast {
        val text = propsFile.readText()
        val current = Regex("""(?m)^pluginVersion=(\d+)\.(\d+)\.(\d+)$""").find(text)
            ?: throw GradleException("pluginVersion=MAJOR.MINOR.PATCH not found in gradle.properties")
        val (major, minor, patch) = current.destructured.toList().map { it.toInt() }
        val next = when (part.get()) {
            "major" -> "${major + 1}.0.0"
            "minor" -> "$major.${minor + 1}.0"
            "patch" -> "$major.$minor.${patch + 1}"
            else -> throw GradleException("-Ppart must be major, minor or patch")
        }
        propsFile.writeText(text.replaceRange(current.range, "pluginVersion=$next"))
        println("pluginVersion ${current.value.substringAfter('=')} -> $next. Add a [$next] section to CHANGELOG.md.")
    }
}

// ./gradlew bup: builds the plugin zip and copies it into bup/ (kept in git, one zip per version).
val bup by tasks.registering(Copy::class) {
    group = "versioning"
    description = "Builds the plugin zip and copies it into bup/."
    from(tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile })
    into(layout.projectDirectory.dir("bup"))
}
