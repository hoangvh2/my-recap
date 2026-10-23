package com.vh.myrecap.reminder

import com.vh.myrecap.core.HttpClient
import com.vh.myrecap.core.Holidays
import com.vh.myrecap.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches holidays-vn.json from the web app's site, so a newly announced schedule (swapped working days, a longer Tết)
 * reaches the phone without a new release. Failing is fine: the bundled or last downloaded schedule stays.
 */
object HolidayUpdater {
    const val URL = "https://desk.vhub.id.vn/holidays-vn.json"

    /** True when a newer schedule was downloaded and saved. */
    suspend fun refresh(settings: SettingsRepository, http: HttpClient = HttpClient(10_000, 15_000), url: String = URL): Boolean = withContext(Dispatchers.IO) {
        try {
            val res = http.get(url)
            if (res.code != 200) return@withContext false
            val got = Holidays.parseData(res.body)
            val current = Holidays.newest(settings.current.holidayJson)
            if (got.overrides.isEmpty() || got.updated <= current.updated) return@withContext false
            settings.update { it.copy(holidayJson = res.body) }
            true
        } catch (_: Exception) {
            false
        }
    }
}
