package com.oxygens.app.ui.launcher

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oxygens.core.virtual.model.CloneInfo

/**
 * Launcher list: shows installed clones, lets the user install new ones (via FAB),
 * remove existing ones, and launch them.
 *
 * Launch wires directly into [CloneListViewModel.launchClone], which builds a stub
 * Intent targeting the first free [StubComponentPool] slot for the clone's process
 * group, passes guest extras (clone ID, package name, main Activity class), and calls
 * [startActivity]. The stub Activity's onCreate then runs the DexClassLoader +
 * GuestContextWrapper + reflection delegation pipeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(onPickApk: () -> Unit, viewModel: CloneListViewModel = viewModel()) {
    val clones by viewModel.clones.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Oxygen S") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onPickApk) {
                Icon(Icons.Filled.Add, contentDescription = "Install guest APK or APKS")
            }
        },
    ) { padding ->
        if (clones.isEmpty()) {
            EmptyState(padding)
        } else {
            CloneList(
                clones   = clones,
                padding  = padding,
                onLaunch = { cloneId -> viewModel.launchClone(cloneId) },
                onRemove = { cloneId -> viewModel.removeClone(cloneId) },
            )
        }
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier        = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "No clones yet.\nTap + to install a guest APK.",
            style     = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun CloneList(
    clones:   List<CloneInfo>,
    padding:  PaddingValues,
    onLaunch: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    LazyColumn(
        contentPadding      = padding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier            = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        items(clones, key = { it.cloneId }) { clone ->
            CloneTile(clone, onLaunch, onRemove)
        }
    }
}

@Composable
private fun CloneTile(
    clone:    CloneInfo,
    onLaunch: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    // Load the stored icon PNG on the composition thread (it's small and already on disk)
    val bitmap = remember(clone.iconPath) {
        clone.iconPath?.let { path ->
            try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
        }
    }

    Card(
        modifier  = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier            = Modifier.padding(12.dp),
            verticalAlignment   = Alignment.CenterVertically,
        ) {
            // App icon (48×48 dp) or placeholder
            if (bitmap != null) {
                Image(
                    bitmap             = bitmap.asImageBitmap(),
                    contentDescription = "${clone.displayName} icon",
                    modifier           = Modifier.size(48.dp),
                )
            } else {
                Box(
                    modifier         = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier           = Modifier.size(32.dp),
                        tint               = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // App name + package name
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = clone.displayName,
                    style    = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text     = clone.guestPackageName,
                    style    = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Launch button — fires launchClone() which builds the stub Intent and
            // starts the appropriate StubActivityN in the clone's process group.
            IconButton(onClick = { onLaunch(clone.cloneId) }) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Launch ${clone.displayName}",
                    tint               = MaterialTheme.colorScheme.primary,
                )
            }

            // Remove button
            IconButton(onClick = { onRemove(clone.cloneId) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove ${clone.displayName}",
                    tint               = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
