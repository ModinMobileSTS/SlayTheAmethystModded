package io.stamethyst.backend.mods

import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.io.File

data class AgentPatchPreflightResult(
    val valid: Boolean,
    val patchClassCount: Int,
    val patchAnnotationCount: Int,
    /** Classes carrying `@SpireInitializer`, the entry point of a non-patch (content/resource) mod. */
    val patchInitializerCount: Int,
    val issues: List<String>,
    val warnings: List<String>,
)

/** Bytecode-level ModTheSpire preflight for compiled AI patch classes. */
object AgentPatchPreflight {
    private const val MAX_ISSUES = 50
    private const val SPIRE_INITIALIZER = "Lcom/evacipated/cardcrawl/modthespire/lib/SpireInitializer;"
    private const val SPIRE_PATCH = "Lcom/evacipated/cardcrawl/modthespire/lib/SpirePatch;"
    private const val SPIRE_PATCH2 = "Lcom/evacipated/cardcrawl/modthespire/lib/SpirePatch2;"
    private const val SPIRE_PATCHES = "Lcom/evacipated/cardcrawl/modthespire/lib/SpirePatches;"
    private const val SPIRE_PATCHES2 = "Lcom/evacipated/cardcrawl/modthespire/lib/SpirePatches2;"
    private const val SPIRE_PREFIX_PATCH = "Lcom/evacipated/cardcrawl/modthespire/lib/SpirePrefixPatch;"
    private const val SPIRE_POSTFIX_PATCH = "Lcom/evacipated/cardcrawl/modthespire/lib/SpirePostfixPatch;"
    private const val SPIRE_INSERT_PATCH = "Lcom/evacipated/cardcrawl/modthespire/lib/SpireInsertPatch;"
    private const val SPIRE_INSTRUMENT_PATCH = "Lcom/evacipated/cardcrawl/modthespire/lib/SpireInstrumentPatch;"
    private const val SPIRE_RAW_PATCH = "Lcom/evacipated/cardcrawl/modthespire/lib/SpireRawPatch;"
    private const val SPIRE_RETURN = "com.evacipated.cardcrawl.modthespire.lib.SpireReturn"

    fun validate(
        patchRoot: File,
        classpath: List<File>,
    ): AgentPatchPreflightResult {
        val classFiles = patchRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("class", ignoreCase = true) }
            .sortedBy { it.absolutePath }
            .toList()
        val issues = ArrayList<String>()
        val warnings = ArrayList<String>()
        var annotationCount = 0
        var initializerCount = 0
        val lookup = TargetLookup(classpath)

        classFiles.forEach { classFile ->
            val node = runCatching { AgentPatchTargetInspector.readClassNode(classFile.readBytes()) }
                .getOrElse { error ->
                    issues += "${classFile.relativeTo(patchRoot)}: invalid class file (${error.message ?: "parse failed"})"
                    return@forEach
                }
            val specs = patchAnnotations(node)
            annotationCount += specs.size
            if (specs.isEmpty() && isSpireInitializer(node)) {
                // A non-patch mod (content registration or resource pack) may register everything
                // through a single @SpireInitializer entry point instead of @SpirePatch hooks.
                initializerCount++
                validateInitializer(
                    node = node,
                    location = classFile.relativeTo(patchRoot).invariantSeparatorsPath,
                    issues = issues,
                    warnings = warnings,
                )
            }
            specs.forEach { spec ->
                validateSpec(
                    classFile = classFile,
                    patchRoot = patchRoot,
                    patchClassNode = node,
                    spec = spec,
                    lookup = lookup,
                    issues = issues,
                    warnings = warnings,
                )
            }
        }

        if (annotationCount == 0 && initializerCount == 0) {
            warnings += "No @SpirePatch or @SpireInitializer entry point found; packaging a resource or helper-only patch mod."
        }

