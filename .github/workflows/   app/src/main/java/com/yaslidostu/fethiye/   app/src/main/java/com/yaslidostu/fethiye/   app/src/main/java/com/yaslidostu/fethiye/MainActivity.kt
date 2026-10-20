package com.yaslidostu.fethiye

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*

enum class MedicationStatus { PENDING, TAKEN, MISSED, SNOOZED }
enum class SosTriggerType { BUTTON, FALL_DETECTED, VOICE }
enum class SosStatus { SENT, ACKNOWLEDGED, CANCELLED }
enum class SyncOperation { INSERT, UPDATE, DELETE }

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: String,
    val fullName: String,
    val phone: String = "0532 555 10 20",
    val bloodType: String = "A Rh+",
    val highContrast: Boolean = false,
    val fontSizeScale: Float = 1.0f,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "emergency_contact")
data class EmergencyContactEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val phone: String,
    val relation: String,
    val priority: Int = 1
)

@Entity(tableName = "medication")
data class MedicationEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val dosage: String,
    val time: String,
    val instructions: String,
    val stockCount: Int = 20,
    val status: MedicationStatus = MedicationStatus.PENDING
)

@Entity(tableName = "health_vital")
data class HealthVitalEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    val glucose: Double = 100.0,
    val note: String = ""
)

@Entity(tableName = "appointment")
data class AppointmentEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val doctor: String,
    val location: String,
    val date: String,
    val time: String
)

@Entity(tableName = "sos_event")
data class SosEventEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val triggeredAt: Long = System.currentTimeMillis(),
    val latitude: Double?,
    val longitude: Double?,
    val triggerType: SosTriggerType = SosTriggerType.BUTTON,
    val status: SosStatus = SosStatus.SENT
)

@Entity(tableName = "sync_queue")
data class SyncQueueEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val operation: SyncOperation,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class DolmusRoute(
    val id: String,
    val code: String,
    val name: String,
    val badge: String,
    val stops: String,
    val fare: String,
    val primaryColor: Color,
    val fixedTimes: List<String>
)

data class DolmusDepartureInfo(
    val nextTime: String,
    val diffMinutes: Int,
    val upcomingDepartures: List<String>
)

data class LiveWeather(
    val city: String = "Fethiye, Muğla",
    val temp: String = "--°C",
    val description: String = "Yükleniyor...",
    val advice: String = "Fethiye için hava durumu hesaplanıyor.",
    val wind: String = "-- km/s",
    val humidity: String = "--%"
)

class AppTypeConverters {
    @TypeConverter fun fromStatus(s: MedicationStatus?): String = s?.name ?: MedicationStatus.PENDING.name
    @TypeConverter fun toStatus(v: String?): MedicationStatus = runCatching { MedicationStatus.valueOf(v ?: "") }.getOrDefault(MedicationStatus.PENDING)
    @TypeConverter fun fromSosTrigger(s: SosTriggerType?): String = s?.name ?: SosTriggerType.BUTTON.name
    @TypeConverter fun toSosTrigger(v: String?): SosTriggerType = runCatching { SosTriggerType.valueOf(v ?: "") }.getOrDefault(SosTriggerType.BUTTON)
    @TypeConverter fun fromSosStatus(s: SosStatus?): String = s?.name ?: SosStatus.SENT.name
    @TypeConverter fun toSosStatus(v: String?): SosStatus = runCatching { SosStatus.valueOf(v ?: "") }.getOrDefault(SosStatus.SENT)
    @TypeConverter fun fromSyncOp(s: SyncOperation?): String = s?.name ?: SyncOperation.INSERT.name
    @TypeConverter fun toSyncOp(v: String?): SyncOperation = runCatching { SyncOperation.valueOf(v ?: "") }.getOrDefault(SyncOperation.INSERT)
}

