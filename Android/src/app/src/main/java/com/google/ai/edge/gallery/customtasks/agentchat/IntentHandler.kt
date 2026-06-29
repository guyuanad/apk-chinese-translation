/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.util.Log
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.net.toUri
import com.google.ai.edge.gallery.notifications.NotificationScheduleManagerEntryPoint
import com.google.ai.edge.gallery.proto.ScheduledNotification
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.EntryPointAccessors
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@JsonClass(generateAdapter = true)
data class OpenAppParams(val package_name: String)

/** Map common Chinese app names to package names for fallback. */
private val APP_NAME_TO_PACKAGE_MAP = mapOf(
  "抖音" to "com.ss.android.ugc.aweme",
  "TikTok" to "com.zhiliaoapp.musically",
  "微信" to "com.tencent.mm",
  "WeChat" to "com.tencent.mm",
  "支付宝" to "com.eg.android.AlipayGphone",
  "Alipay" to "com.eg.android.AlipayGphone",
  "淘宝" to "com.taobao.taobao",
  "Taobao" to "com.taobao.taobao",
  "小红书" to "com.xingin.xhs",
  "Bilibili" to "tv.danmaku.bili",
  "哔哩哔哩" to "tv.danmaku.bili",
  "QQ" to "com.tencent.mobileqq",
  "微博" to "com.sina.weibo",
  "高德地图" to "com.autonavi.minimap",
  "美团" to "com.sankuai.meituan",
  "饿了么" to "me.ele",
  "京东" to "com.jingdong.app.mall",
  "拼多多" to "com.xunmeng.pinduoduo",
  "知乎" to "com.zhihu.android",
  "网易云音乐" to "com.netease.cloudmusic",
  "QQ音乐" to "com.tencent.qqmusic",
  "番茄免费小说" to "com.dragon.read",
  "番茄小说" to "com.dragon.read",
  "番茄" to "com.dragon.read",
  "Chrome" to "com.android.chrome",
  "chrome" to "com.android.chrome",
  "浏览器" to "com.android.chrome",
  "Edge" to "com.microsoft.emmx",
  "UC浏览器" to "com.UCMobile",
  "夸克" to "com.quark.browser",
  "爱原生" to "com.aiego.agent",
  "AISelf" to "com.aiego.agent",
  "华为浏览器" to "com.huawei.browser",
  "荣耀浏览器" to "com.hihonor.browser",
  "应用市场" to "com.hihonor.appmarket",
  "应用商店" to "com.hihonor.appmarket",
  "华为应用市场" to "com.huawei.appmarket",
  "酷安" to "com.coolapk.market",
  "小米应用商店" to "com.xiaomi.market",
  "VIVO应用商店" to "com.vivo.market",
  "OPPO软件商店" to "com.oppo.market",
  "设置" to "com.android.settings",
  "电话" to "com.android.dialer",
  "信息" to "com.android.messaging",
  "短信" to "com.android.messaging",
  "日历" to "com.android.calendar",
  "相机" to "com.android.camera2",
  "相册" to "com.google.android.apps.photos",
  "文件管理器" to "com.hihonor.filemanager",
  "计算器" to "com.android.calculator2",
  "时钟" to "com.android.deskclock",
  "录音机" to "com.android.soundrecorder",
  "指南针" to "com.android.compass",
)

/** Map commonly hallucinated package names to correct ones. */
private val WRONG_PACKAGE_NAME_MAP = mapOf(
  "com.xiaomiqq.novel" to "com.dragon.read",
  "com.xiaomiqqnovel" to "com.dragon.read",
  "com.xiaomi.novel" to "com.dragon.read",
  "com.xiaomi.qq.novel" to "com.dragon.read",
)

@JsonClass(generateAdapter = true)
data class SendEmailParams(
  val extra_email: String,
  val extra_subject: String,
  val extra_text: String,
)

@JsonClass(generateAdapter = true)
data class SendSmsParams(val phone_number: String, val sms_body: String)

@JsonClass(generateAdapter = true)
data class CreateCalendarEventParams(
  val title: String,
  val description: String,
  val begin_time: String,
  val end_time: String,
)

@JsonClass(generateAdapter = true) data class ReadCalendarEventsParams(val date: String? = null, val start_date: String? = null, val end_date: String? = null)

