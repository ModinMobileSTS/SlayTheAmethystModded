import java.io.File
import kotlin.LazyThreadSafetyMode
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

open class SteamJarsExtension(private val project: Project) {
    private val workshopContentRoot = "content/646570"
    private val steamAppsDir: File by lazy(LazyThreadSafetyMode.NONE) { resolveSteamAppsDir() }
    private val desktopJarFile: File by lazy(LazyThreadSafetyMode.NONE) { resolveDesktopJar() }

    fun desktopJar(): File = desktopJarFile

    fun modJar(id: String, name: String): File {
        val jarFile = workshopDir()
            .resolve(workshopContentRoot)
            .resolve(id)
            .resolve(name)
        if (!jarFile.isFile) {
            throw GradleException(
                "Missing mod jar: ${jarFile.absolutePath}. " +
                    "Please ensure workshop mod $id provides $name."
            )
        }
        return jarFile
    }

    private fun resolveDesktopJar(): File {
        val steamStsDir = slayTheSpireDir()
        val directJar = steamStsDir.resolve("desktop-1.0.jar")
        if (directJar.isFile) {
            return directJar
        }

        val fallbackJar = project
            .fileTree(steamStsDir) { include("**/desktop-1.0.jar") }
            .files.minByOrNull { it.absolutePath }
        return fallbackJar
            ?: throw GradleException("Missing desktop-1.0.jar. Expected at ${directJar.absolutePath}")
    }

    private fun slayTheSpireDir(): File {
        val directory = steamAppsDir.resolve("common/SlayTheSpire")
        if (!directory.isDirectory) {
            throw GradleException(
                "Missing SlayTheSpire directory: ${directory.absolutePath}. " +
                    "Expected STEAM_PATH to resolve to a Steam root or steamapps directory."
            )
        }
        return directory
    }

    private fun workshopDir(): File {
        val directory = steamAppsDir.resolve("workshop")
        if (!directory.isDirectory) {
            throw GradleException(
                "Missing workshop directory: ${directory.absolutePath}. " +
                    "Required to resolve workshop mod jars."
            )
        }
        return directory
    }

    private fun resolveSteamAppsDir(): File {
        val steamAppsPath = readSteamPath()
        val configuredDir = project.file(steamAppsPath)
        if (!configuredDir.isDirectory) {
            throw GradleException("STEAM_PATH is not a directory: $steamAppsPath")
        }

        val candidates = linkedSetOf(
            configuredDir,
            configuredDir.resolve("steamapps")
        )
        val resolved = candidates.firstOrNull { candidate ->
            candidate.isDirectory &&
                (candidate.resolve("common").isDirectory || candidate.resolve("workshop").isDirectory)
        } ?: candidates.firstOrNull { candidate ->
            candidate.isDirectory && candidate.name.equals("steamapps", ignoreCase = true)
        }
        if (resolved != null) {
            return resolved
        }

        throw GradleException(
            "Invalid STEAM_PATH: $steamAppsPath. " +
                "Expected Steam root (contains steamapps/) or steamapps dir."
        )
    }

    private fun readSteamPath(): String {
        val configuredPath = project.providers.environmentVariable("STEAM_PATH")
            .orElse(project.providers.gradleProperty("steam.path"))
            .orNull
            ?.trim()
            .orEmpty()
        if (configuredPath.isEmpty()) {
            throw GradleException(
                "Missing STEAM_PATH. Examples: " +
                    "macOS export STEAM_PATH='/Users/username/Library/Application Support/Steam/steamapps/'; " +
                    "Linux export STEAM_PATH='~/.local/share/Steam/steamapps/'; " +
                    "Windows PowerShell setx STEAM_PATH 'C:/Program Files (x86)/Steam/steamapps/'"
            )
        }
        val unquotedPath = configuredPath.removeSurrounding("\"").removeSurrounding("'")
        return expandHome(unquotedPath)
    }

    private fun expandHome(path: String): String {
        if (!path.startsWith("~")) {
            return path
        }
        val home = System.getProperty("user.home").orEmpty()
        return when {
            path == "~" -> home
            path.startsWith("~/") || path.startsWith("~\\") -> home + path.substring(1)
            else -> path
        }
    }
}

@Suppress("unused")
class SteamPathPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("steamJars", SteamJarsExtension::class.java, project)
    }
}
