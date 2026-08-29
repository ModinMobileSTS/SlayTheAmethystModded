/*
 * Material Design Icons source:
 * https://github.com/google/material-design-icons/blob/master/src/action/account_circle/materialicons/24px.svg
 */
package io.stamethyst.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import io.stamethyst.ui.Icons

val Icons.AccountCircle: ImageVector
    get() {
        if (_accountCircle != null) {
            return _accountCircle!!
        }
        _accountCircle = ImageVector.Builder(
            name = "Filled.AccountCircle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                curveToRelative(0f, 5.52f, 4.48f, 10f, 10f, 10f)
                curveToRelative(5.52f, 0f, 10f, -4.48f, 10f, -10f)
                curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
                close()
                moveTo(12f, 6f)
                curveToRelative(1.93f, 0f, 3.5f, 1.57f, 3.5f, 3.5f)
                curveTo(15.5f, 11.43f, 13.93f, 13f, 12f, 13f)
                curveToRelative(-1.93f, 0f, -3.5f, -1.57f, -3.5f, -3.5f)
                curveTo(8.5f, 7.57f, 10.07f, 6f, 12f, 6f)
                close()
                moveTo(12f, 20f)
                curveToRelative(-2.03f, 0f, -4.43f, -0.82f, -6.14f, -2.88f)
                curveTo(7.55f, 15.8f, 9.68f, 15f, 12f, 15f)
                curveToRelative(2.32f, 0f, 4.45f, 0.8f, 6.14f, 2.12f)
                curveTo(16.43f, 19.18f, 14.03f, 20f, 12f, 20f)
                close()
            }
        }.build()
        return _accountCircle!!
    }

private var _accountCircle: ImageVector? = null