@JsonClass(generateAdapter = true)
data class CalendarEventDto(
  val event_id: String,
  val title: String,
  val description: String,
  val begin_time: String,
  val end_time: String,
)

@JsonClass(generateAdapter = true) data class ReadCalendarEventsResponse(val events: List<CalendarEventDto>)

@JsonClass(generateAdapter = true)
data class UpdateCalendarEventParams(
  val event_id: String,
  val title: String? = null,
  val description: String? = null,
  val begin_time: String? = null,
  val end_time: String? = null,
)

@JsonClass(generateAdapter = true)
data class DeleteCalendarEventParams(val event_id: String)

@JsonClass(generateAdapter = true)
data class WriteClipboardParams(val text: String)

enum class IntentAction(
  val action: String,
  val requiresUserConfirmation: Boolean = false,
  val confirmationMessage: String = "",
) {
  OPEN_APP("open_app"),
  SEND_EMAIL(
    "send_email",
    requiresUserConfirmation = true,
    confirmationMessage = "确认发送这封邮件？",
  ),
  SEND_SMS(
    "send_sms",
    requiresUserConfirmation = true,
    confirmationMessage = "确认发送这条短信？",
  ),
  CREATE_CALENDAR_EVENT(
    "create_calendar_event",
    requiresUserConfirmation = true,
    confirmationMessage = "确认创建这个日历事件？",
  ),
  READ_CALENDAR_EVENTS("read_calendar_events"),
  UPDATE_CALENDAR_EVENT(
    "update_calendar_event",
    requiresUserConfirmation = true,
    confirmationMessage = "确认更新这个日历事件？",
  ),
  DELETE_CALENDAR_EVENT(
    "delete_calendar_event",
    requiresUserConfirmation = true,
    confirmationMessage = "确认删除这个日历事件？此操作不可撤销。",
  ),
  GET_CURRENT_DATE_AND_TIME("get_current_date_and_time"),
  SCHEDULE_NOTIFICATION(
    "schedule_notification",
    requiresUserConfirmation = true,
    confirmationMessage = "确认创建这个日程提醒？",
  ),
  READ_CLIPBOARD("read_clipboard"),
  WRITE_CLIPBOARD(
    "write_clipboard",
    requiresUserConfirmation = true,
    confirmationMessage = "确认将文字复制到剪贴板？",
  );

  companion object {
    fun from(action: String): IntentAction? = entries.find { it.action == action }
  }
}

@JsonClass(generateAdapter = true)
data class ScheduleNotificationParams(
  val title: String,
  val message: String,
  val hour: Int,
  val minute: Int,
  val deeplink: String? = null,
  val task_id: String? = null,
  val model_name: String? = null,
  val year: Int? = null,
  val month: Int? = null,
  val day: Int? = null,
  val repeat_daily: Boolean? = null,
)

/**
 * Dynamically builds a mapping from installed app display names to package names.
 * Scans all installed apps using PackageManager.getApplicationLabel() at first call,
 * then caches the result. This eliminates the need for manual app name mappings.
 *
 * Returns a map of {lowercase_display_name: package_name} for all launchable apps.
 */
private const val DYNAMIC_MAP_TAG = "IntentHandler"

private fun buildAppNameToPackageMap(context: Context): Map<String, String> {
  val pm = context.packageManager
  val installedApps = pm.getInstalledApplications(0)
  val map = mutableMapOf<String, String>()

  for (app in installedApps) {
    val packageName = app.packageName
    try {
      val appInfo = pm.getApplicationInfo(packageName, 0)
      val label = pm.getApplicationLabel(appInfo).toString().trim()
      if (label.isNotEmpty()) {
        // Store lowercase key for case-insensitive matching
        map[label.lowercase()] = packageName
        // Also store the original case
        if (label.lowercase() != label) {
          map[label] = packageName
        }
      }
    } catch (e: Exception) {
      // Skip apps we can't read
    }
  }

  return map.toMap()
}

/**
 * Cache for the dynamic app name mapping. Built lazily on first access.
 */
private var cachedAppNameMap: Map<String, String>? = null

