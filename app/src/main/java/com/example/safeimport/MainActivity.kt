package com.example.safeimport

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Detects a frozen main thread (an ANR) and writes a stack dump to `crashFile`, since
 *  ANRs don't go through the normal uncaught-exception handler and would otherwise leave
 *  no trace at all. */
private fun installAnrWatchdog(crashFile: java.io.File) {
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    Thread {
        while (true) {
            val responded = java.util.concurrent.atomic.AtomicBoolean(false)
            mainHandler.post { responded.set(true) }
            Thread.sleep(3000)
            if (!responded.get()) {
                val sb = StringBuilder("Possible freeze/ANR detected at ${java.util.Date()}\n\n")
                for ((t, trace) in Thread.getAllStackTraces()) {
                    sb.append("Thread: ${t.name} (state=${t.state})\n")
                    trace.forEach { sb.append("    at $it\n") }
                    sb.append("\n")
                }
                runCatching { crashFile.writeText(sb.toString()) }
            }
            Thread.sleep(4000)
        }
    }.apply { isDaemon = true; name = "anr-watchdog"; start() }
}

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

        installAnrWatchdog(crashFile)

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

/** Set right before launching our own system picker (file save/open) so the auto-lock
 *  doesn't fire for that momentary background transition — only for genuinely leaving
 *  the app (home, app switch, screen off, etc.). */
object AutoLockGuard {
    @Volatile var suppressNext = false
}

/** Shows a set-password / enter-password screen before revealing the app content.
 *  Re-locks automatically whenever the app is actually backgrounded (home button, app
 *  switch, screen off) — the password is required again every time. Opening our own
 *  file picker for encrypted backup/restore is deliberately excluded (see AutoLockGuard),
 *  since that also briefly backgrounds the activity but isn't "leaving the app". */
