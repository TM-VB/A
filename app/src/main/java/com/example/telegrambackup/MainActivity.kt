package com.example.telegrambackup

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.room.*
import androidx.work.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// ==========================================
// 1. الثيم والألوان واتجاه الواجهة (RTL Theme)
// ==========================================
private val LightColors = lightColorScheme(
    primary = Color(0xFF2A8EEA),
    secondary = Color(0xFF50A7EA),
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF50A7EA),
    secondary = Color(0xFF2A8EEA),
    background = Color(0xFF17212B),
    surface = Color(0xFF242F3D)
)

@Composable
fun TelegramBackupTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

// ==========================================
// 2. إدارة جلسة تيليجرام (TelegramManager)
// ==========================================
sealed interface TelegramAuthState {
    data object Initializing : TelegramAuthState
    data object WaitPhoneNumber : TelegramAuthState
    data class WaitCode(val timeoutSeconds: Int = 0) : TelegramAuthState
    data class WaitPassword(val passwordHint: String? = null, val hasRecovery: Boolean = false) : TelegramAuthState
    data class WaitRegistration(val termsOfService: String? = null) : TelegramAuthState
    data object Authenticated : TelegramAuthState
    data object LoggingOut : TelegramAuthState
    data object Closed : TelegramAuthState
    data class Error(val code: Int, val message: String, val localizedMessage: String) : TelegramAuthState
}

class TelegramException(val code: Int, override val message: String) : Exception("TDLib Error [$code]: $message")

class TelegramManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var client: Client? = null

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Initializing)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private val _updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 128)
    val updates: SharedFlow<TdApi.Object> = _updates.asSharedFlow()

    companion object {
        @Volatile private var instance: TelegramManager? = null
        fun getInstance(context: Context): TelegramManager = instance ?: synchronized(this) {
            instance ?: TelegramManager(context.applicationContext).also { instance = it }
        }
    }

    init {
        try {
            System.loadLibrary("tdjni")
            Client.execute(TdApi.SetLogVerbosityLevel(1))
        } catch (e: UnsatisfiedLinkError) {
            Log.e("TelegramManager", "Native libtdjni is missing or loading", e)
        }
    }

    fun start() {
        if (client != null) return
        client = Client.create(
            { update -> handleUpdate(update) },
            { error -> Log.e("TelegramManager", "Update Error: ${error?.message}") },
            { defaultError -> Log.e("TelegramManager", "Default Error: ${defaultError?.message}") }
        )
    }

    private fun handleUpdate(update: TdApi.Object?) {
        if (update == null) return
        scope.launch { _updates.emit(update) }
        if (update is TdApi.UpdateAuthorizationState) handleAuthState(update.authorizationState)
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val tdlibDir = File(context.filesDir, "tdlib_session").apply { if (!exists()) mkdirs() }
                val filesDir = File(context.filesDir, "tdlib_media").apply { if (!exists()) mkdirs() }
                val params = TdApi.TdlibParameters().apply {
                    databaseDirectory = tdlibDir.absolutePath
                    filesDirectory = filesDir.absolutePath
                    useFileDatabase = true
                    useChatInfoDatabase = true
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = BuildConfig.TELEGRAM_API_ID
                    apiHash = BuildConfig.TELEGRAM_API_HASH
                    systemLanguageCode = "ar"
                    deviceModel = Build.MODEL
                    systemVersion = Build.VERSION.RELEASE
                    applicationVersion = "1.0.0"
                }
                send(TdApi.SetTdlibParameters(params), onError = { emitError(it) })
            }
            is TdApi.AuthorizationStateWaitEncryptionKey -> send(TdApi.CheckDatabaseEncryptionKey(byteArrayOf()), onError = { emitError(it) })
            is TdApi.AuthorizationStateWaitPhoneNumber -> _authState.value = TelegramAuthState.WaitPhoneNumber
            is TdApi.AuthorizationStateWaitCode -> _authState.value = TelegramAuthState.WaitCode(state.codeInfo?.timeout ?: 0)
            is TdApi.AuthorizationStateWaitPassword -> _authState.value = TelegramAuthState.WaitPassword(state.passwordHint, state.hasRecoveryEmailAddress)
            is TdApi.AuthorizationStateWaitRegistration -> _authState.value = TelegramAuthState.WaitRegistration(state.termsOfService?.text?.text)
            is TdApi.AuthorizationStateReady -> _authState.value = TelegramAuthState.Authenticated
            is TdApi.AuthorizationStateLoggingOut -> _authState.value = TelegramAuthState.LoggingOut
            is TdApi.AuthorizationStateClosed -> {
                _authState.value = TelegramAuthState.Closed
                client = null
                start()
            }
        }
    }

    private fun emitError(error: TdApi.Error) {
        val localized = when {
            error.message.startsWith("PHONE_NUMBER_INVALID") -> "رقم الهاتف غير صالح"
            error.message.startsWith("PHONE_CODE_INVALID") -> "رمز التأكيد غير صحيح"
            error.message.startsWith("PHONE_CODE_EXPIRED") -> "انتهت صلاحية رمز التأكيد"
            error.message.startsWith("PASSWORD_HASH_INVALID") -> "كلمة المرور غير صحيحة"
            error.message.startsWith("FLOOD_WAIT") -> "يرجى الانتظار والمحاولة لاحقاً"
            else -> "خطأ: ${error.message}"
        }
        _authState.value = TelegramAuthState.Error(error.code, error.message, localized)
    }

    suspend fun <R : TdApi.Object> execute(function: TdApi.Function<R>): Result<R> = suspendCancellableCoroutine { cont ->
        val cl = client ?: return@suspendCancellableCoroutine cont.resume(Result.failure(IllegalStateException("TDLib not ready")))
        cl.send(function) { result ->
            when (result) {
                is TdApi.Error -> cont.resume(Result.failure(TelegramException(result.code, result.message)))
                else -> @Suppress("UNCHECKED_CAST") cont.resume(Result.success(result as R))
            }
        }
    }

    fun send(function: TdApi.Function<*>, onResult: (TdApi.Object) -> Unit = {}, onError: (TdApi.Error) -> Unit = {}) {
        client?.send(function) { result ->
            when (result) {
                is TdApi.Error -> onError(result)
                else -> onResult(result)
            }
        }
    }

    fun setPhoneNumber(phone: String) = send(TdApi.SetAuthenticationPhoneNumber(phone, TdApi.PhoneNumberAuthenticationSettings()), onError = { emitError(it) })
    fun checkAuthCode(code: String) = send(TdApi.CheckAuthenticationCode(code), onError = { emitError(it) })
    fun checkAuthPassword(pass: String) = send(TdApi.CheckAuthenticationPassword(pass), onError = { emitError(it) })
    fun registerUser(first: String, last: String) = send(TdApi.RegisterUser(first, last), onError = { emitError(it) })
    fun logout() = send(TdApi.LogOut())

    suspend fun getMe(): Result<TdApi.User> = execute(TdApi.GetMe())
    suspend fun getChat(chatId: Long): Result<TdApi.Chat> = execute(TdApi.GetChat(chatId))

    suspend fun getAvailableBackupChats(limit: Int = 50): Result<List<TdApi.Chat>> = try {
        execute(TdApi.LoadChats(TdApi.ChatListMain(), limit))
        val chats = execute(TdApi.GetChats(TdApi.ChatListMain(), limit)).getOrThrow()
        val list = mutableListOf<TdApi.Chat>()
        for (id in chats.chatIds) {
            getChat(id).getOrNull()?.let { list.add(it) }
        }
        Result.success(list)
    } catch (e: Exception) { Result.failure(e) }

    suspend fun uploadSinglePhoto(chatId: Long, imageFile: File, caption: String = "", onProgress: (Int) -> Unit): Result<Pair<Long, String?>> = suspendCancellableCoroutine { cont ->
        val inputPhoto = TdApi.InputMessagePhoto(TdApi.InputFileLocal(imageFile.absolutePath), null, IntArray(0), 0, 0, TdApi.FormattedText(caption, emptyArray()), 0, false)
        val sendMessage = TdApi.SendMessage(chatId, 0, null, null, null, inputPhoto)
        var targetFileId = -1
        var sentMessageId = 0L

        val job = scope.launch {
            updates.collect { update ->
                if (update is TdApi.UpdateFile && update.file.id == targetFileId) {
                    val total = if (update.file.expectedSize > 0) update.file.expectedSize else imageFile.length()
                    if (total > 0) onProgress(((update.file.remote.uploadedSize.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100))
                }
                if (update is TdApi.UpdateMessageSendSucceeded && (update.oldMessageId == sentMessageId || update.message.id == sentMessageId)) {
                    val photo = (update.message.content as? TdApi.MessagePhoto)?.photo
                    onProgress(100)
                    if (cont.isActive) cont.resume(Result.success(Pair(update.message.id, photo?.sizes?.lastOrNull()?.photo?.remote?.id)))
                }
            }
        }
        cont.invokeOnCancellation { job.cancel() }
        client?.send(sendMessage) { res ->
            when (res) {
                is TdApi.Message -> {
                    sentMessageId = res.id
                    targetFileId = (res.content as? TdApi.MessagePhoto)?.photo?.sizes?.lastOrNull()?.photo?.id ?: -1
                }
                is TdApi.Error -> {
                    job.cancel()
                    if (cont.isActive) cont.resume(Result.failure(TelegramException(res.code, res.message)))
                }
            }
        }
    }

    suspend fun uploadSingleVideo(chatId: Long, videoFile: File, durationSeconds: Int, width: Int, height: Int, caption: String = "", onProgress: (Int) -> Unit): Result<Pair<Long, String?>> = suspendCancellableCoroutine { cont ->
        val inputVideo = TdApi.InputMessageVideo(TdApi.InputFileLocal(videoFile.absolutePath), null, IntArray(0), durationSeconds, width, height, true, TdApi.FormattedText(caption, emptyArray()), 0, false)
        val sendMessage = TdApi.SendMessage(chatId, 0, null, null, null, inputVideo)
        var targetFileId = -1
        var sentMessageId = 0L

        val job = scope.launch {
            updates.collect { update ->
                if (update is TdApi.UpdateFile && update.file.id == targetFileId) {
                    val total = if (update.file.expectedSize > 0) update.file.expectedSize else videoFile.length()
                    if (total > 0) onProgress(((update.file.remote.uploadedSize.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100))
                }
                if (update is TdApi.UpdateMessageSendSucceeded && (update.oldMessageId == sentMessageId || update.message.id == sentMessageId)) {
                    val video = (update.message.content as? TdApi.MessageVideo)?.video
                    onProgress(100)
                    if (cont.isActive) cont.resume(Result.success(Pair(update.message.id, video?.video?.remote?.id)))
                }
            }
        }
        cont.invokeOnCancellation { job.cancel() }
        client?.send(sendMessage) { res ->
            when (res) {
                is TdApi.Message -> {
                    sentMessageId = res.id
                    targetFileId = (res.content as? TdApi.MessageVideo)?.video?.video?.id ?: -1
                }
                is TdApi.Error -> {
                    job.cancel()
                    if (cont.isActive) cont.resume(Result.failure(TelegramException(res.code, res.message)))
                }
            }
        }
    }

    suspend fun downloadPhotoFromMessage(chatId: Long, messageId: Long, onProgress: (Int) -> Unit): Result<File> = suspendCancellableCoroutine { cont ->
        scope.launch {
            try {
                val message = execute(TdApi.GetMessage(chatId, messageId)).getOrThrow()
                val photo = (message.content as? TdApi.MessagePhoto)?.photo?.sizes?.lastOrNull()?.photo
                    ?: return@launch cont.resume(Result.failure(Exception("لا توجد صورة في الرسالة")))

                if (photo.local.isDownloadingCompleted && !photo.local.path.isNullOrEmpty()) {
                    onProgress(100)
                    return@launch cont.resume(Result.success(File(photo.local.path)))
                }

                val targetFileId = photo.id
                val job = launch {
                    updates.collect { update ->
                        if (update is TdApi.UpdateFile && update.file.id == targetFileId) {
                            val total = if (update.file.expectedSize > 0) update.file.expectedSize else update.file.size
                            if (total > 0) onProgress(((update.file.local.downloadedSize.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100))
                            if (update.file.local.isDownloadingCompleted && !update.file.local.path.isNullOrEmpty()) {
                                onProgress(100)
                                if (cont.isActive) cont.resume(Result.success(File(update.file.local.path)))
                            }
                        }
                    }
                }
                cont.invokeOnCancellation { job.cancel() }
                client?.send(TdApi.DownloadFile(targetFileId, 32, 0, 0, false)) {}
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(Result.failure(e))
            }
        }
    }
}

// ==========================================
// 3. التخزين المحلي والإعدادات (Preferences)
// ==========================================
data class BackupDestination(val chatId: Long, val title: String, val typeDescription: String)

class BackupPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("telegram_backup_prefs", Context.MODE_PRIVATE)
    private val _destinationFlow = MutableStateFlow(getSavedDestination())
    val destinationFlow: StateFlow<BackupDestination?> = _destinationFlow.asStateFlow()

    companion object {
        @Volatile private var instance: BackupPreferences? = null
        fun getInstance(context: Context): BackupPreferences = instance ?: synchronized(this) {
            instance ?: BackupPreferences(context.applicationContext).also { instance = it }
        }
    }

    fun saveDestination(chatId: Long, title: String, typeDescription: String) {
        prefs.edit().putLong("chat_id", chatId).putString("title", title).putString("type", typeDescription).apply()
        _destinationFlow.value = BackupDestination(chatId, title, typeDescription)
    }

    fun getSavedDestination(): BackupDestination? {
        val chatId = prefs.getLong("chat_id", 0L)
        val title = prefs.getString("title", null)
        val type = prefs.getString("type", "محادثة") ?: "محادثة"
        return if (chatId != 0L && !title.isNullOrEmpty()) BackupDestination(chatId, title, type) else null
    }

    fun clearDestination() {
        prefs.edit().clear().apply()
        _destinationFlow.value = null
    }
}

data class AutoBackupSettings(
    val isEnabled: Boolean = false,
    val isWifiOnly: Boolean = true,
    val requiresCharging: Boolean = false,
    val requiresBatteryNotLow: Boolean = true,
    val lastScanTimestamp: Long = 0L
)

class AutoBackupPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("auto_backup_settings", Context.MODE_PRIVATE)
    private val _settingsFlow = MutableStateFlow(getSettings())
    val settingsFlow: StateFlow<AutoBackupSettings> = _settingsFlow.asStateFlow()

    companion object {
        @Volatile private var instance: AutoBackupPreferences? = null
        fun getInstance(context: Context): AutoBackupPreferences = instance ?: synchronized(this) {
            instance ?: AutoBackupPreferences(context.applicationContext).also { instance = it }
        }
    }

    fun getSettings(): AutoBackupSettings = AutoBackupSettings(
        isEnabled = prefs.getBoolean("enabled", false),
        isWifiOnly = prefs.getBoolean("wifi_only", true),
        requiresCharging = prefs.getBoolean("charging", false),
        requiresBatteryNotLow = prefs.getBoolean("battery_not_low", true),
        lastScanTimestamp = prefs.getLong("last_scan", 0L)
    )

    fun updateSettings(isEnabled: Boolean? = null, isWifiOnly: Boolean? = null, requiresCharging: Boolean? = null, requiresBatteryNotLow: Boolean? = null) {
        val editor = prefs.edit()
        isEnabled?.let { editor.putBoolean("enabled", it) }
        isWifiOnly?.let { editor.putBoolean("wifi_only", it) }
        requiresCharging?.let { editor.putBoolean("charging", it) }
        requiresBatteryNotLow?.let { editor.putBoolean("battery_not_low", it) }
        editor.apply()
        _settingsFlow.value = getSettings()
    }

    fun updateLastScanTimestamp(timestamp: Long) {
        prefs.edit().putLong("last_scan", timestamp).apply()
        _settingsFlow.value = getSettings()
    }
}

