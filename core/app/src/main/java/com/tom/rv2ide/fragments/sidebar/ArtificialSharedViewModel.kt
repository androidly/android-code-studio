package com.tom.rv2ide.fragments.sidebar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents

class ArtificialSharedViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    val aiAgent = AIAgentManager(appContext)
    val agents = Agents(appContext)
}
