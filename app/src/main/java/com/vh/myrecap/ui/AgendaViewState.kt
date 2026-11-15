package com.vh.myrecap.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vh.myrecap.core.OwnerFilter
import java.time.YearMonth

/**
 * Where the person is in the secretary tab: the list (overview, tasks, calendar, expenses, notes), the
 * filters, the month being looked at and the scroll position. It lives in [AppViewModel] because opening
 * an item's editor takes the home screen out of composition; editing several expenses in a row must
 * return to the same list each time.
 */
class AgendaViewState {
    var filterIndex by mutableIntStateOf(0)
    var noteKindKey by mutableStateOf<String?>(null)
    var notePending by mutableStateOf(false)
    var monthView by mutableStateOf(false)
    var ownerFilter by mutableStateOf(OwnerFilter.ALL)
    var expenseMonth by mutableStateOf(YearMonth.now())
    val list = LazyListState()
}
