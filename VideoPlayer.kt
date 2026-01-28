package com.example.tvmat

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import `is`.xyz.mpv.MPVLib

// --- SAFETY LOCK ---
// Prevents the "Double Init" crash and Black Screen issues
private var isMpvInitialized = false

@Composable
fun VideoPlayer(
    url: String,
    showControls: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 1. CONFIGURE & INITIALIZE (Runs only once)
    LaunchedEffect(Unit) {
        if (!isMpvInitialized) {
            try {
                // --- FIX FOR TS FILES & BLACK SCREEN ---
                MPVLib.setOptionString("vo", "gpu")
                MPVLib.setOptionString("gpu-context", "android")
                
                // CRITICAL FIX: 'auto' recovers from background better than 'mediacodec'
                MPVLib.setOptionString("hwdec", "auto") 
                MPVLib.setOptionString("hwdec-codecs", "all")
                
                // CRITICAL FIX: Probe size allows TS files to load
                MPVLib.setOptionString("demuxer-lavf-probesize", "5000000")
                MPVLib.setOptionString("demuxer-lavf-analyzeduration", "5000000")

                // FIX FOR LIVE STREAMS (Buffering)
                MPVLib.setOptionString("cache", "yes")
                MPVLib.setOptionString("demuxer-max-bytes", "64000000") // 64MB Buffer
                MPVLib.setOptionString("demuxer-max-back-bytes", "64000000")
                MPVLib.setOptionString("network-timeout", "30") // Wait longer for connection

                // Standard Settings
                MPVLib.setOptionString("keepaspect", "no")
                MPVLib.setOptionString("tls-verify", "no")
                
                // OSD
                MPVLib.setOptionString("osd-level", "3")
                MPVLib.setOptionString("osd-on-seek", "msg-bar")

                // INITIALIZE
                MPVLib.init()
                isMpvInitialized = true 
                
            } catch (e: Exception) {
                Log.e("MPV", "Init Error: ${e.message}")
            }
        }
    }

    // 2. LIFECYCLE HANDLER (Fixes Black Screen on Resume)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (isMpvInitialized) {
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        MPVLib.setPropertyBoolean("pause", true)
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        // Unpause when we come back
                        MPVLib.setPropertyBoolean("pause", false)
                    }
                    else -> {}
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 3. Handle URL Loading
    LaunchedEffect(url) {
        if (url.isNotEmpty() && isMpvInitialized) {
            try {
                MPVLib.command("loadfile", url)
            } catch (e: Exception) {
                Log.e("MPV", "Load Error: ${e.message}")
            }
        }
    }

    // 4. Dynamic Controls Toggle
    LaunchedEffect(showControls) {
        if (isMpvInitialized) {
            val valStr = if (showControls) "yes" else "no"
            try {
                MPVLib.setPropertyString("osc", valStr)
            } catch (e: Exception) {}
        }
    }

    // 5. Render Surface
    AndroidView(
        factory = {
            SurfaceView(context).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                keepScreenOn = true // Prevents screen from sleeping
                
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        if (isMpvInitialized) {
                            // Re-attach surface immediately when app opens/resumes
                            MPVLib.attachSurface(holder.surface)
                        }
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        if (isMpvInitialized) {
                            MPVLib.setPropertyInt("android-surface-size", width * height)
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        if (isMpvInitialized) {
                            // Detach safely when app minimizes
                            MPVLib.detachSurface()
                        }
                    }
                })
            }
        },
        modifier = modifier
    )
    
    // 6. Cleanup
    DisposableEffect(Unit) {
        onDispose {
            if (isMpvInitialized) {
                MPVLib.detachSurface()
            }
        }
    }
}
