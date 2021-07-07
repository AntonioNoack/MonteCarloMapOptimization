package me.anno.montecarlo

import me.anno.config.DefaultConfig.style
import me.anno.gpu.drawing.DrawTextures.drawTexture
import me.anno.montecarlo.Studio.map
import me.anno.montecarlo.Studio.tex
import me.anno.ui.base.Panel

object TextureView : Panel(style) {

    override fun tickUpdate() {
        super.tickUpdate()
        invalidateDrawing()
    }

    override fun onDraw(x0: Int, y0: Int, x1: Int, y1: Int) {
        super.onDraw(x0, y0, x1, y1)

        val tex = tex!!

        // calculate an update
        map.update()
        // map the data to colors
        map.mapValues()
        // upload the image
        tex.createRGBA(map.colors)
        // draw the image
        tex.bind(0)

        val cx = x + w / 2
        val cy = y + h / 2

        var dx = w / 2
        var dy = h / 2

        // scale it small
        if (dy * map.width > dx * map.height) {
            dy = dx * map.height / map.width
        } else {
            dx = dy * map.width / map.height
        }

        drawTexture(cx - dx, cy - dy, dx * 2, dy * 2, tex, -1, null)

        // GFX.copy()

    }

}