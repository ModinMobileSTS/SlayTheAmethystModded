package io.stamethyst.backend.mods

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** One type found on the compile classpath, without its members. */
data class ApiTypeSummary(
    val binaryName: String,
    val kind: String,
    val superName: String,
    val interfaces: List<String>,
    val isPublic: Boolean,
    val isNested: Boolean,
    val origin: String,
) {
    val simpleName: String get() = binaryName.substringAfterLast('.')
}

/** One constructor, method, or field of an indexed type. */
data class ApiMember(
    val kind: String,
    val name: String,
    val declaration: String,
    val isStatic: Boolean,
)

data class ApiTypeDetail(
    val summary: ApiTypeSummary,
    val constructors: List<ApiMember>,
    val methods: List<ApiMember>,
    val fields: List<ApiMember>,
)

/**
 * Read-only symbol index over the game/mod compile classpath.
 *
 * The parent mod's JAR is bytecode and the game API is not documented by a machine-readable
 * descriptor, so the agent needs a way to discover exact type and member signatures instead of
 * recalling them. Signatures in this index come straight from the installed bytecode, so they are
 * always the truth for the version the resulting patch will run against.
 *
 * The full index stores only type headers (name, kind, super type, interfaces, origin); members are
 * parsed on demand so indexing thousands of classes stays cheap. The result is cached per classpath
 * signature, because the classpath does not change within an editing session.
 */
object AgentApiIndex {
    private const val MAX_ENTRIES = 200_000
    private val cache = ConcurrentHashMap<String, Index>()

    fun index(classpath: List<File>): Index {
        val signature = classpath.joinToString("|") { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
        if (cache.size > 4) cache.clear()
        return cache.computeIfAbsent(signature) { build(classpath) }
    }

    private fun build(classpath: List<File>): Index {
        val types = LinkedHashMap<String, ApiTypeSummary>()
        var seen = 0
        classpath.forEach { entry ->
            if (!entry.exists()) return@forEach
            val origin = entry.name
            classNames(entry).forEach { internalName ->
                if (seen >= MAX_ENTRIES) return@forEach
                seen++
                val summary = readSummary(entry, internalName, origin) ?: return@forEach
                types.putIfAbsent(summary.binaryName, summary)
            }
        }
        return Index(types)
    }

    private fun classNames(entry: File): Sequence<String> {
        if (entry.isDirectory) {
            val root = entry.canonicalFile.toPath()
            return entry.walkTopDown()
                .filter { it.isFile && it.extension.equals("class", ignoreCase = true) }
                .map { it.canonicalFile.toPath().let { path -> root.relativize(path).toString() } }
                .map { it.removeSuffix(".class").replace(File.separatorChar, '/') }
        }
        if (!entry.isFile || !entry.name.endsWith(".jar", ignoreCase = true)) return emptySequence()
        val names = ArrayList<String>()
        runCatching {
            ZipFile(entry).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                    .forEach { names += it.name.removeSuffix(".class") }
            }
        }
        return names.asSequence()
    }

