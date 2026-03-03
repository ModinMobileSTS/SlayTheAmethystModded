import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import java.io.File

fun Project.runCommand(cmd: String, defaultValue: String = ""): String {
    val output = providers.exec {
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("windows")) {
            commandLine("cmd", "/c", cmd)
        } else {
            commandLine("sh", "-c", cmd)
        }
    }
    return if (output.result.get().exitValue == 0) {
        output.standardOutput.asText.get().trim()
    } else {
        defaultValue
    }
}

fun Project.readGradleProperty(name: String, defaultValue: String = ""): String =
    providers.gradleProperty(name)
        .orElse(defaultValue)
        .get()

val Project.steamJars: SteamJarsExtension
    get() = extensions.getByType<SteamJarsExtension>()

fun Project.desktopJar(): File = steamJars.desktopJar()

fun Project.modJar(id: String, name: String): File = steamJars.modJar(id, name)
