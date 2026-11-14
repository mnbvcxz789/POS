package id.digitalservice.pos

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Digital Service POS — pembungkus WebView untuk aplikasi kasir (HTML) yang berjalan offline.
 *
 * Halaman dimuat dari aset lewat origin https://appassets.androidplatform.net sehingga
 * IndexedDB, crypto.subtle (hash PIN) dan fitur web lain berjalan seperti di browser.
 * Fitur yang tidak didukung WebView dijembatani lewat objek JavaScript `AndroidBridge`.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val APP_HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$APP_HOST/assets/www/index.html"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private lateinit var webView: WebView
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSaveContent: String? = null
    private var pendingBtAction: (() -> Unit)? = null
    private var pendingBtDenied: ((String) -> Unit)? = null

    // ---------- Activity result launchers ----------
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            filePathCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            filePathCallback = null
        }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val content = pendingSaveContent
            pendingSaveContent = null
            val uri = result.data?.data
            if (result.resultCode != RESULT_OK || uri == null || content == null) {
                jsToast("Penyimpanan dibatalkan", "info")
                return@registerForActivityResult
            }
            io.execute {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    jsToast("File tersimpan", "success")
                } catch (e: Exception) {
                    jsToast("Gagal menyimpan file: ${e.message}", "error")
                }
            }
        }

    private val btPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingBtAction
            val denied = pendingBtDenied
            pendingBtAction = null
            pendingBtDenied = null
            if (granted) action?.invoke()
            else denied?.invoke("Izin Bluetooth ditolak. Aktifkan lewat Info Aplikasi > Izin > Perangkat terdekat.")
        }

    // ---------- Lifecycle ----------
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        val container = FrameLayout(this)
        container.setBackgroundColor(Color.WHITE)
        webView = WebView(this)
        container.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(container)

        // Jaga konten agar tidak tertutup status bar, navigation bar, notch, dan keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setupWebView()
        setupBackHandling()
        webView.loadUrl(START_URL)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        webView.destroy()
        super.onDestroy()
    }

    // ---------- WebView ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            textZoom = 100
            setSupportZoom(false)
        }
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (url.host == APP_HOST) return false
                openExternal(url.toString())
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    Toast.makeText(this@MainActivity, "Tidak ada aplikasi pemilih file", Toast.LENGTH_SHORT).show()
                    false
                }
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("Ya") { _, _ -> result?.confirm() }
                    .setNegativeButton("Batal") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(window.handleAndroidBack && window.handleAndroidBack()) ? 'y' : 'n'"
                ) { result ->
                    if (result == null || !result.contains("y")) finish()
                }
            }
        })
    }

    // ---------- Helper ----------
    private fun callJs(script: String) {
        runOnUiThread {
            if (!isDestroyed) webView.evaluateJavascript(script, null)
        }
    }

    private fun jsToast(message: String, type: String) {
        callJs("window.__nativeToast && window.__nativeToast(${JSONObject.quote(message)}, ${JSONObject.quote(type)})")
    }

    private fun printResult(ok: Boolean, message: String) {
        callJs("window.__onNativePrintResult && window.__onNativePrintResult($ok, ${JSONObject.quote(message)})")
    }

    private fun printersResult(list: JSONArray, error: String?) {
        val err = if (error == null) "null" else JSONObject.quote(error)
        callJs("window.__onNativePrinters && window.__onNativePrinters($list, $err)")
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka tautan ini", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Bluetooth (printer thermal ESC/POS via SPP) ----------
    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun withBluetooth(onDenied: (String) -> Unit, action: () -> Unit) {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            onDenied("Perangkat ini tidak memiliki Bluetooth.")
            return
        }
        val runIfOn = {
            if (!adapter.isEnabled) onDenied("Bluetooth mati. Nyalakan Bluetooth lalu coba lagi.")
            else action()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingBtAction = runIfOn
            pendingBtDenied = onDenied
            btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        runIfOn()
    }

    @SuppressLint("MissingPermission")
    private fun listBondedPrinters() {
        val adapter = bluetoothAdapter()
        val arr = JSONArray()
        try {
            adapter?.bondedDevices?.forEach { d ->
                val major = d.bluetoothClass?.majorDeviceClass
                arr.put(
                    JSONObject()
                        .put("name", d.name ?: "Tanpa nama")
                        .put("address", d.address)
                        .put("isPrinter", major == BluetoothClass.Device.Major.IMAGING)
                )
            }
            printersResult(arr, null)
        } catch (e: SecurityException) {
            printersResult(arr, "Izin Bluetooth belum diberikan.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        val secure = device.createRfcommSocketToServiceRecord(SPP_UUID)
        return try {
            secure.connect()
            secure
        } catch (first: Exception) {
            try { secure.close() } catch (_: Exception) { }
            // Beberapa printer murah hanya mau koneksi "insecure".
            val insecure = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            try {
                insecure.connect()
                insecure
            } catch (second: Exception) {
                try { insecure.close() } catch (_: Exception) { }
                throw second
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun doPrint(address: String, text: String) {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            printResult(false, "Bluetooth tidak tersedia.")
            return
        }
        try {
            val device = adapter.getRemoteDevice(address)
            openSocket(device).use { socket ->
                val out = socket.outputStream
                out.write(byteArrayOf(0x1B, 0x40))                    // ESC @  : reset printer
                out.write(text.toByteArray(Charsets.US_ASCII))
                out.write(byteArrayOf(0x0A, 0x0A, 0x0A))               // feed kertas
                out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))         // GS V   : potong (diabaikan printer tanpa cutter)
                out.flush()
                Thread.sleep(600)
            }
            printResult(true, "Struk tercetak")
        } catch (e: Exception) {
            printResult(false, "Gagal mencetak: ${e.message ?: "koneksi gagal"}. Pastikan printer menyala & sudah dipasangkan.")
        }
    }

    // ---------- Jembatan JavaScript ----------
    inner class Bridge {
        @JavascriptInterface
        fun openExternal(url: String) {
            runOnUiThread { this@MainActivity.openExternal(url) }
        }

        @JavascriptInterface
        fun saveFile(filename: String, mime: String, content: String) {
            runOnUiThread {
                pendingSaveContent = content
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    putExtra(Intent.EXTRA_TITLE, filename)
                }
                try {
                    createDocumentLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    pendingSaveContent = null
                    jsToast("Tidak ada aplikasi pengelola file", "error")
                }
            }
        }

        @JavascriptInterface
        fun printPage(jobName: String) {
            runOnUiThread {
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                val name = jobName.ifBlank { "Struk" }
                pm.print(name, webView.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
            }
        }

        @JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Pesan WhatsApp", text))
            }
        }

        @JavascriptInterface
        fun listPrinters() {
            runOnUiThread {
                withBluetooth(onDenied = { msg -> printersResult(JSONArray(), msg) }) { listBondedPrinters() }
            }
        }

        @JavascriptInterface
        fun printEscPos(address: String, text: String) {
            runOnUiThread {
                withBluetooth(onDenied = { msg -> printResult(false, msg) }) {
                    io.execute { doPrint(address, text) }
                }
            }
        }

        @JavascriptInterface
        fun appVersion(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
