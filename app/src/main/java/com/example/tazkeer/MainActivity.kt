package com.example.tazkeer

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.*

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tasksContainer: LinearLayout
    private lateinit var qadaaContainer: LinearLayout
    private lateinit var prayersStatusText: TextView
    private var selectedHour = 21
    private var selectedMinute = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        prefs = getSharedPreferences("tazkeer_prefs", Context.MODE_PRIVATE)

        requestEssentialPermissions()
        ReminderHelper.createNotificationChannels(this)

        setContentView(buildMainUI())
        refreshUI()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    private fun requestEssentialPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 100)
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun buildMainUI(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F4F6F9"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 50, 40, 80)
        }

        val header = TextView(this).apply {
            text = "🕌 المذكّر الملحّ للصلاة والمهام"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1B5E20"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        root.addView(header)

        // 1. قسم إعدادات الصلاة
        val prayerCard = createCard()
        prayerCard.addView(createSectionTitle("أولاً: إعدادات مواقيت الصلاة"))

        val cityInput = EditText(this).apply {
            hint = "المدينة بالإنجليزية (مثال: Beirut أو Riyadh)"
            setText(prefs.getString("city", "Beirut"))
        }
        val countryInput = EditText(this).apply {
            hint = "الدولة بالإنجليزية (مثال: Lebanon أو Saudi Arabia)"
            setText(prefs.getString("country", "Lebanon"))
        }
        val prayerIntervalInput = EditText(this).apply {
            hint = "تكرار رنة (تن) كل كم دقيقة؟ (مثال: 10)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("prayer_interval", 10).toString())
        }

        val pickToneBtn = createButton("🎵 اختيار رنة بداية الأذان / التنبيه الأول", "#00695C") {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "اختر نغمة التنبيه الأول")
            }
            startActivityForResult(intent, 200)
        }

        prayersStatusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 16, 0, 16)
        }

        val fetchBtn = createButton("🔄 جلب المواقيت وتفعيل منبهات الصلاة", "#2E7D32") {
            val city = cityInput.text.toString().trim().ifEmpty { "Beirut" }
            val country = countryInput.text.toString().trim().ifEmpty { "Lebanon" }
            val interval = prayerIntervalInput.text.toString().toIntOrNull() ?: 10

            prefs.edit()
                .putString("city", city)
                .putString("country", country)
                .putInt("prayer_interval", interval)
                .apply()

            fetchPrayerTimes(city, country, interval)
        }

        prayerCard.addView(cityInput)
        prayerCard.addView(countryInput)
        prayerCard.addView(prayerIntervalInput)
        prayerCard.addView(pickToneBtn)
        prayerCard.addView(fetchBtn)
        prayerCard.addView(prayersStatusText)
        root.addView(prayerCard)

        // 2. قسم قائمة القضاء (الصلوات الفائتة)
        val qadaaCard = createCard()
        qadaaCard.addView(createSectionTitle("📋 قائمة القضاء (الصلوات الفائتة)"))
        qadaaContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        qadaaCard.addView(qadaaContainer)
        root.addView(qadaaCard)

        // 3. قسم إضافة المهام اليومية
        val taskCard = createCard()
        taskCard.addView(createSectionTitle("ثانياً: إضافة مهمة يومية ملحة"))

        val taskTitleInput = EditText(this).apply {
            hint = "اسم المهمة (مثال: تعلم مهارة على كورسيرا)"
        }
        val taskIntervalInput = EditText(this).apply {
            hint = "تكرار التذكير كل كم دقيقة؟ (مثال: 15)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("15")
        }

        val timeBtn = createButton("⏰ وقت البداية: 21:00 (اضغط للتغيير)", "#1565C0") {}
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                selectedHour = h
                selectedMinute = m
                timeBtn.text = String.format("⏰ وقت البداية: %02d:%02d", h, m)
            }, selectedHour, selectedMinute, true).show()
        }

        val addTaskBtn = createButton("➕ حفظ وتفعيل المهمة", "#0D47A1") {
            val title = taskTitleInput.text.toString().trim()
            if (title.isEmpty()) {
                Toast.makeText(this, "يرجى كتابة اسم المهمة", Toast.LENGTH_SHORT).show()
                return@createButton
            }
            val interval = taskIntervalInput.text.toString().toIntOrNull() ?: 15
            ReminderHelper.addCustomTask(this, title, selectedHour, selectedMinute, interval)
            taskTitleInput.setText("")
            refreshUI()
            Toast.makeText(this, "تم تفعيل المهمة بنجاح", Toast.LENGTH_SHORT).show()
        }

        taskCard.addView(taskTitleInput)
        taskCard.addView(taskIntervalInput)
        taskCard.addView(timeBtn)
        taskCard.addView(addTaskBtn)

        tasksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 0)
        }
        taskCard.addView(tasksContainer)
        root.addView(taskCard)

        scroll.addView(root)
        return scroll
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                prefs.edit().putString("custom_tone", uri.toString()).apply()
                Toast.makeText(this, "تم حفظ نغمة البداية بنجاح", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchPrayerTimes(city: String, country: String, intervalMinutes: Int) {
        prayersStatusText.text = "⏳ جاري جلب مواقيت الصلاة من الإنترنت..."
        Thread {
            try {
                val urlStr = "https://api.aladhan.com/v1/timingsByCity?city=${
                    URLEncoder.encode(city, "UTF-8")
                }&country=${URLEncoder.encode(country, "UTF-8")}&method=4"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val response = conn.inputStream.bufferedReader().readText()
                val timings = JSONObject(response).getJSONObject("data").getJSONObject("timings")

                val prayersOrder = listOf(
                    "Fajr" to "صلاة الفجر",
                    "Dhuhr" to "صلاة الظهر",
                    "Asr" to "صلاة العصر",
                    "Maghrib" to "صلاة المغرب",
                    "Isha" to "صلاة العشاء"
                )

                val savedObj = JSONObject()
                for ((key, _) in prayersOrder) {
                    val raw = timings.getString(key).split(" ")[0]
                    savedObj.put(key, raw)
                }
                prefs.edit().putString("prayer_timings_json", savedObj.toString()).apply()

                ReminderHelper.scheduleAllPrayers(this, savedObj, intervalMinutes)

                runOnUiThread {
                    refreshUI()
                    Toast.makeText(this, "تم تحديث وجدولة مواقيت الصلاة!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    prayersStatusText.text = "❌ تعذر جلب المواقيت، تأكد من اتصال الإنترنت."
                }
            }
        }.start()
    }

    private fun refreshUI() {
        val jsonStr = prefs.getString("prayer_timings_json", null)
        if (jsonStr != null) {
            val obj = JSONObject(jsonStr)
            prayersStatusText.text = "المواقيت المفعّلة اليوم:\n" +
                    "• الفجر: ${obj.optString("Fajr")}   |   • الظهر: ${obj.optString("Dhuhr")}\n" +
                    "• العصر: ${obj.optString("Asr")}   |   • المغرب: ${obj.optString("Maghrib")}\n" +
                    "• العشاء: ${obj.optString("Isha")}"
        } else {
            prayersStatusText.text = "لم يتم جلب المواقيت بعد. اضغط على زر الجلب بالأعلى."
        }

        qadaaContainer.removeAllViews()
        val qadaaArr = JSONArray(prefs.getString("qadaa_list", "[]"))
        if (qadaaArr.length() == 0) {
            qadaaContainer.addView(TextView(this).apply {
                text = "🎉 لا توجد صلوات فائتة في قائمة القضاء."
                setTextColor(Color.parseColor("#388E3C"))
            })
        } else {
            for (i in 0 until qadaaArr.length()) {
                val itemName = qadaaArr.getString(i)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 10, 0, 10)
                    gravity = Gravity.CENTER_VERTICAL
                }
                val tv = TextView(this).apply {
                    text = "⚠️ $itemName"
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    setTextColor(Color.parseColor("#C62828"))
                    setTypeface(null, Typeface.BOLD)
                }
                val doneBtn = createButton("تم القضاء ✅", "#2E7D32") {
                    ReminderHelper.removeQadaaAt(this, i)
                    refreshUI()
                }
                row.addView(tv)
                row.addView(doneBtn)
                qadaaContainer.addView(row)
            }
        }

        tasksContainer.removeAllViews()
        val tasksArr = JSONArray(prefs.getString("custom_tasks", "[]"))
        for (i in 0 until tasksArr.length()) {
            val t = tasksArr.getJSONObject(i)
            val id = t.getInt("id")
            val title = t.getString("title")
            val hour = t.getInt("hour")
            val minute = t.getInt("minute")
            val interval = t.getInt("interval")

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
                gravity = Gravity.CENTER_VERTICAL
            }
            val info = TextView(this).apply {
                text = String.format("📌 %s (%02d:%02d - كل %d د)", title, hour, minute, interval)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setTextColor(Color.BLACK)
            }
            val stopBtn = createButton("أتممت ✅", "#388E3C") {
                ReminderHelper.completeTask(this, id)
                Toast.makeText(this, "تم إيقاف تذكير اليوم لهذه المهمة", Toast.LENGTH_SHORT).show()
            }
            val delBtn = createButton("حذف 🗑️", "#D32F2F") {
                ReminderHelper.deleteCustomTask(this, id)
                refreshUI()
            }
            row.addView(info)
            row.addView(stopBtn)
            row.addView(delBtn)
            tasksContainer.addView(row)
        }
    }

    private fun createCard(): LinearLayout {
        val bg = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 28f
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(36, 36, 36, 36)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, 36)
            }
            elevation = 6f
        }
    }

    private fun createSectionTitle(txt: String): TextView {
        return TextView(this).apply {
            text = txt
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A237E"))
            setPadding(0, 0, 0, 20)
        }
    }

    private fun createButton(txt: String, hexColor: String, onClick: () -> Unit): Button {
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor(hexColor))
            cornerRadius = 20f
        }
        return Button(this).apply {
            text = txt
            setTextColor(Color.WHITE)
            background = bg
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                setMargins(8, 12, 8, 12)
            }
            setOnClickListener { onClick() }
        }
    }
}