private fun getAppNameToPackageMap(context: Context): Map<String, String> {
  if (cachedAppNameMap == null) {
    Log.d(DYNAMIC_MAP_TAG, "Building dynamic app name to package mapping...")
    val start = System.currentTimeMillis()
    cachedAppNameMap = buildAppNameToPackageMap(context)
    val elapsed = System.currentTimeMillis() - start
    Log.d(DYNAMIC_MAP_TAG, "Built ${cachedAppNameMap!!.size} app name mappings in ${elapsed}ms")
  }
  return cachedAppNameMap!!
}

object IntentHandler {
  private const val TAG = "IntentHandler"

  /** Clear cached app name mapping (useful for testing). */
  fun clearCache() {
    cachedAppNameMap = null
  }

  suspend fun handleAction(
    context: Context,
    action: String,
    parameters: String,
    // requestPermission is a suspend function that takes a permission string and returns true if
    // the permission is granted, false otherwise.
    requestPermission: suspend (String) -> Boolean,
  ): String {
    return when (IntentAction.from(action)) {
      IntentAction.OPEN_APP -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(OpenAppParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null && params.package_name.isNotEmpty()) {
            var packageName = params.package_name

            // Step 1: Check if it's a commonly hallucinated wrong package name and correct it.
            val corrected = WRONG_PACKAGE_NAME_MAP[packageName]
            if (corrected != null && corrected != packageName) {
              packageName = corrected
              Log.d(TAG, "Corrected hallucinated package name '$params.package_name' to '$packageName'")
            }

            // Step 2: Try to get the launch intent directly.
            var intent = context.packageManager.getLaunchIntentForPackage(packageName)

            // Step 3: If not found and it looks like an app name (not a package name), try the static name mapping.
            if (intent == null && !packageName.contains(".")) {
              val mappedPackage = APP_NAME_TO_PACKAGE_MAP[packageName]
              if (mappedPackage != null) {
                packageName = mappedPackage
                Log.d(TAG, "Mapped app name '$params.package_name' to package '$packageName' (static map)")
                intent = context.packageManager.getLaunchIntentForPackage(packageName)
              }
            }

            // Step 4: If still not found, try the dynamic mapping (scans all installed apps).
            val originalPackageName = packageName  // Keep original for error messages
            if (intent == null) {
              val dynamicMap = getAppNameToPackageMap(context)

              // 4a. Try exact match (app display name → package name)
              // Then verify the matched app actually has a launch intent
              val exactMatch = dynamicMap[packageName] ?: dynamicMap[packageName.lowercase()]
              if (exactMatch != null) {
                val verifiedIntent = context.packageManager.getLaunchIntentForPackage(exactMatch)
                if (verifiedIntent != null) {
                  packageName = exactMatch
                  Log.d(TAG, "Mapped '$params.package_name' to package '$packageName' (dynamic exact match, verified)")
                  intent = verifiedIntent
                } else {
                  Log.d(TAG, "Dynamic exact match '$params.package_name' → '$exactMatch' but no launch intent, skipping")
                }
              }

              // 4b. Try partial/substring match against app display names.
              // e.g. "采货" → "采货侠", "微信" → "微信"
              if (intent == null) {
                val inputLower = packageName.lowercase()
                Log.d(TAG, "Trying partial match for: '$inputLower'")
                // Collect all matching apps, then sort by match quality
                val candidates = mutableListOf<Pair<String, String>>() // (appName, packageName)
                for ((appName, appPackage) in dynamicMap) {
                  val appNameLower = appName.lowercase()
                  if (appNameLower.contains(inputLower) || inputLower.contains(appNameLower)) {
                    // Check it has a launch intent
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(appPackage)
                    if (launchIntent != null) {
                      candidates.add(appName to appPackage)
                    }
                  }
                }
                // Sort by: exact match first, then starts-with, then contains
                // Shorter app name = better match (less specific)
                candidates.sortWith(compareBy(
                  { it.first.lowercase() != inputLower },  // exact match first
                  { !it.first.lowercase().startsWith(inputLower) },  // starts-with second
                  { it.first.length },  // shorter name first
                ))
                if (candidates.isNotEmpty()) {
                  val (bestName, bestPackage) = candidates.first()
                  packageName = bestPackage
                  Log.d(TAG, "Partial matched '$params.package_name' to package '$packageName' via display name '$bestName' (candidates: ${candidates.joinToString(", ") { it.first }})")
                  intent = context.packageManager.getLaunchIntentForPackage(packageName)
                }
              }

              // 4c. If still no match and input looks like a hallucinated package name,
              // try keyword matching against app DISPLAY NAMES.
              if (intent == null && packageName.contains(".")) {
                val parts = packageName.split(".")
                val noiseWords = setOf("com", "android", "net", "org", "io", "cn", "co", "ai")
                val keywords = parts
                  .filter { it.length >= 4 && it !in noiseWords }
                  .toSet()
                if (keywords.isNotEmpty()) {
                  Log.d(TAG, "Keyword matching with: $keywords")
                  for ((appName, appPackage) in dynamicMap) {
                    if (keywords.any { kw -> appName.contains(kw, ignoreCase = true) }) {
                      val launchIntent = context.packageManager.getLaunchIntentForPackage(appPackage)
                      if (launchIntent != null) {
                        packageName = appPackage
                        Log.d(TAG, "Keyword matched '$params.package_name' to package '$packageName' via display name '$appName'")
                        intent = launchIntent
                        break
                      }
                    }
                  }
                }
              }
            }

            if (intent != null) {
              context.startActivity(intent)
              Log.d(TAG, "Opened app: $packageName")
              "succeeded"
            } else {
              Log.e(TAG, "No launch intent found for package: $originalPackageName (resolved to: $packageName)")
              // Debug: log all packages containing key substrings
              val pm = context.packageManager
              val allPackages = pm.getInstalledApplications(0)
              // Extract the last two components of package name for fuzzy matching (e.g. "ugc.aweme")
              val parts = packageName.split(".")
              val lastPart = parts.lastOrNull() ?: ""
              val secondLastPart = parts.dropLast(1).lastOrNull() ?: ""
              Log.d(TAG, "Fuzzy match keywords: last=$lastPart, secondLast=$secondLastPart")

              val similarPackages = allPackages
                .filter { app ->
                  (lastPart.isNotEmpty() && app.packageName.contains(lastPart, ignoreCase = true)) ||
                    (secondLastPart.isNotEmpty() && app.packageName.contains(secondLastPart, ignoreCase = true))
                }
                .map { it.packageName }
                .sorted()
                .toSet()
              Log.d(TAG, "Similar packages found: ${similarPackages.joinToString(", ")}")
              val similarMsg = if (similarPackages.isNotEmpty()) {
                " Found similar installed packages: ${similarPackages.joinToString(", ")}. Try one of these."
              } else {
                // List all installed packages for debugging (limit to first 20)
                val allPackageNames = allPackages.map { it.packageName }.sorted().take(20)
                Log.d(TAG, "No similar packages found. First 20 installed packages: ${allPackageNames.joinToString(", ")}")
                ""
              }
              "failed: App '$packageName' not installed or has no launch intent.$similarMsg"
            }
          } else {
            Log.e(TAG, "Failed to parse open_app parameters: $parameters")
            "failed: package_name is required"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to open app: $parameters", e)
          "failed: ${e.message}"
        }
      }
      IntentAction.SEND_EMAIL -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(SendEmailParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val intent =
              Intent(Intent.ACTION_SEND).apply {
                data = "mailto:".toUri()
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(params.extra_email))
                putExtra(Intent.EXTRA_SUBJECT, params.extra_subject)
                putExtra(Intent.EXTRA_TEXT, params.extra_text)
              }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse send_email parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse send_email parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.SEND_SMS -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(SendSmsParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val uri = "smsto:${params.phone_number}".toUri()
            val intent = Intent(Intent.ACTION_SENDTO, uri)
            intent.putExtra("sms_body", params.sms_body)
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse send_sms parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse send_sms parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.CREATE_CALENDAR_EVENT -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(CreateCalendarEventParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val beginTimeMillis = format.parse(params.begin_time)?.time ?: 0L
            val endTimeMillis = format.parse(params.end_time)?.time ?: 0L
            val intent =
              Intent(Intent.ACTION_INSERT).apply {
                data = Events.CONTENT_URI
                putExtra(Events.TITLE, params.title)
                putExtra(Events.DESCRIPTION, params.description)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTimeMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTimeMillis)
              }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse create_calendar_event parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse create_calendar_event parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.READ_CALENDAR_EVENTS -> {
        readCalendarEvents(context, parameters, requestPermission)
      }
      IntentAction.UPDATE_CALENDAR_EVENT -> {
        updateCalendarEvent(context, parameters, requestPermission)
      }
      IntentAction.DELETE_CALENDAR_EVENT -> {
        deleteCalendarEvent(context, parameters, requestPermission)
      }
      IntentAction.READ_CLIPBOARD -> {
        readClipboard(context)
      }
      IntentAction.WRITE_CLIPBOARD -> {
        writeClipboard(context, parameters)
      }
      IntentAction.GET_CURRENT_DATE_AND_TIME -> {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss EEEE", Locale.getDefault())
        val currentDateAndTime = sdf.format(Date())
        Log.d(
          TAG,
          "get_current_date_and_time via handleAction. Current date and time: $currentDateAndTime",
        )
        currentDateAndTime
      }
      IntentAction.SCHEDULE_NOTIFICATION -> {
        scheduleNotification(context, parameters)
      }
      null -> "failed"
    }
  }

