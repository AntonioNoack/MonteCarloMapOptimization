package me.anno.montecarlo

import me.anno.animation.Type
import me.anno.config.DefaultConfig
import me.anno.config.DefaultConfig.style
import me.anno.engine.EngineActions
import me.anno.gpu.GFX
import me.anno.gpu.texture.Texture2D
import me.anno.image.Image
import me.anno.input.ActionManager
import me.anno.io.ResourceHelper
import me.anno.io.files.FileReference.Companion.getReference
import me.anno.io.files.InvalidRef
import me.anno.language.translation.Dict
import me.anno.language.translation.NameDesc
import me.anno.maths.Maths.length
import me.anno.studio.StudioBase
import me.anno.ui.Panel
import me.anno.ui.Window
import me.anno.ui.base.Visibility
import me.anno.ui.base.groups.PanelListY
import me.anno.ui.base.scrolling.ScrollPanelY
import me.anno.ui.custom.CustomList
import me.anno.ui.debug.ConsoleOutputPanel.Companion.createConsole
import me.anno.ui.editor.OptionBar
import me.anno.ui.editor.color.spaces.HSLuv
import me.anno.ui.editor.config.ConfigPanel
import me.anno.ui.input.EnumInput
import me.anno.ui.input.FileInput
import me.anno.ui.input.FloatInput
import me.anno.ui.input.IntInput
import me.anno.utils.Color.rgba
import org.joml.Vector3f
import org.lwjgl.system.MemoryUtil
import java.awt.image.BufferedImage
import java.util.*
import javax.imageio.ImageIO
import kotlin.math.*

object Studio : StudioBase(false, "Monte Carlo Map Optimization", "MapOptimization", 0) {

    var iterationsPerFrame = 100000

    fun sq(x: Float) = x * x

    var distanceType = DistanceType.MAX_NORM
    var texturePath = Texture.STAR.path
    var connectionProtector = ConnectionProtector.NO_CHECKS

    class RawImage(w: Int, h: Int, val colorMap: IntArray) : Image(w, h, 4, true) {

        val data = IntArray(width * height)
        val colors = MemoryUtil.memAllocInt(width * height)

        override fun getRGB(index: Int) = colors[index]

