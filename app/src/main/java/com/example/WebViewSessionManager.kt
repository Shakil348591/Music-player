package com.example

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object WebViewSessionManager {

    const val TARGET_URL =
        "https://mw5j3v-my-site-7ov8hzmq-shakilofficial348.wix-vibe-site.com/"

    private var contextWrapper: MutableContextWrapper? = null
    private var webView: BackgroundWebView? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserLauncher: ((Array<String>) -> Unit)? = null

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadProgress = MutableStateFlow(0)
    val loadProgress: StateFlow<Int> = _loadProgress.asStateFlow()

    private val _hasError = MutableStateFlow(false)
    val hasError: StateFlow<Boolean> = _hasError.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _stopEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopEvents: SharedFlow<Unit> = _stopEvents.asSharedFlow()

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(activityContext: Context): BackgroundWebView {
        val appContext = activityContext.applicationContext
        val wrapper = contextWrapper?.apply {
            baseContext = activityContext
        } ?: MutableContextWrapper(activityContext).also {
            contextWrapper = it
        }

        webView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            existing.onResume()
            existing.resumeTimers()
            return existing
        }

        val created = BackgroundWebView(wrapper).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            val targetView = this
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(targetView, true)
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: return false
                    if (scheme == "http" || scheme == "https") {
                        return false
                    }
                    return try {
                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        appContext.startActivity(intent)
                        true
                    } catch (_: ActivityNotFoundException) {
                        true
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    _isLoading.value = true
                    _hasError.value = false
                    _canGoBack.value = view?.canGoBack() == true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    _isLoading.value = false
                    _canGoBack.value = view?.canGoBack() == true
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true && error?.errorCode != -3) {
                        _isLoading.value = false
                        _hasError.value = true
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    _loadProgress.value = newProgress
                    if (newProgress >= 100) {
                        _isLoading.value = false
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    val resources = request?.resources ?: return
                    val granted = resources.filter {
                        it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                    }.toTypedArray()
                    if (granted.isNotEmpty()) {
                        request.grant(granted)
                    } else {
                        request.deny()
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    val mimeTypes = fileChooserParams?.acceptTypes
                        ?.filter { it.isNotBlank() }
                        ?.toTypedArray()
                        ?.takeIf { it.isNotEmpty() }
                        ?: arrayOf("*/*")
                    val launcher = fileChooserLauncher
                    if (launcher != null) {
                        launcher.invoke(mimeTypes)
                        return true
                    }
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    return false
                }
            }

            loadUrl(TARGET_URL)
        }

        webView = created
        return created
    }

    fun detachFromActivity(applicationContext: Context) {
        contextWrapper?.baseContext = applicationContext
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
    }

    fun registerFileChooserLauncher(launcher: ((Array<String>) -> Unit)?) {
        fileChooserLauncher = launcher
    }

    fun onFilesSelected(uris: List<Uri>) {
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    fun reload() {
        _hasError.value = false
        _isLoading.value = true
        val view = webView ?: return
        if (view.url.isNullOrBlank()) {
            view.loadUrl(TARGET_URL)
        } else {
            view.reload()
        }
    }

    fun goBack(): Boolean {
        val view = webView ?: return false
        return if (view.canGoBack()) {
            view.goBack()
            _canGoBack.value = view.canGoBack()
            true
        } else {
            false
        }
    }

    fun resumeWebView() {
        webView?.apply {
            onResume()
            resumeTimers()
        }
    }

    fun stopAndDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        webView?.apply {
            keepAliveInBackground = false
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        contextWrapper = null
        _isLoading.value = true
        _hasError.value = false
        _canGoBack.value = false
        _stopEvents.tryEmit(Unit)
    }
}