  suspend fun readCalendarEvents(
    context: Context,
    parameters: String,
    requestPermission: suspend (String) -> Boolean,
  ): String {
    if (
      checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
      val granted = requestPermission(android.Manifest.permission.READ_CALENDAR)
      if (!granted) {
        Log.e(TAG, "READ_CALENDAR permission denied by user")
        return "failed: READ_CALENDAR permission denied by user"
      }
    }

    try {
      val moshi = Moshi.Builder().build()
      val jsonAdapter = moshi.adapter(ReadCalendarEventsParams::class.java)
      val params = jsonAdapter.fromJson(parameters)
      if (params != null) {
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        // Determine start and end time.
        // If start_date and end_date are provided, use those.
        // If only date is provided, use that single day.
        // If nothing is provided, default to today.
        val startDate = params.start_date ?: params.date
        val endDate = params.end_date ?: params.date

        val cal = Calendar.getInstance()
        val startOfDayMillis: Long
        val endOfDayMillis: Long

        if (startDate != null && endDate != null) {
          // Range query.
          val startObj = dayFormat.parse(startDate)
          val endObj = dayFormat.parse(endDate)
          if (startObj == null || endObj == null) {
            Log.e(TAG, "Failed to parse date range: start=$startDate, end=$endDate")
            return "failed: invalid date range"
          }
          cal.timeInMillis = startObj.time
          cal.set(Calendar.HOUR_OF_DAY, 0)
          cal.set(Calendar.MINUTE, 0)
          cal.set(Calendar.SECOND, 0)
          cal.set(Calendar.MILLISECOND, 0)
          startOfDayMillis = cal.timeInMillis

          cal.timeInMillis = endObj.time
          cal.set(Calendar.HOUR_OF_DAY, 23)
          cal.set(Calendar.MINUTE, 59)
          cal.set(Calendar.SECOND, 59)
          cal.set(Calendar.MILLISECOND, 999)
          endOfDayMillis = cal.timeInMillis
        } else {
          // Single day (default to today).
          val dateStr = startDate ?: dayFormat.format(Date())
          val dateObj = dayFormat.parse(dateStr)
          if (dateObj == null) {
            Log.e(TAG, "Failed to parse date: $dateStr")
            return "failed: invalid date"
          }
          cal.timeInMillis = dateObj.time
          cal.set(Calendar.HOUR_OF_DAY, 0)
          cal.set(Calendar.MINUTE, 0)
          cal.set(Calendar.SECOND, 0)
          cal.set(Calendar.MILLISECOND, 0)
          startOfDayMillis = cal.timeInMillis

          cal.apply {
            add(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MILLISECOND, -1)
          }
          endOfDayMillis = cal.timeInMillis
        }

        val projection =
          arrayOf(Events._ID, Instances.TITLE, Instances.DESCRIPTION, Instances.BEGIN, Instances.END)

        val builder = Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(builder, startOfDayMillis)
        android.content.ContentUris.appendId(builder, endOfDayMillis)

        val cursor =
          context.contentResolver.query(
            builder.build(),
            projection,
            null,
            null,
            "${Instances.BEGIN} ASC",
          )

        val eventsList = mutableListOf<CalendarEventDto>()
        cursor?.use { c ->
          val idIdx = c.getColumnIndex(Events._ID)
          val titleIdx = c.getColumnIndex(Instances.TITLE)
          val descIdx = c.getColumnIndex(Instances.DESCRIPTION)
          val startIdx = c.getColumnIndex(Instances.BEGIN)
          val endIdx = c.getColumnIndex(Instances.END)
          val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
          while (c.moveToNext()) {
            val eventId = if (idIdx >= 0) c.getString(idIdx) ?: "" else ""
            val title = if (titleIdx >= 0) c.getString(titleIdx) ?: "" else ""
            val desc = if (descIdx >= 0) c.getString(descIdx) ?: "" else ""
            val start = if (startIdx >= 0) c.getLong(startIdx) else 0L
            val end = if (endIdx >= 0) c.getLong(endIdx) else 0L
            eventsList.add(
              CalendarEventDto(
                event_id = eventId,
                title = title,
                description = desc,
                begin_time = if (start > 0) timeFormat.format(Date(start)) else "",
                end_time = if (end > 0) timeFormat.format(Date(end)) else "",
              )
            )
          }
        }
        val responseAdapter = moshi.adapter(ReadCalendarEventsResponse::class.java)
        return responseAdapter.toJson(ReadCalendarEventsResponse(eventsList))
      } else {
        Log.e(TAG, "Failed to parse read_calendar_events parameters: $parameters")
        return "failed"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read calendar events: $parameters", e)
      return "failed: ${e.message}"
    }
  }

  /** Updates an existing calendar event by event ID. */
  suspend fun updateCalendarEvent(
    context: Context,
    parameters: String,
    requestPermission: suspend (String) -> Boolean,
  ): String {
    if (
      checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
      val granted = requestPermission(android.Manifest.permission.WRITE_CALENDAR)
      if (!granted) {
        return "failed: WRITE_CALENDAR permission denied by user"
      }
    }

    try {
      val moshi = Moshi.Builder().build()
      val jsonAdapter = moshi.adapter(UpdateCalendarEventParams::class.java)
      val params = jsonAdapter.fromJson(parameters)
      if (params != null) {
        val eventId = params.event_id.toLongOrNull()
        if (eventId == null) {
          return "failed: invalid event_id '$params.event_id'"
        }

        val uri = CalendarContract.Events.CONTENT_URI.buildUpon().appendPath(eventId.toString()).build()
        val values = android.content.ContentValues()
        if (params.title != null) values.put(Events.TITLE, params.title)
        if (params.description != null) values.put(Events.DESCRIPTION, params.description)
        if (params.begin_time != null) {
          val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
          values.put(Events.DTSTART, format.parse(params.begin_time)?.time ?: 0L)
        }
        if (params.end_time != null) {
          val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
          values.put(Events.DTEND, format.parse(params.end_time)?.time ?: 0L)
        }

        val rows = context.contentResolver.update(uri, values, null, null)
        return if (rows > 0) "succeeded" else "failed: event not found or no changes made"
      } else {
        Log.e(TAG, "Failed to parse update_calendar_event parameters: $parameters")
        return "failed"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to update calendar event: $parameters", e)
      return "failed: ${e.message}"
    }
  }

  /** Deletes a calendar event by event ID. */
  suspend fun deleteCalendarEvent(
    context: Context,
    parameters: String,
    requestPermission: suspend (String) -> Boolean,
  ): String {
    if (
      checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
      val granted = requestPermission(android.Manifest.permission.WRITE_CALENDAR)
      if (!granted) {
        return "failed: WRITE_CALENDAR permission denied by user"
      }
    }

    try {
      val moshi = Moshi.Builder().build()
      val jsonAdapter = moshi.adapter(DeleteCalendarEventParams::class.java)
      val params = jsonAdapter.fromJson(parameters)
      if (params != null) {
        val eventId = params.event_id.toLongOrNull()
        if (eventId == null) {
          return "failed: invalid event_id '$params.event_id'"
        }

        val uri = CalendarContract.Events.CONTENT_URI.buildUpon().appendPath(eventId.toString()).build()
        val rows = context.contentResolver.delete(uri, null, null)
        return if (rows > 0) "succeeded" else "failed: event not found"
      } else {
        Log.e(TAG, "Failed to parse delete_calendar_event parameters: $parameters")
        return "failed"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to delete calendar event: $parameters", e)
      return "failed: ${e.message}"
    }
  }

  fun scheduleNotification(context: Context, parameters: String): String {
    try {
      val moshi = Moshi.Builder().build()
      val jsonAdapter = moshi.adapter(ScheduleNotificationParams::class.java)
      val params = jsonAdapter.fromJson(parameters)
      if (params != null) {
        val notificationProtoBuilder =
          ScheduledNotification.newBuilder()
            .setId(java.util.UUID.randomUUID().toString())
            .setTitle(params.title)
            .setMessage(params.message)
            .setHour(params.hour)
            .setMinute(params.minute)
            .setChannelId("agent_skill_tasks_channel")
            .setChannelName("Agent Skill Task")
        if (params.deeplink != null) {
          notificationProtoBuilder.setDeeplink(params.deeplink)
        } else if (params.task_id != null && params.model_name != null) {
          val uri =
            "com.google.ai.edge.gallery://model/${params.task_id}/${params.model_name}"
              .toUri()
              .buildUpon()
              .appendQueryParameter("query", params.message)
              .build()
              .toString()
          Log.d(TAG, "Setting constructed deeplink to: $uri")
          notificationProtoBuilder.setDeeplink(uri)
        } else if (params.task_id != null) {
          val uri =
            "com.google.ai.edge.gallery://${params.task_id}/"
              .toUri()
              .buildUpon()
              .appendQueryParameter("query", params.message)
              .build()
              .toString()
          Log.d(TAG, "Setting constructed deeplink to: $uri")
          notificationProtoBuilder.setDeeplink(uri)
        } else {
          val fallbackUri =
            "com.google.ai.edge.gallery://llm_agent_chat/"
              .toUri()
              .buildUpon()
              .appendQueryParameter("query", params.message)
              .build()
              .toString()
          Log.d(TAG, "Setting fallback deeplink to: $fallbackUri")
          notificationProtoBuilder.setDeeplink(fallbackUri)
        }
        if (params.year != null) {
          notificationProtoBuilder.setYear(params.year)
        }
        if (params.month != null) {
          notificationProtoBuilder.setMonth(params.month)
        }
        if (params.day != null) {
          notificationProtoBuilder.setDay(params.day)
        }
        if (params.repeat_daily != null) {
          notificationProtoBuilder.setRepeatDaily(params.repeat_daily)
        }

        val entryPoint =
          EntryPointAccessors.fromApplication(
            context.applicationContext,
            NotificationScheduleManagerEntryPoint::class.java,
          )
        val success =
          entryPoint
            .notificationScheduleManager()
            .scheduleNotification(notificationProtoBuilder.build())
        if (!success) {
          return "failed"
        }
        return "succeeded"
      } else {
        Log.e(TAG, "Failed to parse schedule_notification parameters: $parameters")
        return "failed"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to parse schedule_notification parameters: $parameters", e)
      return "failed"
    }
  }

  /** Reads text from the system clipboard. */
  fun readClipboard(context: Context): String {
    try {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      val clip = clipboard.primaryClip
      if (clip != null && clip.itemCount > 0) {
        val text = clip.getItemAt(0).text?.toString() ?: ""
        if (text.isNotEmpty()) {
          Log.d(TAG, "Clipboard read succeeded. Length: ${text.length}")
          return text
        }
      }
      Log.d(TAG, "Clipboard is empty or contains no text.")
      return "failed: clipboard is empty or contains no text"
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read clipboard", e)
      return "failed: ${e.message}"
    }
  }

  /** Writes text to the system clipboard. */
  fun writeClipboard(context: Context, parameters: String): String {
    try {
      val moshi = Moshi.Builder().build()
      val jsonAdapter = moshi.adapter(WriteClipboardParams::class.java)
      val params = jsonAdapter.fromJson(parameters)
      if (params != null && params.text.isNotEmpty()) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Agent Chat", params.text)
        clipboard.setPrimaryClip(clip)
        Log.d(TAG, "Clipboard write succeeded. Length: ${params.text.length}")
        return "succeeded"
      } else {
        Log.e(TAG, "Failed to parse write_clipboard parameters: $parameters")
        return "failed: empty or invalid text"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to write clipboard", e)
      return "failed: ${e.message}"
    }
  }
}
