package com.jjuicy.intellij

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

private val LOG = logger<GGBrowserPanel>()

/**
 * A panel that embeds the jjuicy web frontend via JCEF.
 *
 * Shows a loading state while the backend process is starting, then swaps
 * to the JCEF browser once the server URL is known. Falls back to an error
 * panel if JCEF is not supported.
 */
class GGBrowserPanel(
    private val processManager: GGProcessManager,
    private val parentDisposable: Disposable,
) : JPanel(BorderLayout()), GGProcessManager.ProcessListener {

    private var browser: JBCefBrowser? = null

    init {
        if (!JBCefApp.isSupported()) {
            showMessage("JCEF is not available in this IDE configuration.\nJjuicy requires JCEF to display the web UI.")
        } else {
            showLoadingState()
            processManager.addListener(this)
        }
    }

    override fun onReady(url: String) {
        SwingUtilities.invokeLater {
            showBrowser(url)
        }
    }

    override fun onCrashed() {
        SwingUtilities.invokeLater {
            showMessage("The jjuicy backend process crashed.\nUse the notification to restart it.")
        }
    }

    private fun showLoadingState() {
        removeAll()
        add(JLabel("Starting jjuicy…", SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.PLAIN, 13f)
        }, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun showMessage(message: String) {
        removeAll()
        add(JLabel("<html>${message.replace("\n", "<br>")}</html>", SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.PLAIN, 13f)
        }, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    // "light" for bright IDE themes, "dark" otherwise
    private fun currentScheme(): String = if (JBColor.isBright()) "light" else "dark"

    private fun applyScheme(jbBrowser: JBCefBrowser) {
        val scheme = currentScheme()
        jbBrowser.cefBrowser.executeJavaScript(
            "document.documentElement.setAttribute('data-gg-scheme', '$scheme')",
            jbBrowser.cefBrowser.url,
            0
        )
    }

    // secondary signal for late-arriving code that checks window.__GG_EMBEDDED__
    private fun applyEmbeddedMode(jbBrowser: JBCefBrowser) {
        jbBrowser.cefBrowser.executeJavaScript(
            "window.__GG_EMBEDDED__ = true;",
            jbBrowser.cefBrowser.url,
            0
        )
    }

    private fun showBrowser(url: String) {
        val jbBrowser = JBCefBrowser.createBuilder()
            .setUrl("$url?embedded")
            .build()

        // prevent JCEF from opening target=_blank links inside the panel
        jbBrowser.jbCefClient.addLifeSpanHandler(
            object : org.cef.handler.CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(
                    browser: org.cef.browser.CefBrowser?,
                    frame: org.cef.browser.CefFrame?,
                    targetUrl: String?,
                    targetFrameName: String?,
                ): Boolean {
                    // open external links in the system browser
                    if (targetUrl != null) {
                        try {
                            java.awt.Desktop.getDesktop().browse(java.net.URI(targetUrl))
                        } catch (e: Exception) {
                            LOG.warn("Could not open URL in system browser: $targetUrl", e)
                        }
                    }
                    // return true to cancel the popup (we handled it)
                    return true
                }
            },
            jbBrowser.cefBrowser
        )

        // push the current IDE theme once the page has finished loading
        jbBrowser.jbCefClient.addLoadHandler(
            object : org.cef.handler.CefLoadHandlerAdapter() {
                override fun onLoadEnd(
                    browser: org.cef.browser.CefBrowser?,
                    frame: org.cef.browser.CefFrame?,
                    httpStatusCode: Int,
                ) {
                    if (frame?.isMain == true) {
                        applyScheme(jbBrowser)
                        applyEmbeddedMode(jbBrowser)
                    }
                }
            },
            jbBrowser.cefBrowser
        )

        // re-apply whenever the user changes the IDE theme at runtime
        ApplicationManager.getApplication().messageBus
            .connect(parentDisposable)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { applyScheme(jbBrowser) })

        Disposer.register(parentDisposable, jbBrowser)

        removeAll()
        add(jbBrowser.component, BorderLayout.CENTER)
        revalidate()
        repaint()

        browser = jbBrowser
        LOG.info("JCEF browser loaded jjuicy at $url")
    }

    fun dispose() {
        processManager.removeListener(this)
        browser?.let { Disposer.dispose(it) }
        browser = null
    }
}
