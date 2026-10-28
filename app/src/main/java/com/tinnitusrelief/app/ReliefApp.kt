package com.tinnitusrelief.app

import android.app.Application
import android.content.Context

class ReliefApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

val Context.graph: AppGraph get() = (applicationContext as ReliefApp).graph
