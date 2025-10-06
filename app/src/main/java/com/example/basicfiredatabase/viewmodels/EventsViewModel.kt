package com.example.basicfiredatabase.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * Simple shared ViewModel used to tell sibling fragments to reload.
 * We send a pair: (originId, timestamp) so receivers can ignore reloads
 * coming from themselves and treat each change as a new event.
 */
class EventsViewModel : ViewModel() {
    private val _reloadTrigger = MutableLiveData<Pair<String, Long>>()
    val reloadTrigger: LiveData<Pair<String, Long>> = _reloadTrigger

    fun triggerReload(originId: String) {
        _reloadTrigger.value = Pair(originId, System.currentTimeMillis())
    }
}
