package jp.katayama.filelist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import jp.katayama.filelist.ui.theme.FilelistTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Log

data class FileItem(val name: String, val path: String, val isDirectory: Boolean, val lastModified: Long, val size: Long)

enum class SortBy { NAME, DATE, SIZE }
enum class SortOrder { ASCENDING, DESCENDING }
enum class ViewMode { LIST, MULTILINE_LIST, GRID, LARGE_GRID }

class MainActivity : ComponentActivity() {

    // Launcher for Android 11+ (API 30+)
    private val storageActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                recreate()
            } else {
                showPermissionDenied()
            }
        }
    }

    // Launcher for Android 10 and below (API 24-29)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            recreate()
        } else {
            showPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ logic
            if (Environment.isExternalStorageManager()) {
                setContent { AppContent() }
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                storageActivityResultLauncher.launch(intent)
            }
        } else {
            // Android 10 and below logic
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED -> {
                    setContent { AppContent() }
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    private fun showPermissionDenied() {
        setContent {
            FilelistTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Permission denied. App cannot function.")
                }
            }
        }
    }
}

private fun searchRecursively(directory: File, query: String, foundFiles: MutableList<FileItem>) {
    directory.listFiles()?.forEach { file ->
        if (file.name.contains(query, ignoreCase = true)) {
            foundFiles.add(FileItem(file.name, file.path, file.isDirectory, file.lastModified(), if (file.isDirectory) 0 else file.length()))
        }
        if (file.isDirectory) {
            searchRecursively(file, query, foundFiles)
        }
    }
}

