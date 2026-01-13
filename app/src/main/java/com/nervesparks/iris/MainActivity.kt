package com.nervesparks.iris

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.llama.cpp.LLamaAndroid
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nervesparks.iris.data.UserPreferencesRepository
import com.nervesparks.iris.irisapp.ServiceLocator
import com.nervesparks.iris.ui.SettingsBottomSheet
import java.io.File

class MainViewModelFactory(
    private val llamaAndroid: LLamaAndroid,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(llamaAndroid, userPreferencesRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class MainActivity(
    downloadManager: DownloadManager? = null,
    clipboardManager: ClipboardManager? = null,
): ComponentActivity() {

    private val downloadManager by lazy { downloadManager ?: getSystemService<DownloadManager>()!! }
    private val clipboardManager by lazy { clipboardManager ?: getSystemService<ClipboardManager>()!! }

    private lateinit var viewModel: MainViewModel

    // ✅ Track embedding model download
    private var embeddingDownloadId: Long? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == embeddingDownloadId) {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                if (dm != null && isDownloadComplete(dm, id)) {
                    // ✅ Notify ServiceLocator that embedding is ready
                    ServiceLocator.ensureEmbeddingReady(context)
                    Toast.makeText(
                        context,
                        "Embedding model downloaded successfully! You can now upload documents.",
                        Toast.LENGTH_LONG
                    ).show()
                    embeddingDownloadId = null
                }
            }
        }
    }

    private fun isDownloadComplete(dm: DownloadManager, downloadId: Long): Boolean {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query)
        if (!cursor.moveToFirst()) {
            cursor.close()
            return false
        }
        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val status = cursor.getInt(statusIdx)
        cursor.close()
        return status == DownloadManager.STATUS_SUCCESSFUL
    }

    val darkNavyBlue = Color(0xFF001F3D)
    val lightNavyBlue = Color(0xFF3A4C7C)

    val gradientBrush = Brush.verticalGradient(
        colors = listOf(darkNavyBlue, lightNavyBlue)
    )

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#FF070915")

        StrictMode.setVmPolicy(
            VmPolicy.Builder(StrictMode.getVmPolicy())
                .detectLeakedClosableObjects()
                .build()
        )

        // ✅ Initialize ServiceLocator for RAG
        ServiceLocator.init(applicationContext)

        // ✅ Register download receiver
        registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            RECEIVER_NOT_EXPORTED
        )

        val userPrefsRepo = UserPreferencesRepository.getInstance(applicationContext)
        val lLamaAndroid = LLamaAndroid.instance()
        val viewModelFactory = MainViewModelFactory(lLamaAndroid, userPrefsRepo)
        viewModel = ViewModelProvider(this, viewModelFactory)[MainViewModel::class.java]

        val transparentColor = Color.Transparent.toArgb()
        window.decorView.rootView.setBackgroundColor(transparentColor)

        val extFilesDir = getExternalFilesDir(null) ?: filesDir

        val models = listOf(
            Downloadable(
                "Llama-3.2-3B-Instruct-Q4_K_L.gguf",
                Uri.parse("https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_L.gguf?download=true"),
                File(extFilesDir, "Llama-3.2-3B-Instruct-Q4_K_L.gguf")
            ),
            Downloadable(
                "Llama-3.2-1B-Instruct-Q6_K_L.gguf",
                Uri.parse("https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q6_K_L.gguf?download=true"),
                File(extFilesDir, "Llama-3.2-1B-Instruct-Q6_K_L.gguf")
            ),
            Downloadable(
                "stablelm-2-1_6b-chat.Q4_K_M.imx.gguf",
                Uri.parse("https://huggingface.co/Crataco/stablelm-2-1_6b-chat-imatrix-GGUF/resolve/main/stablelm-2-1_6b-chat.Q4_K_M.imx.gguf?download=true"),
                File(extFilesDir, "stablelm-2-1_6b-chat.Q4_K_M.imx.gguf")
            )
        )

        viewModel.loadExistingModels(extFilesDir)

        setContent {
            var showSettingSheet by remember { mutableStateOf(false) }
            var showEmbeddingDownloadDialog by remember { mutableStateOf(false) }

            // ✅ Check embedding model on startup
            LaunchedEffect(Unit) {
                if (!ServiceLocator.isEmbeddingModelDownloaded(applicationContext)) {
                    showEmbeddingDownloadDialog = true
                }
            }

            // ✅ Get embedding model info from ViewModel
            val embeddingModel = viewModel.embeddingModels.firstOrNull()

            // ✅ Embedding model download dialog
            if (showEmbeddingDownloadDialog && embeddingModel != null) {
                EmbeddingDownloadDialog(
                    modelName = embeddingModel["name"] ?: "Embedding Model",
                    modelSize = embeddingModel["size"] ?: "~25MB",
                    modelDescription = embeddingModel["description"] ?: "Required for document processing",
                    onDownload = {
                        // ✅ Download using info from ViewModel
                        val source = embeddingModel["source"] ?: return@EmbeddingDownloadDialog
                        val destination = embeddingModel["destination"] ?: return@EmbeddingDownloadDialog

                        embeddingDownloadId = downloadEmbeddingModel(
                            context = applicationContext,
                            downloadManager = downloadManager,
                            sourceUrl = source,
                            destinationFileName = destination
                        )
                        showEmbeddingDownloadDialog = false
                        Toast.makeText(
                            applicationContext,
                            "Downloading embedding model...",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onDismiss = { showEmbeddingDownloadDialog = false }
                )
            }

            var UserGivenModel by remember {
                mutableStateOf(
                    TextFieldValue(
                        text = viewModel.userGivenModel,
                        selection = TextRange(viewModel.userGivenModel.length)
                    )
                )
            }
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier
                            .width(300.dp)
                            .fillMaxHeight(),
                        drawerContainerColor= Color(0xFF070915),
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(5.dp)
                                .fillMaxHeight(),
                        ) {
                            // Top section with logo and name
                            Column {
                                Row(
                                    modifier =  Modifier
                                        .fillMaxWidth()
                                        .padding(start = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.logo),
                                        contentDescription = "Logo",
                                        modifier = Modifier.size(35.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                    Spacer(Modifier.padding(5.dp))
                                    Text(
                                        text = "Iris",
                                        fontWeight = FontWeight(500),
                                        color = Color.White,
                                        fontSize = 30.sp
                                    )
                                    Spacer(Modifier.weight(1f))
                                    if (showSettingSheet) {
                                        SettingsBottomSheet(
                                            viewModel= viewModel,
                                            onDismiss = { showSettingSheet = false }
                                        )
                                    }
                                }
                                Row(modifier = Modifier.padding(start = 45.dp)) {
                                    Text(
                                        text = "NerveSparks",
                                        color = Color(0xFF636466),
                                        fontSize = 16.sp
                                    )
                                }
                            }

                            Spacer(Modifier.height(20.dp))

                            Column (modifier = Modifier.padding(6.dp)){
                                Text(
                                    text = "Active Model",
                                    fontSize = 16.sp,
                                    color = Color(0xFF636466),
                                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)
                                )
                                Text(
                                    text = viewModel.loadedModelName.value,
                                    fontSize = 16.sp,
                                    color = Color.White,
                                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)
                                )
                            }

                            // ✅ Show embedding model status
                            Column (modifier = Modifier.padding(6.dp)){
                                Text(
                                    text = "Embedding Model",
                                    fontSize = 16.sp,
                                    color = Color(0xFF636466),
                                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)
                                )
                                val context = LocalContext.current
                                val isEmbeddingReady = remember {
                                    ServiceLocator.isEmbeddingModelDownloaded(context)
                                }
                                Text(
                                    text = if (isEmbeddingReady) {
                                        embeddingModel?.get("name") ?: "Ready"
                                    } else {
                                        "Not downloaded (tap to download)"
                                    },
                                    fontSize = 16.sp,
                                    color = if (isEmbeddingReady) Color.White else Color(0xFFFF6B6B),
                                    modifier = Modifier
                                        .padding(vertical = 4.dp, horizontal = 8.dp)
                                        .clickable {
                                            if (!isEmbeddingReady) {
                                                showEmbeddingDownloadDialog = true
                                            }
                                        }
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // Bottom links
                            Column(
                                verticalArrangement = Arrangement.Bottom,
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Star us button
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .padding(horizontal = 16.dp)
                                        .background(
                                            color = Color(0xFF14161f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                ) {
                                    val context = LocalContext.current
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable {
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    data = Uri.parse("https://github.com/nerve-sparks/iris_android")
                                                }
                                                context.startActivity(intent)
                                            }
                                    ) {
                                        Text(
                                            text = "Star us",
                                            color = Color(0xFF78797a),
                                            fontSize = 14.sp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Image(
                                            modifier = Modifier.size(24.dp),
                                            painter = painterResource(id = R.drawable.github_svgrepo_com),
                                            contentDescription = "Github icon"
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(5.dp))

                                // Powered by
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = 16.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "powered by",
                                        color = Color(0xFF636466),
                                        fontSize = 14.sp
                                    )
                                    val context = LocalContext.current
                                    Text(
                                        modifier = Modifier.clickable {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                data = Uri.parse("https://github.com/ggerganov/llama.cpp")
                                            }
                                            context.startActivity(intent)
                                        },
                                        text = " llama.cpp",
                                        color = Color(0xFF78797a),
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                },
            ) {
                ChatScreen(
                    viewModel,
                    clipboardManager,
                    downloadManager,
                    models,
                    extFilesDir,
                )
            }
        }
    }

    /**
     * ✅ Download embedding model using info from ViewModel
     */
    private fun downloadEmbeddingModel(
        context: Context,
        downloadManager: DownloadManager,
        sourceUrl: String,
        destinationFileName: String
    ): Long {
        val destination = context.getExternalFilesDir(null) ?: context.filesDir
        val destinationFile = File(destination, destinationFileName)

        // Clean up any partial downloads
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        val request = DownloadManager.Request(Uri.parse(sourceUrl)).apply {
            setTitle("Embedding Model")
            setDescription("Downloading embedding model for document processing")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(destinationFile))
            setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or
                        DownloadManager.Request.NETWORK_MOBILE
            )
        }

        return downloadManager.enqueue(request)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Receiver may not be registered
        }
    }
}

@Composable
fun EmbeddingDownloadDialog(
    modelName: String,
    modelSize: String,
    modelDescription: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Download Embedding Model")
        },
        text = {
            Column {
                Text("To use document processing features, you need to download the embedding model:")
                Spacer(modifier = Modifier.height(8.dp))
                Text("• Model: $modelName", fontSize = 14.sp)
                Text("• Size: $modelSize", fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    modelDescription,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text("Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later")
            }
        },
        containerColor = Color(0xFF070915)
    )
}

@Composable
fun LinearGradient() {
    val darkNavyBlue = Color(0xFF050a14)
    val lightNavyBlue = Color(0xFF051633)
    val gradient = Brush.linearGradient(
        colors = listOf(darkNavyBlue, lightNavyBlue),
        start = Offset(0f, 300f),
        end = Offset(0f, 1000f)
    )
    Box(modifier = Modifier.background(gradient).fillMaxSize())
}