@Dao
interface UserDao {
    @Query("SELECT * FROM user_profile LIMIT 1") fun observeUser(): Flow<UserProfileEntity?>
    @Query("SELECT * FROM user_profile LIMIT 1") suspend fun getUserOnce(): UserProfileEntity?
    @Upsert suspend fun upsertUser(user: UserProfileEntity)
}

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medication WHERE userId = :userId ORDER BY time ASC") fun observeMedications(userId: String): Flow<List<MedicationEntity>>
    @Upsert suspend fun upsert(med: MedicationEntity)
    @Query("UPDATE medication SET status = :status WHERE id = :id") suspend fun updateStatus(id: String, status: MedicationStatus)
    @Query("UPDATE medication SET stockCount = stockCount - 1 WHERE id = :id AND stockCount > 0") suspend fun decrementStock(id: String)
}

@Dao
interface VitalDao {
    @Query("SELECT * FROM health_vital WHERE userId = :userId ORDER BY timestamp DESC") fun observeVitals(userId: String): Flow<List<HealthVitalEntity>>
    @Upsert suspend fun upsert(vital: HealthVitalEntity)
}

@Dao
interface AppointmentDao {
    @Query("SELECT * FROM appointment WHERE userId = :userId ORDER BY date ASC, time ASC") fun observeAppointments(userId: String): Flow<List<AppointmentEntity>>
    @Upsert suspend fun upsert(appointment: AppointmentEntity)
}

@Dao
interface EmergencyContactDao {
    @Query("SELECT * FROM emergency_contact WHERE userId = :userId ORDER BY priority ASC") fun observeContacts(userId: String): Flow<List<EmergencyContactEntity>>
    @Upsert suspend fun upsert(contact: EmergencyContactEntity)
}

@Dao
interface SosDao {
    @Upsert suspend fun insertSos(event: SosEventEntity)
    @Query("SELECT * FROM sos_event ORDER BY triggeredAt DESC LIMIT 20") fun observeSosEvents(): Flow<List<SosEventEntity>>
}

@Dao
interface SyncQueueDao {
    @Upsert suspend fun enqueue(item: SyncQueueEntity)
    @Query("SELECT * FROM sync_queue ORDER BY createdAt ASC LIMIT 20") suspend fun peek(): List<SyncQueueEntity>
    @Query("DELETE FROM sync_queue WHERE id = :id") suspend fun delete(id: String)
}

