package app.hearth.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A few icons the bundled Material set doesn't include, as 24dp vectors. */
object HearthIcons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) b.addPath(addPathNodes(p), fill = SolidColor(Color.Black))
        return b.build()
    }

    val Flame by lazy {
        icon("Flame", "M12,2C13.2,5.3 17.5,7.4 17.5,12.2C17.5,15.6 15.1,18.5 12,18.5C8.9,18.5 6.5,15.6 6.5,12.2C6.5,9.9 7.8,8.3 8.8,7.2C9,9 9.9,10.1 11,10.5C10.5,7.6 10.9,4.6 12,2Z" +
            "M5,20.5h14v1.5h-14z")
    }
    val Copy by lazy {
        icon("Copy", "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z")
    }
    val Branch by lazy {
        icon("Branch", "M14,4l2.29,2.29 -2.88,2.88 1.42,1.42 2.88,-2.88L20,10V4H14zM10,4H4v6l2.29,-2.29 4.71,4.7V20h2v-8.41L7.71,6.29 10,4z")
    }
    val Sparkle by lazy {
        icon("Sparkle", "M12,2l2.2,6.6L21,11l-6.8,2.4L12,20l-2.2,-6.6L3,11l6.8,-2.4z" + "M19,15l0.9,2.1L22,18l-2.1,0.9L19,21l-0.9,-2.1L16,18l2.1,-0.9z")
    }
    val Continue by lazy { icon("Continue", "M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z") }
    val Mask by lazy {
        icon("Mask", "M12,3C7,3 3,4.5 3,4.5V11c0,5 4,9 9,10c5,-1 9,-5 9,-10V4.5C21,4.5 17,3 12,3zM8.5,11.5c-0.8,0 -1.5,-0.7 -1.5,-1.5h3C10,10.8 9.3,11.5 8.5,11.5zM12,17c-2,0 -3.5,-1.3 -4,-3h8C15.5,15.7 14,17 12,17zM15.5,11.5c-0.8,0 -1.5,-0.7 -1.5,-1.5h3C17,10.8 16.3,11.5 15.5,11.5z")
    }
    val Stop by lazy { icon("Stop", "M7,7h10v10H7z") }
    val Book by lazy {
        icon("Book", "M18,2H6C4.9,2 4,2.9 4,4v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4C20,2.9 19.1,2 18,2zM6,4h5v8l-2.5,-1.5L6,12V4z")
    }
    val Group by lazy {
        icon("Group", "M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3S14.34,11 16,11zM8,11c1.66,0 2.99,-1.34 2.99,-3S9.66,5 8,5C6.34,5 5,6.34 5,8S6.34,11 8,11zM8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5C15,14.17 10.33,13 8,13zM16,13c-0.29,0 -0.62,0.02 -0.97,0.05c1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5C23,14.17 18.33,13 16,13z")
    }
    val Upload by lazy { icon("Upload", "M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z") }
    val Regex by lazy {
        icon("Regex", "M16,3h2v3.27l2.83,-1.63 1,1.73L19,8l2.83,1.63 -1,1.73L18,9.73V13h-2V9.73l-2.83,1.63 -1,-1.73L15,8l-2.83,-1.63 1,-1.73L16,6.27V3zM3,15h6v6H3z")
    }
}
