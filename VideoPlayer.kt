package com.example.tvmat

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import `is`.xyz.mpv.MPVLib

@Composable
fun VideoPlayer(
    url: String,
    showControls: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // REMOVED: FocusRequester and onKeyEvent. 
    // Control is now fully delegated to MainActivity.kt

    key(showControls) {
        var isInitialized by remember { mutableStateOf(false) }

        DisposableEffect(Unit) {
            try {
                MPVLib.create(context.applicationContext)

                // --- PERFORMANCE SETTINGS ---
                MPVLib.setOptionString("vo", "gpu")
                MPVLib.setOptionString("gpu-context", "android")
                MPVLib.setOptionString("hwdec", "mediacodec")
                MPVLib.setOptionString("hwdec-codecs", "all")
                MPVLib.setOptionString("framedrop", "vo")
                MPVLib.setOptionString("vd-lavc-dr", "yes")
                MPVLib.setOptionString("keepaspect", "no")
                MPVLib.setOptionString("tls-verify", "no")

                // --- CONTROLS & SEEK BAR ---
                val oscVal = if (showControls) "yes" else "no"
                MPVLib.setOptionString("osc", oscVal)
                MPVLib.setOptionString("input-default-bindings", "yes")

                if (showControls) {
                    MPVLib.setOptionString("script-opts", "osc-layout=box,osc-seekbarstyle=bar,osc-deadzonesize=0,osc-minmousemove=0")
                }

                // --- OSD SETTINGS ---
                MPVLib.setOptionString("osd-level", "3")
                MPVLib.setOptionString("osd-on-seek", "msg-bar") 
                MPVLib.setOptionString("osd-scale-by-window", "no")
                MPVLib.setOptionString("osd-duration", "2500")

                // --- VISUAL STYLE ---
                MPVLib.setOptionString("osd-font-size", "50")
                MPVLib.setOptionString("osd-bar-align-y", "0.9") 
                MPVLib.setOptionString("osd-bar-w", "95")      
                MPVLib.setOptionString("osd-bar-h", "3")       
                MPVLib.setOptionString("osd-color", "#FFFFFFFF") 
                MPVLib.setOptionString("osd-border-color", "#FF000000")

                MPVLib.init()
                isInitialized = true
                
            } catch (e: Exception) {
                Log.e("MPV", "Failed to init: ${e.message}")
            }

            onDispose {
                try {
                    MPVLib.detachSurface()
                    MPVLib.destroy()
                    isInitialized = false
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (isInitialized) {
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> MPVLib.setPropertyBoolean("pause", false)
                        Lifecycle.Event.ON_PAUSE -> MPVLib.setPropertyBoolean("pause", true)
                        else -> Unit
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(url, isInitialized) {
            if (isInitialized && url.isNotEmpty()) {
                MPVLib.command(*arrayOf("loadfile", url))
            }
        }

        // WRAPPER BOX: Purely for Layout now, no input handling
        Box(modifier = modifier) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        keepScreenOn = true
                        
                        // Fallback click listener for manual taps (if using a mouse/touch)
                        isClickable = true
                        setOnClickListener {
                            if (isInitialized && showControls) {
                                MPVLib.command("script-binding", "osc/visibility")
                            }
                        }

                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                if (isInitialized) MPVLib.attachSurface(holder.surface)
                                else {
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        try { MPVLib.attachSurface(holder.surface) } catch(e:Exception){}
                                    }, 500)
                                }
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
                                if (isInitialized) MPVLib.setPropertyInt("android-surface-size", w * h)
                            }
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                if (isInitialized) MPVLib.detachSurface()
                            }
                        })
                    }
                },
                modifier = Modifier.matchParentSize()
            )
        }
    }
}
