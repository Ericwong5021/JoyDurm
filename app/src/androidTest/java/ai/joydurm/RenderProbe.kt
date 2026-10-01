package ai.joydurm

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.sceneview.SceneView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Diagnostic only: neither frame activity nor a surface copy satisfies display acceptance. */
internal class RenderProbe private constructor(private val scene: SceneView) : AutoCloseable {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val previous=scene.onFrame
    private var frames=0L
    private var lastFrameNs=0L
    init { scene.onFrame={ time -> frames++; lastFrameNs=SystemClock.elapsedRealtimeNanos(); previous?.invoke(time) } }

    fun capture(directory: File, label: String) {
        val report=JSONObject().put("runId",InstrumentationRegistry.getArguments().getString("joydurmRunId"))
        var bitmap: Bitmap?=null
        instrumentation.runOnMainSync {
            report.put("frames",frames).put("lastFrameAgeNs",if(lastFrameNs==0L)JSONObject.NULL else SystemClock.elapsedRealtimeNanos()-lastFrameNs)
                .put("lifecycle",scene.lifecycle?.currentState?.name).put("shown",scene.isShown)
                .put("windowFocus",scene.hasWindowFocus()).put("attached",scene.isAttachedToWindow)
                .put("surfaceValid",scene.holder.surface.isValid).put("ready",scene.uiHelper.isReadyToRender)
                .put("viewportWidth",scene.view.viewport.width).put("viewportHeight",scene.view.viewport.height)
                .put("cameraPosition",scene.cameraNode.worldPosition.toString()).put("cameraRotation",scene.cameraNode.worldRotation.toString())
            if(scene.width>0 && scene.height>0)bitmap=Bitmap.createBitmap(scene.width,scene.height,Bitmap.Config.ARGB_8888)
        }
        bitmap?.let { image ->
            val retirement=Any()
            var finished=false
            var abandoned=false
            var pending=false
            try {
                val completed=CountDownLatch(1)
                var status=PixelCopy.ERROR_UNKNOWN
                try {
                    pending=true
                    PixelCopy.request(scene.holder.surface,image,{
                        synchronized(retirement) { status=it; finished=true; if(abandoned)image.recycle() }
                        completed.countDown()
                    },Handler(Looper.getMainLooper()))
                    report.put("pixelCopyCompleted",completed.await(1,TimeUnit.SECONDS)).put("pixelCopyStatus",status)
                    if(completed.count==0L && status==PixelCopy.SUCCESS) {
                        var warm=0; var brightest=0
                        for(y in image.height*30/100 until image.height*65/100 step 4)
                            for(x in image.width/20 until image.width*19/20 step 4) {
                                val pixel=image.getPixel(x,y); val r=(pixel shr 16) and 255; val g=(pixel shr 8) and 255; val b=pixel and 255
                                brightest=maxOf(brightest,r,g,b)
                                if(r>=60 && g>=30 && r>=b+25 && g>=b+8 && r>g)warm++
                            }
                        report.put("surfaceWarmPixels",warm).put("surfaceBrightest",brightest)
                        File(directory,"$label-surface.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                    }
                } catch(error: Exception) { pending=false; report.put("pixelCopyError",error.toString()) }
            } finally {
                // A timed-out copy may still own its destination. Retire after its
                // callback, never while Android is writing into the bitmap.
                synchronized(retirement) { if(!pending || finished)image.recycle() else abandoned=true }
            }
        }
        File(directory,"$label-render-probe.json").writeText(report.toString(2))
    }

    override fun close() { instrumentation.runOnMainSync { scene.onFrame=previous } }

    companion object {
        fun attach(): RenderProbe? {
            var probe: RenderProbe?=null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                fun views(view: View): List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
                val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull()
                activity?.window?.decorView?.let { root -> views(root).filterIsInstance<SceneView>().singleOrNull()?.let { probe=RenderProbe(it) } }
            }
            return probe
        }
    }
}
