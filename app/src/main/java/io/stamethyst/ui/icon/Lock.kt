/*
 * Material Design Icons source:
 * https://github.com/google/material-design-icons/blob/master/src/action/lock/materialicons/24px.svg
 */
package io.stamethyst.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import io.stamethyst.ui.Icons

val Icons.Lock: ImageVector
    get() {
        if (_lock != null) {
            return _lock!!
        }
        _lock = ImageVector.Builder(
            name = "Filled.Lock",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(18f, 8f)
                horizontalLineTo(17f)
                verticalLineTo(6f)
                curveTo(17f, 3.24f, 14.76f, 1f, 12f, 1f)
                curveTo(9.24f, 1f, 7f, 3.24f, 7f, 6f)
                verticalLineTo(8f)
                horizontalLineTo(6f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(10f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(12f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(10f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(12f, 17f)
                curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
                curveToRelative(0f, -1.1f, 0.9f, -2f, 2f, -2f)
                curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
                curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f)
                close()
                moveTo(15.1f, 8f)
                horizontalLineTo(8.9f)
                verticalLineTo(6f)
                curveTo(8.9f, 4.29f, 10.29f, 2.9f, 12f, 2.9f)
                curveToRelative(1.71f, 0f, 3.1f, 1.39f, 3.1f, 3.1f)
                verticalLineTo(8f)
                close()
            }
        }.build()
        return _lock!!
    }

private var _lock: ImageVector? = null
