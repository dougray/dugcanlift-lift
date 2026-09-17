package com.dugcanlift.macrocalc

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dugcanlift.macrocalc.ui.theme.DugCanLiftCalcTheme

/**
 * Health Connect requires an activity that responds to
 * ACTION_SHOW_PERMISSIONS_RATIONALE (and, on Android 14+, the
 * VIEW_PERMISSION_USAGE alias in the manifest) so someone can read the
 * privacy policy from Health Connect's own settings without opening the app.
 *
 * The policy lives at dugcanlift.com, the same URL the Play listing names, so
 * this hands off to the browser rather than keeping a second copy that could
 * drift. A phone with no browser used to crash here; it now gets a short
 * in-app summary with the address to type in.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
            finish()
        } catch (e: ActivityNotFoundException) {
            setContent { DugCanLiftCalcTheme { RationaleFallback(onClose = ::finish) } }
        }
    }

    companion object {
        const val PRIVACY_POLICY_URL = "https://www.dugcanlift.com/app/privacy/"

        const val HEALTH_CONNECT_SUMMARY =
            "LIFT reads your steps and step history from Health Connect to show today's " +
                "steps against your goal, and to include daily steps in Send to Coach when " +
                "you choose to. It writes a run, walk or hike, with its route, distance and " +
                "climb, only when you tap Export to Health Connect. LIFT has no servers: " +
                "your steps leave this phone only in an email you send to your coach yourself."
    }
}

@Composable
private fun RationaleFallback(onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text("How LIFT uses Health Connect", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(PermissionsRationaleActivity.HEALTH_CONNECT_SUMMARY, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Text("The full privacy policy is at:", style = MaterialTheme.typography.bodyMedium)
            SelectionContainer {
                Text(PermissionsRationaleActivity.PRIVACY_POLICY_URL, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}
