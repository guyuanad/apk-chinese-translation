package com.google.ai.edge.gallery

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GlobalLogger {
  private const val TAG = "GlobalLogger"
  private var logFileWriter: FileWriter? = null
  private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

  fun initialize(context: Context) {
    try {
      val logDir = context.getExternalFilesDir(null)
      if (logDir != null && !logDir.exists()) {
        logDir.mkdirs()
      }
      val logFile = File(logDir, "app_detailed_log.txt")
      logFileWriter = FileWriter(logFile, true)
      log(TAG, "INIT", "Global Logger Initialized")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize logger", e)
    }
  }

  fun log(tag: String, level: String, message: String) {
    try {
      val timestamp = dateFormat.format(Date())
      val threadId = Thread.currentThread().id
      val logLine = "[$timestamp | $level | TID:$threadId | $tag | $message]\n"
      
      logFileWriter?.append(logLine)
      logFileWriter?.flush()
      Log.d(tag, message)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to write log", e)
    }
  }
}
