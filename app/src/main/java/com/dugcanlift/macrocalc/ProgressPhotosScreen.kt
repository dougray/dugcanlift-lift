package com.dugcanlift.macrocalc

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dugcanlift.macrocalc.data.ProgressPhotoStore
import com.dugcanlift.macrocalc.ui.theme.dclAccentText
import com.dugcanlift.macrocalc.ui.theme.dclTextButtonColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * "Oct 26, 2026", the reader's way: the locale's medium date, as LIFT iPhone's `PlanDayText.short`
 * is its day, abbreviated month and year.
 */
internal fun photoDay(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/**
 * Home → Body weight → Photos. A port of LIFT iPhone's `ProgressPhotosView`, word for word: photos
 * picked with the system Photo Picker (no permission), kept in [ProgressPhotoStore], a before/after
 * comparison, and long-press to delete. Full screen, as the iPhone's sheet is.
 */
@Composable
fun ProgressPhotosDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ProgressPhotoStore.forApp(context) }
    val scope = rememberCoroutineScope()
    var photos by remember { mutableStateOf<List<ProgressPhotoStore.Photo>>(emptyList()) }
    var comparing by remember { mutableStateOf<Pair<ProgressPhotoStore.Photo, ProgressPhotoStore.Photo>?>(null) }
    var confirmingDelete by remember { mutableStateOf<ProgressPhotoStore.Photo?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { photos = withContext(Dispatchers.IO) { store.photos() } }

    // The system Photo Picker: it runs outside LIFT and hands back only the picture chosen, so no
    // storage or media permission is asked for. Where a phone has no picker of its own, androidx
    // falls back to the document picker, which needs none either.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("could not open that photo")
                    store.save(bytes)
                    store.photos()
                }.getOrNull()
            }
            if (saved == null) failed = true else photos = saved
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, colors = dclTextButtonColors()) { Text("Done") }
                    Text(
                        "Progress photos",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    TextButton(
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        colors = dclTextButtonColors()
                    ) { Text("Add photo") }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val first = photos.firstOrNull()
                    val last = photos.lastOrNull()
                    if (photos.size >= 2 && first != null && last != null) {
                        TextButton(onClick = { comparing = first to last }, colors = dclTextButtonColors()) {
                            Text("Compare ${photoDay(first.date)} with ${photoDay(last.date)}")
                        }
                    }
                    PhotoGrid(photos, onDelete = { confirmingDelete = it })
                    Text(
                        "Photos stay on this phone, in LIFT's own storage. They are not in backup files " +
                            "and never go to a coach. Deleting LIFT deletes them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        comparing?.let { (before, after) ->
            PhotoCompareDialog(before, after, photos, onDismiss = { comparing = null })
        }
        if (failed) {
            AlertDialog(
                onDismissRequest = { failed = false },
                text = { Text("That photo couldn't be added.") },
                confirmButton = { TextButton(onClick = { failed = false }, colors = dclTextButtonColors()) { Text("OK") } }
            )
        }
        confirmingDelete?.let { photo ->
            AlertDialog(
                onDismissRequest = { confirmingDelete = null },
                title = { Text("Delete this photo?") },
                text = { Text("It is only on this phone, so this can't be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        store.delete(photo)
                        photos = store.photos()
                        confirmingDelete = null
                    }, colors = dclTextButtonColors()) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingDelete = null }, colors = dclTextButtonColors()) { Text("Cancel") }
                }
            )
        }
    }
}

/** At least 104 dp a column, as the iPhone's adaptive grid is. */
@Composable
private fun PhotoGrid(photos: List<ProgressPhotoStore.Photo>, onDelete: (ProgressPhotoStore.Photo) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gap = 8.dp
        val columns = ((maxWidth + gap) / (104.dp + gap)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            photos.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { photo ->
                        Box(modifier = Modifier.weight(1f)) { Thumbnail(photo, onDelete) }
                    }
                    repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(photo: ProgressPhotoStore.Photo, onDelete: (ProgressPhotoStore.Photo) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val label = "Photo from ${photoDay(photo.date)}"
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .combinedClickable(onClick = { menu = true }, onLongClick = { menu = true }, onClickLabel = "Delete")
            .semantics { contentDescription = label }
    ) {
        PhotoImage(photo, longest = 512, modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(8.dp)))
        Spacer(modifier = Modifier.height(4.dp))
        Text(photoDay(photo.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete(photo) })
        }
    }
}

/** A stored photo, decoded off the main thread at about [longest] px, cropped to fill. */
@Composable
private fun PhotoImage(photo: ProgressPhotoStore.Photo, longest: Int, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, photo.id, longest) {
        value = withContext(Dispatchers.IO) { ProgressPhotoStore.decode(photo.file, longest) }
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.outline)) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Two photos one over the other, with a handle to slide between them. */
@Composable
private fun PhotoCompareDialog(
    initialBefore: ProgressPhotoStore.Photo,
    initialAfter: ProgressPhotoStore.Photo,
    all: List<ProgressPhotoStore.Photo>,
    onDismiss: () -> Unit
) {
    var before by remember { mutableStateOf(initialBefore) }
    var after by remember { mutableStateOf(initialAfter) }
    var split by remember { mutableFloatStateOf(0.5f) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(72.dp))
                    Text(
                        "Compare",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = onDismiss, colors = dclTextButtonColors()) { Text("Done") }
                }
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(10.dp))
                            .clearAndSetSemantics {
                                contentDescription = "Before and after"
                                stateDescription = "${(split * 100).roundToInt()} percent before"
                                setProgress { value -> split = value.coerceIn(0f, 1f); true }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures { at -> split = (at.x / size.width).coerceIn(0f, 1f) }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    split = (change.position.x / size.width).coerceIn(0f, 1f)
                                }
                            }
                    ) {
                        val full = Modifier.fillMaxSize()
                        PhotoImage(after, ProgressPhotoStore.LONGEST, full)
                        Box(
                            modifier = full.drawWithContent {
                                clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
                            }
                        ) { PhotoImage(before, ProgressPhotoStore.LONGEST, full) }
                        val handle = with(LocalDensity.current) { (constraints.maxWidth * split).toDp() - 1.dp }
                        Box(
                            modifier = Modifier
                                .offset(x = handle)
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(Color.White)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        PhotoChoice("Before", before, all) { before = it }
                        PhotoChoice("After", after, all) { after = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoChoice(
    title: String,
    selected: ProgressPhotoStore.Photo,
    all: List<ProgressPhotoStore.Photo>,
    onPick: (ProgressPhotoStore.Photo) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, colors = dclTextButtonColors()) {
            Text("$title: ${photoDay(selected.date)}", color = dclAccentText())
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            all.forEach { photo ->
                DropdownMenuItem(text = { Text(photoDay(photo.date)) }, onClick = { open = false; onPick(photo) })
            }
        }
    }
}