// ==========================================
// 4. قاعدة البيانات المحلية (Room Database)
// ==========================================
@Entity(tableName = "backed_up_files", indices = [Index(value = ["fileHash", "telegramChatId"], unique = true), Index(value = ["localMediaId"])])
data class BackedUpFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localMediaId: Long,
    val fileHash: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val telegramChatId: Long,
    val telegramMessageId: Long,
    val telegramFileId: String?,
    val uploadStatus: String = "COMPLETED",
    val uploadTimestamp: Long = System.currentTimeMillis()
)

enum class BackupTaskStatus { PENDING, UPLOADING, COMPLETED, ALREADY_EXISTS, FAILED, CANCELLED }

@Entity(tableName = "backup_queue")
data class BackupQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localMediaId: Long,
    val fileHash: String? = null,
    val uriString: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val targetChatId: Long,
    val status: BackupTaskStatus = BackupTaskStatus.PENDING,
    val progress: Int = 0,
    val errorMessage: String? = null,
    val telegramMessageId: Long? = null,
    val telegramFileId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface BackupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertBackupRecord(record: BackedUpFileEntity): Long
    @Query("SELECT * FROM backed_up_files ORDER BY uploadTimestamp DESC") fun getAllBackedUpFiles(): Flow<List<BackedUpFileEntity>>
    @Query("SELECT EXISTS(SELECT 1 FROM backed_up_files WHERE fileHash = :hash AND telegramChatId = :chatId AND uploadStatus = 'COMPLETED')") suspend fun isHashBackedUpInChat(hash: String, chatId: Long): Boolean
    @Query("SELECT * FROM backed_up_files WHERE fileHash = :hash AND telegramChatId = :chatId LIMIT 1") suspend fun getExistingRecord(hash: String, chatId: Long): BackedUpFileEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM backed_up_files WHERE localMediaId = :mediaId AND uploadStatus = 'COMPLETED')") suspend fun isMediaBackedUp(mediaId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertQueueItems(items: List<BackupQueueEntity>): List<Long>
    @Update suspend fun updateQueueItem(item: BackupQueueEntity)
    @Query("SELECT * FROM backup_queue ORDER BY id ASC") fun getAllQueueItemsFlow(): Flow<List<BackupQueueEntity>>
    @Query("SELECT * FROM backup_queue WHERE status = 'PENDING' ORDER BY id ASC LIMIT 1") suspend fun getNextPendingTask(): BackupQueueEntity?
    @Query("SELECT * FROM backup_queue WHERE id = :id") suspend fun getTaskById(id: Long): BackupQueueEntity?
    @Query("UPDATE backup_queue SET status = 'CANCELLED' WHERE status = 'PENDING'") suspend fun cancelAllPending()
    @Query("UPDATE backup_queue SET status = 'PENDING', progress = 0, errorMessage = null WHERE status = 'FAILED'") suspend fun retryAllFailed()
    @Query("UPDATE backup_queue SET status = 'PENDING', progress = 0, errorMessage = null WHERE id = :id") suspend fun retryTask(id: Long)
    @Query("UPDATE backup_queue SET status = 'CANCELLED' WHERE id = :id") suspend fun cancelTask(id: Long)

    @Query("SELECT COUNT(*) FROM backed_up_files WHERE mimeType NOT LIKE 'video/%' AND fileName NOT LIKE '%.mp4' AND fileName NOT LIKE '%.mkv'") fun getBackedUpPhotosCountFlow(): Flow<Int>
    @Query("SELECT COUNT(*) FROM backed_up_files WHERE mimeType LIKE 'video/%' OR fileName LIKE '%.mp4' OR fileName LIKE '%.mkv'") fun getBackedUpVideosCountFlow(): Flow<Int>
    @Query("SELECT COUNT(*) FROM backed_up_files") fun getTotalBackedUpCountFlow(): Flow<Int>
    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM backed_up_files") fun getTotalBackedUpSizeFlow(): Flow<Long>
    @Query("SELECT MAX(uploadTimestamp) FROM backed_up_files") fun getLastBackupTimestampFlow(): Flow<Long?>
    @Query("SELECT COUNT(*) FROM backup_queue WHERE status = 'FAILED'") fun getFailedTasksCountFlow(): Flow<Int>
    @Query("SELECT COUNT(*) FROM backup_queue WHERE status = 'PENDING' OR status = 'UPLOADING'") fun getActiveQueueCountFlow(): Flow<Int>
}

@Database(entities = [BackedUpFileEntity::class, BackupQueueEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun backupDao(): BackupDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "telegram_backup.db").build().also { INSTANCE = it }
        }
    }
}

// ==========================================
// 5. مسح الوسائط وحساب الهاش (Media Helpers)
// ==========================================
data class LocalMediaItem(val id: Long, val uri: Uri, val name: String, val size: Long, val mimeType: String, val isVideo: Boolean, val dateModified: Long)
data class MediaSummary(val photoCount: Int = 0, val videoCount: Int = 0, val totalSizeBytes: Long = 0L, val mediaItems: List<LocalMediaItem> = emptyList()) {
    val totalFilesCount: Int get() = photoCount + videoCount
    val formattedTotalSize: String
        get() {
            val mb = totalSizeBytes / (1024.0 * 1024.0)
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.2f جيجابايت", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f ميجابايت", mb)
                else -> "${totalSizeBytes / 1024} ك.ب"
            }
        }
}

class LocalMediaScanner(private val context: Context) {
    suspend fun scanMedia(): MediaSummary = withContext(Dispatchers.IO) {
        val list = mutableListOf<LocalMediaItem>()
        var pCount = 0
        var vCount = 0
        var totalSize = 0L

        val imgProj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_MODIFIED)
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imgProj, null, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val size = c.getLong(sizeCol)
                list.add(LocalMediaItem(id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), c.getString(nameCol) ?: "photo_$id", size, c.getString(mimeCol) ?: "image/jpeg", false, c.getLong(dateCol)))
                pCount++
                totalSize += size
            }
        }

        val vidProj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.DATE_MODIFIED)
        context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, vidProj, null, null, "${MediaStore.Video.Media.DATE_MODIFIED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val size = c.getLong(sizeCol)
                list.add(LocalMediaItem(id, ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), c.getString(nameCol) ?: "video_$id", size, c.getString(mimeCol) ?: "video/mp4", true, c.getLong(dateCol)))
                vCount++
                totalSize += size
            }
        }
        list.sortByDescending { it.dateModified }
        MediaSummary(pCount, vCount, totalSize, list)
    }

    companion object {
        fun getRequiredPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

object FileHasher {
    suspend fun calculateSha256(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val stream: InputStream = context.contentResolver.openInputStream(uri) ?: return@withContext Result.failure(Exception("Cannot open stream"))
            stream.use { s ->
                val buf = ByteArray(8192)
                var r: Int
                while (s.read(buf).also { r = it } != -1) digest.update(buf, 0, r)
            }
            Result.success(digest.digest().joinToString("") { "%02x".format(it) })
        } catch (e: Exception) { Result.failure(e) }
    }
}

