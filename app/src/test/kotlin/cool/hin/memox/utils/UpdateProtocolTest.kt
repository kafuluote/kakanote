package cool.hin.memox.utils

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateProtocolTest {
    private fun response(
        available: Boolean = true,
        force: Boolean = false,
        type: String = "minor",
    ) = JSONObject()
        .put("currentVersion", "1.2.0")
        .put("version", "1.3.0")
        .put("versionCode", 13000)
        .put("changelog", "更新说明")
        .put("releaseUrl", "https://example.com/releases")
        .put("updateAvailable", available)
        .put("forceUpdate", force)
        .put("updateType", type)

    @Test fun normalizesAndroidBuildSuffixes() {
        for (name in listOf("1.2.0", "1.2.0-DEBUG", "1.2.0-BETA")) {
            assertEquals("1.2.0", UpdateProtocol.currentVersion(name))
        }
        for (name in listOf("1.2", "v1.2.0", "1.02.0", "1.2.0-rc1")) {
            assertTrue(runCatching { UpdateProtocol.currentVersion(name) }.isFailure)
        }
    }

    @Test fun respectsServerPolicyAndDownloadLink() {
        val minor = UpdateProtocol.parse(response().toString(), "1.2.0")
        assertTrue(minor.updateAvailable)
        assertFalse(minor.required)
        val major = UpdateProtocol.parse(
            response(force = true, type = "major")
                .put("downloadUrl", "https://example.com/memox.apk").toString(),
            "1.2.0",
        )
        assertTrue(major.required)
        assertEquals("https://example.com/memox.apk", major.downloadUrl)
        assertEquals(major, UpdateProtocol.parse(UpdateProtocol.encode(major, "1.2.0"), "1.2.0"))
    }

    @Test fun noUpdateDoesNotFallBackToVersionCodeComparison() {
        val info = UpdateProtocol.parse(
            response(available = false, type = "none").put("versionCode", Int.MAX_VALUE).toString(),
            "1.2.0",
        )
        assertFalse(info.updateAvailable)
        assertFalse(info.required)
    }

    @Test fun rejectsWrongClientInvalidFlagsAndUnsafeLinks() {
        val invalid = listOf(
            response().put("currentVersion", "1.1.0"),
            response().apply { remove("forceUpdate") },
            response().put("forceUpdate", "true"),
            response(available = false, force = true, type = "none"),
            response(type = "none"),
            response().put("releaseUrl", "javascript:alert(1)"),
            response().put("downloadUrl", "file:///sdcard/app.apk"),
            response().put("versionCode", -1),
            response().put("versionCode", 2147483648L),
        )
        invalid.forEach { json ->
            assertTrue(json.toString(), runCatching { UpdateProtocol.parse(json.toString(), "1.2.0") }.isFailure)
        }
    }
}
