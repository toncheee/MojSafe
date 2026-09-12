package com.example.safeimport

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Install a crash handler that writes the stack trace to a plain text file
        // instead of letting the app die silently — this lets a crash be diagnosed
        // just by opening the file (e.g. via a file manager) or by re-opening the
        // app, which shows the last crash at the top of the screen.
        val crashFile = java.io.File(filesDir, "last_crash.txt")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashFile.writeText(
                    "Crashed at ${java.util.Date()}\n\n" +
                    android.util.Log.getStackTraceString(throwable)
                )
            } catch (_: Exception) { /* ignore, we're already crashing */ }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        val previousCrash = if (crashFile.exists()) {
            runCatching { crashFile.readText() }.getOrNull()
        } else null

        var initError: String? = null
        var repo: VaultRepository? = null
        try {
            repo = VaultRepository(applicationContext)
        } catch (t: Throwable) {
            initError = android.util.Log.getStackTraceString(t)
        }

        setContent {
            var themeMode by remember { mutableStateOf(repo?.getThemeMode() ?: "system") }
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars)
                ) {
                    when {
                        initError != null -> ErrorScreen(
                            title = "Couldn't start secure storage",
                            details = initError,
                            crashFile = crashFile
                        )
                        previousCrash != null -> ErrorScreen(
                            title = "The app crashed last time it ran",
                            details = previousCrash,
                            crashFile = crashFile,
                            onDismiss = { crashFile.delete() }
                        )
                        else -> LockGate(
                            repo = repo!!,
                            themeMode = themeMode,
                            onThemeModeChange = {
                                themeMode = it
                                repo.setThemeMode(it)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Shows a set-password / enter-password screen before revealing the app content. */
@Composable
fun LockGate(repo: VaultRepository, themeMode: String, onThemeModeChange: (String) -> Unit) {
    var unlocked by remember { mutableStateOf(false) }
    if (unlocked) {
        App(repo, themeMode, onThemeModeChange)
        return
    }

    var hasPassword by remember { mutableStateOf(repo.hasLockPassword()) }
    var input by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            if (hasPassword) "Enter password" else "Set a password",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; error = null },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (!hasPassword) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; error = null },
                label = { Text("Confirm password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (hasPassword) {
                    if (repo.verifyLockPassword(input)) {
                        unlocked = true
                    } else {
                        error = "Wrong password"
                    }
                } else {
                    when {
                        input.isBlank() -> error = "Password can't be empty"
                        input != confirm -> error = "Passwords don't match"
                        else -> {
                            repo.setLockPassword(input)
                            hasPassword = true
                            unlocked = true
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (hasPassword) "Unlock" else "Set password")
        }
    }
}

@Composable
fun ErrorScreen(
    title: String,
    details: String,
    crashFile: java.io.File,
    onDismiss: (() -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Please copy the text below (long-press → Select all → Copy) and send it back " +
            "for a fix — it's saved at:\n${crashFile.absolutePath}",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
            Text(details, style = MaterialTheme.typography.bodySmall)
        }
        if (onDismiss != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

private sealed class Screen {
    data object Folder : Screen()
    data class Detail(val uid: Long) : Screen()
    data class Edit(val uid: Long?, val parent: Long, val asFolder: Boolean) : Screen()
    data object ChangePassword : Screen()
    data object Theme : Screen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(repo: VaultRepository, themeMode: String, onThemeModeChange: (String) -> Unit) {
    var items by remember { mutableStateOf(repo.loadItems()) }
    var currentFolder by remember { mutableStateOf(0L) } // 0 = root
    var screen by remember { mutableStateOf<Screen>(Screen.Folder) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    fun persist(newItems: List<VaultItem>) {
        items = newItems
        repo.saveItems(newItems)
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val text = BufferedReader(InputStreamReader(stream)).readText()
            try {
                persist(parseVaultItems(text))
                errorMsg = null
            } catch (e: Exception) {
                errorMsg = "Import failed: ${e.message}"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Safe Import") },
            navigationIcon = {
                val showBack = screen != Screen.Folder || currentFolder != 0L
                if (showBack) {
                    IconButton(onClick = {
                        when (screen) {
                            is Screen.Detail, is Screen.Edit, Screen.ChangePassword, Screen.Theme -> screen = Screen.Folder
                            else -> {
                                val cur = items.find { it.uid == currentFolder }
                                currentFolder = cur?.parent ?: 0L
                            }
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                if (screen == Screen.Folder) {
                    IconButton(onClick = { importLauncher.launch("application/json") }) {
                        Icon(Icons.Default.Upload, contentDescription = "Import JSON")
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Theme") },
                            onClick = { menuExpanded = false; screen = Screen.Theme }
                        )
                        DropdownMenuItem(
                            text = { Text("Change password") },
                            onClick = { menuExpanded = false; screen = Screen.ChangePassword }
                        )
                    }
                }
            }
        )

        errorMsg?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        }

        when (val s = screen) {
            Screen.Folder -> Box(Modifier.fillMaxSize()) {
                if (items.isEmpty()) EmptyState() else FolderList(
                    items = items,
                    currentFolder = currentFolder,
                    onOpenFolder = { currentFolder = it },
                    onOpenItem = { screen = Screen.Detail(it.uid) },
                    onEditItem = { screen = Screen.Edit(it.uid, it.parent, it.isFolder) },
                    onDeleteItem = { target ->
                        persist(items.filterNot { it.uid == target.uid || it.parent == target.uid })
                    }
                )
                FabRow(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    onAddFolder = { screen = Screen.Edit(null, currentFolder, asFolder = true) },
                    onAddCard = { screen = Screen.Edit(null, currentFolder, asFolder = false) }
                )
            }
            is Screen.Detail -> {
                val item = items.find { it.uid == s.uid }
                if (item == null) {
                    screen = Screen.Folder
                } else {
                    ItemDetail(
                        item = item,
                        onEdit = { screen = Screen.Edit(item.uid, item.parent, item.isFolder) },
                        onDelete = {
                            persist(items.filterNot { it.uid == item.uid || it.parent == item.uid })
                            screen = Screen.Folder
                        }
                    )
                }
            }
            is Screen.Edit -> {
                val existing = s.uid?.let { uid -> items.find { it.uid == uid } }
                EditItemScreen(
                    existing = existing,
                    isFolder = s.asFolder,
                    onSave = { title, fields ->
                        val uid = existing?.uid ?: newUid()
                        val newItem = VaultItem(
                            uid = uid,
                            parent = s.parent,
                            attr = if (s.asFolder) ATTR_FOLDER else ATTR_CARD,
                            time = existing?.time ?: (System.currentTimeMillis() / 1000),
                            strings = if (s.asFolder) listOf(title) else emptyList()
                        ).let { if (s.asFolder) it else it.withFieldPairs(listOf("Title" to title) + fields) }
                        persist(items.filterNot { it.uid == uid } + newItem)
                        screen = if (existing != null) Screen.Detail(uid) else Screen.Folder
                    },
                    onCancel = { screen = if (existing != null) Screen.Detail(existing.uid) else Screen.Folder }
                )
            }
            Screen.ChangePassword -> ChangePasswordScreen(
                repo = repo,
                onDone = { screen = Screen.Folder }
            )
            Screen.Theme -> ThemeScreen(
                current = themeMode,
                onSelect = { onThemeModeChange(it); screen = Screen.Folder }
            )
        }
    }
}

@Composable
fun FabRow(
    modifier: Modifier = Modifier,
    onAddFolder: () -> Unit,
    onAddCard: () -> Unit
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallFloatingActionButton(onClick = onAddFolder) {
            Icon(Icons.Default.CreateNewFolder, contentDescription = "Add folder")
        }
        FloatingActionButton(onClick = onAddCard) {
            Icon(Icons.Default.Add, contentDescription = "Add card")
        }
    }
}

@Composable
fun ChangePasswordScreen(repo: VaultRepository, onDone: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Change password", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = current, onValueChange = { current = it; error = null },
            label = { Text("Current password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = next, onValueChange = { next = it; error = null },
            label = { Text("New password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = confirm, onValueChange = { confirm = it; error = null },
            label = { Text("Confirm new password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                when {
                    !repo.verifyLockPassword(current) -> error = "Current password is wrong"
                    next.isBlank() -> error = "New password can't be empty"
                    next != confirm -> error = "New passwords don't match"
                    else -> {
                        repo.setLockPassword(next)
                        onDone()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save new password") }
    }
}

@Composable
fun EmptyState() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("No data yet.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap the upload icon above to import items_full.json from the Handy Safe " +
            "migration, or use the + buttons below to add folders/cards by hand.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun ThemeScreen(current: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Theme", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        listOf("system" to "Follow system", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(value) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = current == value, onClick = { onSelect(value) })
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
    }
}

@Composable
fun FolderList(
    items: List<VaultItem>,
    currentFolder: Long,
    onOpenFolder: (Long) -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    onEditItem: (VaultItem) -> Unit,
    onDeleteItem: (VaultItem) -> Unit
) {
    val children = items.filter { it.parent == currentFolder && it.uid != 0L }
    var confirmDeleteItem by remember { mutableStateOf<VaultItem?>(null) }
    var menuForUid by remember { mutableStateOf<Long?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        items(children) { item ->
            ListItem(
                headlineContent = { Text(item.title) },
                supportingContent = {
                    if (item.isFolder) Text("Folder") else Text(item.fieldPairs().drop(1).take(1).joinToString { it.second })
                },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menuForUid = item.uid }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options")
                        }
                        DropdownMenu(
                            expanded = menuForUid == item.uid,
                            onDismissRequest = { menuForUid = null }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                onClick = { menuForUid = null; onEditItem(item) }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = { menuForUid = null; confirmDeleteItem = item }
                            )
                        }
                    }
                },
                modifier = Modifier.clickable {
                    if (item.isFolder) onOpenFolder(item.uid) else onOpenItem(item)
                }
            )
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(80.dp)) } // room for the FABs
    }

    confirmDeleteItem?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDeleteItem = null },
            title = { Text("Delete this?") },
            text = {
                Text(
                    if (target.isFolder) "This deletes the folder and everything inside it."
                    else "This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDeleteItem = null; onDeleteItem(target) }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteItem = null }) { Text("Cancel") }
            }
        )
    }
}


@Composable
fun ItemDetail(item: VaultItem, onEdit: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val revealed = remember { mutableStateMapOf<Int, Boolean>() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
        }
        Spacer(Modifier.height(12.dp))
        item.fieldPairs().drop(if (item.isFolder) 0 else 1).forEachIndexed { idx, (label, value) ->
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this?") },
            text = { Text(if (item.isFolder) "This deletes the folder and everything inside it." else "This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun EditItemScreen(
    existing: VaultItem?,
    isFolder: Boolean,
    onSave: (title: String, fields: List<Pair<String, String>>) -> Unit,
    onCancel: () -> Unit
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    val fields: SnapshotStateList<Pair<String, String>> = remember {
        (existing?.fieldPairs()?.drop(if (isFolder) 0 else 1) ?: emptyList())
            .ifEmpty { listOf("" to "") }
            .toMutableStateList()
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(
            if (existing == null) (if (isFolder) "New folder" else "New card") else "Edit",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(if (isFolder) "Folder name" else "Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (!isFolder) {
            Spacer(Modifier.height(16.dp))
            Text("Fields", style = MaterialTheme.typography.titleMedium)
            fields.forEachIndexed { idx, pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    OutlinedTextField(
                        value = pair.first,
                        onValueChange = { fields[idx] = it to fields[idx].second },
                        label = { Text("Label") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = pair.second,
                        onValueChange = { fields[idx] = fields[idx].first to it },
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { fields.removeAt(idx) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove field")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { fields.add("" to "") }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add field")
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                onSave(
                    title.ifBlank { if (isFolder) "Untitled folder" else "Untitled" },
                    fields.filter { it.first.isNotBlank() || it.second.isNotBlank() }
                )
            }) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
