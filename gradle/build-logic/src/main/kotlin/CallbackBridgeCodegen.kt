import java.security.MessageDigest

enum class CallbackBridgeTarget {
    ANDROID,
    JVM
}

data class CallbackBridgeConstant(
    val name: String,
    val type: String,
    val initializer: String,
    val android: Boolean = false,
    val jvm: Boolean = false
)

data class CallbackBridgeParameter(
    val type: String,
    val name: String
)

data class CallbackBridgeMethod(
    val name: String,
    val returnType: String,
    val parameters: List<CallbackBridgeParameter>,
    val androidVisibility: String? = null,
    val jvmVisibility: String? = null
)

object CallbackBridgeCodegen {
    private val constants = listOf(
        CallbackBridgeConstant("CLIPBOARD_COPY", "int", "2000", android = true, jvm = true),
        CallbackBridgeConstant("CLIPBOARD_PASTE", "int", "2001", android = true, jvm = true),
        CallbackBridgeConstant("CLIPBOARD_OPEN", "int", "2002", android = true),
        CallbackBridgeConstant("EVENT_TYPE_CHAR", "int", "1000", jvm = true),
        CallbackBridgeConstant("EVENT_TYPE_CHAR_MODS", "int", "1001", jvm = true),
        CallbackBridgeConstant("EVENT_TYPE_CURSOR_ENTER", "int", "1002", jvm = true),
        CallbackBridgeConstant("EVENT_TYPE_CURSOR_POS", "int", "1003", jvm = true),
        CallbackBridgeConstant("EVENT_TYPE_FRAMEBUFFER_SIZE", "int", "1004", jvm = true),
        CallbackBridgeConstant("EVENT_TYPE_KEY", "int", "1005", jvm = true),
        CallbackBridgeConstant("EVENT_TYPE_MOUSE_BUTTON", "int", "1006", jvm = true),
        CallbackBridgeConstant("EVENT_TYPE_SCROLL", "int", "1007", jvm = true),
        CallbackBridgeConstant("EVENT_TYPE_WINDOW_SIZE", "int", "1008", jvm = true),
        CallbackBridgeConstant("ANDROID_TYPE_GRAB_STATE", "int", "0", jvm = true),
        CallbackBridgeConstant(
            "INPUT_DEBUG_ENABLED",
            "boolean",
            "Boolean.parseBoolean(System.getProperty(\"glfwstub.debugInput\", \"false\"))",
            jvm = true
        )
    )

