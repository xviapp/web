package com.v2ray.ang.ui.base

import android.net.Uri
import android.os.Bundle
import com.v2ray.ang.helper.FileChooserHelper

/**
 * HelperBaseComponentActivity extends BaseComponentActivity with file chooser helpers for
 * selecting and creating files. Activities that don't need them extend BaseComponentActivity.
 */
abstract class HelperBaseComponentActivity : BaseComponentActivity() {

    private lateinit var fileChooser: FileChooserHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileChooser = FileChooserHelper(this)
    }

    /**
     * Launch file chooser with ACTION_GET_CONTENT intent.
     * Convenience method that delegates to fileChooser helper.
     *
     * @param mimeType MIME type filter for files
     * @param onResult Callback invoked with the selected file URI (null if cancelled)
     */
    protected fun launchFileChooser(
        mimeType: String = "*/*",
        onResult: (Uri?) -> Unit
    ) {
        fileChooser.launch(mimeType, onResult)
    }

    /**
     * Launch document creator to create a new file at user-selected location.
     * Convenience method that delegates to fileChooser helper.
     * Note: No permission check needed as CreateDocument uses Storage Access Framework.
     *
     * @param fileName Default file name for the new document
     * @param onResult Callback invoked with the created file URI (null if cancelled)
     */
    protected fun launchCreateDocument(
        fileName: String,
        onResult: (Uri?) -> Unit
    ) {
        fileChooser.createDocument(fileName, onResult)
    }
}