object ReminderHelper {
    const val CHANNEL_NORMAL = "tazkeer_ding_channel"
    const val CHANNEL_URGENT = "tazkeer_urgent_channel"

    fun createNotificationChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val dingChannel = NotificationChannel(
            CHANNEL_NORMAL,
            "تذكير المهام والصلاة (تن)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "إشعارات التذكير الدورية"
            setSound(null, null)
        }
        val urgentChannel = NotificationChannel(
            CHANNEL_URGENT,
            "تحذير قرب خروج وقت الصلاة",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "تنبيه قوي قبل انتهاء وقت الصلاة بنصف ساعة"
            setSound(null, null)
        }
        nm.createNotificationChannel(dingChannel)
        nm.createNotificationChannel(urgentChannel)
    }

    fun scheduleAllPrayers(context: Context, timings: JSONObject, intervalMinutes: Int) {
        val prayers = listOf(
            Triple(1001, "Fajr", "صلاة الفجر"),
            Triple(1002, "Dhuhr", "صلاة الظهر"),
            Triple(1003, "Asr", "صلاة العصر"),
            Triple(1004, "Maghrib", "صلاة المغرب"),
            Triple(1005, "Isha", "صلاة العشاء")
        )

        val timesInMillis = prayers.map { (_, key, _) ->
            val parts = timings.getString(key).split(":")
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                set(Calendar.MINUTE, parts[1].toInt())
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        }

        val now = System.currentTimeMillis()
        for (i in prayers.indices) {
            val (id, _, arabicName) = prayers[i]
            var startMillis = timesInMillis[i]
            var nextPrayerMillis = if (i < prayers.size - 1) {
                timesInMillis[i + 1]
            } else {
                timesInMillis[0] + 24 * 60 * 60 * 1000L
            }

            if (now >= nextPrayerMillis) {
                startMillis += 24 * 60 * 60 * 1000L
                nextPrayerMillis += 24 * 60 * 60 * 1000L
            }

            val triggerAt = if (now in startMillis until nextPrayerMillis) now + 2000L else startMillis
            scheduleExactAlarm(
                context = context,
                id = id,
                title = arabicName,
                intervalMin = intervalMinutes,
                triggerAtMillis = triggerAt,
                endMillis = nextPrayerMillis,
                isPrayer = true,
                attempt = 0
            )
        }
    }

    fun addCustomTask(context: Context, title: String, hour: Int, minute: Int, intervalMin: Int) {
        val prefs = context.getSharedPreferences("tazkeer_prefs", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("custom_tasks", "[]"))
        val id = (2000..9000).random()
        val obj = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("hour", hour)
            put("minute", minute)
            put("interval", intervalMin)
        }
        arr.put(obj)
        prefs.edit().putString("custom_tasks", arr.toString()).apply()

        scheduleCustomTaskExecution(context, id, title, hour, minute, intervalMin)
    }

    fun scheduleCustomTaskExecution(context: Context, id: Int, title: String, hour: Int, minute: Int, intervalMin: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        scheduleExactAlarm(
            context = context,
            id = id,
            title = title,
            intervalMin = intervalMin,
            triggerAtMillis = cal.timeInMillis,
            endMillis = cal.timeInMillis + 6 * 60 * 60 * 1000L,
            isPrayer = false,
            attempt = 0
        )
    }

    fun scheduleExactAlarm(
        context: Context,
        id: Int,
        title: String,
        intervalMin: Int,
        triggerAtMillis: Long,
        endMillis: Long,
        isPrayer: Boolean,
        attempt: Int
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "ACTION_REMIND"
            putExtra("id", id)
            putExtra("title", title)
            putExtra("interval", intervalMin)
            putExtra("endMillis", endMillis)
            putExtra("isPrayer", isPrayer)
            putExtra("attempt", attempt)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    fun completeTask(context: Context, id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply { action = "ACTION_REMIND" }
        val pi = PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(id)
    }

    fun deleteCustomTask(context: Context, id: Int) {
        completeTask(context, id)
        val prefs = context.getSharedPreferences("tazkeer_prefs", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("custom_tasks", "[]"))
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.getInt("id") != id) newArr.put(item)
        }
        prefs.edit().putString("custom_tasks", newArr.toString()).apply()
    }

    fun addQadaaPrayer(context: Context, prayerTitle: String) {
        val prefs = context.getSharedPreferences("tazkeer_prefs", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("qadaa_list", "[]"))
        val stamp = java.text.SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
        arr.put("$prayerTitle ($stamp)")
        prefs.edit().putString("qadaa_list", arr.toString()).apply()
    }

    fun removeQadaaAt(context: Context, index: Int) {
        val prefs = context.getSharedPreferences("tazkeer_prefs", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("qadaa_list", "[]"))
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            if (i != index) newArr.put(arr.getString(i))
        }
        prefs.edit().putString("qadaa_list", newArr.toString()).apply()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderHelper.createNotificationChannels(context)
        val action = intent.action ?: return
        val id = intent.getIntExtra("id", 0)
        val title = intent.getStringExtra("title") ?: "مهمة"
        val interval = intent.getIntExtra("interval", 10)
        val endMillis = intent.getLongExtra("endMillis", 0L)
        val isPrayer = intent.getBooleanExtra("isPrayer", false)
        val attempt = intent.getIntExtra("attempt", 0)

        when (action) {
            "ACTION_DONE" -> {
                ReminderHelper.completeTask(context, id)
            }

            "ACTION_SNOOZE_30", "ACTION_SNOOZE_60" -> {
                val snoozeMin = if (action == "ACTION_SNOOZE_30") 30 else 60
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(id)
                ReminderHelper.scheduleExactAlarm(
                    context = context,
                    id = id,
                    title = title,
                    intervalMin = interval,
                    triggerAtMillis = System.currentTimeMillis() + snoozeMin * 60 * 1000L,
                    endMillis = endMillis,
                    isPrayer = isPrayer,
                    attempt = attempt + 1
                )
            }

            "ACTION_REMIND" -> {
                val now = System.currentTimeMillis()

                if (isPrayer && endMillis > 0 && now >= endMillis) {
                    ReminderHelper.completeTask(context, id)
                    ReminderHelper.addQadaaPrayer(context, title)
                    showMovedToQadaaNotification(context, id + 500, title)
                    return
                }

                val remainingMillis = endMillis - now
                val isUrgentWindow = isPrayer && remainingMillis in 1..(30 * 60 * 1000L)

                playSmartFeedback(context, isFirstAttempt = (attempt == 0), isUrgent = isUrgentWindow)

                showPersistentReminderNotification(
                    context = context,
                    id = id,
                    title = title,
                    interval = interval,
                    endMillis = endMillis,
                    isPrayer = isPrayer,
                    attempt = attempt,
                    isUrgent = isUrgentWindow
                )

                ReminderHelper.scheduleExactAlarm(
                    context = context,
                    id = id,
                    title = title,
                    intervalMin = interval,
                    triggerAtMillis = now + interval * 60 * 1000L,
                    endMillis = endMillis,
                    isPrayer = isPrayer,
                    attempt = attempt + 1
                )
            }
        }
    }

    private fun playSmartFeedback(context: Context, isFirstAttempt: Boolean, isUrgent: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), -1))
            return
        }

        val prefs = context.getSharedPreferences("tazkeer_prefs", Context.MODE_PRIVATE)
        val toneUri: Uri = when {
            isUrgent -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            isFirstAttempt -> {
                val custom = prefs.getString("custom_tone", null)
                if (custom != null) Uri.parse(custom)
                else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }

        try {
            val ringtone = RingtoneManager.getRingtone(context, toneUri)
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()

            if (isFirstAttempt || isUrgent) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (ringtone.isPlaying) ringtone.stop()
                }, 6000)
            }
        } catch (_: Exception) {}

        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun showPersistentReminderNotification(
        context: Context,
        id: Int,
        title: String,
        interval: Int,
        endMillis: Long,
        isPrayer: Boolean,
        attempt: Int,
        isUrgent: Boolean
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val doneIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = "ACTION_DONE"
            putExtra("id", id)
        }
        val donePi = PendingIntent.getBroadcast(
            context, id * 10 + 1, doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snooze30Intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "ACTION_SNOOZE_30"
            putExtra("id", id)
            putExtra("title", title)
            putExtra("interval", interval)
            putExtra("endMillis", endMillis)
            putExtra("isPrayer", isPrayer)
            putExtra("attempt", attempt)
        }
        val snooze30Pi = PendingIntent.getBroadcast(
            context, id * 10 + 2, snooze30Intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snooze60Intent = Intent(snooze30Intent).apply {
            action = "ACTION_SNOOZE_60"
        }
        val snooze60Pi = PendingIntent.getBroadcast(
            context, id * 10 + 3, snooze60Intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val icon = Icon.createWithResource(context, android.R.drawable.ic_lock_idle_alarm)
        val notifTitle = if (isUrgent) "⚠️ تدارك الوقت: $title (باقي أقل من 30 د!)" else "🔔 تذكير مستمر: $title"
        val notifBody = if (attempt == 0) {
            "دخل الوقت الآن! اضغط (أتممت المهمة) عند الإنجاز."
        } else {
            "التذكير رقم #$attempt — سيتكرر كل $interval د حتى تضغط أتممت المهمة."
        }

        val builder = Notification.Builder(
            context,
            if (isUrgent) ReminderHelper.CHANNEL_URGENT else ReminderHelper.CHANNEL_NORMAL
        )
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(notifTitle)
            .setContentText(notifBody)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(icon, "✅ أتممت المهمة", donePi).build())
            .addAction(Notification.Action.Builder(icon, "⏸️ تأجيل 30 د", snooze30Pi).build())
            .addAction(Notification.Action.Builder(icon, "⏸️ تأجيل 60 د", snooze60Pi).build())

        nm.notify(id, builder.build())
    }

    private fun showMovedToQadaaNotification(context: Context, id: Int, title: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = Notification.Builder(context, ReminderHelper.CHANNEL_NORMAL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("خرج وقت $title")
            .setContentText("تم إيقاف التذكير ونقل $title إلى قائمة القضاء داخل التطبيق.")
            .setAutoCancel(true)
            .build()
        nm.notify(id, notif)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("tazkeer_prefs", Context.MODE_PRIVATE)
            val interval = prefs.getInt("prayer_interval", 10)
            val prayersJson = prefs.getString("prayer_timings_json", null)
            if (prayersJson != null) {
                ReminderHelper.scheduleAllPrayers(context, JSONObject(prayersJson), interval)
            }
            val tasksArr = JSONArray(prefs.getString("custom_tasks", "[]"))
            for (i in 0 until tasksArr.length()) {
                val t = tasksArr.getJSONObject(i)
                ReminderHelper.scheduleCustomTaskExecution(
                    context,
                    t.getInt("id"),
                    t.getString("title"),
                    t.getInt("hour"),
                    t.getInt("minute"),
                    t.getInt("interval")
                )
            }
        }
    }
}
