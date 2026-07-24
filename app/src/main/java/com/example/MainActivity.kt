package com.example

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme

/**
 * KeyInjectionEngine formats and dispatches keyboard events directly into the WebView's active element.
 */
object KeyInjectionEngine {
    fun inject(webView: WebView, key: String, code: String, keyCode: Int) {
        val scriptFormat = """
            (function() {
                var target = document.activeElement || document.body || document;
                var createEvent = function(type) {
                    return new KeyboardEvent(type, { key: '%s', code: '%s', keyCode: %d, which: %d, bubbles: true, cancelable: true, view: window });
                };
                target.dispatchEvent(createEvent('keydown'));
                setTimeout(function() { target.dispatchEvent(createEvent('keyup')); }, 10);
            })();
        """.trimIndent()
        
        val js = String.format(scriptFormat, key, code, keyCode, keyCode)
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep the device screen awake continuously
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        enableEdgeToEdge()
        
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }
    }
}

@SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
@Composable
fun MainScreen(
    mainHandler: Handler = remember { Handler(Looper.getMainLooper()) }
) {
    // Reactive States
    var isControlsVisible by remember { mutableStateOf(true) }
    var isVideoPlaying by remember { mutableStateOf(false) }
    var triggeredByVideoPlay by remember { mutableStateOf(false) }
    
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
            .testTag("main_screen_container")
    ) {
        // Web Engine Layer
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    webViewInstance = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    keepScreenOn = true
                    
                    // WebView settings
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        // User-Agent spoofing exactly as required
                        userAgentString = "Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 SmartTV"
                    }
                    
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            
                            // Inject JavaScript video state observer script at document end
                            val jsObserver = """
                                (function() {
                                    function attachVideoListeners(video) {
                                        if (video && !video.hasAttribute('data-tracked')) {
                                            video.setAttribute('data-tracked', 'true');
                                            video.addEventListener('play', function() {
                                                window.videoObserver.postMessage("playing");
                                            });
                                            video.addEventListener('pause', function() {
                                                window.videoObserver.postMessage("paused");
                                            });
                                            if (!video.paused) {
                                                window.videoObserver.postMessage("playing");
                                            }
                                        }
                                    }
                                    var existingVideo = document.querySelector('video');
                                    if (existingVideo) attachVideoListeners(existingVideo);
                                    var observer = new MutationObserver(function(mutations) {
                                        var v = document.querySelector('video');
                                        if (v) attachVideoListeners(v);
                                    });
                                    observer.observe(document.body, { childList: true, subtree: true });
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(jsObserver, null)
                        }
                    }
                    
                    // Register bidirectional Javascript Interface "videoObserver"
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun postMessage(message: String) {
                            mainHandler.post {
                                if (message == "playing") {
                                    triggeredByVideoPlay = true
                                    isVideoPlaying = true
                                    isControlsVisible = false
                                } else if (message == "paused") {
                                    isVideoPlaying = false
                                }
                            }
                        }
                    }, "videoObserver")
                    
                    // Setup non-blocking tap detector to toggle controls
                    val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapUp(e: MotionEvent): Boolean {
                            triggeredByVideoPlay = false
                            isControlsVisible = !isControlsVisible
                            return false
                        }
                    })
                    setOnTouchListener { _, event ->
                        gestureDetector.onTouchEvent(event)
                        false
                    }
                    
                    loadUrl("https://www.youtube.com/tv")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // UI Layer & Glassmorphic HUD Controller Deck in bottom-right corner
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Persistent Toggle Button at the top-right of the control grouping
                Box(
                    modifier = Modifier
                        .size(48.dp) // meets 48dp target while maintaining sleek HUD look
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0x99000000)) // bg-black/60
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(24.dp)) // border-white/15
                        .shadow(12.dp, RoundedCornerShape(24.dp))
                        .clickable {
                            triggeredByVideoPlay = false
                            isControlsVisible = !isControlsVisible
                        }
                        .testTag("hud_toggle_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isControlsVisible) Icons.Default.VisibilityOff else Icons.Default.TouchApp,
                        contentDescription = if (isControlsVisible) "Hide control panel" else "Show control panel",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                // Layout / slide + fade animation configurations
                val exitSpecFloat = if (triggeredByVideoPlay) tween<Float>(durationMillis = 400) else spring()
                val exitSpecOffset = if (triggeredByVideoPlay) tween<IntOffset>(durationMillis = 400) else spring()
                
                val enterSpecFloat = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                val enterSpecOffset = spring<IntOffset>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                
                AnimatedVisibility(
                    visible = isControlsVisible,
                    enter = slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec = enterSpecOffset
                    ) + fadeIn(animationSpec = enterSpecFloat),
                    exit = slideOutVertically(
                        targetOffsetY = { it / 2 },
                        animationSpec = exitSpecOffset
                    ) + fadeOut(animationSpec = exitSpecFloat)
                ) {
                    // Glassmorphic D-Pad overlay
                    DPadOverlay(
                        webView = webViewInstance,
                        isVideoPlaying = isVideoPlaying
                    )
                }
            }
        }

        // Bottom Status Bar Decor (Frosted gesture bar layout accent)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .width(96.dp)
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
        )
    }
}

@Composable
fun DPadOverlay(
    webView: WebView?,
    isVideoPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .shadow(24.dp, RoundedCornerShape(24.dp))
            .background(Color(0xCC09090B), RoundedCornerShape(24.dp)) // bg-zinc-950/80
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp)) // border-white/20
            .padding(16.dp)
            .width(210.dp)
            .testTag("dpad_overlay_panel"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp) // gap-3 (12dp)
    ) {
        // ROW 1: UTILITY ROW
        Row(
            modifier = Modifier.width(180.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ESC Button
            DPadButton(
                text = "ESC",
                onClick = {
                    webView?.let { KeyInjectionEngine.inject(it, "Escape", "Escape", 27) }
                },
                modifier = Modifier
                    .width(65.dp)
                    .height(32.dp)
                    .testTag("esc_button"),
                backgroundColor = Color(0xE6DC2626), // bg-red-600/90
                shape = RoundedCornerShape(6.dp), // rounded-md
                borderStroke = null,
                fontSize = 11.sp,
                letterSpacing = 1.2.sp
            )
            
            // Dynamic PLAY/PAUSE Button
            DPadButton(
                text = if (isVideoPlaying) "PAUSE" else "PLAY",
                onClick = {
                    webView?.let { KeyInjectionEngine.inject(it, " ", "Space", 32) }
                },
                modifier = Modifier
                    .width(95.dp)
                    .height(32.dp)
                    .testTag("play_pause_button"),
                backgroundColor = Color(0x1AFFFFFF), // bg-white/10
                borderStroke = BorderStroke(1.dp, Color(0x1AFFFFFF)), // border-white/10
                shape = RoundedCornerShape(6.dp), // rounded-md
                fontSize = 11.sp,
                letterSpacing = 1.2.sp
            )
        }
        
        // ROW 2: D-PAD UP ROW
        Row(
            modifier = Modifier.width(180.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            DPadButton(
                icon = {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Up",
                        tint = Color.White.copy(alpha = 0.7f), // opacity-70
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = {
                    webView?.let { KeyInjectionEngine.inject(it, "ArrowUp", "ArrowUp", 38) }
                },
                modifier = Modifier
                    .width(54.dp)
                    .height(42.dp)
                    .testTag("arrow_up_button"),
                backgroundColor = Color(0x0DFFFFFF), // bg-white/5
                borderStroke = BorderStroke(1.dp, Color(0x0DFFFFFF)), // border-white/5
                shape = RoundedCornerShape(8.dp) // rounded-lg
            )
        }
        
        // ROW 3: D-PAD HORIZONTAL AXIS ROW
        Row(
            modifier = Modifier.width(180.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Button
            DPadButton(
                icon = {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = "Left",
                        tint = Color.White.copy(alpha = 0.7f), // opacity-70
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = {
                    webView?.let { KeyInjectionEngine.inject(it, "ArrowLeft", "ArrowLeft", 37) }
                },
                modifier = Modifier
                    .width(54.dp)
                    .height(42.dp)
                    .testTag("arrow_left_button"),
                backgroundColor = Color(0x0DFFFFFF), // bg-white/5
                borderStroke = BorderStroke(1.dp, Color(0x0DFFFFFF)), // border-white/5
                shape = RoundedCornerShape(8.dp) // rounded-lg
            )
            
            // OK Center Button (Blue-to-Cyan Gradient)
            DPadButton(
                text = "OK",
                onClick = {
                    webView?.let { KeyInjectionEngine.inject(it, "Enter", "Enter", 13) }
                },
                modifier = Modifier
                    .width(54.dp)
                    .height(42.dp)
                    .testTag("ok_button"),
                gradientBrush = Brush.linearGradient(
                    colors = listOf(Color(0xFF2563EB), Color(0xFF22D3EE)) // from-blue-600 to-cyan-400
                ),
                contentColor = Color.Black,
                fontSize = 11.sp,
                shape = RoundedCornerShape(8.dp), // rounded-lg
                borderStroke = null
            )
            
            // Right Button
            DPadButton(
                icon = {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = "Right",
                        tint = Color.White.copy(alpha = 0.7f), // opacity-70
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = {
                    webView?.let { KeyInjectionEngine.inject(it, "ArrowRight", "ArrowRight", 39) }
                },
                modifier = Modifier
                    .width(54.dp)
                    .height(42.dp)
                    .testTag("arrow_right_button"),
                backgroundColor = Color(0x0DFFFFFF), // bg-white/5
                borderStroke = BorderStroke(1.dp, Color(0x0DFFFFFF)), // border-white/5
                shape = RoundedCornerShape(8.dp) // rounded-lg
            )
        }
        
        // ROW 4: D-PAD DOWN ROW
        Row(
            modifier = Modifier.width(180.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            DPadButton(
                icon = {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Down",
                        tint = Color.White.copy(alpha = 0.7f), // opacity-70
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = {
                    webView?.let { KeyInjectionEngine.inject(it, "ArrowDown", "ArrowDown", 40) }
                },
                modifier = Modifier
                    .width(54.dp)
                    .height(42.dp)
                    .testTag("arrow_down_button"),
                backgroundColor = Color(0x0DFFFFFF), // bg-white/5
                borderStroke = BorderStroke(1.dp, Color(0x0DFFFFFF)), // border-white/5
                shape = RoundedCornerShape(8.dp) // rounded-lg
            )
        }
    }
}

@Composable
fun DPadButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = "",
    icon: (@Composable () -> Unit)? = null,
    backgroundColor: Color = Color(0x2BFFFFFF),
    contentColor: Color = Color.White,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    borderStroke: BorderStroke? = BorderStroke(1.dp, Color(0x1AFFFFFF)),
    gradientBrush: Brush? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    letterSpacing: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // Custom scale interaction feedback (scales down to 0.94f on press)
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "button_press_scale"
    )
    
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .run {
                if (gradientBrush != null) {
                    background(gradientBrush)
                } else {
                    background(backgroundColor)
                }
            }
            .run {
                if (borderStroke != null) {
                    border(borderStroke, shape)
                } else {
                    this
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current
            ) {
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            icon()
        } else {
            Text(
                text = text,
                color = contentColor,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                letterSpacing = letterSpacing
            )
        }
    }
}