        constructor(image: BufferedImage, colorMap: IntArray) : this(image.width, image.height, colorMap) {
            val dc = colorMap.size - 1
            val dcx = sqrt(dc.toFloat()).roundToInt()
            val dcy = dc / dcx
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = x + y * width
                    val rgb = image.getRGB(x, y)
                    val a = rgb.shr(24).and(255)
                    val r = rgb.shr(16).and(255) * a / 255
                    if (r > 10) {
                        val cx = x * dcx / width
                        val cy = y * dcy / height
                        data[i] = 1 + cx + cy * dcx
                    }
                }
            }
            calculatePopulation()
        }

        val populationCount = IntArray(colorMap.size)
        val centerXSum = LongArray(colorMap.size)
        val centerYSum = LongArray(colorMap.size)

        // calculate the base stats
        fun calculatePopulation() {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = x + y * width
                    val dataI = data[i]
                    populationCount[dataI]++
                    centerXSum[dataI] += x.toLong()
                    centerYSum[dataI] += y.toLong()
                }
            }
        }

        fun change(x: Int, y: Int, i: Int = x + y * width, newValue: Int) {
            val oldValue = data[i]
            if (oldValue != newValue) {
                populationCount[oldValue]--
                populationCount[newValue]++
                centerXSum[oldValue] -= x.toLong()
                centerYSum[oldValue] -= y.toLong()
                centerXSum[newValue] += x.toLong()
                centerYSum[newValue] += y.toLong()
                data[i] = newValue
            }
        }

        fun mapValues() {
            // show all colors
            for (i in 0 until width * height) {
                colors.put(i, colorMap[data[i]])
            }
            // show centers of colors
            for (i in 1 until colorMap.size) {
                val population = populationCount[i]
                if (population > 0) {
                    val x = (centerXSum[i] / population).toInt()
                    val y = (centerYSum[i] / population).toInt()
                    for (ny in max(0, y - 2)..min(y + 2, height - 1)) {
                        for (nx in max(0, x - 2)..min(x + 2, width - 1)) {
                            // val dx = nx - x
                            // val dy = ny - y
                            colors.put(nx + ny * width, -1)
                        }
                    }
                }
            }
        }

        val random = Random(1234L)
        val localColors = IntArray(25)
        var randomness = 0f

        fun calculateScore(x: Int, y: Int, i: Int, value: Int): Float {
            // a sensible score
            // a large population is bad
            val population = populationCount[value].toFloat()
            val sizeScore = -population
            // a large distance is bad
            val dx = x - centerXSum[value] / population
            val dy = y - centerYSum[value] / population
            val positionScore = -distanceType.maxFunction(abs(dx), abs(dy))
            return sizeScore + positionScore
        }

        fun update() {

            val newColors = ArrayList<Int>()

            val radius = 2
            val size = radius * 2 + 1

            val startTime = System.nanoTime()

            // sample randomly from x points:
            iterations@ for (iteration in 0 until iterationsPerFrame) {

                val time = System.nanoTime()
                if (abs(time - startTime) > 30_000_000) break@iterations

                val x = radius + random.nextInt(width - 2 * radius)
                val y = radius + random.nextInt(height - 2 * radius)
                val i = x + y * width

                if (data[i] == 0) continue@iterations

                val oldColor = data[i]
                if (oldColor == data[i - 1] &&
                    oldColor == data[i + 1] &&
                    oldColor == data[i - width] &&
                    oldColor == data[i + width]
                ) continue

                for (c in listOf(
                    data[i - 1], data[i + 1],
                    data[i - width], data[i + width]
                )) {
                    if (c != oldColor && c != 0) {
                        newColors.add(c)
                    }
                }

                if (newColors.isEmpty()) continue@iterations
                val newColor = newColors[random.nextInt(newColors.size)]
                newColors.clear()

                // cancel, if this change would split the two color fields
                // this would require pathfinding, and every pixel needs to be able to find its path to the center
                when (connectionProtector) {
                    ConnectionProtector.NO_CHECKS -> {
                    }
                    ConnectionProtector.LINE_OF_SIGHT -> {
                        // another way, still not perfect
                        // line of sight must be fine
                        val ncx = x - (centerXSum[newColor].toFloat() / populationCount[newColor])
                        val ncy = y - (centerYSum[newColor].toFloat() / populationCount[newColor])

                        val length = -(radius + 0.49f) / length(ncx, ncy)

                        val dirX = (ncx * length).roundToInt()
                        val dirY = (ncy * length).roundToInt()

                        /*if (abs(ncx) > abs(ncy)) { // dx
                            dirX = if (ncx < 0) +1 else -1
                        } else {
                            dirY = if (ncy < 0) +1 else -1
                        }*/

                        // line of sight of new color must be ok
                        if (data[i + dirX + dirY * width] != newColor) {
                            continue@iterations
                        }
                    }
                    ConnectionProtector.LOCAL_PATHFINDING -> {

                        // all local pixels of the same color need to be able to find to the others of the same color
                        // a simple way, but it's not perfect

                        //  0  1  2  3  4
                        //  5  6  7  8  9
                        // 10 11 12 13 14
                        // 15 16 17 18 19
                        // 20 21 22 23 24
                        for (dy in -radius..radius) {
                            for (dx in -radius..radius) {
                                localColors[(dx + radius) + (dy + radius) * size] =
                                    data[i + dx + dy * width]
                            }
                        }

                        localColors[12] = newColor
                        for (j in 1 until size * size) {
                            for (k in 0 until j) {
                                // find path from j to k
                                if (localColors[j] == localColors[k] && oldColor == localColors[k] &&
                                    !canFindPath(
                                        localColors, true, size, size,
                                        j % size, j / size, k % size, k / size
                                    )
                                ) continue@iterations
                            }
                        }

                    }
                    ConnectionProtector.EXPENSIVE_PATHFINDING -> {

                        // extremely slow, but perfect (I think)
                        // todo -> just as it sounds, we could optimize it slightly :) (x5-x10 maybe)
                        data[i] = newColor
                        var isOK = true
                        search@ for (dy in -1..+1) {
                            for (dx in -1..+1) {
                                if (!findsPathToCenter(x + dx, y + dy)) {
                                    isOK = false
                                    break@search
                                }
                            }
                        }
                        data[i] = oldColor
                        if (!isOK) continue@iterations
                    }

                }


                // if border, then look whether the change is possible
                val isBorder = newColor != oldColor
                if (isBorder) {

                    if (randomness >= 1f || (randomness > 0f && random.nextFloat() < randomness)) {

                        change(x, y, i, newColor)

                    } else {

                        // evaluate the old and the new score
                        val oldScore = calculateScore(x, y, i, oldColor)
                        val newScore = calculateScore(x, y, i, newColor)

                        // if better or random value, do the change
                        if (newScore > oldScore) {
                            // println("changing $x $y to $newColor")
                            change(x, y, i, newColor)
                        }

                    }

                }
            }

        }

        // much too slow
        fun findsPathToCenter(x0: Int, y0: Int): Boolean {

            val type = data[x0 + y0 * width]
            if (type == 0) return true

            val todoX = ArrayList<Int>()
            val todoY = ArrayList<Int>()

            todoX.add(x0)
            todoY.add(y0)

            val targetX = (centerXSum[type] / populationCount[type]).toInt()
            val targetY = (centerYSum[type] / populationCount[type]).toInt()

            if (targetX == x0 && targetY == y0) return true

            val doneIndices = HashSet<Int>()
            doneIndices.add(x0 + y0 * width)

            while (todoX.isNotEmpty()) {
                val index0 = todoX.lastIndex
                val x = todoX.removeAt(index0)
                val y = todoY.removeAt(index0)
                // println("$x0 $y0: $x $y $type, $index0")
                for (dx in -1..+1) {
                    for (dy in -1..+1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx == targetX && ny == targetY) return true
                        if (nx in 0 until width && ny in 0 until height) {
                            // add this, if not already done
                            val index = nx + ny * width
                            if (data[index] == type && doneIndices.add(index)) {
                                todoX.add(nx)
                                todoY.add(ny)
                            }
                        }
                    }
                }
            }

            return false

        }

    }

    val checked = BooleanArray(25)
    fun canFindPath(
        data: IntArray, first: Boolean, sizeX: Int, sizeY: Int,
        sx: Int, sy: Int, ex: Int, ey: Int
    ): Boolean {
        // could be cached to 9*9*2^9 values
        if (abs(sx - ex) == 1 && sy == ey) return true
        if (abs(sy - ey) == 1 && sx == ey) return true
        // go in all viable directions from start
        if (first) {
            for (i in 0 until sizeX * sizeY) {
                checked[i] = false
            }
            // we checked our position
            checked[sx + sy * sizeX] = true
        }
        val targetColor = data[sx + sy * sizeX]
        for (dy in -1..1) {
            for (dx in -1..+1) {
                val nx = sx + dx
                val ny = sy + dy
                if (nx in 0 until sizeX && ny in 0 until sizeY) {
                    val ni = nx + ny * sizeX
                    if (data[ni] == targetColor && !checked[ni]) {
                        checked[ni] = true
                        if (canFindPath(data, false, sizeX, sizeY, nx, ny, ex, ey)) return true
                    }
                }
            }
        }
        return false
    }

    lateinit var map: RawImage
    var tex: Texture2D? = null

    fun Vector3f.toHex() = rgba((x * 255).toInt(), (y * 255).toInt(), (z * 255).toInt(), 255) or 0xff000000.toInt()

    var districtCount = 16

    fun restart() {

        try {

            val colorMap = IntArray(districtCount + 1) {
                if (it == 0) 0 // black
                else {
                    // map the colors from a HSLuv map
                    HSLuv.toRGB(Vector3f(it.toFloat() / districtCount, 0.7f, 0.7f)).toHex()
                }
            }

            val input = if (texturePath.startsWith("res://"))
                ResourceHelper.loadResource(texturePath.substring(6))
            else getReference(texturePath).inputStream()
            map = RawImage(ImageIO.read(input), colorMap)
            tex?.destroy()
            tex = Texture2D("map", map.width, map.height, 1)

        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    fun createReloadWindow(panel: Panel, fullscreen: Boolean): Window {
        val window = GFX.someWindow
        val windowStack = window.windowStack
        return object : Window(
            panel, fullscreen,
            windowStack,
            if (fullscreen) 0 else window.mouseX.toInt(),
            if (fullscreen) 0 else window.mouseY.toInt(),
        ) {
            override fun destroy() {
                createUI()
            }
        }
    }

    override fun createUI() {

        val windowStack = GFX.someWindow.windowStack
        windowStack.clear()

        Dict.loadDefault()

        restart()

        val y = PanelListY(style)

        // control bar at top for restart / loading / saving
        val options = OptionBar(style)
        options.addMajor("Restart") {
            restart()
        }
        options.addAction("Config", Dict["Settings", "ui.top.config.settings"]) {
            val panel = ConfigPanel(DefaultConfig, false, style)
            val window = createReloadWindow(panel, true)
            panel.create()
            windowStack.push(window)
        }
        options.addAction("Config", Dict["Style", "ui.top.config.style"]) {
            val panel = ConfigPanel(style.values, true, style)
            val window = createReloadWindow(panel, true)
            panel.create()
            windowStack.push(window)
        }

        y.add(options)

        val x = CustomList(false, style)

        val sceneView = TextureView()
        x.add(sceneView)
        sceneView.weight = 1f

        val controls = PanelListY(style)
        controls.add(FloatInput("Randomness", "", map.randomness, Type.FLOAT_01, style).apply {
            setChangeListener { map.randomness = it.toFloat() }
        })
        controls.add(IntInput("Samples/Frame", "", iterationsPerFrame, Type.INT_PLUS, style).apply {
            setChangeListener { iterationsPerFrame = it.toInt() }
        })
        controls.add(
            EnumInput(
                "Distance Metric", true, distanceType.displayName,
                DistanceType.values().map { NameDesc(it.displayName) }, style
            ).apply { setChangeListener { _, index, _ -> distanceType = DistanceType.values()[index] } }
        )
        controls.add(IntInput("District Count", "", districtCount, Type.INT_PLUS, style).apply {
            setChangeListener {
                districtCount = max(1, it.toInt())
                restart()
            }
        })
        var pathValue = ""
        val pathInput = FileInput("Custom path for texture", style, InvalidRef, emptyList(), false)
        pathInput.setChangeListener {
            texturePath = it.absolutePath;
            pathValue = texturePath;
            restart()
        }
        pathInput.visibility = Visibility.GONE
        controls.add(pathInput)
        controls.add(
            EnumInput(
                "Texture for outlines", true, texturePath,
                Texture.values().map { NameDesc(it.name) }, style
            ).apply {
                setChangeListener { _, index, _ ->
                    val type = Texture.values()[index]
                    pathInput.visibility = Visibility[type == Texture.CUSTOM]
                    if (type == Texture.CUSTOM) {
                        texturePath = pathValue
                    } else {
                        texturePath = type.path
                        restart()
                    }
                }
            }
        )

        controls.add(
            EnumInput(
                "Border checks", true, connectionProtector.name,
                ConnectionProtector.values().map { NameDesc(it.name) }, style
            ).apply {
                setChangeListener { _, index, _ -> connectionProtector = ConnectionProtector.values()[index] }
            }
        )

        x.add(ScrollPanelY(controls, style).apply { weight = 0.2f })

        y.add(x)

        y.add(createConsole(style))

        windowStack.add(Window(y, false, windowStack))

        EngineActions.register()
        ActionManager.init()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        run()
    }

}