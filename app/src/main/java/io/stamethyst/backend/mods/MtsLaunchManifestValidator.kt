package io.stamethyst.backend.mods

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

internal object MtsLaunchManifestValidator {
    private const val ROOT_MANIFEST_ENTRY = "ModTheSpire.json"
    private const val REQUIRED_MOD_ID_FIELD = "modid"
    private val OPTIONAL_PRIMITIVE_FIELDS = arrayOf(
        "name",
        "credits",
        "description",
        "version",
        "mts_version",
        "sts_version",
        "update_json"
    )
    private val OPTIONAL_ARRAY_FIELDS = arrayOf(
        "author_list",
        "dependencies",
        "optional_dependencies"
    )

    internal data class ValidationFailure(
        val reason: String
    )

    private data class ValidationResult(
        val modId: String? = null,
        val failure: ValidationFailure? = null
    )

    fun validateModJar(jarFile: File?): ValidationFailure? {
        return inspectModJar(jarFile).failure
    }

    @Throws(IOException::class)
    fun resolveLaunchModId(jarFile: File?): String {
        val result = inspectModJar(jarFile)
        val failure = result.failure
        if (failure != null) {
            throw IOException(failure.reason)
        }
        val modId = result.modId?.trim().orEmpty()
        if (modId.isEmpty()) {
            throw IOException("根目录 ModTheSpire.json 缺少 ModTheSpire 所需的 modid 字段")
        }
        return modId
    }

    private fun inspectModJar(jarFile: File?): ValidationResult {
        if (jarFile == null || !jarFile.isFile) {
            return ValidationResult(failure = ValidationFailure("mod 文件不存在"))
        }

        return try {
            ZipFile(jarFile).use { zipFile ->
                val manifestEntry = zipFile.getEntry(ROOT_MANIFEST_ENTRY)
                    ?: return ValidationResult(failure = ValidationFailure("jar 根目录缺少 ModTheSpire.json"))
                if (manifestEntry.isDirectory) {
                    return ValidationResult(failure = ValidationFailure("jar 根目录的 ModTheSpire.json 不是文件"))
                }

                val rawJson = JarFileIoUtils.readEntry(zipFile, manifestEntry)
                val root = JSONTokener(rawJson).nextValue()
                val manifest = root as? JSONObject
                    ?: return ValidationResult(failure = ValidationFailure("根目录 ModTheSpire.json 不是有效的 JSON 对象"))

                val modId = readRequiredModId(manifest) ?: return ValidationResult(
                    failure = ValidationFailure("根目录 ModTheSpire.json 缺少 ModTheSpire 所需的 modid 字段")
                )
                if (modId.isEmpty()) {
                    return ValidationResult(failure = ValidationFailure("根目录 ModTheSpire.json 的 modid 不能为空"))
                }

                OPTIONAL_PRIMITIVE_FIELDS.forEach { key ->
                    validatePrimitiveField(manifest, key)?.let { return ValidationResult(failure = it) }
                }
                OPTIONAL_ARRAY_FIELDS.forEach { key ->
                    validatePrimitiveArrayField(manifest, key)?.let { return ValidationResult(failure = it) }
                }

                ValidationResult(modId = modId)
            }
        } catch (_: Throwable) {
            ValidationResult(failure = ValidationFailure("读取根目录 ModTheSpire.json 失败"))
        }
    }

    private fun readRequiredModId(manifest: JSONObject): String? {
        if (!manifest.has(REQUIRED_MOD_ID_FIELD) || manifest.isNull(REQUIRED_MOD_ID_FIELD)) {
            return null
        }
        val value = manifest.opt(REQUIRED_MOD_ID_FIELD)
        if (!isPrimitiveJsonValue(value)) {
            return null
        }
        return value?.toString()?.trim()
    }

    private fun validatePrimitiveField(manifest: JSONObject, key: String): ValidationFailure? {
        if (!manifest.has(key) || manifest.isNull(key)) {
            return null
        }
        return if (isPrimitiveJsonValue(manifest.opt(key))) {
            null
        } else {
            ValidationFailure("根目录 ModTheSpire.json 的 $key 字段必须是字符串、数字或布尔值")
        }
    }

    private fun validatePrimitiveArrayField(manifest: JSONObject, key: String): ValidationFailure? {
        if (!manifest.has(key) || manifest.isNull(key)) {
            return null
        }
        val value = manifest.opt(key)
        val array = value as? JSONArray
            ?: return ValidationFailure("根目录 ModTheSpire.json 的 $key 字段必须是数组")
        for (index in 0 until array.length()) {
            val item = array.opt(index)
            if (item == null || item === JSONObject.NULL) {
                continue
            }
            if (!isPrimitiveJsonValue(item)) {
                return ValidationFailure(
                    "根目录 ModTheSpire.json 的 $key 数组元素必须是字符串、数字或布尔值"
                )
            }
        }
        return null
    }

    private fun isPrimitiveJsonValue(value: Any?): Boolean {
        if (value == null || value === JSONObject.NULL) {
            return false
        }
        return value !is JSONObject && value !is JSONArray
    }
}