        return AgentPatchPreflightResult(
            valid = issues.isEmpty(),
            patchClassCount = classFiles.size,
            patchAnnotationCount = annotationCount,
            patchInitializerCount = initializerCount,
            issues = issues.take(MAX_ISSUES),
            warnings = warnings.take(MAX_ISSUES),
        )
    }

    private fun isSpireInitializer(node: ClassNode): Boolean =
        (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty())
            .any { it.desc == SPIRE_INITIALIZER }

    /**
     * ModTheSpire reflects on each `@SpireInitializer` class and calls `initialize()` with no
     * arguments, so the hook must be a no-arg static `void` method. A missing or non-static method
     * makes ModTheSpire print a warning and silently skip the mod, so it is rejected here.
     */
    private fun validateInitializer(
        node: ClassNode,
        location: String,
        issues: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        if (node.access and (org.objectweb.asm.Opcodes.ACC_INTERFACE or org.objectweb.asm.Opcodes.ACC_ANNOTATION) != 0) {
            issues += "$location: @SpireInitializer must be on a concrete class, not an interface or annotation"
            return
        }
        val initialize = node.methods.orEmpty().firstOrNull { it.name == "initialize" && it.desc == "()V" }
        if (initialize == null) {
            issues += "$location: @SpireInitializer requires a no-arg void initialize() method"
            return
        }
        if (initialize.access and org.objectweb.asm.Opcodes.ACC_STATIC == 0) {
            issues += "$location: @SpireInitializer initialize() must be static"
        }
        if (initialize.access and org.objectweb.asm.Opcodes.ACC_PUBLIC == 0) {
            warnings += "$location: @SpireInitializer initialize() is not public; ModTheSpire may not call it"
        }
    }

    private fun validateSpec(
        classFile: File,
        patchRoot: File,
        patchClassNode: ClassNode,
        spec: PatchSpec,
        lookup: TargetLookup,
        issues: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val location = classFile.relativeTo(patchRoot).invariantSeparatorsPath
        val targetClass = spec.targetClassName
        if (targetClass.isBlank()) {
            issues += "$location: patch annotation must set clz or cls."
            return
        }
        val targetNode = lookup.read(targetClass)
        if (targetNode == null) {
            issues += "$location: target class not found on the compile classpath: $targetClass"
            return
        }
        val member = AgentPatchTargetInspector.normalizeMemberName(spec.memberName)
        if (member == "<class>") return
        val candidates = targetNode.methods.orEmpty().filter { method ->
            when (member) {
                "<ctor>" -> method.name == "<init>"
                "<staticinit>" -> method.name == "<clinit>"
                else -> method.name == member
            }
        }
        if (candidates.isEmpty()) {
            issues += "$location: target member not found: $targetClass#$member"
            return
        }

        val typeInfo = resolveParameterTypes(spec)
        if (typeInfo.conflict) {
            issues += "$location: paramtypez and paramtypes disagree for $targetClass#$member"
            return
        }
        if (!typeInfo.explicit) {
            if (candidates.size > 1) {
                issues += "$location: $targetClass#$member has ${candidates.size} overloads; declare exact paramtypez or paramtypes"
            } else {
                warnings += "$location: $targetClass#$member has one overload but the patch does not record an explicit parameter signature"
            }
            return
        }

        val matching = candidates.filter { method ->
            val actual = org.objectweb.asm.Type.getArgumentTypes(method.desc)
                .map { it.className.replace('$', '.') }
            actual == typeInfo.types
        }
        when {
            matching.isEmpty() -> issues +=
                "$location: no target overload matches (${typeInfo.types.joinToString(", ")}) for $targetClass#$member"
            matching.size > 1 -> issues +=
                "$location: parameter signature is still ambiguous for $targetClass#$member"
        }
        if (matching.size == 1) {
            validateHookMethods(
                patchClassNode = patchClassNode,
                targetClassNode = targetNode,
                targetClass = targetClass,
                targetMethod = matching.single(),
                member = member,
                location = location,
                issues = issues,
                warnings = warnings,
            )
        }
    }

    private fun validateHookMethods(
        patchClassNode: ClassNode,
        targetClassNode: ClassNode,
        targetClass: String,
        targetMethod: MethodNode,
        member: String,
        location: String,
        issues: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val hooks = patchClassNode.methods.orEmpty().flatMap { method ->
            val annotations = method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty()
            val annotatedKind = when {
                annotations.any { it.desc == SPIRE_PREFIX_PATCH } -> "prefix"
                annotations.any { it.desc == SPIRE_POSTFIX_PATCH } -> "postfix"
                method.name == "Prefix" -> "prefix"
                method.name == "Postfix" -> "postfix"
                else -> null
            }
            annotatedKind?.let { listOf(HookMethod(it, method)) }.orEmpty()
        }
        if (hooks.isEmpty()) {
            val advancedHooks = patchClassNode.methods.orEmpty().mapNotNull { method ->
                val annotations = method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty()
                val kind = when {
                    annotations.any { it.desc == SPIRE_INSERT_PATCH } -> "insert"
                    annotations.any { it.desc == SPIRE_INSTRUMENT_PATCH } -> "instrument"
                    annotations.any { it.desc == SPIRE_RAW_PATCH } -> "raw"
                    else -> null
                }
                kind?.let { HookMethod(it, method) }
            }
            if (advancedHooks.isEmpty()) {
                issues += "$location: patch class has no Prefix/Postfix/Insert/Instrument/Raw hook method"
            } else {
                advancedHooks.forEach { hook ->
                    if (hook.method.access and org.objectweb.asm.Opcodes.ACC_STATIC == 0) {
                        issues += "$location: ${hook.kind} hook ${hook.method.name}${hook.method.desc} must be static"
                    }
                    warnings += "$location: ${hook.kind} hook is only structurally checked; run a game smoke test before enabling"
                }
            }
            return
        }
        hooks.forEach { hook ->
            validateHook(
                hook = hook,
                targetClassNode = targetClassNode,
                targetClass = AgentPatchTargetInspector.normalizeClassName(targetClass),
                targetMethod = targetMethod,
                member = member,
                location = location,
                issues = issues,
                warnings = warnings,
            )
        }
    }

    private fun validateHook(
        hook: HookMethod,
        targetClassNode: ClassNode,
        targetClass: String,
        targetMethod: MethodNode,
        member: String,
        location: String,
        issues: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val method = hook.method
        val hookName = "${method.name}${method.desc}"
        if ((method.access and org.objectweb.asm.Opcodes.ACC_STATIC) == 0) {
            issues += "$location: $hookName must be static"
        }

        val targetReturn = Type.getReturnType(targetMethod.desc).className.replace('$', '.')
        val hookReturn = Type.getReturnType(method.desc).className.replace('$', '.')
        if (hook.kind == "insert" || hook.kind == "instrument" || hook.kind == "raw") {
            warnings += "$location: ${hook.kind} hook is only structurally checked; run a game smoke test before enabling"
            return
        }
        if (hook.kind == "prefix") {
            if (hookReturn != "void" && hookReturn != SPIRE_RETURN) {
                issues += "$location: Prefix must return void or SpireReturn, got $hookReturn"
            } else if (hookReturn == SPIRE_RETURN) {
                val genericReturn = spireReturnType(method.signature)
                if (genericReturn != null && !returnsCompatible(genericReturn, targetReturn)) {
                    issues += "$location: Prefix returns SpireReturn<$genericReturn> but target returns $targetReturn"
                }
            }
        } else if (targetReturn == "void") {
            if (hookReturn != "void") {
                issues += "$location: Postfix for a void target must return void, got $hookReturn"
            }
        } else if (hookReturn != "void" && !returnsCompatible(hookReturn, targetReturn)) {
            issues += "$location: Postfix must return void or $targetReturn, got $hookReturn"
        }

        val names = parameterNames(method)
        val argumentTypes = Type.getArgumentTypes(method.desc)
        argumentTypes.forEachIndexed { index, argumentType ->
            val name = names.getOrNull(index)
            val typeName = argumentType.className.replace('$', '.')
            when {
                name == null -> {
                    issues += "$location: cannot resolve hook parameter $index in $hookName; use Object[] __args or compile with parameter debug information"
                }
                name == "__args" -> {
                    if (typeName != "java.lang.Object[]") {
                        issues += "$location: __args must be Object[], got $typeName"
                    }
                }
                name == "__instance" -> {
                    if (member == "<clinit>" || targetMethod.access and org.objectweb.asm.Opcodes.ACC_STATIC != 0) {
                        issues += "$location: __instance is invalid for a static target"
                    } else if (typeName != "java.lang.Object" && typeName != targetClass) {
                        warnings += "$location: __instance type $typeName is not the exact target type $targetClass"
                    }
                }
                name == "__result" -> {
                    if (hook.kind != "postfix") {
                        issues += "$location: __result is only valid in a Postfix hook"
                    } else if (!returnsCompatible(typeName, targetReturn)) {
                        issues += "$location: __result type $typeName does not match target return $targetReturn"
                    }
                }
                name.startsWith("___") -> {
                    val fieldName = name.removePrefix("___")
                    val field = targetClassNode.fields.orEmpty()
                        .firstOrNull { it.name == fieldName }
                        ?.desc
                        ?.let { Type.getType(it).className.replace('$', '.') }
                    if (field == null) {
                        issues += "$location: field injection $name does not exist on target $targetClass"
                    } else if (!returnsCompatible(typeName, field)) {
                        issues += "$location: field injection $name expects $field but hook uses $typeName"
                    }
                }
                else -> warnings += "$location: named hook parameter '$name' cannot be matched safely; prefer Object[] __args"
            }
        }
    }

    private fun parameterNames(method: MethodNode): List<String?> {
        val argumentTypes = Type.getArgumentTypes(method.desc)
        val localVariables = method.localVariables.orEmpty()
        if (localVariables.isEmpty()) return List(argumentTypes.size) { null }
        val bySlot = localVariables
            .asSequence()
            .filter { it.name != "this" }
            .groupBy { it.index }
            .mapValues { (_, variables) -> variables.firstOrNull()?.name }
        var slot = if (method.access and org.objectweb.asm.Opcodes.ACC_STATIC == 0) 1 else 0
        return argumentTypes.map { type ->
            val name = bySlot[slot]
            slot += if (type.sort == Type.LONG || type.sort == Type.DOUBLE) 2 else 1
            name
        }
    }

    private fun spireReturnType(signature: String?): String? {
        val raw = signature ?: return null
        val marker = "SpireReturn<"
        val start = raw.indexOf(marker)
        if (start < 0) return null
        val value = raw.substring(start + marker.length).substringBefore('>')
        return runCatching {
            when {
                value.startsWith("L") -> Type.getType("${value.substringBefore(';')};").className.replace('$', '.')
                value.startsWith("[") -> Type.getType(value).className.replace('$', '.')
                else -> value
            }
        }.getOrNull()
    }

    private fun returnsCompatible(actual: String, expected: String): Boolean {
        val normalizedActual = actual.replace('$', '.')
        val normalizedExpected = expected.replace('$', '.')
        if (normalizedActual == normalizedExpected) return true
        if (expected == "void" && actual == "java.lang.Void") return true
        val wrappers = mapOf(
            "boolean" to "java.lang.Boolean",
            "byte" to "java.lang.Byte",
            "char" to "java.lang.Character",
            "short" to "java.lang.Short",
            "int" to "java.lang.Integer",
            "long" to "java.lang.Long",
            "float" to "java.lang.Float",
            "double" to "java.lang.Double",
        )
        return wrappers[normalizedExpected] == normalizedActual || wrappers[normalizedActual] == normalizedExpected
    }

    private fun resolveParameterTypes(spec: PatchSpec): ParameterTypes {
        val typez = spec.paramtypez
        val paramtypes = spec.paramtypes
        val hasTypez = typez != null
        val hasParamtypes = paramtypes != null && paramtypes.none { it.equals("DEFAULT", ignoreCase = true) }
        val typezNames = typez.orEmpty().map { it.className.replace('$', '.') }
        val paramtypeNames = paramtypes.orEmpty().map(AgentPatchTargetInspector::normalizeTypeName)
        val conflict = hasTypez && hasParamtypes && typezNames != paramtypeNames
        return when {
            conflict -> ParameterTypes(explicit = true, types = typezNames, conflict = true)
            hasTypez -> ParameterTypes(explicit = true, types = typezNames, conflict = false)
            hasParamtypes -> ParameterTypes(explicit = true, types = paramtypeNames, conflict = false)
            else -> ParameterTypes(explicit = false, types = emptyList(), conflict = false)
        }
    }

    private fun patchAnnotations(node: ClassNode): List<PatchSpec> {
        val annotations = node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty()
        return annotations.flatMap { annotation ->
            when (annotation.desc) {
                SPIRE_PATCH, SPIRE_PATCH2 -> listOf(annotation.toPatchSpec())
                SPIRE_PATCHES, SPIRE_PATCHES2 -> annotation.containerValues()
                    .mapNotNull { nested ->
                        if (nested.desc == SPIRE_PATCH || nested.desc == SPIRE_PATCH2) nested.toPatchSpec() else null
                    }
                else -> emptyList()
            }
        }
    }

    private fun AnnotationNode.toPatchSpec(): PatchSpec {
        val values = values.orEmpty().chunked(2).associate { pair -> pair[0] as String to pair[1] }
        val targetClass = (values["clz"] as? Type)?.className
            ?: (values["cls"] as? String).orEmpty()
        val member = (values["method"] as? String).orEmpty()
        val typez = (values["paramtypez"] as? List<*>)?.mapNotNull { it as? Type }
        val paramtypes = (values["paramtypes"] as? List<*>)?.mapNotNull { it as? String }
        return PatchSpec(targetClass, member, typez, paramtypes)
    }

    private fun AnnotationNode.containerValues(): List<AnnotationNode> {
        val values = values.orEmpty().chunked(2).associate { pair -> pair[0] as String to pair[1] }
        return (values["value"] as? List<*>)?.mapNotNull { it as? AnnotationNode }.orEmpty()
    }

    private data class PatchSpec(
        val targetClassName: String,
        val memberName: String,
        val paramtypez: List<Type>?,
        val paramtypes: List<String>?,
    )

    private data class ParameterTypes(
        val explicit: Boolean,
        val types: List<String>,
        val conflict: Boolean,
    )

    private data class HookMethod(
        val kind: String,
        val method: MethodNode,
    )

    private class TargetLookup(private val classpath: List<File>) {
        private val cache = HashMap<String, ClassNode?>()

        fun read(className: String): ClassNode? {
            val normalized = AgentPatchTargetInspector.normalizeClassName(className)
            if (cache.containsKey(normalized)) return cache[normalized]
            val bytes = classpath.asSequence().mapNotNull { entry ->
                readBytes(entry, normalized)?.let { entry to it }
            }.firstOrNull()?.second
            val node = bytes?.let { runCatching { AgentPatchTargetInspector.readClassNode(it) }.getOrNull() }
            cache[normalized] = node
            return node
        }

        private fun readBytes(entry: File, className: String): ByteArray? {
            val internalName = className.replace('.', '/')
            if (entry.isDirectory) return File(entry, "$internalName.class").takeIf(File::isFile)?.readBytes()
            if (!entry.isFile) return null
            return runCatching {
                java.util.zip.ZipFile(entry).use { zip ->
                    zip.getEntry("$internalName.class")?.let { zip.getInputStream(it).use { input -> input.readBytes() } }
                }
            }.getOrNull()
        }
    }
}
