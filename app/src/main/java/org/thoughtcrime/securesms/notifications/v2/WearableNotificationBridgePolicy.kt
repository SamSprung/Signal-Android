package org.thoughtcrime.securesms.notifications.v2

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * Decides how image preview notifications should be shaped for notification listeners
 * that forward phone notifications to companion wearables.
 */
internal object WearableNotificationBridgePolicy {

  enum class ImagePreviewPresentation {
    MEDIA,
    TEXT_ONLY_FALLBACK
  }

  private val TEXT_ONLY_FALLBACK_LISTENER_PACKAGES: Set<String> = setOf(
    "com.garmin.android.apps.connectmobile"
  )

  private val NATIVE_WEAR_IMAGE_LISTENER_PACKAGES: Set<String> = setOf(
    "com.google.android.apps.wear.companion",
    "com.google.android.wearable.app",
    "com.samsung.android.geargplugin",
    "com.samsung.android.watchplugin",
    "com.samsung.wearable.watch7plugin"
  )

  private val NATIVE_WEAR_IMAGE_LISTENER_PREFIXES: Set<String> = setOf(
    "com.samsung.wearable."
  )

  fun getImagePreviewPresentation(context: Context): ImagePreviewPresentation {
    val listenerPackages: Set<String> = NotificationManagerCompat.getEnabledListenerPackages(context)
    val hasTextOnlyFallbackListener: Boolean = listenerPackages.any { it in TEXT_ONLY_FALLBACK_LISTENER_PACKAGES }
    val hasNativeWearImageListener: Boolean = listenerPackages.any { packageName ->
      packageName in NATIVE_WEAR_IMAGE_LISTENER_PACKAGES ||
        NATIVE_WEAR_IMAGE_LISTENER_PREFIXES.any { packageName.startsWith(it) }
    }

    return if (hasTextOnlyFallbackListener && !hasNativeWearImageListener) {
      ImagePreviewPresentation.TEXT_ONLY_FALLBACK
    } else {
      ImagePreviewPresentation.MEDIA
    }
  }
}
