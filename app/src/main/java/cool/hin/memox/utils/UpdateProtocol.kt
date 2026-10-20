package cool.hin.memox.utils

import java.net.URI
import org.json.JSONObject

/** 与 GoFrame 更新检查接口保持一致，不在客户端重新推导强制更新策略。 */
internal object UpdateProtocol {
    private val versionPattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

    fun currentVersion(versionName: String): String {
        val version = versionName.removeSuffix("-DEBUG").removeSuffix("-BETA")
        require(versionPattern.matches(version)) { "Invalid app version" }
        return version
    }

    fun parse(text: String, currentVersion: String): UpdateChecker.UpdateInfo {
        val json = JSONObject(text)
        require(json.getString("currentVersion") == currentVersion) { "Mismatched client version" }
        val version = json.getString("version")
        require(versionPattern.matches(version)) { "Invalid release version" }
        val code = json.getLong("versionCode")
        require(code in 1..Int.MAX_VALUE.toLong()) { "Invalid versionCode" }
        val available = json.get("updateAvailable") as? Boolean ?: error("Missing updateAvailable")
        val forced = json.get("forceUpdate") as? Boolean ?: error("Missing forceUpdate")
        val type = json.getString("updateType")
        require(
            if (available) type == "major" || type == "minor" else type == "none" && !forced
        ) { "Inconsistent update decision" }
        return UpdateChecker.UpdateInfo(
            version = version,
            versionCode = code.toInt(),
            changelog = json.optString("changelog", ""),
            releaseUrl = webURL(json.getString("releaseUrl")),
            downloadUrl =
                json.optString("downloadUrl", "").takeIf { it.isNotBlank() }?.let(::webURL),
            updateAvailable = available,
            forceUpdate = forced,
            updateType = type,
        )
    }

    fun encode(info: UpdateChecker.UpdateInfo, currentVersion: String): String =
        JSONObject()
            .put("currentVersion", currentVersion)
            .put("version", info.version)
            .put("versionCode", info.versionCode)
            .put("changelog", info.changelog)
            .put("releaseUrl", info.releaseUrl)
            .put("downloadUrl", info.downloadUrl ?: "")
            .put("updateAvailable", info.updateAvailable)
            .put("forceUpdate", info.forceUpdate)
            .put("updateType", info.updateType)
            .toString()

    private fun webURL(value: String): String {
        val uri = URI(value)
        require(
            (uri.scheme == "https" || uri.scheme == "http") &&
                !uri.host.isNullOrBlank() && uri.userInfo == null
        ) { "Invalid update URL" }
        return value
    }
}