    private fun readSummary(entry: File, internalName: String, origin: String): ApiTypeSummary? {
        val bytes = readClassBytes(entry, internalName) ?: return null
        var access = 0
        var name = internalName
        var superName = ""
        val interfaces = ArrayList<String>()
        runCatching {
            ClassReader(bytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visit(
                        version: Int,
                        visitedAccess: Int,
                        visitedName: String,
                        signature: String?,
                        visitedSuperName: String?,
                        visitedInterfaces: Array<out String>?,
                    ) {
                        access = visitedAccess
                        name = visitedName
                        superName = visitedSuperName.orEmpty()
                        visitedInterfaces.orEmpty().forEach(interfaces::add)
                    }

                    // Members are read on demand; returning null keeps this pass cheap.
                    override fun visitMethod(
                        access: Int,
                        name: String?,
                        descriptor: String?,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ) = null
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        }.getOrNull() ?: return null
        return ApiTypeSummary(
            binaryName = name.replace('/', '.').replace('$', '.'),
            kind = kindOf(access),
            superName = superName.replace('/', '.').replace('$', '.'),
            interfaces = interfaces.map { it.replace('/', '.').replace('$', '.') },
            isPublic = access and Opcodes.ACC_PUBLIC != 0,
            isNested = name.contains('$'),
            origin = origin,
        )
    }

    private fun readClassBytes(entry: File, internalName: String): ByteArray? {
        if (entry.isDirectory) {
            return File(entry, "$internalName.class").takeIf(File::isFile)?.readBytes()
        }
        if (!entry.isFile) return null
        return runCatching {
            ZipFile(entry).use { zip ->
                zip.getEntry("$internalName.class")?.let { zip.getInputStream(it).use { input -> input.readBytes() } }
            }
        }.getOrNull()
    }

    private fun kindOf(access: Int): String = when {
        access and Opcodes.ACC_ANNOTATION != 0 -> "annotation"
        access and Opcodes.ACC_INTERFACE != 0 -> "interface"
        access and Opcodes.ACC_ENUM != 0 -> "enum"
        else -> "class"
    }

    /** Searches type names, and optionally restricts to types implementing a given interface. */
    fun search(
        types: Collection<ApiTypeSummary>,
        query: String?,
        implementsFilter: String?,
        limit: Int,
    ): List<ApiTypeSummary> {
        val needle = query?.trim()?.lowercase().orEmpty()
        val interfaceNeedle = implementsFilter?.trim()?.lowercase()?.replace('$', '.')
        val scored = ArrayList<Pair<Int, ApiTypeSummary>>()
        types.forEach { type ->
            // Compiler-generated members (anonymous classes, lambdas) are never useful to the agent.
            if (type.simpleName.firstOrNull()?.isDigit() == true) return@forEach
            if (interfaceNeedle != null &&
                type.interfaces.none { it.lowercase().endsWith(interfaceNeedle) || it.lowercase() == interfaceNeedle }
            ) {
                return@forEach
            }
            if (needle.isNotEmpty()) {
                val score = score(type, needle) ?: return@forEach
                scored += score to type
            } else {
                scored += 0 to type
            }
        }
        return scored
            .sortedWith(compareByDescending<Pair<Int, ApiTypeSummary>> { it.first }.thenBy { it.second.binaryName })
            .take(limit.coerceIn(1, MAX_SEARCH_LIMIT))
            .map { it.second }
    }

    private fun score(type: ApiTypeSummary, needle: String): Int? {
        val simple = type.simpleName.lowercase()
        val full = type.binaryName.lowercase()
        val base = when {
            simple == needle -> 100
            simple.startsWith(needle) -> 60
            simple.contains(needle) -> 40
            full.contains(needle) -> 20
            else -> return null
        }
        var score = base
        if (type.isPublic) score += 10
        if (!type.isNested) score += 8
        if (PREFERRED_PACKAGES.any { full.startsWith(it) }) score += 15
        if (type.origin.startsWith("BaseMod") || type.origin.startsWith("StSLib")) score += 5
        return score
    }

    /** Reads one type's public and protected surface from the classpath. */
    fun describe(classpath: List<File>, binaryName: String, memberFilter: String?): ApiTypeDetail? {
        val internal = resolveInternalName(classpath, binaryName) ?: return null
        val entry = findEntry(classpath, internal) ?: return null
        val bytes = readClassBytes(entry, internal) ?: return null
        val node = AgentPatchTargetInspector.readClassNode(bytes)
        val name = node.name.replace('/', '.').replace('$', '.')
        val summary = ApiTypeSummary(
            binaryName = name,
            kind = kindOf(node.access),
            superName = node.superName.orEmpty().replace('/', '.').replace('$', '.'),
            interfaces = node.interfaces.orEmpty().map { it.replace('/', '.').replace('$', '.') },
            isPublic = node.access and Opcodes.ACC_PUBLIC != 0,
            isNested = node.name.contains('$'),
            origin = entry.name,
        )
        val filter = memberFilter?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        val constructors = ArrayList<ApiMember>()
        val methods = ArrayList<ApiMember>()
        val fields = ArrayList<ApiMember>()
        node.methods.orEmpty().forEach { method ->
            if (method.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) == 0) return@forEach
            if (method.name.startsWith("<") && method.name != "<init>") return@forEach
            if (filter != null && !method.name.lowercase().contains(filter)) return@forEach
            val member = ApiMember(
                kind = if (method.name == "<init>") "constructor" else "method",
                name = if (method.name == "<init>") simpleNameOf(name) else method.name,
                declaration = describeMethod(name, method.name, method.desc, method.access),
                isStatic = method.access and Opcodes.ACC_STATIC != 0,
            )
            if (method.name == "<init>") constructors += member else methods += member
        }
        node.fields.orEmpty().forEach { field ->
            if (field.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) == 0) return@forEach
            if (filter != null && !field.name.lowercase().contains(filter)) return@forEach
            fields += ApiMember(
                kind = "field",
                name = field.name,
                declaration = "${if (field.access and Opcodes.ACC_STATIC != 0) "static " else ""}" +
                    "${javaType(Type.getType(field.desc))} ${field.name}",
                isStatic = field.access and Opcodes.ACC_STATIC != 0,
            )
        }
        return ApiTypeDetail(summary, constructors, methods, fields)
    }

    private fun describeMethod(owner: String, name: String, desc: String, access: Int): String {
        val arguments = Type.getArgumentTypes(desc).joinToString(", ") { javaType(it) }
        val modifiers = buildString {
            if (access and Opcodes.ACC_STATIC != 0) append("static ")
            if (access and Opcodes.ACC_ABSTRACT != 0) append("abstract ")
        }
        return if (name == "<init>") {
            "$modifiers${simpleNameOf(owner)}($arguments)"
        } else {
            "$modifiers${javaType(Type.getReturnType(desc))} $name($arguments)"
        }
    }

    private fun simpleNameOf(binaryName: String): String = binaryName.substringAfterLast('.')

    internal fun javaType(type: Type): String = when (type.sort) {
        Type.ARRAY -> javaType(type.elementType) + "[]"
        else -> type.className.replace('$', '.')
    }

    /**
     * Resolves a dotted binary name to an internal name, allowing nested types to be written either
     * as `AbstractCard$CardColor` or as the source form `AbstractCard.CardColor`.
     */
    private fun resolveInternalName(classpath: List<File>, binaryName: String): String? {
        val raw = binaryName.trim()
        val normalized = if (raw.startsWith("L") && raw.endsWith(";")) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }.replace('/', '.')
        val parts = normalized.split('.').filter(String::isNotEmpty)
        if (parts.isEmpty()) return null
        for (split in parts.size - 1 downTo 0) {
            val internal = (parts.subList(0, split) + parts.subList(split, parts.size).joinToString("$"))
                .joinToString("/")
            if (findEntry(classpath, internal) != null) return internal
        }
        return null
    }

    private fun findEntry(classpath: List<File>, internalName: String): File? =
        classpath.firstOrNull { entry ->
            when {
                entry.isDirectory -> File(entry, "$internalName.class").isFile
                entry.isFile && entry.name.endsWith(".jar", ignoreCase = true) -> runCatching {
                    ZipFile(entry).use { it.getEntry("$internalName.class") != null }
                }.getOrDefault(false)
                else -> false
            }
        }

    private val PREFERRED_PACKAGES = listOf(
        "basemod.",
        "com.evacipated.cardcrawl.mod.stslib.",
        "com.megacrit.cardcrawl.",
        "com.evacipated.cardcrawl.modthespire.",
    )

    const val MAX_SEARCH_LIMIT = 100

    /** Immutable light index of every type on a resolved classpath. */
    class Index internal constructor(private val types: Map<String, ApiTypeSummary>) {
        val size: Int get() = types.size

        fun all(): Collection<ApiTypeSummary> = types.values
    }
}
