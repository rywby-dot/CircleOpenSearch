package org.circleopensearch

import android.content.*
import android.net.Uri

/** Google integration is isolated because its exported entry points may change. */
object LensLauncher {
    private const val GOOGLE = "com.google.android.googlequicksearchbox"
    fun open(context: Context, image: Uri) {
        val clip = ClipData.newUri(context.contentResolver, "Screenshot", image)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, image)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val handlers = context.packageManager.queryIntentActivities(send, 0)
        val lens = handlers.firstOrNull { it.activityInfo.packageName == "com.google.ar.lens" }
            ?: handlers.firstOrNull { it.activityInfo.packageName == GOOGLE && (it.activityInfo.name.contains("lens", true) || it.loadLabel(context.packageManager).toString().contains("lens", true)) }
        if (lens != null) send.component = ComponentName(lens.activityInfo.packageName, lens.activityInfo.name)
        context.startActivity(if (lens != null) send else Intent.createChooser(send, "Search with Google Lens").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
