package com.jjuicy.intellij

import com.google.gson.JsonParser
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.ui.LafManagerListener
import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.UISettingsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.BorderLayout
import java.awt.Font
import java.net.HttpURLConnection
import java.net.URI
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
    private val project: Project,
    private val processManager: GGProcessManager,
    private val parentDisposable: Disposable,
) : JPanel(BorderLayout()), GGProcessManager.ProcessListener {

    private var browser: JBCefBrowser? = null
    private var jsQueryBridge: JBCefJSQuery? = null
    private var activeDiffFile: VirtualFile? = null

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

    // match the IDE's UI font size so GG text is proportional to surrounding panels
    private fun applyFontSize(jbBrowser: JBCefBrowser) {
        val fontSize = UISettings.getInstance().fontSize2D
        jbBrowser.cefBrowser.executeJavaScript(
            "document.documentElement.style.fontSize = '${fontSize}px';",
            jbBrowser.cefBrowser.url,
            0
        )
    }

    // wire window.__gg_openDiff so the frontend can request IntelliJ's native diff view
    private fun applyDiffBridge(jbBrowser: JBCefBrowser) {
        val query = jsQueryBridge ?: return
        val injection = query.inject("json")
        jbBrowser.cefBrowser.executeJavaScript(
            "window.__gg_openDiff = function(json) { $injection };",
            jbBrowser.cefBrowser.url,
            0
        )
    }

    // fetch before/after content from backend and open IntelliJ's diff viewer
    private fun openDiff(serverUrl: String, requestJson: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val url = URI.create("$serverUrl/api/query/query_file_content").toURL()
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(requestJson.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode != 200) {
                    LOG.warn("query_file_content returned ${conn.responseCode}")
                    return@executeOnPooledThread
                }

                val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val responseObj = JsonParser.parseString(responseBody).asJsonObject
                val before = if (responseObj.get("before")?.isJsonNull == false)
                    responseObj.get("before")?.asString else null
                val after = if (responseObj.get("after")?.isJsonNull == false)
                    responseObj.get("after")?.asString else null

                // extract filename for display and file-type detection
                val fileName = try {
                    val reqObj = JsonParser.parseString(requestJson).asJsonObject
                    reqObj.getAsJsonObject("path")?.get("relative_path")?.asString
                } catch (_: Exception) { null }

                ApplicationManager.getApplication().invokeLater {
                    val factory = DiffContentFactory.getInstance()
                    val fileType = fileName?.let {
                        FileTypeManager.getInstance().getFileTypeByFileName(it)
                    }

                    val beforeContent = if (fileType != null)
                        factory.create(project, before ?: "", fileType)
                    else
                        factory.create(project, before ?: "")

                    val afterContent = if (fileType != null)
                        factory.create(project, after ?: "", fileType)
                    else
                        factory.create(project, after ?: "")

                    val request = SimpleDiffRequest(
                        fileName ?: "Diff",
                        beforeContent,
                        afterContent,
                        "Before",
                        "After",
                    )

                    val editorManager = FileEditorManager.getInstance(project)
                    activeDiffFile?.let { editorManager.closeFile(it) }
                    val openBefore = editorManager.openFiles.toSet()
                    DiffManager.getInstance().showDiff(project, request)
                    activeDiffFile = (editorManager.openFiles.toSet() - openBefore).firstOrNull()
                }
            } catch (e: Exception) {
                LOG.warn("Failed to open IntelliJ diff view", e)
            }
        }
    }

    private fun showBrowser(url: String) {
        val jbBrowser = JBCefBrowser.createBuilder()
            .setUrl("$url?embedded")
            .build()

        // create the JS-to-Kotlin bridge for opening diffs in the native viewer
        val jsQuery = JBCefJSQuery.create(jbBrowser as JBCefBrowserBase)
        jsQuery.addHandler { json ->
            val serverUrl = processManager.serverUrl
            if (serverUrl != null) {
                openDiff(serverUrl, json)
            }
            JBCefJSQuery.Response(null)
        }
        jsQueryBridge = jsQuery
        Disposer.register(parentDisposable, jsQuery)

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
                        applyFontSize(jbBrowser)
                        applyDiffBridge(jbBrowser)
                    }
                }
            },
            jbBrowser.cefBrowser
        )

        // re-apply whenever the user changes the IDE theme or font size at runtime
        ApplicationManager.getApplication().messageBus
            .connect(parentDisposable)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { applyScheme(jbBrowser) })
        ApplicationManager.getApplication().messageBus
            .connect(parentDisposable)
            .subscribe(UISettingsListener.TOPIC, UISettingsListener { applyFontSize(jbBrowser) })

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
        jsQueryBridge = null
        activeDiffFile = null
        browser?.let { Disposer.dispose(it) }
        browser = null
    }
}
