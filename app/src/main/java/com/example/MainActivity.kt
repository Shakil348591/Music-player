package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startBackgroundService()

        setContent {
            MyApplicationTheme {
                WebOnlyScreen(
                    onMoveToBackground = { moveTaskToBack(true) },
                    onServiceStopped = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WebViewSessionManager.resumeWebView()
    }

    override fun onDestroy() {
        WebViewSessionManager.registerFileChooserLauncher(null)
        WebViewSessionManager.detachFromActivity(applicationContext)
        super.onDestroy()
    }

    private fun startBackgroundService() {
        val serviceIntent = Intent(this, BackgroundWebService::class.java).apply {
            action = BackgroundWebService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}

@Composable
private fun WebOnlyScreen(
    onMoveToBackground: () -> Unit,
    onServiceStopped: () -> Unit
) {
    val context = LocalContext.current
    val isLoading by WebViewSessionManager.isLoading.collectAsStateWithLifecycle()
    val loadProgress by WebViewSessionManager.loadProgress.collectAsStateWithLifecycle()
    val hasError by WebViewSessionManager.hasError.collectAsStateWithLifecycle()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { }
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            WebViewSessionManager.onFilesSelected(uris)
        }
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(Unit) {
        WebViewSessionManager.stopEvents.collectLatest {
            onServiceStopped()
        }
    }

    DisposableEffect(filePickerLauncher) {
        WebViewSessionManager.registerFileChooserLauncher { mimeTypes ->
            filePickerLauncher.launch(mimeTypes)
        }
        onDispose {
            WebViewSessionManager.registerFileChooserLauncher(null)
        }
    }

    BackHandler {
        if (!WebViewSessionManager.goBack()) {
            onMoveToBackground()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    val webView = WebViewSessionManager.getOrCreateWebView(ctx)
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    addView(webView)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("site_webview")
        )

        if (isLoading && !hasError) {
            LinearProgressIndicator(
                progress = { (loadProgress.coerceIn(0, 100)) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopCenter)
                    .testTag("loading_progress")
            )
        }

        if (hasError) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.connection_error_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.connection_error_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { WebViewSessionManager.reload() },
                    modifier = Modifier.testTag("retry_button")
                ) {
                    Text(text = stringResource(R.string.retry))
                }
            }
        }
    }
}