@Composable
fun NewFolderDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Folder") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Folder Name") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (folderName.isNotBlank()) {
                        onCreate(folderName)
                    }
                },
                enabled = folderName.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AppContent() {
    val context = LocalContext.current
    val externalStorageRoot = Environment.getExternalStorageDirectory()
    val internalStorageRoot = context.filesDir

    val sharedPreferences = remember { context.getSharedPreferences("filelist_prefs", Context.MODE_PRIVATE) }
    val lastPath = sharedPreferences.getString("last_path", null)

    var currentDir by remember {
        val defaultPath = externalStorageRoot
        val lastFile = lastPath?.let { File(it) }
        mutableStateOf(if (lastFile != null && lastFile.exists() && lastFile.isDirectory) lastFile else defaultPath)
    }
    var searchQuery by remember { mutableStateOf("") }
    
    var searchResults by remember { mutableStateOf<List<FileItem>?>(null) }
    var stashedSearchResults by remember { mutableStateOf<List<FileItem>?>(null) }
    var stashedSearchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var sortBy by remember { mutableStateOf(SortBy.NAME) }
    var sortOrder by remember { mutableStateOf(SortOrder.ASCENDING) }
    val coroutineScope = rememberCoroutineScope()
    var selectionMode by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf<Set<FileItem>>(emptySet()) }
    var filesInDir by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var moveMode by remember { mutableStateOf(false) }
    var itemsToMove by remember { mutableStateOf<Set<FileItem>>(emptySet()) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var highlightedPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentDir) {
        sharedPreferences.edit().putString("last_path", currentDir.absolutePath).apply()
        val newFiles = withContext(Dispatchers.IO) {
            currentDir.listFiles()
                ?.map { FileItem(it.name, it.path, it.isDirectory, it.lastModified(), if (it.isDirectory) 0 else it.length()) }
                ?: emptyList()
        }
        filesInDir = newFiles
    }

    val sourceFiles = searchResults ?: filesInDir

    val filteredFiles = if (searchResults == null && searchQuery.isNotBlank()) {
        sourceFiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
    } else {
        sourceFiles
    }

    val filesToShow = filteredFiles.let { files ->
        val comparator = when (sortBy) {
            SortBy.NAME -> compareBy<FileItem> { it.name.lowercase() }
            SortBy.DATE -> compareBy { it.lastModified }
            SortBy.SIZE -> compareBy { it.size }
        }

        val finalComparator = compareBy<FileItem> { !it.isDirectory }.then(comparator)
        
        if (sortOrder == SortOrder.DESCENDING) {
            files.sortedWith(finalComparator.reversed())
        } else {
            files.sortedWith(finalComparator)
        }
    }

    val clearSearchState = {
        searchResults = null
        stashedSearchResults = null
        searchQuery = ""
        stashedSearchQuery = ""
        selectionMode = false
        selectedItems = emptySet()
        moveMode = false
        itemsToMove = emptySet()
    }

    FilelistTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                if (showNewFolderDialog) {
                    NewFolderDialog(
                        onDismiss = { showNewFolderDialog = false },
                        onCreate = { folderName ->
                            coroutineScope.launch {
                                val newFolder = File(currentDir, folderName)
                                val success = withContext(Dispatchers.IO) {
                                    newFolder.mkdir()
                                }
                                if (success) {
                                    // Refresh file list
                                    val newFiles = withContext(Dispatchers.IO) {
                                        currentDir.listFiles()
                                            ?.map { FileItem(it.name, it.path, it.isDirectory, it.lastModified(), if (it.isDirectory) 0 else it.length()) }
                                            ?: emptyList()
                                    }
                                    filesInDir = newFiles
                                }
                                showNewFolderDialog = false
                            }
                        }
                    )
                }

                // (1) Path
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(64.dp),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Box(
                        modifier = Modifier.padding(8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (selectionMode) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${selectedItems.size} selected",
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = {
                                    itemsToMove = selectedItems
                                    selectedItems = emptySet()
                                    selectionMode = false
                                    moveMode = true
                                }) { Text("Move") }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = {
                                    coroutineScope.launch {
                                        withContext(Dispatchers.IO) {
                                            selectedItems.forEach {
                                                File(it.path).deleteRecursively()
                                            }
                                        }
                                        selectionMode = false
                                        selectedItems = emptySet()
                                        // Refresh file list
                                        filesInDir = withContext(Dispatchers.IO) {
                                            currentDir.listFiles()
                                                ?.map { FileItem(it.name, it.path, it.isDirectory, it.lastModified(), if (it.isDirectory) 0 else it.length()) }
                                                ?: emptyList()
                                        }
                                    }
                                }) { Text("Delete") }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = {
                                    selectionMode = false
                                    selectedItems = emptySet()
                                }) { Text("Cancel") }
                            }
                        } else if (moveMode) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Moving ${itemsToMove.size} items.",
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = {
                                    coroutineScope.launch {
                                        withContext(Dispatchers.IO) {
                                            itemsToMove.forEach { itemToMove ->
                                                val sourceFile = File(itemToMove.path)
                                                val destFile = File(currentDir, itemToMove.name)
                                                sourceFile.renameTo(destFile)
                                            }
                                        }
                                        moveMode = false
                                        itemsToMove = emptySet()
                                        // Refresh file list
                                        filesInDir = withContext(Dispatchers.IO) {
                                            currentDir.listFiles()
                                                ?.map { FileItem(it.name, it.path, it.isDirectory, it.lastModified(), if (it.isDirectory) 0 else it.length()) }
                                                ?: emptyList()
                                        }
                                    }
                                }) { Text("Paste Here") }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = {
                                    moveMode = false
                                    itemsToMove = emptySet()
                                }) { Text("Cancel") }
                            }
                        } else {
                            Text(
                                text = if (searchResults == null) "Path: ${currentDir?.absolutePath ?: "N/A"}" else "Search Results for \"$searchQuery\""
                            )
                        }
                    }
                }

                if (searchResults == null) {
                    val folderCount = filesToShow.count { it.isDirectory }
                    val fileCount = filesToShow.count { !it.isDirectory }
                    Text(
                        text = "Folders: $folderCount, Files: $fileCount",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                // (2) Search UI
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it; searchResults = null }, // Clear deep search results on instant search
                        label = { Text("Filter or Search...") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        if (searchQuery.isNotBlank()) {
                            stashedSearchResults = null
                            stashedSearchQuery = ""
                            coroutineScope.launch {
                                isSearching = true
                                val results = mutableListOf<FileItem>()
                                withContext(Dispatchers.IO) {
                                    searchRecursively(currentDir, searchQuery, results)
                                }
                                searchResults = results
                                isSearching = false
                            }
                        }
                    }) { Text("Search") }
                }

                // (3) Navigation Buttons
                @OptIn(ExperimentalLayoutApi::class)
                Row(
                    modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val navButtonContentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    val navButtonModifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)

                    Button(
                        onClick = { currentDir = internalStorageRoot; clearSearchState() },
                        modifier = navButtonModifier,
                        contentPadding = navButtonContentPadding
                    ) { Text("Internal", fontSize = 12.sp) }

                    Button(
                        onClick = { currentDir = externalStorageRoot; clearSearchState() },
                        modifier = navButtonModifier,
                        contentPadding = navButtonContentPadding
                    ) { Text("External", fontSize = 12.sp) }

                    Button(
                        onClick = { 
                            currentDir.parentFile?.let {
                                highlightedPath = currentDir.absolutePath
                                currentDir = it
                                searchResults = null
                                searchQuery = ""
                            }
                        },
                        enabled = currentDir.absolutePath != internalStorageRoot.absolutePath && currentDir.absolutePath != externalStorageRoot.absolutePath,
                        modifier = navButtonModifier,
                        contentPadding = navButtonContentPadding
                    ) { Text("Up", fontSize = 12.sp) }

                    Button(
                        onClick = { showNewFolderDialog = true },
                        modifier = navButtonModifier,
                        contentPadding = navButtonContentPadding
                    ) { Text("New Folder", fontSize = 12.sp) }

                    Button(
                        onClick = {
                            searchResults = stashedSearchResults
                            searchQuery = stashedSearchQuery
                            stashedSearchResults = null
                            stashedSearchQuery = ""
                        },
                        enabled = stashedSearchResults != null,
                        modifier = navButtonModifier,
                        contentPadding = navButtonContentPadding
                    ) { Text("to Results", fontSize = 12.sp) }
                }
                
                // (4) View Controls
                Row(
                    modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("View:", fontSize = 12.sp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SortButton("List", viewMode == ViewMode.LIST) { viewMode = ViewMode.LIST }
                        SortButton("M-List", viewMode == ViewMode.MULTILINE_LIST) { viewMode = ViewMode.MULTILINE_LIST }
                        SortButton("Grid", viewMode == ViewMode.GRID) { viewMode = ViewMode.GRID }
                        SortButton("L-Grid", viewMode == ViewMode.LARGE_GRID) { viewMode = ViewMode.LARGE_GRID }
                    }
                }

                // (5) Sort controls
                Row(
                    modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Sort by:", fontSize = 12.sp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SortButton("Name", sortBy == SortBy.NAME) { sortBy = SortBy.NAME }
                        SortButton("Date", sortBy == SortBy.DATE) { sortBy = SortBy.DATE }
                        SortButton("Size", sortBy == SortBy.SIZE) { sortBy = SortBy.SIZE }

                        HorizontalDivider(modifier = Modifier.height(24.dp).width(1.dp))

                        SortButton("Asc", sortOrder == SortOrder.ASCENDING) { sortOrder = SortOrder.ASCENDING }
                        SortButton("Desc", sortOrder == SortOrder.DESCENDING) { sortOrder = SortOrder.DESCENDING }
                    }
                }

                when {
                    isSearching -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    filesToShow.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if(searchResults != null) "No results found." else "Folder is empty or not available.") }
                    else -> {
                        FileList(
                            files = filesToShow,
                            viewMode = viewMode,
                            selectionMode = selectionMode,
                            selectedItems = selectedItems,
                            onItemClick = { fileItem ->
                                highlightedPath = null
                                if (selectionMode) {
                                    selectedItems = if (selectedItems.contains(fileItem)) {
                                        selectedItems - fileItem
                                    } else {
                                        selectedItems + fileItem
                                    }
                                } else {
                                    if (searchResults != null) { // From search results
                                        stashedSearchResults = searchResults
                                        stashedSearchQuery = searchQuery
                                        currentDir = if (fileItem.isDirectory) {
                                            File(fileItem.path)
                                        } else {
                                            File(fileItem.path).parentFile ?: currentDir
                                        }
                                        searchResults = null
                                        searchQuery = ""
                                    } else { // From normal directory view
                                        if (fileItem.isDirectory) {
                                            currentDir = File(fileItem.path)
                                        }
                                    }
                                }
                            },
                            onItemLongClick = { fileItem ->
                                highlightedPath = null
                                if (!selectionMode && !moveMode) {
                                    selectionMode = true
                                    selectedItems = setOf(fileItem)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                            highlightedPath = highlightedPath
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SortButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    val buttonModifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp) 

    if (isSelected) {
        Button(
            onClick = onClick,
            modifier = buttonModifier,
            contentPadding = contentPadding
        ) { Text(text, fontSize = 12.sp) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            contentPadding = contentPadding
        ) { Text(text, fontSize = 12.sp) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    file: FileItem,
    isSingleLine: Boolean,
    isHighlighted: Boolean,
    isSelected: Boolean,
    onItemClick: (FileItem) -> Unit,
    onItemLongClick: (FileItem) -> Unit
) {
    val formattedDate = remember(file.lastModified) { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(file.lastModified)) }
    val formattedSize = remember(file.size, file.isDirectory) {
        if (file.isDirectory) "" else {
            val kb = file.size / 1024
            val mb = kb / 1024
            when { mb > 0 -> "$mb MB"; kb > 0 -> "$kb KB"; else -> "${file.size} B" }
        }
    }

    //val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent
    val backgroundColor = when {
        //isHighlighted -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        isHighlighted -> MaterialTheme.colorScheme.secondaryContainer
        //isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }

    Log.d("FileListItem", "file.name=${file.name},backgroundColor=${backgroundColor}")

    val modifier = Modifier
        .fillMaxWidth()
        .background(backgroundColor)
        .combinedClickable(
            onClick = { onItemClick(file) },
            onLongClick = { onItemLongClick(file) }
        )
        .padding(vertical = 8.dp)

    if (isSingleLine) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${if (file.isDirectory) "📁" else "📄"} ${file.name}",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(text = formattedDate, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.padding(horizontal = 8.dp))
            Text(text = formattedSize, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 60.dp))
        }
    } else {
        Column(
            modifier = modifier,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = if (file.isDirectory) "📁" else "📄", modifier = Modifier.padding(end = 8.dp))
                Text(text = file.name)
            }
            if (!file.isDirectory) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = formattedDate,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = formattedSize,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(
    file: FileItem,
    isLarge: Boolean,
    isSelected: Boolean,
    onItemClick: (FileItem) -> Unit,
    onItemLongClick: (FileItem) -> Unit
) {
    val isImage = remember(file.name) {
        file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true) ||
        file.name.endsWith(".png", true) || file.name.endsWith(".gif", true) ||
        file.name.endsWith(".webp", true)
    }
    val imageSize = if (isLarge) 420.dp else 80.dp
    val iconFontSize = if (isLarge) 240.sp else 48.sp
    val textFontSize = if (isLarge) 16.sp else 12.sp

    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier
            .padding(4.dp)
            .combinedClickable(
                onClick = { onItemClick(file) },
                onLongClick = { onItemLongClick(file) }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.size(imageSize), contentAlignment = Alignment.Center) {
                if (isImage && !file.isDirectory) {
                    AsyncImage(
                        model = File(file.path),
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = if (file.isDirectory) "📁" else "📄",
                        fontSize = iconFontSize
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = file.name,
                fontSize = textFontSize,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun FileList(
    files: List<FileItem>,
    viewMode: ViewMode,
    selectionMode: Boolean,
    selectedItems: Set<FileItem>,
    onItemClick: (FileItem) -> Unit,
    onItemLongClick: (FileItem) -> Unit,
    modifier: Modifier = Modifier,
    highlightedPath: String?
) {
    when (viewMode) {
        ViewMode.LIST, ViewMode.MULTILINE_LIST -> {
            LazyColumn(modifier = modifier) {
                items(files) { file ->
                    FileListItem(
                        file = file,
                        isSingleLine = viewMode == ViewMode.LIST,
                        isHighlighted = file.path == highlightedPath,
                        isSelected = selectedItems.contains(file),
                        onItemClick = onItemClick,
                        onItemLongClick = onItemLongClick
                    )
                }
            }
        }
        ViewMode.GRID, ViewMode.LARGE_GRID -> {
            val gridSize = if (viewMode == ViewMode.LARGE_GRID) 480.dp else 100.dp
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = gridSize),
                modifier = modifier
            ) {
                items(files) { file ->
                    FileGridItem(
                        file = file,
                        isLarge = viewMode == ViewMode.LARGE_GRID,
                        isSelected = selectedItems.contains(file),
                        onItemClick = onItemClick,
                        onItemLongClick = onItemLongClick
                    )
                }
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
fun FileListPreview() {
    FilelistTheme {
        val files = listOf(
            FileItem("folder1", "/folder1", true, 0, 0),
            FileItem("file1.jpg", "/file1.jpg", false, System.currentTimeMillis(), 1024),
            FileItem("file2", "/file2", false, System.currentTimeMillis(), 2048000)
        )
        FileList(files = files, viewMode = ViewMode.LIST, selectionMode = false, selectedItems = emptySet(), onItemClick = {}, onItemLongClick = {}, highlightedPath = "")
    }
}

@Preview(showBackground = true, widthDp = 1000)
@Composable
fun FileListPreviewLarge() {
    FilelistTheme {
        val files = listOf(
            FileItem("folder1", "/folder1", true, 0, 0),
            FileItem("file1.jpg", "/file1.jpg", false, System.currentTimeMillis(), 1024),
            FileItem("file2", "/file2", false, System.currentTimeMillis(), 2048000)
        )
        FileList(files = files, viewMode = ViewMode.LARGE_GRID, selectionMode = false, selectedItems = emptySet(), onItemClick = {}, onItemLongClick = {}, highlightedPath = "")
    }
}