@Composable
fun LockGate(repo: VaultRepository, themeMode: String, onThemeModeChange: (String) -> Unit) {
    var unlocked by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (AutoLockGuard.suppressNext) {
                    AutoLockGuard.suppressNext = false
                } else {
                    unlocked = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_safe_logo),
            contentDescription = null,
            modifier = Modifier.size(120.dp)
        )
        Spacer(Modifier.height(16.dp))
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
    data class Move(val uid: Long) : Screen()
    data class EncryptedBackupPrompt(val restoring: Boolean) : Screen()
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

    fun moveItem(uid: Long, newParent: Long) {
        persist(items.map { if (it.uid == uid) it.copy(parent = newParent) else it })
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    var pendingPassphrase by remember { mutableStateOf<CharArray?>(null) }
    val encryptedBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val pass = pendingPassphrase
        pendingPassphrase = null
        if (uri == null || pass == null) return@rememberLauncherForActivityResult
        try {
            val plaintext = items.toJsonArray().toString().toByteArray()
            val encrypted = SecureBackup.encrypt(plaintext, pass)
            context.contentResolver.openOutputStream(uri)?.use { it.write(encrypted) }
            errorMsg = null
        } catch (e: Exception) {
            errorMsg = "Encrypted backup failed: ${e.message}"
        }
    }
    val encryptedRestoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val pass = pendingPassphrase
        pendingPassphrase = null
        if (uri == null || pass == null) return@rememberLauncherForActivityResult
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw Exception("Couldn't read the file")
            val plaintext = SecureBackup.decrypt(bytes, pass)
            persist(parseVaultItems(String(plaintext)))
            errorMsg = null
        } catch (e: Exception) {
            errorMsg = e.message ?: "Encrypted restore failed"
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("MojSafe") },
            navigationIcon = {
                val showBack = screen != Screen.Folder || currentFolder != 0L
                if (showBack) {
                    IconButton(onClick = {
                        when (screen) {
                            is Screen.Detail, is Screen.Edit, Screen.ChangePassword, Screen.Theme,
                            is Screen.Move, is Screen.EncryptedBackupPrompt -> screen = Screen.Folder
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
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Encrypted backup…") },
                            onClick = { menuExpanded = false; screen = Screen.EncryptedBackupPrompt(restoring = false) }
                        )
                        DropdownMenuItem(
                            text = { Text("Encrypted restore…") },
                            onClick = { menuExpanded = false; screen = Screen.EncryptedBackupPrompt(restoring = true) }
                        )
                        HorizontalDivider()
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
                    onMoveItem = { screen = Screen.Move(it.uid) },
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
                        onMove = { screen = Screen.Move(item.uid) },
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
                            strings = listOf(title) + fields.flatMap { (l, v) -> listOf(l, v) }
                        )
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
            is Screen.Move -> {
                val item = items.find { it.uid == s.uid }
                if (item == null) {
                    screen = Screen.Folder
                } else {
                    MoveScreen(
                        items = items,
                        itemToMove = item,
                        onMoveTo = { dest ->
                            moveItem(item.uid, dest)
                            screen = Screen.Folder
                        },
                        onCancel = { screen = Screen.Folder }
                    )
                }
            }
            is Screen.EncryptedBackupPrompt -> PassphraseScreen(
                title = if (s.restoring) "Encrypted restore" else "Encrypted backup",
                confirmRequired = !s.restoring,
                onConfirm = { pass ->
                    pendingPassphrase = pass
                    AutoLockGuard.suppressNext = true
                    if (s.restoring) {
                        encryptedRestoreLauncher.launch("*/*")
                    } else {
                        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm").format(java.util.Date())
                        encryptedBackupLauncher.launch("mojsafe-backup-$stamp.mojsafe")
                    }
                    screen = Screen.Folder
                },
                onCancel = { screen = Screen.Folder }
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
fun PassphraseScreen(
    title: String,
    confirmRequired: Boolean,
    onConfirm: (CharArray) -> Unit,
    onCancel: () -> Unit
) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (confirmRequired)
                "Choose a passphrase to protect this backup file. You'll need it again to restore — write it down somewhere safe, it can't be recovered if lost."
            else
                "Enter the passphrase this backup was created with.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it; error = null },
            label = { Text("Passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (confirmRequired) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; error = null },
                label = { Text("Confirm passphrase") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                when {
                    pass.isBlank() -> error = "Passphrase can't be empty"
                    confirmRequired && pass != confirm -> error = "Passphrases don't match"
                    else -> onConfirm(pass.toCharArray())
                }
            }) { Text("Continue") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
fun MoveScreen(
    items: List<VaultItem>,
    itemToMove: VaultItem,
    onMoveTo: (Long) -> Unit,
    onCancel: () -> Unit
) {
    // Can't move a folder into itself or into one of its own descendants.
    val forbidden = if (itemToMove.isFolder) descendantsOf(items, itemToMove.uid) else emptySet()

    // Flat, indented list of every eligible folder — simpler and far less error-prone
    // than a drill-down browser. Depth is computed by walking each folder's parent
    // chain, bounded so corrupt/cyclic data can never hang the UI.
    data class Row(val folder: VaultItem?, val depth: Int) // folder == null means "Root"

    fun depthOf(folder: VaultItem): Int {
        var depth = 0
        var cur: VaultItem? = folder
        var steps = 0
        while (cur != null && cur.parent != 0L && steps < 200) {
            cur = items.find { it.uid == cur!!.parent }
            depth++
            steps++
        }
        return depth
    }

    val rows = remember(items, itemToMove.uid) {
        val folders = items.filter { it.isFolder && it.uid !in forbidden }
            .sortedBy { it.title.lowercase() }
        listOf(Row(null, 0)) + folders.map { Row(it, depthOf(it)) }
    }

    var confirmTarget by remember { mutableStateOf<Row?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Move \"${itemToMove.title}\"", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text("Choose a destination:", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.folder?.uid ?: -1L }) { row ->
                val destUid = row.folder?.uid ?: 0L
                val isCurrent = itemToMove.parent == destUid
                val isSelf = row.folder?.uid == itemToMove.uid
                ListItem(
                    headlineContent = {
                        Text(
                            (row.folder?.title ?: "Root (top level)") +
                                if (isCurrent) "  (current location)" else ""
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (row.folder == null) Icons.Default.Home else Icons.Default.Folder,
                            contentDescription = null
                        )
                    },
                    modifier = Modifier
                        .padding(start = (row.depth * 24).dp)
                        .let { m ->
                            if (isCurrent || isSelf) m else m.clickable { confirmTarget = row }
                        }
                )
                HorizontalDivider()
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }

    confirmTarget?.let { row ->
        val destUid = row.folder?.uid ?: 0L
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text("Move here?") },
            text = {
                Text(
                    "Move \"${itemToMove.title}\" into " +
                        "\"${row.folder?.title ?: "Root (top level)"}\"" +
                        if (itemToMove.isFolder) " (everything inside it moves along with it)." else "."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmTarget = null; onMoveTo(destUid) }) { Text("Move") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) { Text("Cancel") }
            }
        )
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
            "Open the ⋮ menu above and tap \"Encrypted restore…\" to bring in a previous " +
            "export, or use the + buttons below to add folders/cards by hand.",
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
    onMoveItem: (VaultItem) -> Unit,
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
                    if (item.isFolder) Text("Folder") else Text(item.fieldPairs(startIndex = 1).take(1).joinToString { it.second })
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
                                text = { Text("Move") },
                                onClick = { menuForUid = null; onMoveItem(item) }
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
fun ItemDetail(item: VaultItem, onEdit: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val revealed = remember { mutableStateMapOf<Int, Boolean>() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                IconButton(onClick = onMove) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Move") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
        }
        Spacer(Modifier.height(12.dp))
        item.fieldPairs(startIndex = if (item.isFolder) 0 else 1).forEachIndexed { idx, (label, value) ->
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

/** Moves the element at `from` to position `to`, shifting the others — not a swap,
 *  since a drag can jump over more than one row at a time. */
private fun <T> SnapshotStateList<T>.move(from: Int, to: Int) {
    if (from == to || to !in indices) return
    val item = removeAt(from)
    add(to, item)
}

/** One column (either all labels or all values) that can be reordered independently
 *  by pressing and holding its drag handle. Kept generic so labels and values can be
 *  dragged separately — useful for fixing a label that ended up paired with the wrong
 *  value, without having to retype anything. */
@Composable
private fun DraggableColumnEntry(
    text: String,
    hint: String,
    onTextChange: (String) -> Unit,
    onDragStart: () -> Unit,
    onDragBy: (deltaPx: Float) -> Unit,
    onDragEnd: () -> Unit,
    isDragged: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                if (isDragged) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = "Press and hold to move this $hint independently",
            modifier = Modifier
                .padding(end = 2.dp)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, dragAmount -> change.consume(); onDragBy(dragAmount.y) },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() }
                    )
                }
        )
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(hint) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Two independently-reorderable columns — labels and values — so a label that ended
 *  up next to the wrong value (common after importing messy legacy data) can be fixed
 *  by dragging just one side, instead of retyping or only being able to move whole
 *  label+value rows together. */
@Composable
fun ReorderableFields(labels: SnapshotStateList<String>, values: SnapshotStateList<String>) {
    var rowHeightPx by remember { mutableStateOf(0f) }

    var draggedLabelIndex by remember { mutableStateOf<Int?>(null) }
    var labelDragOffset by remember { mutableStateOf(0f) }
    var draggedValueIndex by remember { mutableStateOf<Int?>(null) }
    var valueDragOffset by remember { mutableStateOf(0f) }

    fun handleDrag(
        current: Int,
        deltaY: Float,
        offset: Float,
        setOffset: (Float) -> Unit,
        setIndex: (Int) -> Unit,
        list: SnapshotStateList<String>
    ) {
        val newOffset = offset + deltaY
        val height = rowHeightPx
        if (height > 0f) {
            val moveBy = (newOffset / height).toInt()
            if (moveBy != 0) {
                val newIndex = (current + moveBy).coerceIn(0, list.size - 1)
                if (newIndex != current) {
                    list.move(current, newIndex)
                    setIndex(newIndex)
                    setOffset(newOffset - moveBy * height)
                    return
                }
            }
        }
        setOffset(newOffset)
    }

    Column {
        for (idx in labels.indices) {
            val isLabelDragged = idx == draggedLabelIndex
            val isValueDragged = idx == draggedValueIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { if (rowHeightPx == 0f) rowHeightPx = it.height.toFloat() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DraggableColumnEntry(
                    text = labels[idx],
                    hint = "label",
                    onTextChange = { labels[idx] = it },
                    onDragStart = { draggedLabelIndex = idx; labelDragOffset = 0f },
                    onDragBy = { d ->
                        handleDrag(idx, d, labelDragOffset, { labelDragOffset = it }, { draggedLabelIndex = it }, labels)
                    },
                    onDragEnd = { draggedLabelIndex = null; labelDragOffset = 0f },
                    isDragged = isLabelDragged,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { translationY = if (isLabelDragged) labelDragOffset else 0f }
                        .zIndex(if (isLabelDragged) 1f else 0f)
                )
                Spacer(Modifier.width(8.dp))
                DraggableColumnEntry(
                    text = values[idx],
                    hint = "value",
                    onTextChange = { values[idx] = it },
                    onDragStart = { draggedValueIndex = idx; valueDragOffset = 0f },
                    onDragBy = { d ->
                        handleDrag(idx, d, valueDragOffset, { valueDragOffset = it }, { draggedValueIndex = it }, values)
                    },
                    onDragEnd = { draggedValueIndex = null; valueDragOffset = 0f },
                    isDragged = isValueDragged,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { translationY = if (isValueDragged) valueDragOffset else 0f }
                        .zIndex(if (isValueDragged) 1f else 0f)
                )
                IconButton(onClick = {
                    labels.removeAt(idx)
                    values.removeAt(idx)
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove field")
                }
            }
        }
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
    val initialPairs = (existing?.fieldPairs(startIndex = if (isFolder) 0 else 1) ?: emptyList())
        .ifEmpty { listOf("" to "") }
    val labels: SnapshotStateList<String> = remember { initialPairs.map { it.first }.toMutableStateList() }
    val values: SnapshotStateList<String> = remember { initialPairs.map { it.second }.toMutableStateList() }

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
            Text(
                "Fields — press and hold a handle to move that label or value on its own " +
                "(handy for fixing a mismatched pair), or move both to reorder the whole row.",
                style = MaterialTheme.typography.titleMedium
            )
            ReorderableFields(labels, values)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { labels.add(""); values.add("") }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add field")
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val fields = labels.zip(values).filter { it.first.isNotBlank() || it.second.isNotBlank() }
                onSave(title.ifBlank { if (isFolder) "Untitled folder" else "Untitled" }, fields)
            }) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
