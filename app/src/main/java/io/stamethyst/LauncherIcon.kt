package io.stamethyst

enum class LauncherIcon(
    val title: String,
    val description: String,
    private val aliasClassName: String,
) {
    CLASSIC(
        title = "故障机器人",
        description = "默认图标",
        aliasClassName = ".alias.LauncherIconClassicAlias"
    ),
    RUBY(
        title = "铁甲战士",
        description = "铁甲战士图标",
        aliasClassName = ".alias.LauncherIconRubyAlias"
    ),
    EMERALD(
        title = "静默猎手",
        description = "静默猎手图标",
        aliasClassName = ".alias.LauncherIconEmeraldAlias"
    ),
    AMBER(
        title = "观者",
        description = "观者图标",
        aliasClassName = ".alias.LauncherIconAmberAlias"
    );

    fun resolveAliasClassName(packageName: String): String {
        return if (aliasClassName.startsWith(".")) {
            "$packageName$aliasClassName"
        } else {
            aliasClassName
        }
    }
}