    private val methods = listOf(
        CallbackBridgeMethod(
            name = "nativeSendData",
            returnType = "void",
            parameters = listOf(
                CallbackBridgeParameter("boolean", "direct"),
                CallbackBridgeParameter("int", "type"),
                CallbackBridgeParameter("String", "payload")
            ),
            jvmVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeSetUseInputStackQueue",
            returnType = "void",
            parameters = listOf(CallbackBridgeParameter("boolean", "useInputStackQueue")),
            androidVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeSendChar",
            returnType = "boolean",
            parameters = listOf(CallbackBridgeParameter("char", "codepoint")),
            androidVisibility = "private"
        ),
        CallbackBridgeMethod(
            name = "nativeSendCharMods",
            returnType = "boolean",
            parameters = listOf(
                CallbackBridgeParameter("char", "codepoint"),
                CallbackBridgeParameter("int", "mods")
            ),
            androidVisibility = "private"
        ),
        CallbackBridgeMethod(
            name = "nativeSendKey",
            returnType = "void",
            parameters = listOf(
                CallbackBridgeParameter("int", "key"),
                CallbackBridgeParameter("int", "scancode"),
                CallbackBridgeParameter("int", "action"),
                CallbackBridgeParameter("int", "mods")
            ),
            androidVisibility = "private"
        ),
        CallbackBridgeMethod(
            name = "nativeSendCursorPos",
            returnType = "void",
            parameters = listOf(
                CallbackBridgeParameter("float", "x"),
                CallbackBridgeParameter("float", "y")
            ),
            androidVisibility = "private"
        ),
        CallbackBridgeMethod(
            name = "nativeSendMouseButton",
            returnType = "void",
            parameters = listOf(
                CallbackBridgeParameter("int", "button"),
                CallbackBridgeParameter("int", "action"),
                CallbackBridgeParameter("int", "mods")
            ),
            androidVisibility = "private"
        ),
        CallbackBridgeMethod(
            name = "nativeSendScroll",
            returnType = "void",
            parameters = listOf(
                CallbackBridgeParameter("double", "xOffset"),
                CallbackBridgeParameter("double", "yOffset")
            ),
            androidVisibility = "private"
        ),
        CallbackBridgeMethod(
            name = "nativeSendScreenSize",
            returnType = "void",
            parameters = listOf(
                CallbackBridgeParameter("int", "width"),
                CallbackBridgeParameter("int", "height")
            ),
            androidVisibility = "private"
        ),
        CallbackBridgeMethod(
            name = "nativeSetWindowAttrib",
            returnType = "void",
            parameters = listOf(
                CallbackBridgeParameter("int", "attrib"),
                CallbackBridgeParameter("int", "value")
            ),
            androidVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeRequestCloseWindow",
            returnType = "boolean",
            parameters = emptyList(),
            androidVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeSetInputReady",
            returnType = "boolean",
            parameters = listOf(CallbackBridgeParameter("boolean", "inputReady")),
            androidVisibility = "public",
            jvmVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeSetRuntimeForeground",
            returnType = "void",
            parameters = listOf(CallbackBridgeParameter("boolean", "foreground")),
            androidVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeIsRuntimeForeground",
            returnType = "boolean",
            parameters = emptyList(),
            androidVisibility = "public",
            jvmVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeSetGrabbing",
            returnType = "void",
            parameters = listOf(CallbackBridgeParameter("boolean", "grabbing")),
            androidVisibility = "public",
            jvmVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeSetAudioMuted",
            returnType = "void",
            parameters = listOf(CallbackBridgeParameter("boolean", "muted")),
            androidVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeEnableGamepadDirectInput",
            returnType = "boolean",
            parameters = emptyList(),
            androidVisibility = "public",
            jvmVisibility = "private"
        ),
        CallbackBridgeMethod(
            name = "nativeSetGlBridgeSwapHeartbeatLoggingEnabled",
            returnType = "void",
            parameters = listOf(CallbackBridgeParameter("boolean", "enabled")),
            androidVisibility = "private"
        ),
        CallbackBridgeMethod(
            name = "nativeGetGlSwapCount",
            returnType = "int",
            parameters = emptyList(),
            androidVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeGetGlContextGeneration",
            returnType = "int",
            parameters = emptyList(),
            androidVisibility = "public",
            jvmVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeGetCursorX",
            returnType = "float",
            parameters = emptyList(),
            androidVisibility = "public",
            jvmVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeGetCursorY",
            returnType = "float",
            parameters = emptyList(),
            androidVisibility = "public",
            jvmVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeClipboard",
            returnType = "String",
            parameters = listOf(
                CallbackBridgeParameter("int", "type"),
                CallbackBridgeParameter("byte[]", "copySource")
            ),
            androidVisibility = "public",
            jvmVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeCreateGamepadButtonBuffer",
            returnType = "ByteBuffer",
            parameters = emptyList(),
            androidVisibility = "private",
            jvmVisibility = "public"
        ),
        CallbackBridgeMethod(
            name = "nativeCreateGamepadAxisBuffer",
            returnType = "ByteBuffer",
            parameters = emptyList(),
            androidVisibility = "private",
            jvmVisibility = "public"
        )
    )

    val contractHash: String = sha1Hex(
        buildString {
            constants.forEach { constant ->
                append(constant.name)
                append('|')
                append(constant.type)
                append('|')
                append(constant.initializer)
                append('|')
                append(constant.android)
                append('|')
                append(constant.jvm)
                append('\n')
            }
            methods.forEach { method ->
                append(method.name)
                append('|')
                append(method.returnType)
                append('|')
                append(method.androidVisibility ?: "-")
                append('|')
                append(method.jvmVisibility ?: "-")
                method.parameters.forEach { parameter ->
                    append('|')
                    append(parameter.type)
                    append(' ')
                    append(parameter.name)
                }
                append('\n')
            }
        }
    )

    fun renderTemplate(template: String, target: CallbackBridgeTarget): String {
        return template
            .replace("__CALLBACK_BRIDGE_CONTRACT_HASH__", contractHash)
            .replace("__CALLBACK_BRIDGE_CONSTANTS__", renderConstants(target))
            .replace("__CALLBACK_BRIDGE_NATIVE_METHODS__", renderMethods(target))
    }

    fun fingerprint(vararg parts: String): String = sha1Hex(parts.joinToString(separator = "\n"))

    private fun renderConstants(target: CallbackBridgeTarget): String {
        val rendered = constants
            .filter { constant ->
                when (target) {
                    CallbackBridgeTarget.ANDROID -> constant.android
                    CallbackBridgeTarget.JVM -> constant.jvm
                }
            }
            .joinToString("\n") { constant ->
                "    public static final ${constant.type} ${constant.name} = ${constant.initializer};"
            }
        return rendered.trimEnd()
    }

    private fun renderMethods(target: CallbackBridgeTarget): String {
        val rendered = methods.mapNotNull { method ->
            val visibility = when (target) {
                CallbackBridgeTarget.ANDROID -> method.androidVisibility
                CallbackBridgeTarget.JVM -> method.jvmVisibility
            } ?: return@mapNotNull null
            val parameters = method.parameters.joinToString(", ") { parameter ->
                "${parameter.type} ${parameter.name}"
            }
            "    $visibility static native ${method.returnType} ${method.name}($parameters);"
        }.joinToString("\n\n")
        return rendered.trimEnd()
    }

    private fun sha1Hex(value: String): String {
        return MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
