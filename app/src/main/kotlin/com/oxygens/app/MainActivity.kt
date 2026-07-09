package com.oxygens.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oxygens.app.ui.launcher.CloneListViewModel
import com.oxygens.app.ui.launcher.LauncherScreen
import com.oxygens.app.ui.theme.OxygenSTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Host UI entry point. Runs in the main process only (never a ":clone_N" process), so
 * it never touches VirtualCore/VPMS/VAMS directly — all clone lifecycle operations go
 * through CloneListViewModel -> CloneManager.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OxygenSTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OxygenSApp()
                }
            }
        }
    }
}

@Composable
private fun OxygenSApp(viewModel: CloneListViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pickApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val apkFile = withContext(Dispatchers.IO) { copyUriToCacheFile(context, uri) }
            if (apkFile == null) {
                viewModel.reportError("Could not read the picked file.")
            } else {
                // The cache copy is only needed for the install call itself; clean it
                // up once installClone finishes (success or failure) so repeated
                // installs don't leak files into the cache dir.
                viewModel.installClone(apkFile, displayName = null, onDone = { apkFile.delete() })
            }
        }
    }

    LauncherScreen(
        // "*/*" so the file picker also shows .apks bundle files (bundletool output),
        // not just .apk files. Validation happens downstream in GuestApkParser.
        onPickApk = { pickApkLauncher.launch("*/*") },
        viewModel = viewModel,
    )
}

/**
 * SAF `content://` Uris from GetContent aren't java.io.File-backed, but
 * [com.oxygens.core.virtual.CloneManager.installClone] needs a real File (it reads the
 * APK multiple times: parsing, extraction, native-lib install). Copy the picked
 * document into the app's cache dir once up front rather than threading Uri/
 * ContentResolver through core-loader/core-virtual, which are plain-JVM-testable and
 * intentionally don't depend on android.content.
 */
private fun copyUriToCacheFile(context: android.content.Context, uri: Uri): File? {
    val resolver = context.contentResolver
    val destination = File(context.cacheDir, "guest-${System.currentTimeMillis()}.apk")
    return try {
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        } ?: return null
        destination
    } catch (e: java.io.IOException) {
        destination.delete()
        null
    }
}
