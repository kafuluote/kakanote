package cool.hin.memox.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import cool.hin.memox.BuildConfig
import cool.hin.memox.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 可选更新保留红点提示，强制更新在所有 LockedActivity 中展示不可跳过的弹窗。 */
object UpdateChecker {

    data class UpdateInfo(
        val version: String,
        val versionCode: Int,
        val changelog: String,
        val releaseUrl: String?,
        val downloadUrl: String? = null,
        val updateAvailable: Boolean = BuildConfig.VERSION_CODE < versionCode,
        val forceUpdate: Boolean = false,
        val updateType: String = "none",
    ) {
        val required: Boolean get() = updateAvailable && forceUpdate
    }

    val state = MutableLiveData<UpdateInfo?>()

    private const val CACHE_NAME = "version_update"
    private const val CACHE_RESPONSE = "required_response"
    private const val CACHE_BUILD = "installed_build"
    private const val RETRY_INTERVAL_MS = 30_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inFlight: Deferred<UpdateInfo?>? = null
    private var lastAutomaticCheck: Long? = null
    private var cacheRestored = false

    fun isNewer(info: UpdateInfo): Boolean = info.updateAvailable

    fun shouldShow(info: UpdateInfo): Boolean = info.updateAvailable

    /** 在基类中注册，确保通知、分享和桌面快捷方式入口同样执行强制更新。 */
    fun observe(activity: FragmentActivity) {
        if (!cacheRestored) {
            restoreRequiredUpdate(activity.applicationContext)
            cacheRestored = true
        }
        state.observe(activity) { info ->
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                syncRequiredDialog(activity, info)
            }
        }
    }

    fun onActivityResumed(activity: FragmentActivity) {
        syncRequiredDialog(activity, state.value)
        checkForUpdates(activity.applicationContext)
    }

    fun checkForUpdates(context: Context) {
        // 可选更新和“无更新”每个进程检查一次；强制更新允许重查以撤销错误发布。
        if (state.value?.required == false) return
        val now = SystemClock.elapsedRealtime()
        if (lastAutomaticCheck?.let { now - it < RETRY_INTERVAL_MS } == true) return
        lastAutomaticCheck = now
        request(context.applicationContext)
    }

    private fun request(context: Context): Deferred<UpdateInfo?> {
        inFlight?.takeIf { it.isActive }?.let { return it }
        return scope.async {
            val info = withContext(Dispatchers.IO) {
                runCatching { fetch(context.getString(R.string.update_check_url)) }.getOrNull()
            }
            // 网络失败不能清除已经确认的强制更新状态。
            if (info != null) rememberResult(context, info)
            info
        }.also { inFlight = it }
    }

    internal fun fetch(endpoint: String): UpdateInfo {
        val currentVersion = UpdateProtocol.currentVersion(BuildConfig.VERSION_NAME)
        val url = Uri.parse(endpoint).buildUpon()
            .appendQueryParameter("currentVersion", currentVersion)
            .build()
        val conn = (URL(url.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "memoX/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            check(conn.responseCode == HttpURLConnection.HTTP_OK) { "Update check failed" }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            UpdateProtocol.parse(text, currentVersion)
        } finally {
            conn.disconnect()
        }
    }

    internal fun rememberResult(context: Context, info: UpdateInfo) {
        val preferences = context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
        val editor = preferences.edit()
        if (info.required) {
            editor.putString(CACHE_BUILD, installedBuild())
                .putString(
                    CACHE_RESPONSE,
                    UpdateProtocol.encode(info, UpdateProtocol.currentVersion(BuildConfig.VERSION_NAME)),
                )
        } else {
            editor.clear()
        }
        editor.apply()
        state.value = info
    }

    internal fun restoreRequiredUpdate(context: Context) {
        val preferences = context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
        if (preferences.getString(CACHE_BUILD, null) != installedBuild()) {
            preferences.edit().clear().apply()
            return
        }
        val info = preferences.getString(CACHE_RESPONSE, null)?.let { text ->
            runCatching {
                UpdateProtocol.parse(text, UpdateProtocol.currentVersion(BuildConfig.VERSION_NAME))
            }.getOrNull()
        }
        if (info?.required == true) {
            state.value = info
        } else {
            preferences.edit().clear().apply()
        }
    }

    private fun installedBuild(): String = "${BuildConfig.VERSION_NAME}:${BuildConfig.VERSION_CODE}"

    fun showUpdateDialog(activity: FragmentActivity, info: UpdateInfo) {
        if (!info.updateAvailable || activity.isFinishing || activity.isDestroyed) return
        val manager = activity.supportFragmentManager
        if (manager.isStateSaved) return
        val existing = manager.findFragmentByTag(UpdateDialogFragment.TAG) as? UpdateDialogFragment
        if (existing?.info == info) return
        if (existing != null) {
            existing.dismiss()
            manager.executePendingTransactions()
        }
        UpdateDialogFragment.newInstance(info).showNow(manager, UpdateDialogFragment.TAG)
    }

    private fun syncRequiredDialog(activity: FragmentActivity, info: UpdateInfo?) {
        if (info?.required == true) {
            showUpdateDialog(activity, info)
        } else if (!activity.supportFragmentManager.isStateSaved) {
            val dialog = activity.supportFragmentManager.findFragmentByTag(UpdateDialogFragment.TAG)
                as? UpdateDialogFragment
            if (dialog?.info?.required == true) {
                dialog.dismiss()
                activity.supportFragmentManager.executePendingTransactions()
            }
        }
    }

    /** null 表示检查失败，与成功返回“无更新”区分。并发检查共用同一请求。 */
    fun checkNow(context: Context, onResult: (UpdateInfo?) -> Unit) {
        val request = request(context.applicationContext)
        scope.launch { onResult(request.await()) }
    }

    fun openUpdate(context: Context, info: UpdateInfo): Boolean =
        openRelease(context, info.downloadUrl ?: info.releaseUrl)

    fun openRelease(context: Context, releaseUrl: String?): Boolean =
        runCatching {
            val url = releaseUrl ?: context.getString(R.string.update_release_url)
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.isSuccess
}
