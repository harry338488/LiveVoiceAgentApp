package com.example.livevoiceagent.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.example.livevoiceagent.model.SearchSource
import com.example.livevoiceagent.network.LinkupSearchClient
import com.example.livevoiceagent.network.LinkupSearchResult
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

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
    val linkupResult: LinkupSearchResult? = null
)

class LocationMapsToolManager(
    private val context: Context,
    private val linkupClient: LinkupSearchClient
) {

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
    fun getCurrentLocationSnapshot(): DeviceLocationSnapshot? {
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
                    val isMoreAccurate = loc.accuracy < bestLocation.accuracy
                    if (isSignificantlyNewer || (isNewer && isMoreAccurate)) {
                        bestLocation = loc
                    }
                }
            }

            val finalLoc = bestLocation ?: return null
            val lat = finalLoc.latitude
            val lng = finalLoc.longitude

            var addressLine = ""
            var cityOrDistrict = ""

            runCatching {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lng, 1)
                    val first = addresses?.firstOrNull()
                    if (first != null) {
                        addressLine = (0..first.maxAddressLineIndex)
                            .mapNotNull { first.getAddressLine(it) }
                            .joinToString(" ")
                            .trim()
                        val admin = first.adminArea.orEmpty()
                        val subAdmin = first.subAdminArea.orEmpty()
                        val locality = first.locality.orEmpty()
                        val subLocality = first.subLocality.orEmpty()
                        val thoroughfare = first.thoroughfare.orEmpty()
                        cityOrDistrict = listOf(admin, subAdmin, locality, subLocality, thoroughfare)
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString("")
                    }
                }
            }

            if (addressLine.isBlank()) {
                addressLine = String.format(Locale.US, "%.5f, %.5f", lat, lng)
            }
            if (cityOrDistrict.isBlank()) {
                cityOrDistrict = addressLine
            }

            DeviceLocationSnapshot(
                latitude = lat,
                longitude = lng,
                accuracyMeters = finalLoc.accuracy,
                addressLine = addressLine,
                cityOrDistrict = cityOrDistrict,
                provider = finalLoc.provider ?: "gps"
            )
        }.getOrNull()
    }

    fun buildCurrentLocationContextString(): String {
        val snap = getCurrentLocationSnapshot() ?: return ""
        val coords = String.format(Locale.US, "%.5f, %.5f", snap.latitude, snap.longitude)
        return "📍 【使用者目前手機即時 GPS 位置】：${snap.addressLine} (經緯度: $coords)"
    }

    fun executeLocationOrMapTool(
        toolName: String,
        argsJson: JSONObject,
        linkupApiKey: String,
        linkupDepth: String
    ): LocationToolExecutionResult {
        return runCatching {
            when (toolName) {
                "get_user_current_location" -> executeGetCurrentLocation()
                "search_nearby_places_with_linkup" -> executeSearchNearbyWithLinkup(
                    argsJson = argsJson,
                    linkupApiKey = linkupApiKey,
                    linkupDepth = linkupDepth
                )
                "open_google_maps_navigation" -> executeOpenGoogleMapsNavigation(argsJson)
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
                title = "📍 在 Google Maps 查看目前位置 (${snap.cityOrDistrict.take(18)})",
                url = mapUrl,
                snippet = snap.addressLine
            )
        )
    }

    private fun executeSearchNearbyWithLinkup(
        argsJson: JSONObject,
        linkupApiKey: String,
        linkupDepth: String
    ): LocationToolExecutionResult {
        val placeQuery = argsJson.optString("query", "").trim().ifBlank { "附近推薦餐廳與景點" }
        val areaOverride = argsJson.optString("area_override", "").trim()
        val snap = getCurrentLocationSnapshot()

        val locationAnchor = when {
            areaOverride.isNotBlank() -> areaOverride
            snap != null -> "${snap.addressLine} (${String.format(Locale.US, "%.4f, %.4f", snap.latitude, snap.longitude)})"
            else -> ""
        }

        val combinedSearchQuery = if (locationAnchor.isNotBlank()) {
            "$locationAnchor 附近 $placeQuery 地址 營業時間 評價"
        } else {
            "$placeQuery 地址 營業時間 評價"
        }

        val googleMapsSearchTarget = if (areaOverride.isNotBlank()) {
            "$areaOverride $placeQuery"
        } else if (snap != null) {
            "${snap.cityOrDistrict} $placeQuery"
        } else {
            placeQuery
        }
        val mapsSearchUrl =
            "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(googleMapsSearchTarget, "UTF-8")}"

        val mapAction = SearchSource(
            title = "🗺️ 在 Google Maps 探索附近：$placeQuery",
            url = mapsSearchUrl,
            snippet = locationAnchor.ifBlank { placeQuery }
        )

        if (linkupApiKey.isBlank()) {
            val fallbackJson = JSONObject().apply {
                put("status", "partial")
                put("user_location", locationAnchor.ifBlank { "未知" })
                put("google_maps_search_url", mapsSearchUrl)
                put("message", "已定位使用者位置 ($locationAnchor) 並產生 Google Maps 周邊搜尋連結，但尚未設定 Linkup API Key。")
            }.toString()
            return LocationToolExecutionResult(
                toolOutputJson = fallbackJson,
                actionLogText = "📍 周邊搜尋：「$googleMapsSearchTarget」",
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
            put("google_maps_nearby_url", mapsSearchUrl)
            put("linkup_findings", searchRes.rawFormattedForLlm)
        }.toString()

        return LocationToolExecutionResult(
            toolOutputJson = outJson,
            actionLogText = "📍 定位結合 Linkup 周邊搜尋：「$googleMapsSearchTarget」",
            mapActionSource = mapAction,
            linkupResult = enrichedResult
        )
    }

    private fun executeOpenGoogleMapsNavigation(argsJson: JSONObject): LocationToolExecutionResult {
        val destination = argsJson.optString("destination", "").trim()
        if (destination.isBlank()) {
            return LocationToolExecutionResult(
                toolOutputJson = JSONObject().put("status", "error")
                    .put("message", "缺少目的地 (destination)").toString(),
                actionLogText = "⚠️ 導航缺少目的地"
            )
        }

        val originArg = argsJson.optString("origin", "").trim()
        val rawMode = argsJson.optString("travel_mode", "transit").lowercase().trim()
        val travelMode = when {
            rawMode.contains("walk") || rawMode.contains("步") -> "walking"
            rawMode.contains("driv") || rawMode.contains("car") || rawMode.contains("車") -> "driving"
            rawMode.contains("bicyc") || rawMode.contains("bike") || rawMode.contains("單車") -> "bicycling"
            else -> "transit"
        }
        val modeLabel = when (travelMode) {
            "walking" -> "🚶 步行"
            "driving" -> "🚗 開車/計程車"
            "bicycling" -> "🚲 單車"
            else -> "🚇 大眾運輸"
        }
        val autoLaunch = argsJson.optBoolean("auto_launch_app", true)

        val snap = getCurrentLocationSnapshot()
        val encodedDest = URLEncoder.encode(destination, "UTF-8")
        val originParam = when {
            originArg.isNotBlank() -> "&origin=${URLEncoder.encode(originArg, "UTF-8")}"
            snap != null -> "&origin=${snap.latitude},${snap.longitude}"
            else -> ""
        }

        val universalMapsUrl =
            "https://www.google.com/maps/dir/?api=1$originParam&destination=$encodedDest&travelmode=$travelMode"

        var launchedDirectly = false
        if (autoLaunch) {
            launchedDirectly = launchGoogleMapsIntent(universalMapsUrl)
        }

        val mapSource = SearchSource(
            title = "🗺️ 開啟 Google Maps 導航：$destination ($modeLabel)",
            url = universalMapsUrl,
            snippet = if (snap != null) "從 ${snap.cityOrDistrict} 出發 ➔ $destination" else "前往 $destination"
        )

        val outJson = JSONObject().apply {
            put("status", "success")
            put("destination", destination)
            put("travel_mode", travelMode)
            put("origin_used", originArg.ifBlank { snap?.addressLine ?: "手機目前位置" })
            put("google_maps_navigation_url", universalMapsUrl)
            put("launched_google_maps_app", launchedDirectly)
            put(
                "message",
                if (launchedDirectly) {
                    "已直接為使用者喚醒手機 Google Maps 導航至「$destination」（模式：$modeLabel），同時在對話氣泡附上導航按鈕。"
                } else {
                    "已在對話氣泡產生「🗺️ 開啟 Google Maps 導航：$destination ($modeLabel)」按鈕。"
                }
            )
        }.toString()

        return LocationToolExecutionResult(
            toolOutputJson = outJson,
            actionLogText = "🗺️ Google Maps 路線導航：$destination ($modeLabel)",
            mapActionSource = mapSource
        )
    }

    fun launchGoogleMapsIntent(url: String): Boolean {
        return runCatching {
            val uri = Uri.parse(url)
            val mapsIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (mapsIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(mapsIntent)
            } else {
                val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        val LOCATION_MAPS_TOOL_NAMES = setOf(
            "get_user_current_location",
            "search_nearby_places_with_linkup",
            "open_google_maps_navigation"
        )

        fun buildResponsesLocationToolSchemas(): List<JSONObject> {
            return listOf(
                JSONObject().apply {
                    put("type", "function")
                    put("name", "get_user_current_location")
                    put(
                        "description",
                        "讀取使用者手機當下的 GPS 經緯度與真實街道地址/行政區（免費免 API Key）。當使用者詢問『我現在在哪裡』、『附近有什麼』或需要計算前往下一個行事曆地點的路線時呼叫。"
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
                        "自動結合使用者手機目前的 GPS 所在位置與 Linkup 網路搜尋，查找附近的餐廳、居酒屋、藥妝店、咖啡廳、車站或景點，並自動產生 Google Maps 周邊地圖按鈕。"
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
                        "為使用者規劃前往某個目的地的 Google Maps 導航路線，可直接喚醒手機的 Google Maps App 開始導航，並在對話氣泡附上『🗺️ 開啟 Google Maps 導航』按鈕。"
                    )
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("destination", JSONObject().apply {
                                put("type", "string")
                                put("description", "目的地名稱或地址，例如：『一蘭拉麵 新宿中央東口店』、『台北 101』或行事曆上的會議地點")
                            })
                            put("origin", JSONObject().apply {
                                put("type", "string")
                                put("description", "（選填）出發地名稱或地址；若留空則自動以使用者手機目前 GPS 座標為出發點")
                            })
                            put("travel_mode", JSONObject().apply {
                                put("type", "string")
                                put("enum", JSONArray().put("transit").put("walking").put("driving").put("bicycling"))
                                put("description", "交通方式：transit (大眾運輸/捷運/電車)、walking (步行)、driving (開車/計程車)、bicycling (單車)")
                            })
                            put("auto_launch_app", JSONObject().apply {
                                put("type", "boolean")
                                put("description", "若使用者明確要求『幫我開導航』、『帶我去』、『打開地圖』則設為 true（直接跳轉 Google Maps App）；若只是推薦路線供使用者參考則設為 false（僅在氣泡顯示導航按鈕）")
                            })
                        })
                        put("required", JSONArray().put("destination"))
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
