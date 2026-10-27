package com.example.livevoiceagent.tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.livevoiceagent.model.SearchSource
import com.example.livevoiceagent.network.LinkupSearchClient
import com.example.livevoiceagent.network.LinkupSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class DeviceLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val addressLine: String,
    val cityOrDistrict: String,
    val provider: String
)

data class LocationToolExecutionResult(
    val toolOutputJson: String,
    val actionLogText: String,
    val mapActionSource: SearchSource? = null,
    val linkupResult: LinkupSearchResult? = null,
    val autoLaunchedNavigation: Boolean = false,
    val requestedHangupReason: String? = null
)

class LocationMapsToolManager(
    private val context: Context,
    private val linkupClient: LinkupSearchClient
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    var onAutoLaunchGoogleMaps: ((primaryUri: String, fallbackWebUrl: String, destinationLabel: String) -> Unit)? = null

    @Volatile
    var onRequestEndLiveSession: ((reason: String) -> Unit)? = null

    @Volatile
    private var cachedGeocodeKey: String = ""
    @Volatile
    private var cachedAddressPair: Pair<String, String>? = null
    @Volatile
    private var cachedGeocodeWallMs: Long = 0L

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun getCurrentLocationSnapshot(allowBlockingGeocode: Boolean = true): DeviceLocationSnapshot? {
        if (!hasLocationPermission()) return null
        return runCatching {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var bestLocation: Location? = null
            for (provider in providers) {
                val loc = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() ?: continue
                if (bestLocation == null) {
                    bestLocation = loc
                } else {
                    val timeDelta = loc.time - bestLocation.time
                    val isSignificantlyNewer = timeDelta > 120_000L
                    val isNewer = timeDelta > 0L
                    val accuracyDelta = loc.accuracy - bestLocation.accuracy
                    if (isSignificantlyNewer || (isNewer && accuracyDelta <= 50f)) {
                        bestLocation = loc
                    }
                }
            }
            val finalLoc = bestLocation ?: return null
            val (addressLine, cityOrDistrict) = reverseGeocodeCached(
                lat = finalLoc.latitude,
                lon = finalLoc.longitude,
                allowBlocking = allowBlockingGeocode
            )
            DeviceLocationSnapshot(
                latitude = finalLoc.latitude,
                longitude = finalLoc.longitude,
                accuracyMeters = finalLoc.accuracy,
                addressLine = addressLine,
                cityOrDistrict = cityOrDistrict,
                provider = finalLoc.provider ?: "gps"
            )
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocodeCached(lat: Double, lon: Double, allowBlocking: Boolean): Pair<String, String> {
        val key = String.format(Locale.US, "%.3f,%.3f", lat, lon)
        val now = System.currentTimeMillis()
        val existing = cachedAddressPair
        if (existing != null && cachedGeocodeKey == key && (now - cachedGeocodeWallMs) < 300_000L) {
            return existing
        }
        val coords = String.format(Locale.US, "%.5f, %.5f", lat, lon)
        if (!allowBlocking) {
            return existing ?: (coords to coords)
        }
        return runCatching {
            if (!Geocoder.isPresent()) {
                return@runCatching coords to coords
            }
            val geocoder = Geocoder(context, Locale.TAIWAN)
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            val first = addresses?.firstOrNull()
            if (first != null) {
                val fullLine = (0..first.maxAddressLineIndex)
                    .mapNotNull { first.getAddressLine(it) }
                    .joinToString(" ")
                    .ifBlank {
                        listOfNotNull(first.adminArea, first.locality, first.subLocality, first.thoroughfare)
                            .joinToString("")
                    }
                val cityDistrict = listOfNotNull(
                    first.countryName,
                    first.adminArea,
                    first.locality ?: first.subAdminArea,
                    first.subLocality
                ).distinct().joinToString(" ")
                val resolved = (fullLine.ifBlank { coords }) to (cityDistrict.ifBlank { fullLine.ifBlank { coords } })
                cachedGeocodeKey = key
                cachedAddressPair = resolved
                cachedGeocodeWallMs = System.currentTimeMillis()
                resolved
            } else {
                coords to coords
            }
        }.getOrElse {
            existing ?: (coords to coords)
        }
    }

    @Suppress("DEPRECATION")
    private fun forwardGeocodePlace(query: String): Pair<Double, Double>? {
        return runCatching {
            if (!Geocoder.isPresent()) return null
            val geocoder = Geocoder(context, Locale.TAIWAN)
            val list = geocoder.getFromLocationName(query, 1)
            val first = list?.firstOrNull() ?: return null
            first.latitude to first.longitude
        }.getOrNull()
    }

    fun buildCurrentLocationContextString(allowBlockingGeocode: Boolean = false): String {
        return buildLocationStatusLine(allowBlockingGeocode) + "\n" + NAVIGATION_RULES
    }

    private fun buildLocationStatusLine(allowBlockingGeocode: Boolean): String {
        if (!hasLocationPermission()) {
            return "【使用者目前位置狀態】：尚未授予手機 GPS 定位權限（若使用者詢問附近店家或路線，可提醒授權定位或詢問所在地區）。"
        }
        val snap = getCurrentLocationSnapshot(allowBlockingGeocode = allowBlockingGeocode)
            ?: return "【使用者目前位置狀態】：GPS 定位權限已開啟，但暫無快取座標（若室內訊號較弱，可請使用者口頭告知所在區域或直接呼叫定位工具）。"
        val coords = String.format(Locale.US, "%.5f, %.5f", snap.latitude, snap.longitude)
        return "【使用者手機即時 GPS 位置】：${snap.addressLine}（座標：$coords，精準度 ±${snap.accuracyMeters.toInt()}m）。"
    }

    suspend fun executeLocationOrMapTool(
        toolName: String,
        argsJson: JSONObject,
        linkupApiKey: String,
        linkupDepth: String,
        googleMapsApiKey: String = ""
    ): LocationToolExecutionResult {
        return runCatching {
            when (toolName) {
                "get_user_current_location" -> executeGetCurrentLocation()
                "search_nearby_places_with_linkup" -> executeSearchNearbyPlaces(
                    argsJson = argsJson,
                    linkupApiKey = linkupApiKey,
                    linkupDepth = linkupDepth,
                    googleMapsApiKey = googleMapsApiKey
                )
                "open_google_maps_navigation" -> executeOpenGoogleMapsNavigation(
                    argsJson = argsJson,
                    googleMapsApiKey = googleMapsApiKey
                )
                "end_live_voice_session" -> executeEndLiveVoiceSession(argsJson)
                else -> LocationToolExecutionResult(
                    toolOutputJson = JSONObject().put("status", "error")
                        .put("message", "未知的定位/地圖工具: $toolName").toString(),
                    actionLogText = "⚠️ 未知地圖工具: $toolName"
                )
            }
        }.getOrElse { err ->
            LocationToolExecutionResult(
                toolOutputJson = JSONObject().put("status", "error")
                    .put("message", "定位或地圖工具執行失敗: ${err.message}").toString(),
                actionLogText = "⚠️ 地圖工具錯誤: ${err.message}"
            )
        }
    }

    private fun executeEndLiveVoiceSession(argsJson: JSONObject): LocationToolExecutionResult {
        val reason = argsJson.optString("reason", "user_goodbye").ifBlank { "user_goodbye" }
        val farewell = argsJson.optString("farewell_spoken", "好的，有需要隨時再叫我，掰掰！")
        onRequestEndLiveSession?.invoke(reason)
        val out = JSONObject().apply {
            put("status", "success")
            put("reason", reason)
            put("farewell_spoken", farewell)
            put("message", if (reason.contains("bystander") || reason.contains("false")) {
                "偵測為旁人閒聊或誤觸喚醒，請保持安靜不要說話，系統將立即自動掛斷。"
            } else {
                "已排程於講完道別語後自動掛斷 Live Chat 通話並退回免費待機模式。"
            })
        }.toString()
        return LocationToolExecutionResult(
            toolOutputJson = out,
            actionLogText = "📴 語音免動手自動掛斷 ($reason)",
            requestedHangupReason = reason
        )
    }

    private fun executeGetCurrentLocation(): LocationToolExecutionResult {
        if (!hasLocationPermission()) {
            return LocationToolExecutionResult(
                toolOutputJson = JSONObject().apply {
                    put("status", "error")
                    put("message", "尚未取得手機 GPS 定位權限 (ACCESS_FINE_LOCATION)。請提醒使用者允許定位權限，或直接口頭告知目前所在地區。")
                }.toString(),
                actionLogText = "📍 尚未取得 GPS 定位權限"
            )
        }

        val snap = getCurrentLocationSnapshot()
            ?: return LocationToolExecutionResult(
                toolOutputJson = JSONObject().apply {
                    put("status", "error")
                    put("message", "目前無法從系統取得有效的最後已知 GPS 座標（可能剛開機或室內訊號遮蔽），請詢問使用者所在位置。")
                }.toString(),
                actionLogText = "📍 暫時無法取得 GPS 座標"
            )

        val coords = String.format(Locale.US, "%.5f,%.5f", snap.latitude, snap.longitude)
        val mapUrl = "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(coords, "UTF-8")}"
        val previewSnippet =
            "MAP_PREVIEW|lat=${snap.latitude}|lon=${snap.longitude}|uLat=${snap.latitude}|uLon=${snap.longitude}|name=目前所在位置|addr=${snap.addressLine}|meta=精準度 ±${snap.accuracyMeters.toInt()}m"

        val json = JSONObject().apply {
            put("status", "success")
            put("latitude", snap.latitude)
            put("longitude", snap.longitude)
            put("accuracy_meters", snap.accuracyMeters)
            put("address", snap.addressLine)
            put("city_or_district", snap.cityOrDistrict)
            put("google_maps_url", mapUrl)
        }.toString()

        return LocationToolExecutionResult(
            toolOutputJson = json,
            actionLogText = "📍 已讀取手機目前位置：${snap.addressLine} ($coords)",
            mapActionSource = SearchSource(
                title = "📍 目前位置：${snap.cityOrDistrict.take(20)}",
                url = mapUrl,
                snippet = previewSnippet
            )
        )
    }

    private suspend fun executeSearchNearbyPlaces(
        argsJson: JSONObject,
        linkupApiKey: String,
        linkupDepth: String,
        googleMapsApiKey: String
    ): LocationToolExecutionResult {
        val placeQuery = argsJson.optString("query", "").trim().ifBlank { "附近推薦餐廳與景點" }
        val areaOverride = argsJson.optString("area_override", "").trim()
        val snap = getCurrentLocationSnapshot()

        val locationAnchor = when {
            areaOverride.isNotBlank() -> areaOverride
            snap != null -> "${snap.addressLine} (${String.format(Locale.US, "%.4f, %.4f", snap.latitude, snap.longitude)})"
            else -> ""
        }

        val googleMapsSearchTarget = when {
            areaOverride.isNotBlank() -> "$areaOverride $placeQuery"
            snap != null -> "${snap.cityOrDistrict} $placeQuery"
            else -> placeQuery
        }
        val mapsSearchUrl =
            "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(googleMapsSearchTarget, "UTF-8")}"

        // 1. If Google Maps Places API Key is configured, query official Google Places API (New) first!
        val cleanMapsKey = googleMapsApiKey.trim()
        if (cleanMapsKey.isNotBlank()) {
            val placesResult = queryGooglePlacesNewApi(
                apiKey = cleanMapsKey,
                textQuery = googleMapsSearchTarget,
                userSnap = snap
            )
            if (placesResult != null) {
                return placesResult
            }
        }

        // 2. Fallback / Hybrid: Android GPS + Geocoder + Linkup Search (remains 100% inside the app with In-App Map Preview!)
        val geocodedTarget = forwardGeocodePlace(googleMapsSearchTarget)
        val previewLat = geocodedTarget?.first ?: snap?.latitude ?: 25.033964
        val previewLon = geocodedTarget?.second ?: snap?.longitude ?: 121.564468
        val uLat = snap?.latitude ?: previewLat
        val uLon = snap?.longitude ?: previewLon

        val mapAction = SearchSource(
            title = "🗺️ 周邊地圖預覽：$placeQuery",
            url = mapsSearchUrl,
            snippet = "MAP_PREVIEW|lat=$previewLat|lon=$previewLon|uLat=$uLat|uLon=$uLon|name=$placeQuery|addr=${locationAnchor.ifBlank { googleMapsSearchTarget }}|meta=點擊可在 App 內預覽地圖或啟動導航"
        )

        val combinedSearchQuery = if (locationAnchor.isNotBlank()) {
            "$locationAnchor 附近 $placeQuery 地址 營業時間 評價"
        } else {
            "$placeQuery 地址 營業時間 評價"
        }

        if (linkupApiKey.isBlank()) {
            val fallbackJson = JSONObject().apply {
                put("status", "partial")
                put("user_location", locationAnchor.ifBlank { "未知" })
                put("google_maps_search_url", mapsSearchUrl)
                put("message", "已定位使用者位置 ($locationAnchor) 並在 App 內產生互動地圖預覽卡片。")
            }.toString()
            return LocationToolExecutionResult(
                toolOutputJson = fallbackJson,
                actionLogText = "📍 周邊探索：「$googleMapsSearchTarget」",
                mapActionSource = mapAction
            )
        }

        val searchRes = linkupClient.search(
            apiKey = linkupApiKey,
            query = combinedSearchQuery,
            depth = linkupDepth
        ).getOrElse { err ->
            LinkupSearchResult(
                query = combinedSearchQuery,
                answer = "周邊搜尋失敗: ${err.message}",
                sources = listOf(mapAction),
                rawFormattedForLlm = "Linkup 周邊搜尋錯誤: ${err.message}"
            )
        }

        val enrichedSources = listOf(mapAction) + searchRes.sources
        val enrichedResult = searchRes.copy(sources = enrichedSources.distinctBy { it.url })

        val outJson = JSONObject().apply {
            put("status", "success")
            put("user_current_location", locationAnchor.ifBlank { "未取得 GPS，僅以關鍵字搜尋" })
            put("search_query_used", combinedSearchQuery)
            put("in_app_map_preview_ready", true)
            put("linkup_findings", searchRes.rawFormattedForLlm)
            put("note_for_ai", "店家資訊與『🗺️ App 內看地圖 / 🚀 直接導航』卡片已顯示在對話氣泡中，使用者無須跳出 App 即可查看地圖預覽。")
        }.toString()

        return LocationToolExecutionResult(
            toolOutputJson = outJson,
            actionLogText = "📍 定位結合 Linkup 周邊搜尋：「$googleMapsSearchTarget」",
            mapActionSource = mapAction,
            linkupResult = enrichedResult
        )
    }

    private suspend fun queryGooglePlacesNewApi(
        apiKey: String,
        textQuery: String,
        userSnap: DeviceLocationSnapshot?
    ): LocationToolExecutionResult? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("textQuery", textQuery)
                put("languageCode", "zh-TW")
                put("maxResultCount", 5)
                if (userSnap != null) {
                    put("locationBias", JSONObject().apply {
                        put("circle", JSONObject().apply {
                            put("center", JSONObject().apply {
                                put("latitude", userSnap.latitude)
                                put("longitude", userSnap.longitude)
                            })
                            put("radius", 2500.0)
                        })
                    })
                }
            }

            val req = Request.Builder()
                .url("https://places.googleapis.com/v1/places:searchText")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Goog-Api-Key", apiKey)
                .addHeader(
                    "X-Goog-FieldMask",
                    "places.displayName,places.formattedAddress,places.location,places.rating,places.userRatingCount,places.currentOpeningHours,places.googleMapsUri"
                )
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || bodyStr.isBlank()) return@withContext null
                val root = JSONObject(bodyStr)
                val placesArr = root.optJSONArray("places") ?: return@withContext null
                if (placesArr.length() == 0) return@withContext null

                val sources = mutableListOf<SearchSource>()
                val placesJsonSummary = JSONArray()
                val summaryBuilder = StringBuilder()
                summaryBuilder.appendLine("📍 Google Maps Places 官方周邊搜尋結果（共 ${placesArr.length()} 間）：")

                for (i in 0 until placesArr.length()) {
                    val p = placesArr.optJSONObject(i) ?: continue
                    val name = p.optJSONObject("displayName")?.optString("text", "")?.ifBlank { "未知店家" } ?: "未知店家"
                    val addr = p.optString("formattedAddress", "")
                    val rating = p.optDouble("rating", 0.0)
                    val ratingCount = p.optInt("userRatingCount", 0)
                    val openNowObj = p.optJSONObject("currentOpeningHours")
                    val hasOpenInfo = openNowObj != null && openNowObj.has("openNow")
                    val isOpenNow = openNowObj?.optBoolean("openNow", false) ?: false

                    val locObj = p.optJSONObject("location")
                    val pLat = locObj?.optDouble("latitude", 0.0) ?: 0.0
                    val pLon = locObj?.optDouble("longitude", 0.0) ?: 0.0

                    val distInfo = if (userSnap != null && pLat != 0.0 && pLon != 0.0) {
                        val results = FloatArray(1)
                        Location.distanceBetween(userSnap.latitude, userSnap.longitude, pLat, pLon, results)
                        val distM = results[0].roundToInt()
                        val walkMin = (distM / 75.0).roundToInt().coerceAtLeast(1)
                        "${distM}公尺 (步行約${walkMin}分)"
                    } else {
                        ""
                    }

                    val ratingStr = if (rating > 0.0) "${rating}★ ($ratingCount)" else "無評分"
                    val openStr = when {
                        !hasOpenInfo -> "營業時間依現場為準"
                        isOpenNow -> "🟢 營業中"
                        else -> "🔴 休息中"
                    }
                    val metaLine = listOf(ratingStr, openStr, distInfo).filter { it.isNotBlank() }.joinToString(" · ")

                    val navUrl = if (pLat != 0.0 && pLon != 0.0) {
                        "https://www.google.com/maps/dir/?api=1&destination=$pLat,$pLon&travelmode=walking"
                    } else {
                        "https://www.google.com/maps/dir/?api=1&destination=${URLEncoder.encode("$name $addr", "UTF-8")}&travelmode=walking"
                    }

                    val uLat = userSnap?.latitude ?: pLat
                    val uLon = userSnap?.longitude ?: pLon
                    val previewSnippet =
                        "MAP_PREVIEW|lat=$pLat|lon=$pLon|uLat=$uLat|uLon=$uLon|name=$name|addr=$addr|meta=$metaLine"

                    sources.add(
                        SearchSource(
                            title = "📍 $name ($metaLine)",
                            url = navUrl,
                            snippet = previewSnippet
                        )
                    )

                    summaryBuilder.appendLine("${i + 1}. 【$name】 — $metaLine | 地址：$addr")
                    placesJsonSummary.put(JSONObject().apply {
                        put("rank", i + 1)
                        put("name", name)
                        put("rating", ratingStr)
                        put("open_status", openStr)
                        put("distance_and_walk_time", distInfo)
                        put("address", addr)
                        put("latitude", pLat)
                        put("longitude", pLon)
                    })
                }

                val formattedText = summaryBuilder.toString().trim()
                val outJson = JSONObject().apply {
                    put("status", "success")
                    put("provider", "Google Places API (New)")
                    put("user_location", userSnap?.addressLine ?: "未知")
                    put("places", placesJsonSummary)
                    put(
                        "note_for_ai",
                        "所有店家評價、營業狀態、距離時間與『🗺️ App 內看地圖預覽』皆已直接顯示在 App 對話氣泡中，使用者無須跳離 App。若使用者決定要去哪一家並說『幫我導航去第X家』，請接著呼叫 open_google_maps_navigation。"
                    )
                }.toString()

                LocationToolExecutionResult(
                    toolOutputJson = outJson,
                    actionLogText = "📍 Google Places 官方精準周邊搜尋：「$textQuery」（找到 ${placesArr.length()} 間）",
                    mapActionSource = sources.firstOrNull(),
                    linkupResult = LinkupSearchResult(
                        query = "📍 Google Maps 周邊：$textQuery",
                        answer = formattedText,
                        sources = sources,
                        rawFormattedForLlm = formattedText
                    )
                )
            }
        }.getOrNull()
    }

    /** A destination resolved to a concrete place (via Google Places API or Android Geocoder), biased to the user's GPS. */
    data class ResolvedDestination(
        val name: String,
        val address: String,
        val latitude: Double,
        val longitude: Double,
        val placeId: String,
        val distanceMeters: Int?,
        val pickedNearest: Boolean,
        val provider: String,
        val alternatives: List<String>
    )

    @Volatile
    private var lastNavLaunchKey: String = ""
    @Volatile
    private var lastNavLaunchWallMs: Long = 0L

    private fun distanceTo(snap: DeviceLocationSnapshot?, lat: Double, lon: Double): Int? {
        if (snap == null || (lat == 0.0 && lon == 0.0)) return null
        val out = FloatArray(1)
        Location.distanceBetween(snap.latitude, snap.longitude, lat, lon, out)
        return out[0].roundToInt()
    }

    private fun normalizePlaceText(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[\\s　·・,，。.()（）\\-_/]"), "")
            .replace("台", "臺")

    /**
     * Resolves a spoken (possibly fuzzy / homophone-misrecognized) destination into one concrete place.
     * - Always biased around the user's current GPS position, so "德意山莊春風區" resolves to the one in 北投 without saying the district.
     * - For chain stores / generic categories (麥當勞、7-11、加油站…) it picks the NEAREST branch instead of Google's "most prominent" one.
     */
    suspend fun resolveNavigationDestination(
        query: String,
        googleMapsApiKey: String,
        preferNearestHint: Boolean? = null
    ): ResolvedDestination? = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext null
        val snap = getCurrentLocationSnapshot(allowBlockingGeocode = false)
        val key = googleMapsApiKey.trim()
        if (key.isNotBlank()) {
            resolveWithPlacesApi(cleanQuery, key, snap, preferNearestHint)?.let { return@withContext it }
        }
        resolveWithGeocoder(cleanQuery, snap, preferNearestHint)
    }

    private fun queryPlacesForNavigation(
        apiKey: String,
        textQuery: String,
        snap: DeviceLocationSnapshot?,
        radiusMeters: Double,
        rankByDistance: Boolean
    ): List<JSONObject> {
        return runCatching {
            val payload = JSONObject().apply {
                put("textQuery", textQuery)
                put("languageCode", "zh-TW")
                put("regionCode", "TW")
                put("maxResultCount", 10)
                if (rankByDistance) put("rankPreference", "DISTANCE")
                if (snap != null) {
                    put("locationBias", JSONObject().apply {
                        put("circle", JSONObject().apply {
                            put("center", JSONObject().apply {
                                put("latitude", snap.latitude)
                                put("longitude", snap.longitude)
                            })
                            put("radius", radiusMeters)
                        })
                    })
                }
            }
            val req = Request.Builder()
                .url("https://places.googleapis.com/v1/places:searchText")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Goog-Api-Key", apiKey)
                .addHeader("X-Goog-FieldMask", "places.id,places.displayName,places.formattedAddress,places.location")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) return@use emptyList<JSONObject>()
                val arr = JSONObject(body).optJSONArray("places") ?: return@use emptyList<JSONObject>()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            }
        }.getOrDefault(emptyList())
    }

    private fun resolveWithPlacesApi(
        query: String,
        apiKey: String,
        snap: DeviceLocationSnapshot?,
        preferNearestHint: Boolean?
    ): ResolvedDestination? {
        data class Cand(val name: String, val addr: String, val lat: Double, val lon: Double, val id: String, val dist: Int?)

        fun toCands(list: List<JSONObject>): List<Cand> = list.mapNotNull { p ->
            val loc = p.optJSONObject("location") ?: return@mapNotNull null
            val lat = loc.optDouble("latitude", 0.0)
            val lon = loc.optDouble("longitude", 0.0)
            if (lat == 0.0 && lon == 0.0) return@mapNotNull null
            Cand(
                name = p.optJSONObject("displayName")?.optString("text", "").orEmpty().ifBlank { query },
                addr = p.optString("formattedAddress", ""),
                lat = lat,
                lon = lon,
                id = p.optString("id", ""),
                dist = distanceTo(snap, lat, lon)
            )
        }

        // Pass 1: relevance ranking, biased to a 30 km circle around the user (fixes homophone / missing-district cases)
        val relevance = toCands(queryPlacesForNavigation(apiKey, query, snap, 30_000.0, rankByDistance = false))
        if (relevance.isEmpty()) return null

        val qn = normalizePlaceText(query)
        val nameMatches = relevance.filter { c ->
            val cn = normalizePlaceText(c.name)
            qn.isNotBlank() && (cn.contains(qn) || qn.contains(cn))
        }
        val looksLikeChain = preferNearestHint == true ||
                (preferNearestHint == null && (nameMatches.size >= 2 || isGenericChainOrCategory(query)))

        if (looksLikeChain && snap != null) {
            // Pass 2: explicitly rank by distance within ~6 km so we get the truly nearest branch
            val byDistance = toCands(queryPlacesForNavigation(apiKey, query, snap, 6_000.0, rankByDistance = true))
            val pool = (byDistance + relevance).distinctBy { it.id.ifBlank { "${it.lat},${it.lon}" } }
            val matchingPool = pool.filter { c ->
                val cn = normalizePlaceText(c.name)
                cn.contains(qn) || qn.contains(cn) || isGenericChainOrCategory(query)
            }.ifEmpty { pool }
            val nearest = matchingPool.minByOrNull { it.dist ?: Int.MAX_VALUE } ?: return null
            return ResolvedDestination(
                name = nearest.name,
                address = nearest.addr,
                latitude = nearest.lat,
                longitude = nearest.lon,
                placeId = nearest.id,
                distanceMeters = nearest.dist,
                pickedNearest = true,
                provider = "Google Places API (最近分店)",
                alternatives = matchingPool.sortedBy { it.dist ?: Int.MAX_VALUE }.drop(1).take(3)
                    .map { "${it.name}${it.dist?.let { d -> "（${d}m）" } ?: ""}" }
            )
        }

        val best = relevance.first()
        return ResolvedDestination(
            name = best.name,
            address = best.addr,
            latitude = best.lat,
            longitude = best.lon,
            placeId = best.id,
            distanceMeters = best.dist,
            pickedNearest = false,
            provider = "Google Places API (GPS 周邊優先)",
            alternatives = relevance.drop(1).take(3).map { "${it.name}${it.dist?.let { d -> "（${d}m）" } ?: ""}" }
        )
    }

    @Suppress("DEPRECATION")
    private fun resolveWithGeocoder(
        query: String,
        snap: DeviceLocationSnapshot?,
        preferNearestHint: Boolean?
    ): ResolvedDestination? {
        return runCatching {
            if (!Geocoder.isPresent()) return null
            val geocoder = Geocoder(context, Locale.TAIWAN)
            val results = if (snap != null) {
                val d = 0.3 // ~30 km bounding box around the user
                geocoder.getFromLocationName(query, 8, snap.latitude - d, snap.longitude - d, snap.latitude + d, snap.longitude + d)
                    ?.takeIf { it.isNotEmpty() }
                    ?: geocoder.getFromLocationName(query, 8)
            } else {
                geocoder.getFromLocationName(query, 8)
            }
            if (results.isNullOrEmpty()) return null
            val pickNearest = preferNearestHint != false
            val chosen = if (pickNearest && snap != null) {
                results.minByOrNull { distanceTo(snap, it.latitude, it.longitude) ?: Int.MAX_VALUE }!!
            } else {
                results.first()
            }
            val addr = (0..chosen.maxAddressLineIndex).mapNotNull { chosen.getAddressLine(it) }.joinToString(" ")
            ResolvedDestination(
                name = chosen.featureName?.takeIf { it.isNotBlank() && !it.all { ch -> ch.isDigit() } } ?: query,
                address = addr,
                latitude = chosen.latitude,
                longitude = chosen.longitude,
                placeId = "",
                distanceMeters = distanceTo(snap, chosen.latitude, chosen.longitude),
                pickedNearest = pickNearest && snap != null,
                provider = "Android Geocoder",
                alternatives = emptyList()
            )
        }.getOrNull()
    }

    private suspend fun executeOpenGoogleMapsNavigation(
        argsJson: JSONObject,
        googleMapsApiKey: String
    ): LocationToolExecutionResult {
        val destination = argsJson.optString("destination", "").trim()
        if (destination.isBlank()) {
            return LocationToolExecutionResult(
                toolOutputJson = JSONObject().put("status", "error")
                    .put("message", "缺少目的地 (destination)").toString(),
                actionLogText = "⚠️ 導航缺少目的地"
            )
        }

        val originArg = argsJson.optString("origin", "").trim()
        val rawMode = argsJson.optString("travel_mode", "walking").lowercase().trim()
        val travelMode = when {
            rawMode.contains("walk") || rawMode.contains("步") -> "walking"
            rawMode.contains("driv") || rawMode.contains("car") || rawMode.contains("車") -> "driving"
            rawMode.contains("bicyc") || rawMode.contains("bike") || rawMode.contains("單車") -> "bicycling"
            else -> "transit"
        }
        val modeLabel = when (travelMode) {
            "walking" -> "🚶 步行"
            "driving" -> "🚗 開車/騎車"
            "bicycling" -> "🚲 單車"
            else -> "🚇 大眾運輸"
        }

        // Default auto_launch_app = true so whenever the user asks to navigate, it 100% auto-jumps without clicking!
        val autoLaunch = argsJson.optBoolean("auto_launch_app", true)
        // Fast-path (on-device voice intent) only launches when the place was confidently resolved
        val requireResolved = argsJson.optBoolean("_require_resolved", false)
        val preferNearestHint: Boolean? = if (argsJson.has("prefer_nearest")) argsJson.optBoolean("prefer_nearest") else null

        val snap = getCurrentLocationSnapshot()
        val resolved = resolveNavigationDestination(destination, googleMapsApiKey, preferNearestHint)

        if (resolved == null && (requireResolved || googleMapsApiKey.isNotBlank())) {
            val near = snap?.cityOrDistrict?.takeIf { it.isNotBlank() } ?: "使用者目前所在城市"
            return LocationToolExecutionResult(
                toolOutputJson = JSONObject().apply {
                    put("status", "not_found")
                    put("destination_query", destination)
                    put("user_location", snap?.addressLine ?: "未知")
                    put(
                        "message",
                        "在使用者附近（$near）找不到「$destination」。這很可能是語音辨識的同音字/近音字錯誤。請根據使用者所在地區推測最可能的正確地名（例如同音異字、社區/山莊/大樓/店名），以修正後的名稱再次呼叫 open_google_maps_navigation；若仍有多種可能，請用一句話向使用者確認（例如：「你是指北投的德藝山莊嗎？」），不要亂導航。"
                    )
                }.toString(),
                actionLogText = if (requireResolved) "" else "🔎 找不到「$destination」，正在請 AI 推敲同音字或向您確認"
            )
        }

        val encodedDest = URLEncoder.encode(destination, "UTF-8")
        val originParam = when {
            originArg.isNotBlank() -> "&origin=${URLEncoder.encode(originArg, "UTF-8")}"
            snap != null -> "&origin=${snap.latitude},${snap.longitude}"
            else -> ""
        }

        val universalMapsUrl: String
        val primaryNavigationUri: String
        if (resolved != null) {
            val encName = URLEncoder.encode(resolved.name, "UTF-8")
            val placeIdParam = if (resolved.placeId.isNotBlank()) "&destination_place_id=${resolved.placeId}" else ""
            val destParam = if (resolved.placeId.isNotBlank()) encName else "${resolved.latitude},${resolved.longitude}"
            val originForWeb = if (originArg.isNotBlank()) "&origin=${URLEncoder.encode(originArg, "UTF-8")}" else ""
            universalMapsUrl =
                "https://www.google.com/maps/dir/?api=1$originForWeb&destination=$destParam$placeIdParam&travelmode=$travelMode&dir_action=navigate"
            primaryNavigationUri = when (travelMode) {
                "walking" -> "google.navigation:q=${resolved.latitude},${resolved.longitude}&mode=w"
                "driving" -> "google.navigation:q=${resolved.latitude},${resolved.longitude}&mode=d"
                "bicycling" -> "google.navigation:q=${resolved.latitude},${resolved.longitude}&mode=b"
                else -> universalMapsUrl
            }
        } else {
            universalMapsUrl =
                "https://www.google.com/maps/dir/?api=1$originParam&destination=$encodedDest&travelmode=$travelMode"
            primaryNavigationUri = when (travelMode) {
                "walking" -> "google.navigation:q=$encodedDest&mode=w"
                "driving" -> "google.navigation:q=$encodedDest&mode=d"
                "bicycling" -> "google.navigation:q=$encodedDest&mode=b"
                else -> universalMapsUrl
            }
        }

        val finalLabel = resolved?.name ?: destination
        val launchKey = resolved?.let { if (it.placeId.isNotBlank()) it.placeId else String.format(Locale.US, "%.4f,%.4f", it.latitude, it.longitude) }
            ?: normalizePlaceText(destination)
        val now = System.currentTimeMillis()
        val alreadyLaunched = launchKey == lastNavLaunchKey && now - lastNavLaunchWallMs < 45_000L

        if (autoLaunch && !alreadyLaunched) {
            lastNavLaunchKey = launchKey
            lastNavLaunchWallMs = now
            Handler(Looper.getMainLooper()).post {
                launchGoogleMapsIntent(primaryNavigationUri, universalMapsUrl, finalLabel)
            }
            onAutoLaunchGoogleMaps?.invoke(primaryNavigationUri, universalMapsUrl, finalLabel)
        }

        val pLat = resolved?.latitude ?: forwardGeocodePlace(destination)?.first ?: snap?.latitude ?: 25.033964
        val pLon = resolved?.longitude ?: forwardGeocodePlace(destination)?.second ?: snap?.longitude ?: 121.564468
        val uLat = snap?.latitude ?: pLat
        val uLon = snap?.longitude ?: pLon
        val distText = resolved?.distanceMeters?.let { d ->
            if (d >= 1000) String.format(Locale.US, "%.1f 公里", d / 1000.0) else "$d 公尺"
        }
        val metaLine = listOfNotNull(modeLabel, distText?.let { "距離約 $it" }, if (resolved?.pickedNearest == true) "最近分店" else null)
            .joinToString(" · ")

        val mapSource = SearchSource(
            title = "🚀 Google Maps 導航：$finalLabel ($modeLabel)",
            url = universalMapsUrl,
            snippet = "MAP_PREVIEW|lat=$pLat|lon=$pLon|uLat=$uLat|uLon=$uLon|name=$finalLabel|addr=${resolved?.address?.ifBlank { destination } ?: destination}|meta=$metaLine"
        )

        val outJson = JSONObject().apply {
            put("status", "success")
            put("destination_query", destination)
            put("resolved_place_name", finalLabel)
            put("resolved_address", resolved?.address ?: "")
            distText?.let { put("distance_from_user", it) }
            put("picked_nearest_branch", resolved?.pickedNearest == true)
            put("resolver", resolved?.provider ?: "Google Maps App 自行搜尋")
            if (!resolved?.alternatives.isNullOrEmpty()) put("other_candidates", JSONArray(resolved!!.alternatives))
            put("travel_mode", travelMode)
            put("origin_used", originArg.ifBlank { snap?.addressLine ?: "手機目前位置" })
            put("google_maps_navigation_url", universalMapsUrl)
            put("launched_google_maps_app", autoLaunch)
            put("already_launched_moments_ago", alreadyLaunched)
            put(
                "message",
                "已直接跳轉手機 Google Maps 導航至「$finalLabel」${distText?.let { "（距離約 $it）" } ?: ""}（模式：$modeLabel），無須手動點擊！請只用一句話告知使用者，例如『已經幫你導航到$finalLabel！』，不要再重複呼叫導航工具。若使用者表示不是這個地點，再依其補充重新呼叫。"
            )
        }.toString()

        val logText = buildString {
            append("🚀 已自動跳轉 Google Maps 導航：$finalLabel ($modeLabel)")
            distText?.let { append(" · 距離約 $it") }
            if (resolved?.pickedNearest == true) append(" · 已自動選擇離您最近的分店")
            if (resolved != null && normalizePlaceText(resolved.name) != normalizePlaceText(destination)) {
                append("\n🔎 語音辨識「$destination」➔ 依您目前 GPS 位置比對為「${resolved.name}」${resolved.address.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}")
            }
        }

        return LocationToolExecutionResult(
            toolOutputJson = outJson,
            actionLogText = if (alreadyLaunched) "" else logText,
            mapActionSource = if (alreadyLaunched) null else mapSource,
            autoLaunchedNavigation = autoLaunch
        )
    }

    fun launchGoogleMapsIntent(
        primaryUriStr: String,
        fallbackWebUrl: String = primaryUriStr,
        destinationLabel: String = "目的地"
    ): Boolean {
        return runCatching {
            // Prefer the universal "dir_action=navigate" URL when it carries a place_id (shows the real place name in Maps)
            val preferUniversal = fallbackWebUrl.contains("destination_place_id=")
            val firstUri = if (preferUniversal) fallbackWebUrl else primaryUriStr
            val secondUri = if (preferUniversal) primaryUriStr else fallbackWebUrl

            val primaryIntent = Intent(Intent.ACTION_VIEW, Uri.parse(firstUri)).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val fallbackMapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse(secondUri)).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackWebUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val resolvedIntent = when {
                primaryIntent.resolveActivity(context.packageManager) != null -> primaryIntent
                fallbackMapsIntent.resolveActivity(context.packageManager) != null -> fallbackMapsIntent
                else -> browserIntent
            }

            val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    android.provider.Settings.canDrawOverlays(context)

            // 1. Direct startActivity (succeeds from foreground OR from background when SYSTEM_ALERT_WINDOW is granted)
            val directOk = runCatching { context.startActivity(resolvedIntent); true }.getOrDefault(false)

            // 2. Without overlay permission, background launches may be silently blocked:
            //    add a PendingIntent + high-priority full-screen notification as a safety net.
            if (!directOk || !canOverlay) runCatching {
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                val pi = PendingIntent.getActivity(context, 9912, resolvedIntent, flags)
                if (!directOk) runCatching { pi.send() }

                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelId = "maps_auto_nav_channel"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val ch = NotificationChannel(
                        channelId,
                        "Google Maps 自動導航跳轉",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "當在背景透過語音喚醒要求導航時，自動彈出並啟動 Google Maps"
                    }
                    nm.createNotificationChannel(ch)
                }
                val notif = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_map)
                    .setContentTitle("🚀 正在開啟 Google Maps 導航")
                    .setContentText("目的地：$destinationLabel（點擊立即開啟）")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true)
                    .setTimeoutAfter(12_000L)
                    .build()
                nm.notify(9912, notif)
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        const val NAVIGATION_RULES =
            "【導航規則】①只要使用者表達想前往某地（導航到/帶我去/我要去/去…怎麼走/開車騎車走路去…），一律立即呼叫 open_google_maps_navigation 直接跳轉，不需要使用者說「跳轉」，也不要先反問。" +
                    "②語音辨識常有同音/近音錯字（例如社區、山莊、店名），請以使用者目前 GPS 所在區域推測最可能的正確地名再填入 destination；若工具回傳 not_found，請修正同音字後重試一次，仍找不到才簡短向使用者確認。" +
                    "③連鎖店或類別（麥當勞、全家、7-11、加油站…）且未指定分店時，設 prefer_nearest=true 導航到最近一間。" +
                    "④若系統訊息表示「手機端已直接完成導航」，只需一句話確認，不要重複呼叫導航工具。"
        private val GENERIC_CHAIN_KEYWORDS = listOf(
            "麥當勞", "mcdonald", "肯德基", "kfc", "摩斯", "漢堡王", "星巴克", "starbucks", "路易莎", "cama", "85度c", "85°c",
            "7-11", "711", "7-eleven", "seven", "小七", "全家", "familymart", "萊爾富", "ok超商", "美廉社", "全聯", "家樂福", "costco",
            "便利商店", "超商", "超市", "加油站", "中油", "台塑", "停車場", "捷運站", "公車站", "火車站", "youbike", "ubike",
            "藥局", "藥妝", "屈臣氏", "康是美", "診所", "郵局", "atm", "提款機", "銀行", "廁所", "洗手間", "充電站",
            "早餐店", "咖啡", "手搖", "飲料店", "五十嵐", "清心", "迷客夏", "麥味登", "拉麵", "便當", "夾娃娃", "洗衣"
        )

        /** True for chain brands / generic categories where the user almost always means "the nearest one". */
        fun isGenericChainOrCategory(query: String): Boolean {
            val q = query.lowercase(Locale.ROOT).replace(" ", "")
            if (q.startsWith("最近") || q.startsWith("附近")) return true
            // Strip chain-branch-agnostic phrasing; if a branch name is included ("麥當勞北投店"), treat as specific
            if (Regex("(店|分店|門市|站)$").containsMatchIn(q) && q.length > 5) return false
            return GENERIC_CHAIN_KEYWORDS.any { q.contains(it) } && q.length <= 10
        }

        data class VoiceNavIntent(val destination: String, val travelMode: String)

        private val NAV_PATTERNS = listOf(
            Regex("(?:幫我|請|麻煩|給我)?(?:開(?:啟)?|啟動|開始)?導航(?:一下)?(?:到|去|至|前往|往)?(.+)"),
            Regex("(?:幫我|請|麻煩)?帶我(?:去|到|前往)(.+)"),
            Regex("(?:我要|我想|想要|幫我)?(?:開車|騎車|騎機車|走路|步行|騎腳踏車|騎單車)(?:去|到|前往)(.+)"),
            Regex("(?:我要|我想|想要)?(?:去|到)(.+?)(?:要)?怎麼走"),
            Regex("(?:我要|我想|想要)?前往(.+)")
        )

        /**
         * On-device fast-path parser for explicit navigation commands (no LLM round trip needed).
         * Returns null when the sentence is not clearly a navigation request.
         */
        fun parseVoiceNavigationIntent(rawText: String): VoiceNavIntent? {
            val text = rawText.trim().replace(Regex("[\\s，。！？、,.!?]+"), "")
            if (text.length < 4 || text.length > 40) return null
            // Questions about navigation (not commands) -> let the LLM handle them
            if (text.contains("要不要") || text.contains("能不能導航") || text.contains("可以導航嗎")) return null
            if (listOf("取消", "停止", "結束導航", "關閉導航", "關掉導航", "不用導航", "不要導航", "別導航").any { text.contains(it) }) return null
            var dest: String? = null
            for (p in NAV_PATTERNS) {
                val m = p.find(text) ?: continue
                dest = m.groupValues.getOrNull(1)
                if (!dest.isNullOrBlank()) break
            }
            var d = dest?.trim() ?: return null
            d = d.replace(Regex("^(到|去|至|前往|往)"), "")
            d = d.replace(Regex("(的路線|的路|那邊|那裡|這裡|一下|吧|好嗎|好不好|可以嗎|嗎|喔|哦|啦|呀|耶|謝謝|謝啦|拜託|感謝)+$"), "")
            d = d.replace(Regex("(怎麼走|要怎麼去|怎麼去)$"), "")
            if (d.length < 2 || d.length > 30) return null
            // Reject obviously non-place objects
            val blacklist = listOf("睡覺", "洗澡", "吃飯", "上班", "上課", "廁所嗎", "那個", "這個", "模式", "設定", "功能", "語音", "那邊", "哪裡", "哪")
            if (blacklist.any { d == it }) return null
            val mode = when {
                text.contains("開車") || text.contains("騎車") || text.contains("騎機車") || text.contains("機車") -> "driving"
                text.contains("走路") || text.contains("步行") -> "walking"
                text.contains("腳踏車") || text.contains("單車") || text.contains("ubike", true) -> "bicycling"
                text.contains("捷運") || text.contains("公車") || text.contains("大眾運輸") || text.contains("搭車") -> "transit"
                else -> "walking"
            }
            return VoiceNavIntent(destination = d, travelMode = mode)
        }
        val LOCATION_MAPS_TOOL_NAMES = setOf(
            "get_user_current_location",
            "search_nearby_places_with_linkup",
            "open_google_maps_navigation",
            "end_live_voice_session"
        )

        fun buildResponsesLocationToolSchemas(): List<JSONObject> {
            return listOf(
                JSONObject().apply {
                    put("type", "function")
                    put("name", "get_user_current_location")
                    put(
                        "description",
                        "讀取使用者手機當下的 GPS 經緯度與真實街道地址/行政區。當使用者詢問『我現在在哪裡』或需要確認目前位置時呼叫。"
                    )
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                },
                JSONObject().apply {
                    put("type", "function")
                    put("name", "search_nearby_places_with_linkup")
                    put(
                        "description",
                        "結合手機 GPS 座標、Google Places API 與 Linkup 搜尋附近的餐廳、拉麵店、藥妝店、咖啡廳、景點或距離時間，並在 App 對話氣泡內直接顯示店家評價與『🗺️ App 內互動地圖預覽』（不強制跳出 App）。"
                    )
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("query", JSONObject().apply {
                                put("type", "string")
                                put("description", "想在附近尋找的店家類型或關鍵字，例如：『還在營業的拉麵店』、『燒肉店』、『藥妝店』、『便利商店』")
                            })
                            put("area_override", JSONObject().apply {
                                put("type", "string")
                                put("description", "（選填）若使用者明確指定了某個地區（例如『新宿東口』、『台北信義區』），可填入此欄位；若留空則自動使用手機目前 GPS 地址")
                            })
                        })
                        put("required", JSONArray().put("query"))
                    })
                },
                JSONObject().apply {
                    put("type", "function")
                    put("name", "open_google_maps_navigation")
                    put(
                        "description",
                        "當使用者表達想前往某地（例如『導航到…』、『帶我去…』、『我要去…』、『去…怎麼走』、『開車/騎車/走路去…』）時一律直接呼叫，不需要使用者說『跳轉』！會免點擊直接自動跳轉手機 Google Maps 開始路線導航。系統會以使用者 GPS 位置為中心解析地名（可容忍同音字），連鎖店會自動選最近分店。"
                    )
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("destination", JSONObject().apply {
                                put("type", "string")
                                put("description", "目的地名稱或地址，例如：『一蘭拉麵 新宿中央東口店』、『台北 101』、『麥當勞』或行事曆上的會議地點。若語音辨識結果疑似同音錯字，請依使用者所在地區修正為最可能的正確地名")
                            })
                            put("prefer_nearest", JSONObject().apply {
                                put("type", "boolean")
                                put("description", "連鎖店或類別（如『麥當勞』、『全家』、『加油站』）且使用者沒指定分店時設為 true，自動導航到離使用者最近的一間；指定特定地點時設為 false")
                            })
                            put("origin", JSONObject().apply {
                                put("type", "string")
                                put("description", "（選填）出發地名稱或地址；若留空則自動以使用者手機目前 GPS 座標為出發點")
                            })
                            put("travel_mode", JSONObject().apply {
                                put("type", "string")
                                put("enum", JSONArray().put("walking").put("driving").put("transit").put("bicycling"))
                                put("description", "交通方式：walking (步行)、driving (開車/騎車)、transit (大眾運輸/捷運/電車)、bicycling (單車)")
                            })
                            put("auto_launch_app", JSONObject().apply {
                                put("type", "boolean")
                                put("description", "預設設為 true，直接自動跳轉開啟手機 Google Maps App 開始導航，無須使用者手動點擊按鈕。")
                            })
                        })
                        put("required", JSONArray().put("destination"))
                    })
                },
                JSONObject().apply {
                    put("type", "function")
                    put("name", "end_live_voice_session")
                    put(
                        "description",
                        "當使用者在語音中表示想結束對話（例如說『掰掰』、『再見』、『先這樣謝謝』、『結束通話』、『掛斷』），或是語音喚醒模式下發現是旁人聊天誤觸時呼叫，系統會自動掛斷 Live Chat 通話。"
                    )
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("reason", JSONObject().apply {
                                put("type", "string")
                                put("enum", JSONArray().put("user_goodbye").put("bystander_false_wake").put("task_completed"))
                                put("description", "掛斷原因：user_goodbye (使用者道別或要求掛斷)、bystander_false_wake (旁人閒聊誤觸喚醒)、task_completed (任務完成要求結束)")
                            })
                            put("farewell_spoken", JSONObject().apply {
                                put("type", "string")
                                put("description", "掛斷前對使用者說的一句簡短道別語（若為 bystander_false_wake 則留空）")
                            })
                        })
                        put("required", JSONArray().put("reason"))
                    })
                }
            )
        }

        fun buildChatCompletionsLocationToolSchemas(): List<JSONObject> {
            return buildResponsesLocationToolSchemas().map { schema ->
                JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", schema.optString("name"))
                        put("description", schema.optString("description"))
                        put("parameters", schema.optJSONObject("parameters") ?: JSONObject())
                    })
                }
            }
        }
    }
}
