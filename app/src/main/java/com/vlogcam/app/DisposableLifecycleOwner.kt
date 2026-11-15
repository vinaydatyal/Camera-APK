package com.vlogcam.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * A lifecycle that mirrors [parent] until [destroy] is called.
 *
 * CameraX caches a dual-camera session per (LifecycleOwner, camera pair) and only reads the
 * CompositionSettings when that session is first created. Binding each new dual layout to a
 * fresh owner (and destroying the old one) forces CameraX to apply the new layout.
 */
class DisposableLifecycleOwner(private val parent: LifecycleOwner) : LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    private var destroyed = false

    private val observer = LifecycleEventObserver { _, event ->
        if (!destroyed) registry.handleLifecycleEvent(event)
    }

    init {
        parent.lifecycle.addObserver(observer)
    }

    override val lifecycle: Lifecycle get() = registry

    fun destroy() {
        if (destroyed) return
        destroyed = true
        parent.lifecycle.removeObserver(observer)
        if (registry.currentState == Lifecycle.State.INITIALIZED) {
            registry.currentState = Lifecycle.State.CREATED
        }
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
