package com.rsps1008.stockify.ui.screens

import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.rsps1008.stockify.data.StockMarket

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YahooWebViewScreen(
    stockCode: String,
    market: String,
    navController: NavController
) {
    val context = LocalContext.current
    val yahooUrl = remember(stockCode, market) { buildYahooQuoteUrl(stockCode, market) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Yahoo 股市") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        AndroidView(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            factory = {
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = WebViewClient()
                    webChromeClient = WebChromeClient()
                    loadUrl(yahooUrl)
                }
            },
            update = { view ->
                webView = view
                if (view.url != yahooUrl) {
                    view.loadUrl(yahooUrl)
                }
            }
        )
    }
}

private fun buildYahooQuoteUrl(stockCode: String, market: String): String {
    val quoteSymbol = when (StockMarket.normalize(market)) {
        StockMarket.TW -> if (stockCode.endsWith(".TW", ignoreCase = true)) stockCode else "$stockCode.TW"
        else -> stockCode
    }
    return "https://tw.stock.yahoo.com/quote/${Uri.encode(quoteSymbol)}"
}
