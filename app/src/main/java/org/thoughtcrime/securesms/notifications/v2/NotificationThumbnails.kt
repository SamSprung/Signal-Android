package org.thoughtcrime.securesms.notifications.v2

import android.content.Context
import android.net.Uri
import android.os.Build
import org.signal.core.util.asListContains
import org.signal.core.util.bitmaps.BitmapDecodingException
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.signal.glide.decryptableuri.DecryptableUri
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.util.ImageCompressionUtil
import org.thoughtcrime.securesms.util.RemoteConfig
import org.thoughtcrime.securesms.util.kb

/**
 * Creates and caches attachment thumbnails solely for use by Notifications.
 *
 * Handles LRU cache on it's own due to needing to cleanup BlobProvider when oldest element is evicted.
 *
 * Previously the PartProvider was used and it would provide the entire, full-resolution image assets causing
 * some OEMs to ANR during file reading.
 */
object NotificationThumbnails {
  private val TAG = Log.tag(NotificationThumbnails::class.java)

  private const val MAX_CACHE_SIZE = 16
  private val TARGET_SIZE = 128.kb

  private val executor = SignalExecutors.BOUNDED_IO

  private val thumbnailCache = LinkedHashMap<ThumbnailCacheKey, CachedThumbnail>(MAX_CACHE_SIZE)

  /**
   * Some devices are hitting weird issues when rendering notification thumbnails. It's only a few specific older models, so rather than try to figure out the
   * specifics here, we'll just disable notification thumbnails for them.
   */
  private val isBlocklisted by lazy {
    RemoteConfig.notificationThumbnailProductBlocklist.asListContains(Build.PRODUCT)
  }

  fun getWithoutModifying(notificationItem: NotificationItem): List<NotificationItem.ThumbnailInfo> {
    return notificationItem.resolveThumbnailInfos(context = null)
  }

  fun get(context: Context, notificationItem: NotificationItem): List<NotificationItem.ThumbnailInfo> {
    return notificationItem.resolveThumbnailInfos(context)
  }

  private fun NotificationItem.resolveThumbnailInfos(context: Context?): List<NotificationItem.ThumbnailInfo> {
    if (isBlocklisted) {
      return emptyList()
    }

    return getReadyThumbnailSlides().map { thumbnailSlide ->
      getThumbnailInfoForSlide(context, thumbnailSlide)
    }
  }

  private fun NotificationItem.getThumbnailInfoForSlide(context: Context?, thumbnailSlide: ReadyThumbnailSlide): NotificationItem.ThumbnailInfo {
    if (thumbnailSlide.fileSize < TARGET_SIZE) {
      return NotificationItem.ThumbnailInfo(thumbnailSlide.publicUri, thumbnailSlide.contentType)
    }

    val thumbnail: CachedThumbnail? = synchronized(thumbnailCache) { thumbnailCache[thumbnailSlide.cacheKey(id)] }

    return when {
      thumbnail == CachedThumbnail.PENDING -> NotificationItem.ThumbnailInfo.NONE
      thumbnail != null -> NotificationItem.ThumbnailInfo(thumbnail.uri, thumbnail.contentType)
      context != null -> {
        enqueueThumbnailCompression(context, this, thumbnailSlide)
        NotificationItem.ThumbnailInfo.NONE
      }
      else -> NotificationItem.ThumbnailInfo.NEEDS_SHRINKING
    }
  }

  private fun enqueueThumbnailCompression(context: Context, notificationItem: NotificationItem, thumbnailSlide: ReadyThumbnailSlide) {
    val cacheKey = thumbnailSlide.cacheKey(notificationItem.id)

    val shouldEnqueue: Boolean = synchronized(thumbnailCache) {
      if (thumbnailCache.containsKey(cacheKey)) {
        false
      } else {
        thumbnailCache[cacheKey] = CachedThumbnail.PENDING
        true
      }
    }

    if (!shouldEnqueue) {
      return
    }

    executor.execute {
      val result: ImageCompressionUtil.Result? = try {
        ImageCompressionUtil.compressWithinConstraints(
          context,
          thumbnailSlide.contentType,
          DecryptableUri(thumbnailSlide.sourceUri),
          1024,
          TARGET_SIZE,
          60
        )
      } catch (e: BitmapDecodingException) {
        Log.i(TAG, "Unable to decode bitmap", e)
        null
      }

      if (result == null) {
        Log.i(TAG, "Unable to compress attachment thumbnail for $cacheKey")
        return@execute
      }

      val thumbnailUri = AppDependencies.blobs
        .forData(result.data)
        .withMimeType(result.mimeType)
        .withFileName(result.hashCode().toString())
        .createForSingleSessionInMemory()

      synchronized(thumbnailCache) {
        if (thumbnailCache.size >= MAX_CACHE_SIZE) {
          thumbnailCache.remove(thumbnailCache.keys.first())?.uri?.let {
            AppDependencies.blobs.delete(context, it)
          }
        }
        thumbnailCache[cacheKey] = CachedThumbnail(thumbnailUri, result.mimeType)
      }

      AppDependencies.messageNotifier.updateNotification(context, notificationItem.thread)
    }
  }

  fun removeAllExcept(notificationItems: List<NotificationItem>) {
    val currentMessages = notificationItems.flatMap { notificationItem ->
      notificationItem.getReadyThumbnailSlides().map { thumbnailSlide ->
        thumbnailSlide.cacheKey(notificationItem.id)
      }
    }.toSet()

    synchronized(thumbnailCache) {
      thumbnailCache.keys.removeIf { !currentMessages.contains(it) }
    }
  }

  private fun NotificationItem.getReadyThumbnailSlides(): List<ReadyThumbnailSlide> {
    return slideDeck?.thumbnailSlides?.mapNotNull { slide ->
      val uri: Uri = slide.uri ?: return@mapNotNull null

      if (slide.isInProgress) {
        null
      } else {
        ReadyThumbnailSlide(
          sourceUri = uri,
          publicUri = slide.publicUri,
          contentType = slide.contentType,
          fileSize = slide.fileSize
        )
      }
    } ?: emptyList()
  }

  private fun ReadyThumbnailSlide.cacheKey(messageId: Long): ThumbnailCacheKey {
    return ThumbnailCacheKey(MessageId(messageId), sourceUri.toString())
  }

  private data class ReadyThumbnailSlide(val sourceUri: Uri, val publicUri: Uri?, val contentType: String, val fileSize: Long)
  private data class ThumbnailCacheKey(val messageId: MessageId, val uri: String)

  private data class CachedThumbnail(val uri: Uri?, val contentType: String?) {
    companion object {
      val PENDING = CachedThumbnail(null, null)
    }
  }
}
