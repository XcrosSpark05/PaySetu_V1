package com.paysetu.app

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.NOT_LOGGED_IN)
    val loginState: StateFlow<LoginState> = _loginState

    fun checkUserStatus() {
        repository.checkUserBinding { state ->
            _loginState.value = state
        }
    }

    // This function allows the UI to manually trigger a state change (e.g., after OTP)
    fun updateState(newState: LoginState) {
        _loginState.value = newState
    }
}