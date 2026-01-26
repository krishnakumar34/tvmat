package com.example.tvmat

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URL

data class Channel(
    val id: String, // "1", "2", etc.
    val name: String,
    val group: String,
    val url: String
)

class PlaylistViewModel : ViewModel() {

    // RAW DATA
    private val _allChannels = MutableStateFlow<List<Channel>>(emptyList())
    
    // UI STATES
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    // FILTERED DATA (Combines raw data + search query)
    val filteredChannels = combine(_allChannels, _searchQuery) { channels, query ->
        if (query.isEmpty()) {
            channels
        } else {
            channels.filter { it.name.contains(query, ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ACTIONS
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Loads playlist from either a Remote URL or a Local Content URI.
     * @param url The http/https URL or content:// URI
     * @param contentResolver Required only if reading from a local 'content://' URI
     */
    fun loadPlaylist(url: String, contentResolver: ContentResolver? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                // Reset search when loading new
                _searchQuery.value = ""
                
                val content = if (url.startsWith("content://") && contentResolver != null) {
                    // Handle Local File
                    contentResolver.openInputStream(Uri.parse(url))?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: ""
                } else {
                    // Handle Network URL
                    URL(url).readText()
                }

                val parsedList = parseM3u(content)
                _allChannels.value = parsedList
            } catch (e: Exception) {
                e.printStackTrace()
                // In a real app, handle error state here
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun parseM3u(content: String): List<Channel> {
        val list = mutableListOf<Channel>()
        val lines = content.lines()
        
        var name = "Unknown"
        var group = "Uncategorized"
        var counter = 1

        for (line in lines) {
            val trim = line.trim()
            if (trim.startsWith("#EXTINF")) {
                // Parse Name (after last comma)
                name = trim.substringAfterLast(",").trim()
                
                // Parse Group (regex or simple split)
                if (trim.contains("group-title=\"")) {
                    group = trim.substringAfter("group-title=\"").substringBefore("\"")
                } else {
                    group = "All"
                }
            } else if (trim.isNotEmpty() && !trim.startsWith("#")) {
                list.add(Channel(
                    id = counter.toString(),
                    name = name, 
                    group = group, 
                    url = trim
                ))
                counter++
            }
        }
        return list
    }
}
