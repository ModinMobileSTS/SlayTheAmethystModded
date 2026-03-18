package io.stamethyst.backend.mods

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode

internal data class VupShionModCompatPatchResult(
    val patchedWebButtonConstructor: Boolean,
    val patchedSaveTenkoDataMethod: Boolean,
    val patchedSaveSleyntaPanelMethod: Boolean
) {
    val hasAnyPatch: Boolean
        get() = patchedWebButtonConstructor ||
            patchedSaveTenkoDataMethod ||
            patchedSaveSleyntaPanelMethod
}

internal object VupShionModCompatPatcher {
    private const val TARGET_CLASS_ENTRY = "VUPShionMod/ui/WebButton.class"
    private const val SAVE_HELPER_CLASS_ENTRY = "VUPShionMod/util/SaveHelper.class"
    private const val TARGET_CONSTRUCTOR_DESC =
        "(Ljava/lang/String;FFFFFLcom/badlogic/gdx/graphics/Color;" +
            "Lcom/badlogic/gdx/graphics/Texture;)V"
    private const val VOID_METHOD_DESC = "()V"
    private const val STEAM_APPS_INTERNAL_NAME = "com/codedisaster/steamworks/SteamApps"
    private const val NULL_API_EXCEPTION_INTERNAL_NAME = "VUPShionMod/msic/NullApiException"
    private const val SAVE_TENKO_DATA_METHOD_NAME = "saveTenkoData"
    private const val SAVE_SLEYNTA_PANEL_METHOD_NAME = "saveSleyntaPanel"
    private const val TENKO_PANEL_INTERNAL_NAME = "VUPShionMod/ui/TenkoPanel/TenkoPanel"
    private const val TENKO_GET_PANEL_METHOD_NAME = "getPanel"
    private const val TENKO_GET_PANEL_METHOD_DESC = "()LVUPShionMod/ui/TenkoPanel/TenkoPanel;"
    private const val CRITICAL_SHOT_PANEL_INTERNAL_NAME = "VUPShionMod/ui/SleyntaUI/CriticalShotPanel"
    private const val CRITICAL_SHOT_GET_PANEL_METHOD_DESC =
        "()LVUPShionMod/ui/SleyntaUI/CriticalShotPanel;"
    private const val STAR_DUST_PANEL_INTERNAL_NAME = "VUPShionMod/ui/SleyntaUI/StarDustPanel"
    private const val STAR_DUST_GET_PANEL_METHOD_DESC =
        "()LVUPShionMod/ui/SleyntaUI/StarDustPanel;"
    private const val ABSTRACT_DUNGEON_INTERNAL_NAME =
        "com/megacrit/cardcrawl/dungeons/AbstractDungeon"
    private const val OVERLAY_MENU_INTERNAL_NAME = "com/megacrit/cardcrawl/core/OverlayMenu"
    private const val OVERLAY_MENU_FIELD_NAME = "overlayMenu"
    private const val OVERLAY_MENU_FIELD_DESC = "Lcom/megacrit/cardcrawl/core/OverlayMenu;"
    private const val ENERGY_PANEL_FIELD_NAME = "energyPanel"
    private const val ENERGY_PANEL_FIELD_DESC = "Lcom/megacrit/cardcrawl/ui/panels/EnergyPanel;"