@Database(
    entities = [UserProfileEntity::class, EmergencyContactEntity::class, MedicationEntity::class, HealthVitalEntity::class, AppointmentEntity::class, SosEventEntity::class, SyncQueueEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(AppTypeConverters::class)
abstract class YasliDostuDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun medicationDao(): MedicationDao
    abstract fun vitalDao(): VitalDao
    abstract fun appointmentDao(): AppointmentDao
    abstract fun contactDao(): EmergencyContactDao
    abstract fun sosDao(): SosDao
    abstract fun syncQueueDao(): SyncQueueDao

    companion object {
        private const val DB_NAME = "yaslidostu_fethiye_encrypted.db"

        private fun getSecurePassphrase(context: Context): ByteArray {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                context, "yaslidostu_vault", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val existing = prefs.getString("db_key_seed", null)
            return if (existing != null) {
                android.util.Base64.decode(existing, android.util.Base64.NO_WRAP)
            } else {
                val seed = ByteArray(32)
                SecureRandom().nextBytes(seed)
                val encoded = android.util.Base64.encodeToString(seed, android.util.Base64.NO_WRAP)
                prefs.edit().putString("db_key_seed", encoded).commit()
                seed
            }
        }

        fun getInstance(context: Context): YasliDostuDatabase {
            SQLiteDatabase.loadLibs(context)
            val passphrase = getSecurePassphrase(context)
            val supportFactory = SupportFactory(passphrase)
            return Room.databaseBuilder(context.applicationContext, YasliDostuDatabase::class.java, DB_NAME)
                .openHelperFactory(supportFactory)
                .build()
        }
    }
}

object FethiyeDolmusEngine {
    val routes: List<DolmusRoute> = listOf(
        DolmusRoute(
            id = "hat-karapinar", code = "3-31", name = "Fethiye - Hıdırlık - Karapınar",
            badge = "⭐ Karapınar / Hıdırlık (İlk Hat)",
            stops = "Akbank / Çarşı -> Balık Pazarı -> Salı Pazarı -> Eski SSK -> Hıdırlık Camii -> Karapınar Camii Son Durak",
            fare = "35 ₺", primaryColor = Color(0xFF047857),
            fixedTimes = listOf(
                "07:25", "07:35", "08:05", "08:35", "09:05", "09:35", "10:05", "10:35",
                "11:05", "11:35", "12:05", "12:35", "13:05", "13:35", "14:05", "14:35",
                "15:05", "15:35", "16:05", "16:35", "17:05", "17:35", "18:05", "18:35"
            )
        ),
        DolmusRoute(
            id = "hat-calis-sahil", code = "3-23", name = "Fethiye - Sahilden Çalış",
            badge = "Çalış Sahil Plajı",
            stops = "Merkez Garaj -> Kordon -> Adliye -> Fethiye Devlet Hastanesi -> Çalış Sahil",
            fare = "35 ₺", primaryColor = Color(0xFF2563EB),
            fixedTimes = listOf(
                "07:00", "07:30", "08:00", "08:24", "08:48", "09:12", "09:36", "10:00",
                "10:30", "11:00", "11:30", "12:00", "12:30", "13:00", "13:30", "14:00",
                "14:30", "15:00", "15:30", "16:00", "16:30", "17:00", "17:30", "18:00",
                "18:30", "19:00", "19:40", "20:20", "21:00", "22:00", "23:00"
            )
        ),
        DolmusRoute(
            id = "hat-calis-gunlukbasi", code = "3-24", name = "Fethiye - Günlükbaşı - Çalış",
            badge = "Günlükbaşı / Erasta AVM",
            stops = "Otogar / Erasta AVM -> Taşyaka -> Günlükbaşı -> Barış Manço Parkı -> Çalış",
            fare = "35 ₺", primaryColor = Color(0xFF4F46E5),
            fixedTimes = listOf(
                "06:40", "07:20", "08:00", "08:40", "09:20", "10:00", "10:40", "11:20",
                "12:00", "12:40", "13:20", "14:00", "14:40", "15:20", "16:00", "16:40",
                "17:20", "18:00", "18:40", "19:30", "20:30", "21:30", "22:30"
            )
        ),
        DolmusRoute(
            id = "hat-oludeniz", code = "3-40", name = "Fethiye - Ovacık - Ölüdeniz",
            badge = "Ölüdeniz / Belcekız",
            stops = "Fethiye Otogar Çıkış -> Salı Pazarı -> Ovacık -> Hisarönü -> Belcekız Sahili",
            fare = "50 ₺", primaryColor = Color(0xFF0D9488),
            fixedTimes = listOf(
                "07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00", "10:30",
                "11:00", "11:30", "12:00", "12:30", "13:00", "13:30", "14:00", "14:30",
                "15:00", "15:30", "16:00", "16:30", "17:00", "17:30", "18:00", "19:00",
                "20:00", "21:00", "22:00", "23:00", "00:00"
            )
        ),
        DolmusRoute(
            id = "hat-hastane-tasyaka", code = "3-15", name = "Taşyaka - Patlangıç - Devlet Hastanesi",
            badge = "Devlet Hastanesi Acil",
            stops = "Taşyaka Son Durak -> Patlangıç -> Salı Pazarı -> Çarşı -> Fethiye Devlet Hastanesi",
            fare = "35 ₺", primaryColor = Color(0xFFE11D48),
            fixedTimes = listOf(
                "07:15", "07:45", "08:15", "08:45", "09:15", "09:45", "10:15", "10:45",
                "11:15", "11:45", "12:15", "12:45", "13:15", "13:45", "14:15", "14:45",
                "15:15", "15:45", "16:15", "16:45", "17:15", "17:45", "18:15", "19:00",
                "20:00", "21:00"
            )
        )
    )

    private fun timeToMinutes(timeStr: String): Int {
        val parts = timeStr.split(":")
        return parts[0].toInt() * 60 + parts[1].toInt()
    }

    fun calculateDeparture(route: DolmusRoute, cal: Calendar = Calendar.getInstance()): DolmusDepartureInfo {
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        data class Slot(val display: String, val diff: Int)
        val slots = route.fixedTimes.map { timeStr ->
            val busMin = timeToMinutes(timeStr)
            val diff = if (busMin >= currentMinutes) busMin - currentMinutes
                       else (24 * 60 - currentMinutes) + busMin
            val display = if (busMin >= currentMinutes) timeStr else "$timeStr (Yarın)"
            Slot(display, diff)
        }.sortedBy { it.diff }

        val best = slots.firstOrNull() ?: Slot("—", 0)
        return DolmusDepartureInfo(
            nextTime = best.display,
            diffMinutes = best.diff,
            upcomingDepartures = slots.drop(1).take(3).map { it.display }
        )
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = YasliDostuDatabase.getInstance(application)
    private val vibrator = application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private var tts: TextToSpeech? = null
    var isTtsReady by mutableStateOf(false)

    var currentTimeString by mutableStateOf("--:--:--")
    var currentDateString by mutableStateOf("Yükleniyor...")
    var weatherState by mutableStateOf(LiveWeather())

    val userProfile = db.userDao().observeUser().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val medications = db.medicationDao().observeMedications("usr-1").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val vitals = db.vitalDao().observeVitals("usr-1").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val appointments = db.appointmentDao().observeAppointments("usr-1").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val contacts = db.contactDao().observeContacts("usr-1").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var activeTab by mutableStateOf("dolmus")
    var highContrast by mutableStateOf(false)
    var sosModalOpen by mutableStateOf(false)
    var sosCountdown by mutableStateOf(3)
    var speakingLabel by mutableStateOf("")
    var pendingSosCall by mutableStateOf(false)

    private var sosJob: Job? = null

    init {
        initTts(application)
        initDefaultData()
        startClockTicker()
        fetchFethiyeWeather()
    }

    private fun initTts(context: Context) {
        tts = TextToSpeech(context) { status ->
            viewModelScope.launch(Dispatchers.Main) {
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale("tr", "TR")
                    tts?.setSpeechRate(0.88f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            viewModelScope.launch(Dispatchers.Main) { speakingLabel = "" }
                        }
                        override fun onError(utteranceId: String?) {
                            viewModelScope.launch(Dispatchers.Main) { speakingLabel = "" }
                        }
                    })
                    isTtsReady = true
                }
            }
        }
    }

    fun speak(text: String) {
        if (!isTtsReady) return
        speakingLabel = text
        vibrate(60)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UTT_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        tts?.stop()
        speakingLabel = ""
    }

    private fun vibrate(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
    }

    private fun startClockTicker() {
        viewModelScope.launch(Dispatchers.Default) {
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale("tr", "TR"))
            val dateFormat = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("tr", "TR"))
            while (isActive) {
                val now = Date()
                currentTimeString = timeFormat.format(now)
                currentDateString = dateFormat.format(now)
                delay(1000)
            }
        }
    }

    fun fetchFethiyeWeather() {
        viewModelScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val urlString = "https://api.open-meteo.com/v1/forecast?latitude=36.6217&longitude=29.1164&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&timezone=auto"
                connection = URL(urlString).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                if (connection.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText()
                    reader.close()
                    val json = JSONObject(response)
                    val current = json.getJSONObject("current")
                    val temp = current.getInt("temperature_2m")
                    val wind = current.getInt("wind_speed_10m")
                    val humidity = current.getInt("relative_humidity_2m")
                    val code = current.getInt("weather_code")

                    val condition = when (code) {
                        0 -> "Açık ve Güneşli"
                        1, 2, 3 -> "Parçalı Bulutlu"
              