object FileCacheHelper {
    suspend fun createTempFileFromUri(context: Context, uri: Uri, fileName: String): File = withContext(Dispatchers.IO) {
        val ext = fileName.substringAfterLast(".", "tmp")
        val file = File.createTempFile("upload_", ".$ext", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
        file
    }
}

object MediaStoreSaver {
    suspend fun saveImageToGallery(context: Context, sourceFile: File, displayName: String, mimeType: String): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType.ifBlank { "image/jpeg" })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TelegramBackup")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val itemUri = resolver.insert(collectionUri, contentValues) ?: return@withContext Result.failure(Exception("Failed URI"))
            resolver.openOutputStream(itemUri)?.use { out -> sourceFile.inputStream().use { input -> input.copyTo(out) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }
            Result.success(itemUri)
        } catch (e: Exception) { Result.failure(e) }
    }
}

data class VideoInfo(val durationSeconds: Int, val width: Int, val height: Int)
object VideoMetadataHelper {
    fun extractVideoInfo(context: Context, uri: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            VideoInfo((duration / 1000).toInt(), width, height)
        } catch (e: Exception) { VideoInfo(0, 0, 0) } finally { try { retriever.release() } catch (_: Exception) {} }
    }
}

// ==========================================
// 6. طابور الرفع التلقائي (Queue & WorkManager)
// ==========================================
class BackupQueueManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val database = AppDatabase.getInstance(context)
    private val dao = database.backupDao()
    private val telegramManager = TelegramManager.getInstance(context)

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private var workerJob: Job? = null
    private val mutex = Mutex()

    companion object {
        @Volatile private var INSTANCE: BackupQueueManager? = null
        fun getInstance(context: Context): BackupQueueManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: BackupQueueManager(context.applicationContext).also { INSTANCE = it }
        }
    }

    suspend fun enqueueMedia(items: List<LocalMediaItem>, targetChatId: Long): Int = withContext(Dispatchers.IO) {
        val list = items.map {
            BackupQueueEntity(localMediaId = it.id, uriString = it.uri.toString(), fileName = it.name, fileSize = it.size, mimeType = it.mimeType, targetChatId = targetChatId)
        }
        if (list.isNotEmpty()) {
            dao.insertQueueItems(list)
            startQueueProcessing()
        }
        list.size
    }

    fun startQueueProcessing() {
        if (_isPaused.value) return
        mutex.tryLock()
        if (workerJob?.isActive == true) {
            mutex.unlock()
            return
        }
        workerJob = scope.launch {
            _isProcessing.value = true
            try {
                while (isActive && !_isPaused.value) {
                    val task = dao.getNextPendingTask() ?: break
                    processSingleTask(task)
                    delay(300)
                }
            } finally {
                _isProcessing.value = false
                if (mutex.isLocked) mutex.unlock()
            }
        }
    }

    private suspend fun processSingleTask(task: BackupQueueEntity) {
        var tempFile: File? = null
        try {
            val uri = Uri.parse(task.uriString)
            val fileHash = FileHasher.calculateSha256(context, uri).getOrNull()
            if (fileHash == null) {
                dao.updateQueueItem(task.copy(status = BackupTaskStatus.FAILED, errorMessage = "تعذر قراءة البصمة"))
                return
            }

            if (dao.isHashBackedUpInChat(fileHash, task.targetChatId)) {
                val existing = dao.getExistingRecord(fileHash, task.targetChatId)
                dao.updateQueueItem(task.copy(fileHash = fileHash, status = BackupTaskStatus.ALREADY_EXISTS, progress = 100, telegramMessageId = existing?.telegramMessageId, telegramFileId = existing?.telegramFileId, errorMessage = "منسوخ مسبقاً"))
                return
            }

            dao.updateQueueItem(task.copy(fileHash = fileHash, status = BackupTaskStatus.UPLOADING, progress = 0))
            tempFile = FileCacheHelper.createTempFileFromUri(context, uri, task.fileName)

            val isVideo = task.mimeType.startsWith("video/") || task.fileName.endsWith(".mp4", true) || task.fileName.endsWith(".mkv", true)
            val uploadResult = if (isVideo) {
                val info = VideoMetadataHelper.extractVideoInfo(context, uri)
                telegramManager.uploadSingleVideo(task.targetChatId, tempFile, info.durationSeconds, info.width, info.height, "Backup Video: ${task.fileName}") { prog -> updateProgress(task.id, prog) }
            } else {
                telegramManager.uploadSinglePhoto(task.targetChatId, tempFile, "Backup Photo: ${task.fileName}") { prog -> updateProgress(task.id, prog) }
            }

            uploadResult.onSuccess { (msgId, remId) ->
                val current = dao.getTaskById(task.id)
                if (current?.status != BackupTaskStatus.CANCELLED) {
                    dao.insertBackupRecord(BackedUpFileEntity(localMediaId = task.localMediaId, fileHash = fileHash, fileName = task.fileName, fileSize = task.fileSize, mimeType = task.mimeType, telegramChatId = task.targetChatId, telegramMessageId = msgId, telegramFileId = remId))
                    dao.updateQueueItem(task.copy(fileHash = fileHash, status = BackupTaskStatus.COMPLETED, progress = 100, telegramMessageId = msgId, telegramFileId = remId))
                }
            }.onFailure { error ->
                val current = dao.getTaskById(task.id)
                if (current?.status != BackupTaskStatus.CANCELLED) {
                    dao.updateQueueItem(task.copy(fileHash = fileHash, status = BackupTaskStatus.FAILED, errorMessage = error.localizedMessage ?: "فشل الرفع"))
                }
            }
        } catch (e: Exception) {
            dao.updateQueueItem(task.copy(status = BackupTaskStatus.FAILED, errorMessage = e.localizedMessage ?: "خطأ أثناء الرفع"))
        } finally {
            tempFile?.delete()
        }
    }

    private fun updateProgress(id: Long, prog: Int) = scope.launch {
        val curr = dao.getTaskById(id)
        if (curr?.status == BackupTaskStatus.UPLOADING) dao.updateQueueItem(curr.copy(progress = prog))
    }

    fun pauseQueue() { _isPaused.value = true; workerJob?.cancel(); _isProcessing.value = false }
    fun resumeQueue() { _isPaused.value = false; startQueueProcessing() }
    fun retryFailed() = scope.launch { dao.retryAllFailed(); if (!_isPaused.value) startQueueProcessing() }
    fun retryTask(id: Long) = scope.launch { dao.retryTask(id); if (!_isPaused.value) startQueueProcessing() }
    fun cancelTask(id: Long) = scope.launch { dao.cancelTask(id) }
    fun cancelAll() = scope.launch { dao.cancelAllPending() }
}

class AutoBackupWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val autoPrefs = AutoBackupPreferences.getInstance(context)
            if (!autoPrefs.getSettings().isEnabled) return@withContext Result.success()
            val dest = BackupPreferences.getInstance(context).getSavedDestination() ?: return@withContext Result.success()

            val telegramManager = TelegramManager.getInstance(context).apply { start() }
            if (telegramManager.authState.value !is TelegramAuthState.Authenticated) return@withContext Result.retry()

            val summary = LocalMediaScanner(context).scanMedia()
            val dao = AppDatabase.getInstance(context).backupDao()
            val unbacked = summary.mediaItems.filter { !dao.isMediaBackedUp(it.id) }
            if (unbacked.isNotEmpty()) BackupQueueManager.getInstance(context).enqueueMedia(unbacked, dest.chatId)
            autoPrefs.updateLastScanTimestamp(System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) { Result.retry() }
    }
}

