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
 * Allows configuring the path to the jj binary.
 */
class GGSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private val jjPathField = TextFieldWithBrowseButton()

    override fun getDisplayName(): String = "jjuicy"

    override fun createComponent(): JComponent {
        jjPathField.addBrowseFolderListener(
            null,
            com.intellij.openapi.fileChooser.FileChooserDescriptor(
                true, false, false, false, false, false
            ).withTitle("Select jj Binary")
                .withDescription("Choose the path to the jj executable (leave blank to use PATH)")
        )

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel("jj binary path:"),
                jjPathField,
                1,
                false
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel!!
    }

    override fun isModified(): Boolean = jjPathField.text != GGSettings.instance.jjPath

    override fun apply() {
        GGSettings.instance.jjPath = jjPathField.text
    }

    override fun reset() {
        jjPathField.text = GGSettings.instance.jjPath
    }

    override fun disposeUIResources() {
        panel = null
    }
}
