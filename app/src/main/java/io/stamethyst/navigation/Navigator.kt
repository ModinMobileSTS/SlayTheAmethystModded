package io.stamethyst.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

@Composable
fun rememberAppNavigator(
  vararg initialBackStack: NavKey,
): Navigator {
  val backStack = rememberNavBackStack(elements = initialBackStack)
  return remember(backStack) { Navigator(backStack) }
}

/**
 * 用于管理应用的导航状态的类
 *
 * @property backStack 导航栈
 */
@Stable
class Navigator internal constructor(
  val backStack: NavBackStack<NavKey>,
) {
  /**
   * 检查是否可以返回
   */
  val canGoBack: Boolean inline get() = backStack.size > 1

  /**
   * 获取栈的大小
   */
  val stackSize: Int inline get() = backStack.size

  /**
   * 导航到新页面
   *
   * 示例：
   * ```
   * navigator.push(Settings)
   * // 栈变化: [Home, Profile] → [Home, Profile, Settings]
   * ```
   *
   * @param route 目标页面
   */
  fun push(route: NavKey) {
    backStack.add(route)
  }

  /**
   * 返回到指定页面
   *
   * 示例：
   * ```
   * navigator.popTo(Profile)
   * // 栈变化: [Home, Profile, Settings, Detail] → [Home, Profile]
   * ```
   *
   * @param route 目标页面
   * @return 是否成功（如果目标页面不在栈中返回 false）
   */
  fun popTo(route: NavKey): Boolean {
    val index = backStack.lastIndexOf(route)
    if (index >= 0 && index < backStack.lastIndex) {
      val removeCount = backStack.size - index - 1
      repeat(removeCount) {
        backStack.removeAt(backStack.lastIndex)
      }
      return true
    }
    return false
  }

  /**
   * 返回到根页面
   *
   * 示例：
   * ```
   * navigator.popToRoot()
   * // 栈变化: [Home, Profile, Settings] → [Home]
   * ```
   */
  fun popToRoot() {
    while (backStack.size > 1) {
      backStack.removeAt(backStack.lastIndex)
    }
  }

  /**
   * 返回上一页
   *
   * 示例：
   * ```
   * navigator.goBack()
   * // 栈变化: [Home, Profile, Settings] → [Home, Profile]
   * ```
   *
   * @return 是否成功返回（栈中至少有两个页面时返回 true）
   */
  fun goBack(): Boolean {
    if (backStack.size > 1) {
      backStack.removeAt(backStack.lastIndex)
      return true
    }
    return false
  }

  /**
   * 返回多个页面
   *
   * 示例：
   * ```
   * navigator.goBack(2)
   * // 栈变化: [Home, Profile, Settings, Detail] → [Home, Profile]
   * ```
   *
   * @param count 要返回的页面数量
   */
  fun goBack(count: Int) {
    repeat(count.coerceAtMost(backStack.size - 1)) {
      backStack.removeAt(backStack.lastIndex)
    }
  }

  /**
   * 替换当前页面
   *
   * 示例：
   * ```
   * navigator.replace(Account)
   * // 栈变化: [Home, Profile, Settings] → [Home, Profile, Account]
   * ```
   *
   * @param route 要替换成的页面
   */
  fun replace(route: NavKey) {
    if (backStack.isNotEmpty()) {
      backStack[backStack.lastIndex] = route
    } else {
      backStack.add(route)
    }
  }

  /**
   * 重置到新的根页面
   *
   * 示例：
   * ```
   * navigator.resetRoot(Login)
   * // 栈变化: [Home, Profile, Settings] → [Login]
   * ```
   *
   * @param route 新的根页面
   */
  fun resetRoot(route: NavKey) {
    backStack.clear()
    backStack.add(route)
  }

  /**
   * 重置到新的导航栈
   *
   * 示例：
   * ```
   * navigator.reset(listOf(Home, Dashboard))
   * // 栈变化: [Home, Profile, Settings] → [Home, Dashboard]
   * ```
   *
   * @param backStack 新的导航栈
   */
  fun reset(backStack: List<NavKey>) {
    this.backStack.clear()
    this.backStack.addAll(backStack)
  }
}

/**
 * 用于在 Compose 树中传递 [Navigator] 的 CompositionLocal
 */
val LocalNavigator: ProvidableCompositionLocal<Navigator> = compositionLocalOf {
  error("No Navigator found. Make sure to use AppNavigationDisplay or wrap with NavigatorProvider.")
}

/**
 * 从组合中获取当前的 [Navigator]
 *
 * 这是 `LocalNavigator.current` 的便捷写法
 */
val currentNavigator: Navigator
  @Composable
  @ReadOnlyComposable
  inline get() = LocalNavigator.current
