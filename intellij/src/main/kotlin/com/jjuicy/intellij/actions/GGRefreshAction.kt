package com.jjuicy.intellij.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.jjuicy.intellij.data.GGRepository

class GGRefreshAction : AnAction("Refresh", "Reload the commit log", AllIcons.Actions.Refresh), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { GGRepository.getInstance(it).isReady } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.messageBus.syncPublisher(com.jjuicy.intellij.data.GG_LOG_CHANGED)
            .onLogChanged()
    }
}
