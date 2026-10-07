/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.ui.screen.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import org.eu.nl.syu.zeroclaw.service.engine.EngineProcessManager

/**
 * Embeds the official ZeroClaw web dashboard served by the local gateway.
 *
 * The dashboard is the canonical configuration surface: edits made here are
 * persisted by the engine to `config.toml`, and the app observes them through
 * the gateway config API rather than maintaining a parallel copy.
 *
 * The screen deliberately renders no top app bar of its own: the application
 * shell already provides one for sub-screens, so a second bar would duplicate
 * the header. Reload and open-in-browser actions float over the WebView.
 *
 * @param host Loopback host of the gateway.
 * @param port Gateway port.
 *
 * JavaScript is required for the local dashboard web app; navigation is locked
 * to loopback by LoopbackWebViewClient, so no remote content is loaded.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebDashboardScreen(
    host: String = "127.0.0.1",
    port: Int = EngineProcessManager.DEFAULT_PORT,
) {
    val context = LocalContext.current
    val url = "http://$host:$port/"
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    }
                    setBackgroundColor(Color.TRANSPARENT)
                    webViewClient = LoopbackWebViewClient()
                    webChromeClient =
                        object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(
                                view: WebView?,
                                newProgress: Int,
                            ) {
                                progress = newProgress
                            }
                        }
                    loadUrl(url)
                    webView = this
                }
            },
        )

        if (progress in PROGRESS_START..PROGRESS_END) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        Row(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(
                        color = ComposeColor.Black.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(24.dp),
                    ),
        ) {
            IconButton(onClick = { webView?.reload() }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Reload dashboard",
                    tint = ComposeColor.White,
                )
            }
            IconButton(onClick = { openInBrowser(context, url) }) {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = "Open in browser",
                    tint = ComposeColor.White,
                )
            }
        }
    }
}

/**
 * Keeps dashboard navigation inside the loopback origin and blocks any
 * accidental external navigation from the embedded view.
 */
private class LoopbackWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean {
        val target = request?.url ?: return false
        val isLoopback = target.host == "127.0.0.1" || target.host == "localhost"
        return !isLoopback
    }
}

@SuppressLint("QueryPermissionsNeeded")
private fun openInBrowser(
    context: Context,
    url: String,
) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())
    runCatching { context.startActivity(intent) }
}

private const val PROGRESS_START = 1
private const val PROGRESS_END = 99
