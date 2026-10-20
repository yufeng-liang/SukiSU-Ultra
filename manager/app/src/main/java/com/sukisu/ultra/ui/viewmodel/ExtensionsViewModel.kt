package com.sukisu.ultra.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sukisu.ultra.data.repository.ExtensionRepository
import com.sukisu.ultra.data.repository.ExtensionRepositoryImpl
import com.sukisu.ultra.ui.util.ExtensionId
import com.sukisu.ultra.ui.util.ExtensionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExtensionsUiState(
    val loading: Boolean = false,
    val states: Map<ExtensionId, ExtensionState> = emptyMap(),
)

class ExtensionsViewModel(
    private val repo: ExtensionRepository = ExtensionRepositoryImpl()
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExtensionsUiState())
    val uiState: StateFlow<ExtensionsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val newStates = withContext(Dispatchers.IO) { repo.probeAll() }
            _uiState.update { it.copy(loading = false, states = newStates) }
        }
    }
}
