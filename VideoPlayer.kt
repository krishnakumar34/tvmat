package com.example.tvmat

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    
    // STATE: Track if MPV is actually ready to receive commands
    var isInitialized by remember { mutableStateOf(false) }

    // 1. Initialize MPV Engine (Runs once safely)
    LaunchedEffect(Unit) {
        try {
            MPVLib.create(context.applicationContext) 
            
            // --- VIDEO & DECODING OPTIONS (FIXED FOR TS FILES) ---
            MPVLib.setOptionString("vo", "gpu") 
            MPVLib.setOptionString("gpu-context", "android")
            
            // FIX: Changed from 'mediacodec' to 'auto'. 
            // TS files often fail with strict hardware decoding; 'auto' allows software fallback.
            MPVLib.setOptionString("hwdec", "mediacodec") 
            MPVLib.setOptionString("hwdec-codecs", "all")
            
            // FIX: Increase probe size to detect TS/Stream formats better
            //MPVLib.setOptionString("demuxer-lavf-probesize", "5000000")
            
            MPVLib.setOptionString("tls-verify", "no")
            // Force video to stretch to screen size (ignore aspect ratio)
            MPVLib.setOptionString("keepaspect", "no")

            // --- CONTROLS (OSC) ---
            // Note: If osc=yes, MPV hides the custom 'osd-bar'. This is internal MPV behavior.
            val oscVal = if (showControls) "yes" else "no"
            MPVLib.setOptionString("osc", oscVal)
            MPVLib.setOptionString("input-default-bindings", "yes")

            // --- OSD SETTINGS (FORCE SEEK BAR VISIBILITY) ---
            MPVLib.setOptionString("osd-level", "3")       
            
            // "msg-bar" ensures we see the TIME + The BAR when seeking
            MPVLib.setOptionString("osd-on-seek", "msg-bar") 
            MPVLib.setOptionString("osd-scale-by-window", "no")
            MPVLib.setOptionString("osd-duration", "2500")

            // VISUAL STYLE (Applies only when osc=no)
            MPVLib.setOptionString("osd-font-size", "50")
            MPVLib.setOptionString("osd-bar-align-y", "0.9") 
            MPVLib.setOptionString("osd-bar-w", "95")      
            MPVLib.setOptionString("osd-bar-h", "3")       
            MPVLib.setOptionString("osd-color", "#FFFFFFFF") 
            MPVLib.setOptionString("osd-border-color", "#FF000000")

            MPVLib.init()
            
            isInitialized = true // Mark as Ready
        } catch (e: Exception) {
            Log.e("MPV", "Failed to initialize MPV: ${e.message}")
        }
    }

    // 2. Dynamic Controls Update (Runtime)
    LaunchedEffect(showControls, isInitialized) {
        if (isInitialized) {
            val valStr = if (showControls) "yes" else "no"
            try {
               MPVLib.setPropertyString("osc", valStr)
            } catch (e: Exception) {}
        }
    }

    // 3. Lifecycle Observer (Handle TV Pause/Resume)
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
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 4. Handle URL Loading
    LaunchedEffect(url, isInitialized) {
        if (isInitialized && url.isNotEmpty()) {
            try {
                // Use spread operator (*) to unpack the command array
                MPVLib.command(*arrayOf("loadfile", url))
            } catch (e: Exception) {
                Log.e("MPV", "Error playing URL: ${e.message}")
            }
        }
    }

    // 5. Cleanup on Exit
    DisposableEffect(Unit) {
        onDispose {
            try {
                // Good practice to detach before destroying
                MPVLib.detachSurface()
                MPVLib.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 6. Render Surface
    AndroidView(
        factory = {
            SurfaceView(context).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        if (isInitialized) {
                            MPVLib.attachSurface(holder.surface)
                        } else {
                            // Retry mechanism if surface is ready before MPV init
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                try { MPVLib.attachSurface(holder.surface) } catch(e:Exception){}
                            }, 500)
                        }
                        keepScreenOn = true 
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        if (isInitialized) {
                            MPVLib.setPropertyInt("android-surface-size", width * height)
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        if (isInitialized) {
                            MPVLib.detachSurface()
                        }
                    }
                })
            }
        },
        modifier = modifier
    )
}
