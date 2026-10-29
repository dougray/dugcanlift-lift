package com.dugcanlift.macrocalc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.data.Bookmarklet
import com.dugcanlift.macrocalc.ui.theme.dclCardBorder
import com.dugcanlift.macrocalc.ui.theme.dclTextButtonColors

/** The button beside "Paste a recipe" and "Browse the catalogue" (kit `bookmarklet/README.md`). */
internal const val SEND_FROM_BROWSER = "Send recipes from your browser"

/**
 * The words of [BookmarkletDialog], in order, so a test can read them as strings. They are the
 * wording every build shares for this feature, with {App} = LIFT, word for word: change them in
 * every build or in none. Android has no Safari line and none of the web-only ones.
 */
internal object BookmarkletCopy {
    const val INTRO = "Save this as a bookmark once. On any recipe page, tap the bookmark and the " +
        "recipe opens here for you to check. LIFT never visits the page."
    const val LINK_LABEL = "Send to LIFT"
    const val COPY = "Copy"
    const val COPIED = "Copied"
    val STEPS = listOf(
        "1. Tap Copy.",
        "2. Bookmark any page.",
        "3. Edit the bookmark. Name it Send to LIFT, and paste over its address.",
        "4. On a recipe page, type Send to LIFT in the address bar and tap the bookmark."
    )
    const val DONE = "Done"
}

/**
 * Offers this build's "Send to LIFT" bookmarklet: the `liftApp` string from the kit's
 * `bookmarklets.json`, a Copy button, and the steps to save it in Chrome. A phone browser cannot
 * drag a link to a bookmarks bar, so it is copy, bookmark, edit.
 */
@Composable
internal fun BookmarkletDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bookmarklet = remember { Bookmarklet.load(context) }
    var copied by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SEND_FROM_BROWSER) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(BookmarkletCopy.INTRO, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                // Always bundled (RecipeLinkTest pins the asset); without it there is nothing to copy.
                if (bookmarklet != null) {
                    Text(BookmarkletCopy.LINK_LABEL, style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    // The whole string goes to the clipboard; three lines of it are enough to
                    // show what it is.
                    Text(
                        text = bookmarklet,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(dclCardBorder() ?: BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            copyToClipboard(context, bookmarklet)
                            copied = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (copied) BookmarkletCopy.COPIED else BookmarkletCopy.COPY)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                BookmarkletCopy.STEPS.forEach { step ->
                    Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = dclTextButtonColors()) { Text(BookmarkletCopy.DONE) }
        }
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Send to LIFT", text))
}
