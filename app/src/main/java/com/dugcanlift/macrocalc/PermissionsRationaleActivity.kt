package com.dugcanlift.macrocalc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Health Connect requires an activity that responds to
 * ACTION_SHOW_PERMISSIONS_RATIONALE so someone can see why the app asked for
 * step data without opening the app itself. The real policy already lives at
 * dugcanlift.com, so this just hands off to it rather than duplicating the
 * text in two places.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
        finish()
    }

    companion object {
        const val PRIVACY_POLICY_URL = "https://www.dugcanlift.com/app/privacy/"
    }
}
