package io.github.hypervolumeanc.nav

import android.view.View
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import java.util.function.IntConsumer

/**
 * 直接把 HyperChanger（Apache-2.0，作者 btm_m）的玻璃导航栏接到原生界面上：
 * 原生页面仍然由 Java 管理，这里只把导航栏用 Compose 画出来并回调选中项。
 */
object NavHost {
    private var selection: MutableIntState? = null

    /** 供 Java 侧（例如左右滑动切页）同步底栏选中项。 */
    @JvmStatic
    fun select(index: Int) {
        val state = selection ?: return
        if (state.intValue != index) {
            state.intValue = index
        }
    }

    @JvmStatic
    fun install(
        composeView: ComposeView,
        sourceView: View,
        navigationStyle: String,
        colorMode: String,
        labelMode: String,
        initialIndex: Int,
        labels: List<String>,
        iconPaths: List<String>,
        onSelect: IntConsumer,
    ) {
        val icons = iconPaths.map { vectorFromPath(it) }
        composeView.setContent {
            val selected = remember { mutableIntStateOf(initialIndex) }
            selection = selected
            val tabs = labels.mapIndexed { index, label ->
                HostTab(
                    label = label,
                    className = "tab.$index",
                    icon = icons[index],
                ) {
                    selected.intValue = index
                    onSelect.accept(index)
                }
            }
            CustomNavigation(
                sourceView = sourceView,
                tabs = tabs,
                selectedIndex = selected,
                blurRadius = if (navigationStyle == "liquid_glass") 3 else 18,
                labelMode = labelMode,
                navigationStyle = navigationStyle,
                advancedMaterial = true,
                colorMode = colorMode,
                liquidBottomSpacingDp = 8,
                // 液态玻璃样式的分支只认 tabImageVector / tabIconContent，
                // 不传的话会退化成按标签名猜图标（猜不到就是星星图标）。
                tabImageVector = { index -> icons[index.coerceIn(icons.indices)] },
                // 选中态不再使用 Miuix 默认的蓝色，只保留高光与字重变化。
                accentColorOverride = if (colorMode == "dark") Color.White else Color.Black,
                onHostPreDraw = {},
            )
        }
    }

    private fun vectorFromPath(path: String): ImageVector =
        ImageVector.Builder(
            name = "tab",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes(path),
                fill = SolidColor(Color.Black),
            )
        }.build()
}
