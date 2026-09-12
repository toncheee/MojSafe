package com.example.safeimport

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = VaultRepository(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App(repo)
                }
            }
        }
    }
}

@Composable
fun App(repo: VaultRepository) {
    var items by remember { mutableStateOf(repo.loadItems()) }
    var currentFolder by remember { mutableStateOf(0L) } // 0 = root
    var openItem by remember { mutableStateOf<VaultItem?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberImportLauncher(
        onLoaded = { text ->
            try {
                val parsed = parseVaultItems(text)
                repo.saveItems(parsed)
                items = parsed
                errorMsg = null
            } catch (e: Exception) {
                errorMsg = "Import failed: ${e.message}"
            }
        }
    )

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Safe Import") },
            navigationIcon = {
                if (openItem != null || currentFolder != 0L) {
                    IconButton(onClick = {
                        if (openItem != null) {
                            openItem = null
                        } else {
                            val cur = items.find { it.uid == currentFolder }
                            currentFolder = cur?.parent ?: 0L
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                TextButton(onClick = { importLauncher() }) { Text("Import JSON") }
            }
        )

        errorMsg?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        }

        when {
            items.isEmpty() -> EmptyState()
            openItem != null -> ItemDetail(openItem!!)
            else -> FolderList(
                items = items,
                currentFolder = currentFolder,
                onOpenFolder = { currentFolder = it },
                onOpenItem = { openItem = it }
            )
        }
    }
}

@Composable
private fun rememberImportLauncher(onLoaded: (String) -> Unit): () -> Unit {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val launcher = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        activity.contentResolver.openInputStream(uri)?.use { stream ->
            val text = BufferedReader(InputStreamReader(stream)).readText()
            onLoaded(text)
        }
    }
    return { launcher.launch("application/json") }
}

@Composable
fun EmptyState() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("No data imported yet.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap \"Import JSON\" above and pick the export file produced during migration " +
            "(e.g. items_full.json). After a successful import, delete that plaintext file " +
            "— everything is re-encrypted on-device from then on.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun FolderList(
    items: List<VaultItem>,
    currentFolder: Long,
    onOpenFolder: (Long) -> Unit,
    onOpenItem: (VaultItem) -> Unit
) {
    val children = items.filter { it.parent == currentFolder && it.uid != 0L }
    LazyColumn(Modifier.fillMaxSize()) {
        items(children) { item ->
            ListItem(
                headlineContent = { Text(item.title) },
                supportingContent = {
                    if (item.isFolder) Text("Folder") else Text(item.fieldPairs().take(2).joinToString(" · ") { it.second })
                },
                modifier = Modifier.clickable {
                    if (item.isFolder) onOpenFolder(item.uid) else onOpenItem(item)
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun ItemDetail(item: VaultItem) {
    val revealed = remember { mutableStateMapOf<Int, Boolean>() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        item.fieldPairs().forEachIndexed { idx, (label, value) ->
            val isSensitive = label.contains("PIN", true) ||
                label.contains("password", true) ||
                label.contains("card", true) ||
                label.contains("cvv", true)
            val isRevealed = revealed[idx] ?: !isSensitive

            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(if (isRevealed) value else "•".repeat(value.length.coerceAtLeast(4)))
                }
                if (isSensitive) {
                    IconButton(onClick = { revealed[idx] = !isRevealed }) {
                        Icon(
                            if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle visibility"
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