object WorkManagerHelper {
    fun scheduleOrUpdateAutoBackup(context: Context, settings: AutoBackupSettings) {
        val workManager = WorkManager.getInstance(context)
        if (!settings.isEnabled) {
            workManager.cancelUniqueWork("telegram_auto_backup_work")
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.isWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(settings.requiresCharging)
            .setRequiresBatteryNotLow(settings.requiresBatteryNotLow)
            .build()
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(15, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork("telegram_auto_backup_work", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

// ==========================================
// 7. النماذج والشاشات (ViewModels & Screens)
// ==========================================
sealed class Screen(val route: String) {
    data object Welcome : Screen("welcome")
    data object Auth : Screen("auth")
    data object SelectDestination : Screen("select_destination")
    data object MainContainer : Screen("main_container")
}

sealed class BottomNavItem(val route: String, val title: String, val icon: ImageVector) {
    data object Home : BottomNavItem("home", "الرئيسية", Icons.Default.Home)
    data object Backup : BottomNavItem("backup", "النسخ الاحتياطي", Icons.Default.CloudUpload)
    data object Files : BottomNavItem("files", "الملفات", Icons.Default.Folder)
    data object Settings : BottomNavItem("settings", "الإعدادات", Icons.Default.Settings)
}

// --- Welcome Screen ---
@Composable
fun WelcomeScreen(onGetStartedClick: () -> Unit) {
    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(24.dp))
            Text("Telegram Backup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Text("احفظ صورك وفيديوهاتك بنسخ احتياطي آمن وسريع عبر حساب Telegram الخاص بك.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(36.dp))
            Button(onClick = onGetStartedClick, modifier = Modifier.fillMaxWidth(0.85f).height(50.dp)) {
                Text("ابدأ الآن", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// --- Auth Screen & ViewModel ---
class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val telegramManager = TelegramManager.getInstance(application)
    val authState: StateFlow<TelegramAuthState> = telegramManager.authState
    var errorMessage by mutableStateOf<String?>(null)

    init { telegramManager.start() }

    fun submitPhone(phone: String) {
        val clean = phone.trim().replace(" ", "")
        if (clean.isBlank() || !clean.startsWith("+")) {
            errorMessage = "أدخل رقم الهاتف مسبوقاً برمز الدولة مثل +966..."
            return
        }
        errorMessage = null
        telegramManager.setPhoneNumber(clean)
    }

    fun submitCode(code: String) = telegramManager.checkAuthCode(code.trim())
    fun submitPassword(pass: String) = telegramManager.checkAuthPassword(pass)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(onAuthSuccess: () -> Unit, viewModel: AuthViewModel = viewModel()) {
    val authState by viewModel.authState.collectAsState()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(authState) {
        if (authState is TelegramAuthState.Authenticated) onAuthSuccess()
    }

    val displayError = viewModel.errorMessage ?: (authState as? TelegramAuthState.Error)?.localizedMessage

    Scaffold(topBar = { TopAppBar(title = { Text("تسجيل الدخول", fontWeight = FontWeight.Bold) }) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                if (displayError != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                        Text(displayError, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(14.dp), textAlign = TextAlign.Center)
                    }
                }
                when (val state = authState) {
                    is TelegramAuthState.Initializing, is TelegramAuthState.LoggingOut -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("جاري الاتصال بسيرفر تيليجرام...")
                    }
                    is TelegramAuthState.WaitPhoneNumber, is TelegramAuthState.Error -> {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("أدخل رقم هاتفك", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(value = phone, onValueChange = { phone = it; viewModel.errorMessage = null }, label = { Text("رقم الهاتف الدولي (+966...)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(onClick = { viewModel.submitPhone(phone) }, modifier = Modifier.fillMaxWidth().height(50.dp), enabled = phone.isNotBlank()) {
                            Text("إرسال رمز التأكيد")
                        }
                    }
                    is TelegramAuthState.WaitCode -> {
                        Icon(Icons.Default.MarkEmailRead, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("رمز التحقق", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(value = code, onValueChange = { code = it; viewModel.errorMessage = null }, label = { Text("رمز التأكيد") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(onClick = { viewModel.submitCode(code) }, modifier = Modifier.fillMaxWidth().height(50.dp), enabled = code.isNotBlank()) {
                            Text("تأكيد الرمز")
                        }
                    }
                    is TelegramAuthState.WaitPassword -> {
                        Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("التحقق بخطوتين (2FA)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (!state.passwordHint.isNullOrEmpty()) Text("تلميح: ${state.passwordHint}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(value = password, onValueChange = { password = it; viewModel.errorMessage = null }, label = { Text("كلمة المرور") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(onClick = { viewModel.submitPassword(password) }, modifier = Modifier.fillMaxWidth().height(50.dp), enabled = password.isNotBlank()) {
                            Text("تسجيل الدخول")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

// --- Destination Screen & ViewModel ---
data class ChatItem(val id: Long, val title: String, val typeDescription: String, val isSavedMessages: Boolean = false)
class DestinationViewModel(application: Application) : AndroidViewModel(application) {
    private val telegramManager = TelegramManager.getInstance(application)
    private val backupPreferences = BackupPreferences.getInstance(application)

    var isLoading by mutableStateOf(false)
    var currentDestination by mutableStateOf<BackupDestination?>(null)
    var availableChats by mutableStateOf<List<ChatItem>>(emptyList())

    init { loadChats() }

    fun loadChats() {
        isLoading = true
        currentDestination = backupPreferences.getSavedDestination()
        viewModelScope.launch {
            try {
                val me = telegramManager.getMe().getOrNull()
                val chats = telegramManager.getAvailableBackupChats(40).getOrNull() ?: emptyList()
                val list = mutableListOf<ChatItem>()
                if (me != null) list.add(ChatItem(me.id, "الرسائل المحفوظة (Saved Messages)", "سحابة التخزين الشخصية", true))
                chats.forEach { c ->
                    if (c.id != me?.id) {
                        val desc = when (val t = c.type) {
                            is TdApi.ChatTypePrivate -> "محادثة خاصة"
                            is TdApi.ChatTypeBasicGroup -> "مجموعة"
                            is TdApi.ChatTypeSupergroup -> if (t.isChannel) "قناة" else "مجموعة فائقة"
                            else -> "محادثة"
                        }
                        list.add(ChatItem(c.id, c.title.ifEmpty { "بدون اسم" }, desc))
                    }
                }
                availableChats = list
                currentDestination = backupPreferences.getSavedDestination()
            } finally { isLoading = false }
        }
    }

    fun selectDestination(chat: ChatItem) {
        backupPreferences.saveDestination(chat.id, chat.title, chat.typeDescription)
        currentDestination = backupPreferences.getSavedDestination()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupDestinationScreen(onDestinationConfirmed: () -> Unit, viewModel: DestinationViewModel = viewModel()) {
    var showSheet by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("مكان النسخ الاحتياطي", fontWeight = FontWeight.Bold) }) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (viewModel.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (viewModel.currentDestination != null) {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text("المكان المختار للنسخ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("الاسم: ${viewModel.currentDestination?.title}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text("المعرف: ${viewModel.currentDestination?.chatId} (${viewModel.currentDestination?.typeDescription})", style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { showSheet = true }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("تغيير مكان النسخ")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Button(onClick = onDestinationConfirmed, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Text("المتابعة إلى التطبيق")
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("اختر مكان النسخ الاحتياطي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = { showSheet = true }) { Text("تحديد المحادثة أو القناة") }
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("اختر المحادثة أو السحابة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(viewModel.availableChats) { chat ->
                        ListItem(
                            headlineContent = { Text(chat.title, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("${chat.typeDescription} • ID: ${chat.id}") },
                            leadingContent = { Icon(if (chat.isSavedMessages) Icons.Default.Bookmark else Icons.Default.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.selectDestination(chat)
                                showSheet = false
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// --- Home Screen & ViewModel ---
data class HomeDashboardUiState(
    val photosCount: Int = 0, val videosCount: Int = 0, val totalFilesCount: Int = 0, val totalSizeBytes: Long = 0L,
    val lastBackupDateText: String = "لا يوجد نسخ سابق", val failedFilesCount: Int = 0, val activeQueueCount: Int = 0,
    val isQueuePaused: Boolean = false, val isQueueProcessing: Boolean = false, val backupDestination: BackupDestination? = null,
    val actionMessage: String? = null
) {
    val formattedTotalSize: String
        get() {
            val mb = totalSizeBytes / (1024.0 * 1024.0)
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.2f جيجابايت", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f ميجابايت", mb)
                else -> "${totalSizeBytes / 1024} ك.ب"
            }
        }
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val dao = database.backupDao()
    private val queueManager = BackupQueueManager.getInstance(application)
    private val backupPreferences = BackupPreferences.getInstance(application)
    private val mediaScanner = LocalMediaScanner(application)
    private val _actionMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeDashboardUiState> = combine(
        dao.getBackedUpPhotosCountFlow(), dao.getBackedUpVideosCountFlow(), dao.getTotalBackedUpCountFlow(), dao.getTotalBackedUpSizeFlow(),
        dao.getLastBackupTimestampFlow(), dao.getFailedTasksCountFlow(), dao.getActiveQueueCountFlow(), queueManager.isPaused, queueManager.isProcessing,
        backupPreferences.destinationFlow, _actionMessage
    ) { params ->
        HomeDashboardUiState(
            photosCount = params[0] as Int, videosCount = params[1] as Int, totalFilesCount = params[2] as Int, totalSizeBytes = params[3] as Long,
            lastBackupDateText = (params[4] as Long?)?.let { SimpleDateFormat("yyyy/MM/dd - hh:mm a", Locale.getDefault()).format(Date(it)) } ?: "لا يوجد نسخ سابق",
            failedFilesCount = params[5] as Int, activeQueueCount = params[6] as Int, isQueuePaused = params[7] as Boolean, isQueueProcessing = params[8] as Boolean,
            backupDestination = params[9] as BackupDestination?, actionMessage = params[10] as String?
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeDashboardUiState())

    fun startBackupNow() {
        val dest = backupPreferences.getSavedDestination() ?: run { _actionMessage.value = "اختر مكان النسخ أولاً"; return }
        viewModelScope.launch {
            val summary = mediaScanner.scanMedia()
            val unbacked = summary.mediaItems.filter { !dao.isMediaBackedUp(it.id) }
            if (unbacked.isEmpty()) { _actionMessage.value = "جميع الوسائط منسوخة بالفعل"; return@launch }
            val count = queueManager.enqueueMedia(unbacked, dest.chatId)
            queueManager.resumeQueue()
            _actionMessage.value = "تم بدء النسخ لـ $count ملف"
        }
    }

    fun stopBackup() { queueManager.pauseQueue(); _actionMessage.value = "تم إيقاف النسخ مؤقتاً" }
    fun retryFailedFiles() { queueManager.retryFailed(); _actionMessage.value = "جاري إعادة محاولة الملفات الفاشلة..." }
    fun dismissMessage() { _actionMessage.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    state.actionMessage?.let { msg ->
        Snackbar(modifier = Modifier.padding(16.dp), action = { TextButton(onClick = { viewModel.dismissMessage() }) { Text("حسناً") } }) { Text(msg) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("لوحة التحكم", fontWeight = FontWeight.Bold) }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("مكان النسخ في Telegram", style = MaterialTheme.typography.labelMedium)
                    Text(state.backupDestination?.title ?: "لم يتم تحديد مكان للنسخ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (state.backupDestination != null) Text("ID: ${state.backupDestination?.chatId} (${state.backupDestination?.typeDescription})", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("حالة النسخ:", fontWeight = FontWeight.SemiBold)
                        Text(if (state.isQueueProcessing) "جاري المزامنة..." else if (state.isQueuePaused && state.activeQueueCount > 0) "متوقف مؤقتاً" else "جاهز ومستقر", fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("آخر نسخة احتياطية:")
                        Text(state.lastBackupDateText, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Text("إحصائيات الوسائط", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("الصور المنسوخة", "${state.photosCount}", modifier = Modifier.weight(1f))
                MetricCard("الفيديوهات المنسوخة", "${state.videosCount}", modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("إجمالي الملفات", "${state.totalFilesCount}", modifier = Modifier.weight(1f))
                MetricCard("إجمالي الحجم", state.formattedTotalSize, modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("في الطابور", "${state.activeQueueCount}", modifier = Modifier.weight(1f))
                MetricCard("الملفات الفاشلة", "${state.failedFilesCount}", modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = { viewModel.startBackupNow() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("بدء النسخ الاحتياطي الآن")
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.stopBackup() }, modifier = Modifier.weight(1f).height(48.dp)) { Text("إيقاف النسخ") }
                FilledTonalButton(onClick = { viewModel.retryFailedFiles() }, enabled = state.failedFilesCount > 0, modifier = Modifier.weight(1f).height(48.dp)) { Text("إعادة الفاشلة") }
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

// --- Backup Screen & ViewModel ---
enum class MediaFilterType { ALL, PHOTOS_ONLY, VIDEOS_ONLY }
data class BackupScreenState(
    val isLoading: Boolean = false, val hasPermission: Boolean = false, val summary: MediaSummary = MediaSummary(),
    val selectedItemIds: Set<Long> = emptySet(), val backedUpMediaIds: Set<Long> = emptySet(),
    val filterType: MediaFilterType = MediaFilterType.ALL, val messageNotice: String? = null
)

class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val mediaScanner = LocalMediaScanner(application)
    private val queueManager = BackupQueueManager.getInstance(application)
    private val backupPreferences = BackupPreferences.getInstance(application)
    private val database = AppDatabase.getInstance(application)

    private val _uiState = MutableStateFlow(BackupScreenState())
    val uiState: StateFlow<BackupScreenState> = _uiState.asStateFlow()

    val queueItems: StateFlow<List<BackupQueueEntity>> = database.backupDao().getAllQueueItemsFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val isQueuePaused: StateFlow<Boolean> = queueManager.isPaused

    fun onPermissionResult(granted: Boolean) {
        _uiState.value = _uiState.value.copy(hasPermission = granted)
        if (granted) loadMedia()
    }

    fun setFilter(filter: MediaFilterType) { _uiState.value = _uiState.value.copy(filterType = filter) }

    fun loadMedia() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            val sum = mediaScanner.scanMedia()
            val backedIds = mutableSetOf<Long>()
            sum.mediaItems.forEach { if (database.backupDao().isMediaBackedUp(it.id)) backedIds.add(it.id) }
            val unbacked = sum.mediaItems.filter { !backedIds.contains(it.id) }.map { it.id }.toSet()
            _uiState.value = _uiState.value.copy(isLoading = false, summary = sum, backedUpMediaIds = backedIds, selectedItemIds = unbacked)
        }
    }

    fun toggleSelection(id: Long) {
        if (_uiState.value.backedUpMediaIds.contains(id)) return
        val current = _uiState.value.selectedItemIds.toMutableSet()
        if (current.contains(id)) current.remove(id) else current.add(id)
        _uiState.value = _uiState.value.copy(selectedItemIds = current)
    }

    fun selectAll() {
        val eligible = getFilteredItems().filter { !_uiState.value.backedUpMediaIds.contains(it.id) }.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedItemIds = eligible)
    }

    fun deselectAll() { _uiState.value = _uiState.value.copy(selectedItemIds = emptySet()) }

    fun getFilteredItems(): List<LocalMediaItem> = when (_uiState.value.filterType) {
        MediaFilterType.ALL -> _uiState.value.summary.mediaItems
        MediaFilterType.PHOTOS_ONLY -> _uiState.value.summary.mediaItems.filter { !it.isVideo }
        MediaFilterType.VIDEOS_ONLY -> _uiState.value.summary.mediaItems.filter { it.isVideo }
    }

    fun startBackupQueue() {
        val dest = backupPreferences.getSavedDestination() ?: run { _uiState.value = _uiState.value.copy(messageNotice = "اختر مكان النسخ أولاً"); return }
        val selected = _uiState.value.summary.mediaItems.filter { _uiState.value.selectedItemIds.contains(it.id) }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val added = queueManager.enqueueMedia(selected, dest.chatId)
            _uiState.value = _uiState.value.copy(messageNotice = "تمت إضافة $added ملف لطابور الرفع", selectedItemIds = emptySet())
        }
    }

    fun pauseQueue() = queueManager.pauseQueue()
    fun resumeQueue() = queueManager.resumeQueue()
    fun retryFailed() = queueManager.retryFailed()
    fun retryTask(id: Long) = queueManager.retryTask(id)
    fun cancelTask(id: Long) = queueManager.cancelTask(id)
    fun dismissNotice() { _uiState.value = _uiState.value.copy(messageNotice = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(viewModel: BackupViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val queueItems by viewModel.queueItems.collectAsState()
    val isPaused by viewModel.isQueuePaused.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onPermissionResult(it.values.any { g -> g })
    }

    LaunchedEffect(Unit) {
        val perms = LocalMediaScanner.getRequiredPermissions()
        val granted = perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        viewModel.onPermissionResult(granted)
    }

    state.messageNotice?.let {
        Snackbar(modifier = Modifier.padding(16.dp), action = { TextButton(onClick = { viewModel.dismissNotice() }) { Text("حسناً") } }) { Text(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("النسخ الاحتياطي", fontWeight = FontWeight.Bold) }, actions = {
                if (selectedTab == 0 && state.hasPermission) {
                    IconButton(onClick = { if (state.selectedItemIds.isNotEmpty()) viewModel.deselectAll() else viewModel.selectAll() }) {
                        Icon(if (state.selectedItemIds.isNotEmpty()) Icons.Default.Deselect else Icons.Default.SelectAll, contentDescription = null)
                    }
                }
            })
        },
        bottomBar = {
            if (selectedTab == 0 && state.hasPermission && state.selectedItemIds.isNotEmpty()) {
                Surface(tonalElevation = 8.dp) {
                    Button(onClick = { viewModel.startBackupQueue(); selectedTab = 1 }, modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp)) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("بدء نسخ (${state.selectedItemIds.size}) ملف إلى Telegram")
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("الوسائط") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = {
                    val count = queueItems.count { it.status == BackupTaskStatus.PENDING || it.status == BackupTaskStatus.UPLOADING }
                    Text(if (count > 0) "طابور الرفع ($count)" else "طابور الرفع")
                })
            }

            if (!state.hasPermission) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { launcher.launch(LocalMediaScanner.getRequiredPermissions()) }) { Text("منح صلاحية الوصول للوسائط") }
                }
            } else if (selectedTab == 0) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = state.filterType == MediaFilterType.ALL, onClick = { viewModel.setFilter(MediaFilterType.ALL) }, label = { Text("الكل") })
                        FilterChip(selected = state.filterType == MediaFilterType.PHOTOS_ONLY, onClick = { viewModel.setFilter(MediaFilterType.PHOTOS_ONLY) }, label = { Text("صور (${state.summary.photoCount})") })
                        FilterChip(selected = state.filterType == MediaFilterType.VIDEOS_ONLY, onClick = { viewModel.setFilter(MediaFilterType.VIDEOS_ONLY) }, label = { Text("فيديوهات (${state.summary.videoCount})") })
                    }
                    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(viewModel.getFilteredItems(), key = { it.id }) { item ->
                            val isBacked = state.backedUpMediaIds.contains(item.id)
                            val isSel = state.selectedItemIds.contains(item.id)
                            Box(
                                modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                                    .then(if (isSel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                                    .clickable(enabled = !isBacked) { viewModel.toggleSelection(item.id) }
                            ) {
                                AsyncImage(model = ImageRequest.Builder(context).data(item.uri).crossfade(true).size(256).build(), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                if (item.isVideo) {
                                    Box(modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color.Black.copy(0.7f), RoundedCornerShape(4.dp)).padding(2.dp)) {
                                        Text("${item.size / (1024 * 1024)} MB", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (isBacked) {
                                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.5f)), contentAlignment = Alignment.Center) {
                                        Text("منسوخ", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                } else if (isSel) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (isPaused) FilledTonalButton(onClick = { viewModel.resumeQueue() }) { Text("استئناف") }
                        else OutlinedButton(onClick = { viewModel.pauseQueue() }) { Text("إيقاف مؤقت") }
                        if (queueItems.any { it.status == BackupTaskStatus.FAILED }) FilledTonalButton(onClick = { viewModel.retryFailed() }) { Text("إعادة الفاشلة") }
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(queueItems, key = { it.id }) { task ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (task.mimeType.startsWith("video/")) Icons.Default.Videocam else Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(task.fileName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(when (task.status) {
                                                BackupTaskStatus.PENDING -> "في الانتظار..."
                                                BackupTaskStatus.UPLOADING -> "جاري الرفع: ${task.progress}%"
                                                BackupTaskStatus.COMPLETED -> "تم النسخ بنجاح"
                                                BackupTaskStatus.ALREADY_EXISTS -> "موجود مسبقاً (تم التخطي)"
                                                BackupTaskStatus.FAILED -> "فشل: ${task.errorMessage ?: ""}"
                                                BackupTaskStatus.CANCELLED -> "تم الإلغاء"
                                            }, style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (task.status == BackupTaskStatus.FAILED) {
                                            IconButton(onClick = { viewModel.retryTask(task.id) }) { Icon(Icons.Default.Refresh, contentDescription = null) }
                                        }
                                    }
                                    if (task.status == BackupTaskStatus.UPLOADING) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LinearProgressIndicator(progress = { task.progress / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Files Screen & ViewModel ---
sealed interface RestoreStatus {
    data object Idle : RestoreStatus
    data class Downloading(val progress: Int, val name: String) : RestoreStatus
    data class Success(val name: String) : RestoreStatus
    data class Error(val message: String) : RestoreStatus
}

class FilesViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val telegramManager = TelegramManager.getInstance(application)

    val files: StateFlow<List<BackedUpFileEntity>> = database.backupDao().getAllBackedUpFiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var restoreStatus by mutableStateOf<RestoreStatus>(RestoreStatus.Idle)

    fun restoreFile(file: BackedUpFileEntity) {
        viewModelScope.launch {
            restoreStatus = RestoreStatus.Downloading(0, file.fileName)
            val download = telegramManager.downloadPhotoFromMessage(file.telegramChatId, file.telegramMessageId) { p ->
                restoreStatus = RestoreStatus.Downloading(p, file.fileName)
            }
            download.onSuccess { f ->
                MediaStoreSaver.saveImageToGallery(getApplication(), f, "Restored_${file.fileName}", file.mimeType)
                restoreStatus = RestoreStatus.Success(file.fileName)
            }.onFailure { err ->
                restoreStatus = RestoreStatus.Error(err.localizedMessage ?: "فشل التنزيل")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(viewModel: FilesViewModel = viewModel()) {
    val files by viewModel.files.collectAsState()

    when (val s = viewModel.restoreStatus) {
        is RestoreStatus.Downloading -> {
            AlertDialog(onDismissRequest = {}, title = { Text("جاري الاستعادة") }, text = {
                Column {
                    Text("جاري تنزيل: ${s.name}")
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }, confirmButton = {})
        }
        is RestoreStatus.Success -> {
            AlertDialog(onDismissRequest = { viewModel.restoreStatus = RestoreStatus.Idle }, title = { Text("تمت الاستعادة بنجاح!") }, text = {
                Text("تم حفظ ${s.name} في مجلد Pictures/TelegramBackup بنجاح.")
            }, confirmButton = { Button(onClick = { viewModel.restoreStatus = RestoreStatus.Idle }) { Text("حسناً") } })
        }
        is RestoreStatus.Error -> {
            AlertDialog(onDismissRequest = { viewModel.restoreStatus = RestoreStatus.Idle }, title = { Text("خطأ", color = MaterialTheme.colorScheme.error) }, text = {
                Text(s.message)
            }, confirmButton = { Button(onClick = { viewModel.restoreStatus = RestoreStatus.Idle }) { Text("إغلاق") } })
        }
        RestoreStatus.Idle -> Unit
    }

    Scaffold(topBar = { TopAppBar(title = { Text("الملفات المنسوخة", fontWeight = FontWeight.Bold) }) }) { padding ->
        if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("لا توجد ملفات منسوخة حتى الآن") }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { it.id }) { file ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (file.mimeType.startsWith("video/")) Icons.Default.Videocam else Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.fileName, fontWeight = FontWeight.SemiBold)
                                Text("${file.fileSize / (1024 * 1024)} MB • ${SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(file.uploadTimestamp))}", style = MaterialTheme.typography.bodySmall)
                            }
                            FilledTonalButton(onClick = { viewModel.restoreFile(file) }) { Text("استعادة") }
                        }
                    }
                }
            }
        }
    }
}

// --- Settings Screen & ViewModel ---
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val autoPrefs = AutoBackupPreferences.getInstance(application)
    private val telegramManager = TelegramManager.getInstance(application)
    val autoBackupSettings: StateFlow<AutoBackupSettings> = autoPrefs.settingsFlow

    fun toggleAutoBackup(enabled: Boolean) { autoPrefs.updateSettings(isEnabled = enabled); applyConstraints() }
    fun toggleWifiOnly(wifiOnly: Boolean) { autoPrefs.updateSettings(isWifiOnly = wifiOnly); applyConstraints() }
    fun toggleRequiresCharging(charging: Boolean) { autoPrefs.updateSettings(requiresCharging = charging); applyConstraints() }
    fun toggleBatteryNotLow(notLow: Boolean) { autoPrefs.updateSettings(requiresBatteryNotLow = notLow); applyConstraints() }
    private fun applyConstraints() { WorkManagerHelper.scheduleOrUpdateAutoBackup(getApplication(), autoPrefs.getSettings()) }
    fun logout() { autoPrefs.updateSettings(isEnabled = false); applyConstraints(); telegramManager.logout() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onLogoutSuccess: () -> Unit = {}, viewModel: SettingsViewModel = viewModel()) {
    val autoSettings by viewModel.autoBackupSettings.collectAsState()
    var showLogout by remember { mutableStateOf(false) }

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("تسجيل الخروج") },
            text = { Text("هل أنت متأكد من تسجيل الخروج من حساب Telegram؟") },
            confirmButton = { TextButton(onClick = { showLogout = false; viewModel.logout(); onLogoutSuccess() }) { Text("خروج", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showLogout = false }) { Text("إلغاء") } }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("الإعدادات", fontWeight = FontWeight.Bold) }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("النسخ الاحتياطي التلقائي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            ListItem(headlineContent = { Text("تفعيل النسخ التلقائي في الخلفية") }, trailingContent = { Switch(checked = autoSettings.isEnabled, onCheckedChange = { viewModel.toggleAutoBackup(it) }) })
            HorizontalDivider()
            Text("شروط المزامنة", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            ListItem(headlineContent = { Text("النسخ عبر Wi-Fi فقط") }, trailingContent = { Switch(checked = autoSettings.isWifiOnly, enabled = autoSettings.isEnabled, onCheckedChange = { viewModel.toggleWifiOnly(it) }) })
            ListItem(headlineContent = { Text("أثناء الشحن فقط") }, trailingContent = { Switch(checked = autoSettings.requiresCharging, enabled = autoSettings.isEnabled, onCheckedChange = { viewModel.toggleRequiresCharging(it) }) })
            ListItem(headlineContent = { Text("تجنب النسخ عند انخفاض البطارية") }, trailingContent = { Switch(checked = autoSettings.requiresBatteryNotLow, enabled = autoSettings.isEnabled, onCheckedChange = { viewModel.toggleBatteryNotLow(it) }) })
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("تسجيل الخروج", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                trailingContent = { OutlinedButton(onClick = { showLogout = true }) { Text("خروج") } }
            )
        }
    }
}

// --- Main Container Screen ---
@Composable
fun MainContainerScreen(onLogoutSuccess: () -> Unit = {}) {
    val bottomNavController = rememberNavController()
    val items = listOf(BottomNavItem.Home, BottomNavItem.Backup, BottomNavItem.Files, BottomNavItem.Settings)

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                items.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.title) },
                        label = { Text(item.title) },
                        selected = currentRoute == item.route,
                        onClick = {
                            if (currentRoute != item.route) {
                                bottomNavController.navigate(item.route) {
                                    popUpTo(bottomNavController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(navController = bottomNavController, startDestination = BottomNavItem.Home.route, modifier = Modifier.padding(innerPadding)) {
            composable(BottomNavItem.Home.route) { HomeScreen() }
            composable(BottomNavItem.Backup.route) { BackupScreen() }
            composable(BottomNavItem.Files.route) { FilesScreen() }
            composable(BottomNavItem.Settings.route) { SettingsScreen(onLogoutSuccess = onLogoutSuccess) }
        }
    }
}

// ==========================================
// 8. نقطة البداية وتشغيل التطبيق (Activity)
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TelegramBackupTheme {
                val navController = rememberNavController()
                val context = LocalContext.current
                val telegramManager = remember { TelegramManager.getInstance(context) }
                val backupPreferences = remember { BackupPreferences.getInstance(context) }
                val authState by telegramManager.authState.collectAsState()
                val destination by backupPreferences.destinationFlow.collectAsState()

                val startDest = when {
                    authState is TelegramAuthState.Authenticated && destination != null -> Screen.MainContainer.route
                    authState is TelegramAuthState.Authenticated -> Screen.SelectDestination.route
                    else -> Screen.Welcome.route
                }

                NavHost(navController = navController, startDestination = startDest) {
                    composable(Screen.Welcome.route) { WelcomeScreen(onGetStartedClick = { navController.navigate(Screen.Auth.route) }) }
                    composable(Screen.Auth.route) {
                        AuthScreen(onAuthSuccess = {
                            navController.navigate(Screen.SelectDestination.route) { popUpTo(Screen.Welcome.route) { inclusive = true } }
                        })
                    }
                    composable(Screen.SelectDestination.route) {
                        BackupDestinationScreen(onDestinationConfirmed = {
                            navController.navigate(Screen.MainContainer.route) { popUpTo(Screen.SelectDestination.route) { inclusive = true } }
                        })
                    }
                    composable(Screen.MainContainer.route) {
                        MainContainerScreen(onLogoutSuccess = {
                            backupPreferences.clearDestination()
                            navController.navigate(Screen.Auth.route) { popUpTo(0) { inclusive = true } }
                        })
                    }
                }
            }
        }
    }
}
