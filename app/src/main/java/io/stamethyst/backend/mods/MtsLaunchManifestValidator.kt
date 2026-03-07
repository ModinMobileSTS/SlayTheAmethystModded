package io.stamethyst.backend.mods

import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.zip.ZipFile

internal object MtsLaunchManifestValidator {
    private const val ROOT_MANIFEST_ENTRY = "ModTheSpire.json"
    private const val REQUIRED_ID_FIELD = "ID"

    internal data class ValidationFailure(
        val reason: String
    )

    fun validateModJar(jarFile: File?): ValidationFailure? {
        if (jarFile == null || !jarFile.isFile) {
            return ValidationFailure("mod 文件不存在")
        }

        return try {
            ZipFile(jarFile).use { zipFile ->
                val manifestEntry = zipFile.getEntry(ROOT_MANIFEST_ENTRY)
                    ?: return ValidationFailure("jar 根目录缺少 ModTheSpire.json")
                if (manifestEntry.isDirectory) {
                    return ValidationFailure("jar 根目录的 ModTheSpire.json 不是文件")
                }

                val rawJson = JarFileIoUtils.readEntry(zipFile, manifestEntry)
                val root = JSONTokener(rawJson).nextValue()
                val manifest = root as? JSONObject
                    ?: return ValidationFailure("根目录 ModTheSpire.json 不是有效的 JSON 对象")

                val rawId = manifest.optString(REQUIRED_ID_FIELD, "").trim()
                if (rawId.isEmpty()) {
                    return ValidationFailure("根目录 ModTheSpire.json 缺少 ModTheSpire 所需的 ID 字段")
                }

                null
            }
        } catch (_: Throwable) {
            ValidationFailure("读取根目录 ModTheSpire.json 失败")
        }
    }
}
