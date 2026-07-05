import net.darkhax.curseforgegradle.Constants
import net.darkhax.curseforgegradle.TaskPublishCurseForge

plugins {
    java
    alias(libs.plugins.indra.git)
    alias(libs.plugins.indra.spotlessLicenser)
    alias(libs.plugins.loom)
    alias(libs.plugins.versions)
    alias(libs.plugins.curseForgeGradle)
    alias(libs.plugins.minotaur)
    alias(libs.plugins.publishGithubRelease)
}

base {
    archivesName = "WorldEditCUI"
}

architectury {
    platformSetupLoomIde()
    neoForge()
}

loom {
    accessWidenerPath.set(project.file("src/main/resources/worldeditcui.accesswidener"))
}

dependencies {
    "neoForge"(libs.neoforge)
    "implementation"(libs.cuiProtocol.common)
    "implementation"(libs.cuiProtocol.neoforge)
}

val targetJavaVersion: String by project

// Releasing
val changelogContents = objects.property(String::class)
changelogContents.set(providers.gradleProperty("changelog")
    .map { file(it) }
    .filter { it.exists() }
    .map { it.readText(Charsets.UTF_8) })
changelogContents.finalizeValueOnRead()
val versionName = project.provider { project.version }

val cfApiToken = providers.gradleProperty("cfApiToken")
val modrinthToken = providers.gradleProperty("modrinthToken")
    .orElse(providers.environmentVariable("MODRINTH_TOKEN"))
val githubToken = providers.gradleProperty("githubToken")
    .orElse(providers.environmentVariable("GITHUB_TOKEN"))

tasks {
    val validateRelease by registering {
        inputs.property("changelog", changelogContents).optional(true)
        inputs.property("version", versionName)

        doLast {
            val problems = mutableListOf<String>()
            // General release-readiness
            if (indraGit.headTag() == null) {
                problems.add("Tried to perform a release without being checked out to a tag")
            }
            if (versionName.get().toString().contains("SNAPSHOT")) {
                problems.add("SNAPSHOT versions of WorldEditCUI cannot be published")
            }
            if (!changelogContents.isPresent) {
                problems.add("A file with changelog text must be provided using the 'changelog' Gradle property")
            }

            // CF
            if (!cfApiToken.isPresent) {
                problems.add("No CurseForge API token was set with the 'cfApiToken' Gradle property")
            }

            // MR
            if (!modrinthToken.isPresent) {
                problems.add("No Modrinth access token was set with either the 'modrinthToken' Gradle property, or 'MODRINTH_TOKEN' environment variable")
            }

            // GH
            if (!githubToken.isPresent) {
                problems.add("No GitHub access token was set with either the 'githubToken' Gradle property, or 'GITHUB_TOKEN' environment variable")
            }

            when (problems.size) {
                0 -> return@doLast
                1 -> {
                    throw InvalidUserDataException(problems[0])
                }
                else -> throw InvalidUserDataException(
                    "WorldEditCUI detected the following problems when trying to perform a release:\n\n"
                            + problems.joinToString("\n - ", prefix = " - ")
                )
            }
        }
    }

    val publishToCurseForge by registering(TaskPublishCurseForge::class) {
        val cfProjectId = providers.gradleProperty("cfProjectId")

        apiToken = cfApiToken.get()

        with(upload(cfProjectId.get(), jar)) {
            displayName = project.version
            releaseType = Constants.RELEASE_TYPE_RELEASE
            changelog = changelogContents.getOrElse("")
            // Rendering plugins
            addOptional("canvas-renderer", "sodium", "irisshaders")
            // Config screens, version compatibility
            addOptional("modmenu", "viafabricplus", "worldedit")
            addJavaVersion("Java $targetJavaVersion")
            addGameVersion(libs.versions.minecraft.get())
        }
    }

    val releaseTasks = listOf(publishToCurseForge, publishToGitHub, modrinth)
    releaseTasks.forEach {
        it.configure { dependsOn(validateRelease) }
    }

    register("publishRelease") {
        group = PublishingPlugin.PUBLISH_TASK_GROUP
        dependsOn(releaseTasks)
    }
}

modrinth {
    token = modrinthToken
    projectId = "worldedit-cui"
    syncBodyFrom = providers.provider { file("README.md").readText(Charsets.UTF_8) }
    uploadFile.set(tasks.jar)
    gameVersions.add(libs.versions.minecraft.get())
    changelog = changelogContents
    dependencies {
        optional.project("canvas")
        optional.project("sodium")
        optional.project("iris")
        // Config screens, version compatibility
        optional.project("modmenu")
        optional.project("viafabricplus")
        optional.project("worldedit")
    }
}

githubRelease {
    apiToken = githubToken
    tagName = project.provider {
        indraGit.headTag()?.run { org.eclipse.jgit.lib.Repository.shortenRefName(name) }
    }
    repository = "EngineHub/WorldEditCUI"
    releaseName = "WorldEditCUI v$version"
    releaseBody = changelogContents
    artifacts.from(tasks.jar)
}
