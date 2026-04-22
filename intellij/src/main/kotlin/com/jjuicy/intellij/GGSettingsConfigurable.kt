package com.jjuicy.intellij

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page under Settings → Tools → jjuicy.
 *
 * Allows configuring the path to the gg/jjuicy binary.
 */
class GGSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private val binaryPathField = TextFieldWithBrowseButton()

    override fun getDisplayName(): String = "jjuicy"

    override fun createComponent(): JComponent {
        binaryPathField.addBrowseFolderListener(
            null,
            com.intellij.openapi.fileChooser.FileChooserDescriptor(
                true, false, false, false, false, false
            ).withTitle("Select jjuicy/gg Binary")
                .withDescription("Choose the path to the jjuicy or gg executable")
        )

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel("Binary path:"),
                binaryPathField,
                1,
                false
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        return binaryPathField.text != GGSettings.instance.binaryPath
    }

    override fun apply() {
        GGSettings.instance.binaryPath = binaryPathField.text
    }

    override fun reset() {
        binaryPathField.text = GGSettings.instance.binaryPath
    }

    override fun disposeUIResources() {
        panel = null
    }
}
