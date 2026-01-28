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
import androidx.compose.ui.viewinterop.AndroidView
import `is`.xyz.mpv.MPVLib

// --- SAFETY LOCK ---
// Prevents double-initialization crashes.
// This ensures MPVLib.init() is called only once per app session.
private var isMpvInitialized = false

@Composable
fun VideoPlayer(
    url: String,
    showControls: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 1. CONFIGURE & INITIALIZE (Runs only once)
    LaunchedEffect(Unit) {
        if (!isMpvInitialized) {
            try {
                // --- YOUR CUSTOM SETTINGS ---
                MPVLib.setOptionString("vo", "gpu")
                MPVLib.setOptionString("gpu-context", "android")
                
                // Hardware Decoding (Best for TV / TS files)
                MPVLib.setOptionString("hwdec", "mediacodec") 
                MPVLib.setOptionString("hwdec-codecs", "all")
                
                // Display & Behavior
                MPVLib.setOptionString("keepaspect", "no")
                MPVLib.setOptionString("tls-verify", "no")
                
                // OSD Settings (For native bar if needed)
                MPVLib.setOptionString("osd-level", "3")
                MPVLib.setOptionString("osd-on-seek", "msg-bar")

                // FINALIZE INIT
                MPVLib.init()
                isMpvInitialized = true // Lock to prevent future crashes
                
            } catch (e: Exception) {
                Log.e("MPV", "Init Error: ${e.message}")
            }
        }
    }

    // 2. Handle URL Loading
    LaunchedEffect(url) {
        if (url.isNotEmpty() && isMpvInitialized) {
            try {
                MPVLib.command("loadfile", url)
            } catch (e: Exception) {
                Log.e("MPV", "Load Error: ${e.message}")
            }
        }
    }

    // 3. Dynamic Controls Toggle
    LaunchedEffect(showControls) {
        if (isMpvInitialized) {
            val valStr = if (showControls) "yes" else "no"
            try {
                MPVLib.setPropertyString("osc", valStr)
            } catch (e: Exception) {}
        }
    }

    // 4. Render Surface (The Screen)
    AndroidView(
        factory = {
            SurfaceView(context).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        if (isMpvInitialized) {
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
                            MPVLib.detachSurface()
                        }
                    }
                })
            }
        },
        modifier = modifier
    )
    
    // 5. Cleanup on Dispose
    DisposableEffect(Unit) {
        onDispose {
            if (isMpvInitialized) {
                MPVLib.detachSurface()
            }
        }
    }
}
