package org.signal.core.util.logging

import android.annotation.SuppressLint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.signal.core.util.BuildConfig

@SuppressLint("LogNotSignal")
object AndroidLogger : Log.Logger() {

  private val isLogVerbose: Boolean get() = BuildConfig.VERBOSE_LOGGING

  private val serialExecutor: Executor = Executors.newSingleThreadExecutor { Thread(it, "signal-logcat") }

  override fun v(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) {
    if (isLogVerbose) serialExecutor.execute {
      android.util.Log.v(tag, message.scrub(), t)
    }
  }

  override fun d(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) {
    if (isLogVerbose) serialExecutor.execute {
      android.util.Log.d(tag, message.scrub(), t)
    }
  }

  override fun i(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) {
    if (isLogVerbose) serialExecutor.execute {
      android.util.Log.i(tag, message.scrub(), t)
    }
  }

  override fun w(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) {
    serialExecutor.execute {
      android.util.Log.w(tag, message.scrub(), t)
    }
  }

  override fun e(tag: String, message: String?, t: Throwable?, keepLonger: Boolean) {
    serialExecutor.execute {
      android.util.Log.e(tag, message.scrub(), t)
    }
  }

  override fun flush() {
    val latch = CountDownLatch(1)

    serialExecutor.execute {
      latch.countDown()
    }

    try {
      latch.await()
    } catch (e: InterruptedException) {
      android.util.Log.w("AndroidLogger", "Interrupted while waiting for flush()", e)
    }
  }

  private fun String?.scrub(): String? {
    return this?.let { Scrubber.scrub(it).toString() }
  }
}
