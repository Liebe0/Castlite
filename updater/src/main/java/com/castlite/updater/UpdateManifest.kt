package com.castlite.updater

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Which app is asking: each one downloads its own APK from the same release. */
enum class AppTarget { PHONE, TV }

/** One downloadable APK in a release. */
@Serializable
data class UpdateAsset(
    val url: String,
    /** Lower-case hex SHA-256 of the APK; the download is rejected if it doesn't match. */
    val sha256: String,
    val size: Long,
)

/**
 * `update.json`, published by CI with every release:
 * ```
 * {"versionCode":142,"versionName":"1.1.42","notes":"…","commit":"abc1234",
 *  "phone":{"url":"https://…/CastLite-phone.apk","sha256":"…","size":123},
 *  "tv":{"url":"https://…/CastLite-TV.apk","sha256":"…","size":456}}
 * ```
 */
@Serializable
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val notes: String = "",
    val commit: String = "",
    val publishedAt: String = "",
    val phone: UpdateAsset? = null,
    val tv: UpdateAsset? = null,
) {
    fun assetFor(target: AppTarget): UpdateAsset? = when (target) {
        AppTarget.PHONE -> phone
        AppTarget.TV -> tv
    }

    /** True if this release is newer than [installedVersionCode] and has an APK for [target]. */
    fun isUpdateFor(target: AppTarget, installedVersionCode: Long): Boolean =
        versionCode > installedVersionCode && assetFor(target) != null

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): UpdateManifest = json.decodeFromString(serializer(), text)
    }
}
