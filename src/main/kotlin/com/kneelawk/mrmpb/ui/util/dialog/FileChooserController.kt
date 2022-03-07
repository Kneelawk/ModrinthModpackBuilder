package com.kneelawk.mrmpb.ui.util.dialog

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import com.kneelawk.mrmpb.util.Conflator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.stream.Collectors
import kotlin.io.path.*

@Composable
fun rememberFileChooserController(mode: FileChooserMode, finished: (Path?) -> Unit): FileChooserInterface {
    // Keep input updated with recompositions
    @Suppress("NAME_SHADOWING")
    val mode by rememberUpdatedState(mode)

    @Suppress("NAME_SHADOWING")
    val finished by rememberUpdatedState(finished)

    // Grab the composable's coroutine scope, so we can run stuff in it later
    val composableScope = rememberCoroutineScope()

    // Setup state
    val viewingState: MutableState<Path> = remember { mutableStateOf(Paths.get(System.getProperty("user.home"))) }
    val cViewing by viewingState

    val topBarViewingState = remember { mutableStateOf(cViewing) }

    val fileList = remember { mutableStateListOf<Path>() }

    val selectedState = remember { mutableStateOf("") }
    val cSelected by selectedState

    val selectedProduced by produceState(SelectedProduced(false, null, Paths.get("")), cSelected) {
        // we do this checking in a produceState, so that we can handle cases when filesystem checks like
        // `isRegularFile()` or `isDirectory()` take a significant amount of time.
        val selectedPath = Paths.get(cSelected)
        value = if (cSelected.isEmpty()) {
            SelectedProduced(false, null, selectedPath)
        } else if (!isValidFilename(cSelected)) {
            SelectedProduced(
                false, "Paths must not be blank and must not contain '\"', '*', '<', '>', '?', or '|'.", selectedPath
            )
        } else {
            when (mode) {
                FileChooserMode.SAVE -> {
                    SelectedProduced(true, null, selectedPath)
                }
                FileChooserMode.OPEN_FILE -> {
                    if (withContext(Dispatchers.IO) { selectedPath.isRegularFile() }) {
                        SelectedProduced(true, null, selectedPath)
                    } else {
                        SelectedProduced(false, "The selected file is not a regular file.", selectedPath)
                    }
                }
                FileChooserMode.OPEN_DIRECTORY -> {
                    if (withContext(Dispatchers.IO) { selectedPath.isDirectory() }) {
                        SelectedProduced(true, null, selectedPath)
                    } else {
                        SelectedProduced(false, "The selected file is not a folder.", selectedPath)
                    }
                }
            }
        }
    }
    val cSelectedPath by derivedStateOf { selectedProduced.selectedPath }
    val selectedValidState = derivedStateOf { selectedProduced.valid }
    val selectedErrorState = derivedStateOf { selectedProduced.error }

    val showHiddenFilesState = remember { mutableStateOf(false) }
    val cShowHiddenFiles by showHiddenFilesState

    val listState = rememberLazyListState()

    val showCreateFolderState = remember { mutableStateOf(false) }

    // Setup suspend stuff
    val recalculateFileList = remember {
        Conflator<Unit>(composableScope) {
            fileList.clear()
            val list = withContext(Dispatchers.IO) {
                Files.list(cViewing)
            }.filter {
                (it.isDirectory() || mode != FileChooserMode.OPEN_DIRECTORY) && (!it.isHidden() || cShowHiddenFiles)
            }.sorted { o1, o2 -> o1.name.compareTo(o2.name, ignoreCase = true) }.collect(Collectors.toList())
            fileList.addAll(list)

            val index = fileList.indexOf(Paths.get(cSelected))
            if (index > 0) {
                listState.scrollToItem(index)
            } else {
                listState.scrollToItem(0)
            }
        }
    }

    // Calculate everything the first time or when stuff updates
    LaunchedEffect(mode, cViewing, cShowHiddenFiles) {
        recalculateFileList.send(Unit)
    }

    // No need to remember this as all its state is remembered up above
    return object : FileChooserInterface {
        override var viewing by viewingState
        override var topBarViewing by topBarViewingState
        override val fileList = fileList
        override var selected by selectedState
        override val selectedValid by selectedValidState
        override val selectedError by selectedErrorState
        override var showHiddenFiles by showHiddenFilesState
        override val listState = listState
        override var showCreateFolderDialog by showCreateFolderState

        override fun showHiddenFilesToggle() {
            showHiddenFiles = !showHiddenFiles
        }

        override fun setViewingHome() {
            viewing = Paths.get(System.getProperty("user.home"))
        }

        override fun openCreateFolderDialog() {
            showCreateFolderDialog = true
        }

        @Composable
        override fun newCreateFolderController(): CreateFolderInterface {
            val curViewing = remember { viewing }

            val folderNameState = remember { mutableStateOf("") }
            val cFolderName by folderNameState
            val folderNameProduced by produceState(
                FolderNameProduced(false, null, curViewing.resolve("")), cFolderName
            ) {
                val newFolder = curViewing.resolve(cFolderName)
                value = if (cFolderName.isEmpty()) {
                    FolderNameProduced(false, null, newFolder)
                } else if (!isValidFolderName(cFolderName)) {
                    FolderNameProduced(
                        false,
                        "Folder names must not be blank and must not contain '\"', '*', '<', '>', '?', '|', ':', '/', or '\\'.",
                        newFolder
                    )
                } else if (withContext(Dispatchers.IO) { newFolder.exists() }) {
                    FolderNameProduced(false, "\"$cFolderName\" already exists.", newFolder)
                } else {
                    FolderNameProduced(true, null, newFolder)
                }
            }
            val folderNameValidState = derivedStateOf { folderNameProduced.valid }
            val folderNameErrorState = derivedStateOf { folderNameProduced.error }
            val newFolder by derivedStateOf { folderNameProduced.newFolder }

            // No need to remember this as all its state is remembered elsewhere
            return object : CreateFolderInterface {
                override var folderName by folderNameState
                override val folderNameValid by folderNameValidState
                override val folderNameError by folderNameErrorState

                override fun folderNameUpdate(newName: String) {
                    folderName = newName
                }

                override fun createFolder() {
                    showCreateFolderDialog = false
                    composableScope.launch {
                        withContext(Dispatchers.IO) {
                            Files.createDirectory(newFolder)
                        }
                        selected = newFolder.pathString

                        recalculateFileList.send(Unit)
                    }
                }

                override fun cancel() {
                    showCreateFolderDialog = false
                }
            }
        }

        override fun topBarSelect(newViewing: Path) {
            viewing = newViewing
        }

        override fun selectedUpdate(path: Path) {
            selected = path.pathString
        }

        override fun doubleClick(path: Path) {
            composableScope.launch {
                if (withContext(Dispatchers.IO) { path.isReadable() }) {
                    // TODO: handle modes where double-clicking a file means selecting it
                    val attributes = withContext(Dispatchers.IO) { path.readAttributes<BasicFileAttributes>() }
                    if (attributes.isDirectory) {
                        viewing = path
                        topBarViewing = path
                    }
                }
            }
        }

        override fun isPathSelected(path: Path): Boolean {
            return path == cSelectedPath
        }

        override fun selectedFieldUpdate(newText: String) {
            selected = newText
        }

        override fun select() {
            if (selectedValid) {
                finished(cSelectedPath)
            }
        }

        override fun cancel() {
            finished(null)
        }
    }
}

private val invalidPathChars = charArrayOf('"', '*', '<', '>', '?', '|', 0x7F.toChar())
private val invalidNameChars = charArrayOf('/', '\\', ':')

private fun isValidFilename(name: String): Boolean {
    return name.isNotBlank() && name.length <= 255 && invalidPathChars.none { name.contains(it) }
}

private fun isValidFolderName(name: String): Boolean {
    return isValidFilename(name) && invalidNameChars.none { name.contains(it) }
}

private data class FolderNameProduced(val valid: Boolean, val error: String?, val newFolder: Path)
private data class SelectedProduced(val valid: Boolean, val error: String?, val selectedPath: Path)