    @Throws(IOException::class)
    fun patchInPlace(modJar: File): VupShionModCompatPatchResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }

        val replacements = LinkedHashMap<String, ByteArray>()
        var patchedWebButtonConstructor = false
        var patchedSaveTenkoDataMethod = false
        var patchedSaveSleyntaPanelMethod = false
        ZipFile(modJar).use { zipFile ->
            val hierarchyResolver = ClassHierarchyResolver(zipFile)
            patchClassEntry(zipFile, TARGET_CLASS_ENTRY) { classBytes ->
                patchWebButtonClassBytes(classBytes, hierarchyResolver)
            }?.let { (entryName, patchedBytes) ->
                replacements[entryName] = patchedBytes
                patchedWebButtonConstructor = true
            }
            patchClassEntry(zipFile, SAVE_HELPER_CLASS_ENTRY) { classBytes ->
                patchSaveHelperClassBytes(classBytes, hierarchyResolver)
            }?.let { (entryName, patchedBytes) ->
                replacements[entryName] = patchedBytes
                val patchResult = inspectSaveHelperPatchResult(patchedBytes)
                patchedSaveTenkoDataMethod = patchResult.first
                patchedSaveSleyntaPanelMethod = patchResult.second
            }
        }

        if (replacements.isNotEmpty()) {
            rewriteJarWithReplacements(modJar, replacements)
        }

        return VupShionModCompatPatchResult(
            patchedWebButtonConstructor = patchedWebButtonConstructor,
            patchedSaveTenkoDataMethod = patchedSaveTenkoDataMethod,
            patchedSaveSleyntaPanelMethod = patchedSaveSleyntaPanelMethod
        )
    }

    @Throws(IOException::class)
    private fun patchClassEntry(
        zipFile: ZipFile,
        targetEntryName: String,
        transform: (ByteArray) -> ByteArray?
    ): Pair<String, ByteArray>? {
        val entry = JarFileIoUtils.findEntryIgnoreCase(zipFile, targetEntryName) ?: return null
        if (entry.isDirectory) {
            return null
        }
        val originalBytes = JarFileIoUtils.readEntryBytes(zipFile, entry)
        val patchedBytes = transform(originalBytes) ?: return null
        return entry.name to patchedBytes
    }

    @Throws(IOException::class)
    private fun patchWebButtonClassBytes(
        classBytes: ByteArray,
        hierarchyResolver: ClassHierarchyResolver
    ): ByteArray? {
        val classNode = readClassNode(classBytes)
        val constructor = classNode.methods.firstOrNull { method ->
            method.name == "<init>" && method.desc == TARGET_CONSTRUCTOR_DESC
        } ?: return null

        if (!removeWorkshopSubscriptionGate(constructor)) {
            return null
        }
        return writeClass(classNode, hierarchyResolver)
    }

    @Throws(IOException::class)
    private fun patchSaveHelperClassBytes(
        classBytes: ByteArray,
        hierarchyResolver: ClassHierarchyResolver
    ): ByteArray? {
        val classNode = readClassNode(classBytes)
        val saveTenkoDataMethod = classNode.methods.firstOrNull { method ->
            method.name == SAVE_TENKO_DATA_METHOD_NAME && method.desc == VOID_METHOD_DESC
        }
        val saveSleyntaPanelMethod = classNode.methods.firstOrNull { method ->
            method.name == SAVE_SLEYNTA_PANEL_METHOD_NAME && method.desc == VOID_METHOD_DESC
        }

        var modified = false
        if (saveTenkoDataMethod != null) {
            modified = addPanelAvailabilityGuards(
                method = saveTenkoDataMethod,
                getterOwner = TENKO_PANEL_INTERNAL_NAME,
                getterName = TENKO_GET_PANEL_METHOD_NAME,
                getterDesc = TENKO_GET_PANEL_METHOD_DESC,
                guardedLocalIndexes = intArrayOf(1)
            ) || modified
        }
        if (saveSleyntaPanelMethod != null) {
            modified = addPanelAvailabilityGuards(
                method = saveSleyntaPanelMethod,
                getterOwner = CRITICAL_SHOT_PANEL_INTERNAL_NAME,
                getterName = TENKO_GET_PANEL_METHOD_NAME,
                getterDesc = CRITICAL_SHOT_GET_PANEL_METHOD_DESC,
                guardedLocalIndexes = intArrayOf(1)
            ) || modified
            modified = addPanelAvailabilityGuards(
                method = saveSleyntaPanelMethod,
                getterOwner = STAR_DUST_PANEL_INTERNAL_NAME,
                getterName = TENKO_GET_PANEL_METHOD_NAME,
                getterDesc = STAR_DUST_GET_PANEL_METHOD_DESC,
                guardedLocalIndexes = intArrayOf(2)
            ) || modified
        }

        if (!modified) {
            return null
        }
        return writeClass(classNode, hierarchyResolver)
    }

    private fun removeWorkshopSubscriptionGate(method: MethodNode): Boolean {
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            val next = current.next
            val call = current as? MethodInsnNode
            if (call != null &&
                call.opcode == Opcodes.INVOKEVIRTUAL &&
                call.owner == STEAM_APPS_INTERNAL_NAME &&
                call.name == "isSubscribedApp" &&
                call.desc == "(I)Z"
            ) {
                val guardStart = findGuardStart(call)
                val guardEnd = findGuardEnd(call)
                if (guardStart != null && guardEnd != null) {
                    removeInclusive(method.instructions, guardStart, guardEnd)
                    return true
                }
            }
            current = next
        }
        return false
    }

    private fun addPanelAvailabilityGuards(
        method: MethodNode,
        getterOwner: String,
        getterName: String,
        getterDesc: String,
        guardedLocalIndexes: IntArray
    ): Boolean {
        var modified = false
        if (!hasOverlayEnergyPanelGuard(method)) {
            method.instructions.insert(buildOverlayEnergyPanelGuard())
            modified = true
        }

        if (guardedLocalIndexes.isNotEmpty()) {
            val calls = findPanelGetterStores(method, getterOwner, getterName, getterDesc)
            if (calls.size != guardedLocalIndexes.size) {
                return modified
            }
            for (index in calls.indices) {
                val storeNode = calls[index]
                val localIndex = guardedLocalIndexes[index]
                if (storeNode.`var` != localIndex) {
                    continue
                }
                if (hasNullGuardAfterStore(storeNode, localIndex)) {
                    continue
                }
                method.instructions.insert(storeNode, buildLocalNullGuard(localIndex))
                modified = true
            }
        }
        return modified
    }

    private fun findGuardStart(call: MethodInsnNode): AbstractInsnNode? {
        var current: AbstractInsnNode? = call
        repeat(8) {
            current = previousMeaningful(current)
            val typeInsn = current as? TypeInsnNode ?: return@repeat
            if (typeInsn.opcode == Opcodes.NEW && typeInsn.desc == STEAM_APPS_INTERNAL_NAME) {
                return typeInsn
            }
        }
        return null
    }

    private fun findGuardEnd(call: MethodInsnNode): AbstractInsnNode? {
        val ifNode = nextMeaningful(call) as? JumpInsnNode ?: return null
        if (ifNode.opcode != Opcodes.IFNE) {
            return null
        }
        val exceptionNew = nextMeaningful(ifNode) as? TypeInsnNode ?: return null
        if (exceptionNew.opcode != Opcodes.NEW ||
            exceptionNew.desc != NULL_API_EXCEPTION_INTERNAL_NAME
        ) {
            return null
        }
        val exceptionInit = nextMeaningful(nextMeaningful(exceptionNew)) as? MethodInsnNode ?: return null
        if (exceptionInit.owner != NULL_API_EXCEPTION_INTERNAL_NAME ||
            exceptionInit.name != "<init>" ||
            exceptionInit.desc != "()V"
        ) {
            return null
        }
        val athrow = nextMeaningful(exceptionInit) ?: return null
        if (athrow.opcode != Opcodes.ATHROW) {
            return null
        }
        return athrow
    }

    private fun findPanelGetterStores(
        method: MethodNode,
        getterOwner: String,
        getterName: String,
        getterDesc: String
    ): List<org.objectweb.asm.tree.VarInsnNode> {
        val result = ArrayList<org.objectweb.asm.tree.VarInsnNode>()
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            val call = current as? MethodInsnNode
            if (call != null &&
                call.opcode == Opcodes.INVOKESTATIC &&
                call.owner == getterOwner &&
                call.name == getterName &&
                call.desc == getterDesc
            ) {
                val next = nextMeaningful(call) as? org.objectweb.asm.tree.VarInsnNode
                if (next != null && next.opcode == Opcodes.ASTORE) {
                    result += next
                }
            }
            current = current.next
        }
        return result
    }

    private fun hasOverlayEnergyPanelGuard(method: MethodNode): Boolean {
        val meaningful = meaningfulInstructions(method).take(8)
        if (meaningful.size < 6) {
            return false
        }
        val firstGetStatic = meaningful.getOrNull(0) as? org.objectweb.asm.tree.FieldInsnNode
        val firstJump = meaningful.getOrNull(1) as? JumpInsnNode
        val secondGetStatic = meaningful.getOrNull(3) as? org.objectweb.asm.tree.FieldInsnNode
        val getField = meaningful.getOrNull(4) as? org.objectweb.asm.tree.FieldInsnNode
        val secondJump = meaningful.getOrNull(5) as? JumpInsnNode
        return firstGetStatic?.opcode == Opcodes.GETSTATIC &&
            firstGetStatic.owner == ABSTRACT_DUNGEON_INTERNAL_NAME &&
            firstGetStatic.name == OVERLAY_MENU_FIELD_NAME &&
            firstGetStatic.desc == OVERLAY_MENU_FIELD_DESC &&
            firstJump?.opcode == Opcodes.IFNONNULL &&
            secondGetStatic?.opcode == Opcodes.GETSTATIC &&
            secondGetStatic.owner == ABSTRACT_DUNGEON_INTERNAL_NAME &&
            secondGetStatic.name == OVERLAY_MENU_FIELD_NAME &&
            secondGetStatic.desc == OVERLAY_MENU_FIELD_DESC &&
            getField?.opcode == Opcodes.GETFIELD &&
            getField.owner == OVERLAY_MENU_INTERNAL_NAME &&
            getField.name == ENERGY_PANEL_FIELD_NAME &&
            getField.desc == ENERGY_PANEL_FIELD_DESC &&
            secondJump?.opcode == Opcodes.IFNONNULL
    }

    private fun buildOverlayEnergyPanelGuard(): InsnList {
        val overlayReady = LabelNode()
        val continueLabel = LabelNode()
        return InsnList().apply {
            add(
                org.objectweb.asm.tree.FieldInsnNode(
                    Opcodes.GETSTATIC,
                    ABSTRACT_DUNGEON_INTERNAL_NAME,
                    OVERLAY_MENU_FIELD_NAME,
                    OVERLAY_MENU_FIELD_DESC
                )
            )
            add(JumpInsnNode(Opcodes.IFNONNULL, overlayReady))
            add(org.objectweb.asm.tree.InsnNode(Opcodes.RETURN))
            add(overlayReady)
            add(
                org.objectweb.asm.tree.FieldInsnNode(
                    Opcodes.GETSTATIC,
                    ABSTRACT_DUNGEON_INTERNAL_NAME,
                    OVERLAY_MENU_FIELD_NAME,
                    OVERLAY_MENU_FIELD_DESC
                )
            )
            add(
                org.objectweb.asm.tree.FieldInsnNode(
                    Opcodes.GETFIELD,
                    OVERLAY_MENU_INTERNAL_NAME,
                    ENERGY_PANEL_FIELD_NAME,
                    ENERGY_PANEL_FIELD_DESC
                )
            )
            add(JumpInsnNode(Opcodes.IFNONNULL, continueLabel))
            add(org.objectweb.asm.tree.InsnNode(Opcodes.RETURN))
            add(continueLabel)
        }
    }

    private fun hasNullGuardAfterStore(
        storeNode: org.objectweb.asm.tree.VarInsnNode,
        localIndex: Int
    ): Boolean {
        val loadNode = nextMeaningful(storeNode) as? org.objectweb.asm.tree.VarInsnNode ?: return false
        if (loadNode.opcode != Opcodes.ALOAD || loadNode.`var` != localIndex) {
            return false
        }
        val jumpNode = nextMeaningful(loadNode) as? JumpInsnNode ?: return false
        return jumpNode.opcode == Opcodes.IFNONNULL
    }

    private fun buildLocalNullGuard(localIndex: Int): InsnList {
        val continueLabel = LabelNode()
        return InsnList().apply {
            add(org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, localIndex))
            add(JumpInsnNode(Opcodes.IFNONNULL, continueLabel))
            add(org.objectweb.asm.tree.InsnNode(Opcodes.RETURN))
            add(continueLabel)
        }
    }

    private fun previousMeaningful(node: AbstractInsnNode?): AbstractInsnNode? {
        var current = node?.previous
        while (current is LabelNode || current is LineNumberNode || current is FrameNode) {
            current = current.previous
        }
        return current
    }

    private fun meaningfulInstructions(method: MethodNode): List<AbstractInsnNode> {
        val result = ArrayList<AbstractInsnNode>()
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            if (current !is LabelNode && current !is LineNumberNode && current !is FrameNode) {
                result += current
            }
            current = current.next
        }
        return result
    }

    private fun nextMeaningful(node: AbstractInsnNode?): AbstractInsnNode? {
        var current = node?.next
        while (current is LabelNode || current is LineNumberNode || current is FrameNode) {
            current = current.next
        }
        return current
    }

    private fun removeInclusive(
        instructions: InsnList,
        start: AbstractInsnNode,
        end: AbstractInsnNode
    ) {
        var current: AbstractInsnNode? = start
        while (current != null) {
            val next = current.next
            instructions.remove(current)
            if (current == end) {
                return
            }
            current = next
        }
    }

    private fun readClassNode(classBytes: ByteArray): ClassNode {
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, 0)
        return classNode
    }

    private fun inspectSaveHelperPatchResult(classBytes: ByteArray): Pair<Boolean, Boolean> {
        val classNode = readClassNode(classBytes)
        val saveTenkoDataMethod = classNode.methods.firstOrNull { method ->
            method.name == SAVE_TENKO_DATA_METHOD_NAME && method.desc == VOID_METHOD_DESC
        }
        val saveSleyntaPanelMethod = classNode.methods.firstOrNull { method ->
            method.name == SAVE_SLEYNTA_PANEL_METHOD_NAME && method.desc == VOID_METHOD_DESC
        }
        return Pair(
            saveTenkoDataMethod?.let { hasOverlayEnergyPanelGuard(it) && hasStoreNullGuard(it, 1) } == true,
            saveSleyntaPanelMethod?.let {
                hasOverlayEnergyPanelGuard(it) && hasStoreNullGuard(it, 1) && hasStoreNullGuard(it, 2)
            } == true
        )
    }

    private fun hasStoreNullGuard(method: MethodNode, localIndex: Int): Boolean {
        var current: AbstractInsnNode? = method.instructions.first
        while (current != null) {
            val store = current as? org.objectweb.asm.tree.VarInsnNode
            if (store != null &&
                store.opcode == Opcodes.ASTORE &&
                store.`var` == localIndex &&
                hasNullGuardAfterStore(store, localIndex)
            ) {
                return true
            }
            current = current.next
        }
        return false
    }

    private fun writeClass(
        classNode: ClassNode,
        hierarchyResolver: ClassHierarchyResolver
    ): ByteArray {
        val classWriter = object : ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES) {
            override fun getCommonSuperClass(type1: String, type2: String): String {
                return hierarchyResolver.getCommonSuperClass(type1, type2)
            }
        }
        classNode.accept(classWriter)
        return classWriter.toByteArray()
    }

    private data class ResolvedClassInfo(
        val internalName: String,
        val superInternalName: String?,
        val interfaceInternalNames: List<String>,
        val isInterface: Boolean
    )

    private class ClassHierarchyResolver(
        private val zipFile: ZipFile
    ) {
        private val cache = HashMap<String, ResolvedClassInfo?>()

        fun getCommonSuperClass(type1: String, type2: String): String {
            if (type1 == type2) {
                return type1
            }
            if (isAssignableFrom(type1, type2)) {
                return type1
            }
            if (isAssignableFrom(type2, type1)) {
                return type2
            }

            val firstInfo = resolve(type1)
            val secondInfo = resolve(type2)
            if (firstInfo?.isInterface == true || secondInfo?.isInterface == true) {
                return "java/lang/Object"
            }

            var current = firstInfo
            while (current != null) {
                if (isAssignableFrom(current.internalName, type2)) {
                    return current.internalName
                }
                current = current.superInternalName?.let(::resolve)
            }
            return "java/lang/Object"
        }

        private fun isAssignableFrom(targetInternalName: String, sourceInternalName: String): Boolean {
            if (targetInternalName == sourceInternalName) {
                return true
            }
            if (targetInternalName == "java/lang/Object") {
                return true
            }

            val targetInfo = resolve(targetInternalName)
            val sourceInfo = resolve(sourceInternalName) ?: return false
            if (targetInfo?.isInterface == true) {
                return implementsInterface(sourceInfo, targetInternalName)
            }

            var current: ResolvedClassInfo? = sourceInfo
            while (current != null) {
                if (current.internalName == targetInternalName) {
                    return true
                }
                current = current.superInternalName?.let(::resolve)
            }
            return false
        }

        private fun implementsInterface(
            sourceInfo: ResolvedClassInfo,
            targetInternalName: String
        ): Boolean {
            val visited = HashSet<String>()
            val queue = ArrayDeque<String>()
            queue.addAll(sourceInfo.interfaceInternalNames)
            sourceInfo.superInternalName?.let(queue::addLast)
            while (queue.isNotEmpty()) {
                val currentName = queue.removeFirst()
                if (!visited.add(currentName)) {
                    continue
                }
                if (currentName == targetInternalName) {
                    return true
                }
                val currentInfo = resolve(currentName) ?: continue
                queue.addAll(currentInfo.interfaceInternalNames)
                currentInfo.superInternalName?.let(queue::addLast)
            }
            return false
        }

        private fun resolve(internalName: String): ResolvedClassInfo? {
            return cache.getOrPut(internalName) {
                readFromJar(internalName) ?: readFromRuntime(internalName)
            }
        }

        private fun readFromJar(internalName: String): ResolvedClassInfo? {
            val entryName = "$internalName.class"
            val entry = JarFileIoUtils.findEntryIgnoreCase(zipFile, entryName) ?: return null
            if (entry.isDirectory) {
                return null
            }
            val classBytes = JarFileIoUtils.readEntryBytes(zipFile, entry)
            val reader = ClassReader(classBytes)
            return ResolvedClassInfo(
                internalName = reader.className,
                superInternalName = reader.superName,
                interfaceInternalNames = reader.interfaces.toList(),
                isInterface = (reader.access and Opcodes.ACC_INTERFACE) != 0
            )
        }

        private fun readFromRuntime(internalName: String): ResolvedClassInfo? {
            val className = internalName.replace('/', '.')
            val runtimeClass = try {
                Class.forName(className, false, javaClass.classLoader)
            } catch (_: Throwable) {
                return null
            }
            return ResolvedClassInfo(
                internalName = runtimeClass.name.replace('.', '/'),
                superInternalName = runtimeClass.superclass?.name?.replace('.', '/'),
                interfaceInternalNames = runtimeClass.interfaces
                    .map { iface -> iface.name.replace('.', '/') },
                isInterface = runtimeClass.isInterface
            )
        }
    }

    @Throws(IOException::class)
    private fun rewriteJarWithReplacements(modJar: File, replacements: Map<String, ByteArray>) {
        val tempJar = File(modJar.absolutePath + ".vupshionpatch.tmp")
        val seenNames = LinkedHashSet<String>()
        try {
            ZipFile(modJar).use { zipFile ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        val entries = zipFile.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val entryName = entry.name
                            if (!seenNames.add(entryName)) {
                                continue
                            }

                            val outEntry = ZipEntry(entryName)
                            if (entry.time > 0) {
                                outEntry.time = entry.time
                            }
                            zipOut.putNextEntry(outEntry)
                            if (!entry.isDirectory) {
                                val replacement = replacements[entryName]
                                if (replacement != null) {
                                    zipOut.write(replacement)
                                } else {
                                    zipFile.getInputStream(entry).use { input ->
                                        JarFileIoUtils.copyStream(input, zipOut)
                                    }
                                }
                            }
                            zipOut.closeEntry()
                        }
                    }
                }
            }

            if (modJar.exists() && !modJar.delete()) {
                throw IOException("Failed to replace ${modJar.absolutePath}")
            }
            if (!tempJar.renameTo(modJar)) {
                throw IOException("Failed to move ${tempJar.absolutePath} -> ${modJar.absolutePath}")
            }
            modJar.setLastModified(System.currentTimeMillis())
        } finally {
            if (tempJar.exists()) {
                tempJar.delete()
            }
        }
    }
}
