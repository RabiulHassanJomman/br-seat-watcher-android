package com.iqbal.seatwatcher.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class GlobalState { PAUSED, WATCHING, LOGIN_NEEDED, OFFLINE, BLOCKED, NO_WATCHES }

data class ServiceStatus(
    val state: GlobalState = GlobalState.PAUSED,
    /** Wall-clock time of the next scheduled check, 0 if none. */
    val nextCheckAt: Long = 0,
    val lastCheckAt: Long = 0,
    val checking: Boolean = false,
    val message: String = "",
)

/** Live status shared by the service and the UI (in-process). */
class StatusBus {
    private val _status = MutableStateFlow(ServiceStatus())
    val status: StateFlow<ServiceStatus> = _status

    fun update(f: (ServiceStatus) -> ServiceStatus) = _status.update(f)
    fun set(s: ServiceStatus) { _status.value = s }
}
