package com.vh.myrecap

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.vh.myrecap.core.QuickTemplate
import com.vh.myrecap.widget.CaptureWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** The unified widget inflated in both sizes and both themes (no launcher needed), with pictures for review. */
@RunWith(AndroidJUnit4::class)
class WidgetRenderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val templates = listOf("gọi … mai", "họp … tuần sau", "đổ xăng 50k").map { QuickTemplate(it) }

    @Test
    fun heightDecidesCompactOrFull() {
        assertFalse(CaptureWidget.isFull(0))
        assertFalse(CaptureWidget.isFull(70))
        assertTrue(CaptureWidget.isFull(130))
    }

    @Test
    fun rendersCompactAndFullInLightAndDark() {
        for (night in listOf(false, true)) for (full in listOf(false, true)) {
            val cfg = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val themed = context.createConfigurationContext(cfg)
            val views = CaptureWidget.render(themed, emptyList(), templates, full)
            lateinit var root: View
            instrumentation.runOnMainSync {
                root = views.apply(themed, FrameLayout(themed))
                val w = (360 * themed.resources.displayMetrics.density).toInt()
                val h = ((if (full) 140 else 72) * themed.resources.displayMetrics.density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
            }
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_mic).visibility)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_type).visibility)
            assertEquals(if (full) View.VISIBLE else null, root.findViewById<View>(R.id.widget_template_0)?.visibility)
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            save(bmp, "widget-${if (full) "full" else "compact"}-${if (night) "dark" else "light"}")
        }
    }

    private fun save(bmp: Bitmap, name: String) {
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val b64 = Base64.encodeToString(png, Base64.NO_WRAP)
        val dir = "/data/local/tmp/myrecap-shots"
        device.executeShellCommand("mkdir -p $dir")
        device.executeShellCommand("rm -f $dir/$name.b64")
        b64.chunked(30_000).forEach { device.executeShellCommand("echo -n $it >> $dir/$name.b64") }
        device.executeShellCommand("base64 -d $dir/$name.b64 > $dir/$name.png")
        device.executeShellCommand("rm -f $dir/$name.b64")
    }
}
