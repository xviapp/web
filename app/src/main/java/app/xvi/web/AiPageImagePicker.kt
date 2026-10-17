package app.xvi.web

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Checkable
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * A grid of the page's pictures to check, at most [limit], led by a tile that opens the album instead.
 * Previews load as their cells appear. Choosing the album keeps the pictures checked so far.
 */
internal class AiPageImagePicker(
    private val host: BrowserActivity,
    private val target: BrowserTab,
    private val pictures: AiPageImages,
    private val images: List<AiImages.PageImage>,
    selected: Set<String>,
    private val limit: Int,
    private val album: () -> Unit,
    private val done: (List<AiImages.PageImage>) -> Unit
) {
    private val ui = host.ui
    private val scope = CoroutineScope(host.lifecycleScope.coroutineContext + Job(host.lifecycleScope.coroutineContext[Job]))
    private val transfers = Semaphore(4)
    private val thumbnails = mutableMapOf<Int, Bitmap?>()
    private val started = mutableSetOf<Int>()
    private val checked = BooleanArray(images.size) { images[it].key in selected }
    private val names = images.mapIndexed { index, image -> image.name.ifBlank { ui.str(R.string.ai_page_image_label, index + 1) } }
    private var dialog: AlertDialog? = null
    /** Grid position 0 is the album tile; picture `i` sits at `i + 1`. */
    private val adapter = object : BaseAdapter() {
        override fun getCount() = images.size + 1
        override fun getItem(position: Int) = if (position == 0) null else images[position - 1]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (position == 0) return convertView ?: AlbumTile()
            val index = position - 1
            return (convertView as? Cell ?: Cell()).apply {
                contentDescription = names[index]
                picture.setImageBitmap(thumbnails[index])
                status.visibility = if (thumbnails[index] == null) View.VISIBLE else View.GONE
                status.setText(if (thumbnails.containsKey(index)) R.string.ai_image_preview_failed else R.string.ai_image_loading)
                load(index)
            }
        }
    }

    fun show() {
        val grid = GridView(host).apply {
            adapter = this@AiPageImagePicker.adapter
            numColumns = 3
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = ui.dp(6)
            verticalSpacing = ui.dp(6)
            choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
            selector = ui.selectable(8)
            checked.forEachIndexed { index, chosen -> setItemChecked(index + 1, chosen) }
            setOnItemClickListener { _, _, position, _ ->
                if (position == 0) {
                    setItemChecked(0, false)
                    album()
                    finish()
                    return@setOnItemClickListener
                }
                val chosen = isItemChecked(position)
                if (chosen && checked.count { it } >= limit) {
                    setItemChecked(position, false)
                    host.toast(R.string.ai_image_limit)
                } else checked[position - 1] = chosen
                refreshTitle()
            }
        }
        val note = ui.label(ui.str(R.string.ai_images_note), 12f, ui.palette.muted).apply { setPadding(0, 0, 0, ui.dp(8)) }
        val body = ui.dialogForm(note, grid)
        dialog = host.browserDialog(target).setTitle(title())
            .setView(body)
            .setPositiveButton(R.string.action_done) { _, _ -> finish() }
            .setNegativeButton(R.string.action_cancel, null).create().also { picker ->
                picker.setOnDismissListener { release() }
                picker.show()
                // Leave the title and actions visible even when a page contains many pictures.
                val rows = (images.size + 3) / 3
                val cap = (host.root.height * .55f).toInt()
                grid.layoutParams = grid.layoutParams.apply { height = minOf(rows * ui.dp(112), cap) }
                grid.post {
                    val cell = (grid.width - grid.horizontalSpacing * 2) / 3
                    if (cell > 0) grid.layoutParams = grid.layoutParams.apply { height = minOf(rows * (cell + grid.verticalSpacing), cap) }
                }
            }
    }

    fun dismiss() { dialog?.dismiss() }

    private fun finish() {
        dismiss()
        done(images.filterIndexed { index, _ -> checked[index] })
    }

    private fun title() = ui.str(R.string.ai_page_images_left, (limit - checked.count { it }).coerceAtLeast(0))
    private fun refreshTitle() { dialog?.setTitle(title()) }

    private fun release() {
        scope.cancel()
        thumbnails.values.forEach { it?.recycle() }
        thumbnails.clear()
    }

    private fun load(index: Int) {
        if (!started.add(index)) return
        scope.launch {
            val bitmap = transfers.withPermit { pictures.preview(images[index]) }
            if (!pictures.current()) { bitmap?.recycle(); dismiss(); return@launch }
            thumbnails[index] = bitmap
            adapter.notifyDataSetChanged()
        }
    }

    /** Opens the album; it is never checked itself. */
    private inner class AlbumTile : FrameLayout(host) {
        init {
            background = ui.rounded(ui.palette.base, 8)
            contentDescription = ui.str(R.string.ai_images_from_album)
            val content = ui.column().apply {
                gravity = Gravity.CENTER
                addView(ImageView(host).apply {
                    setImageDrawable(ui.tintedIcon(R.drawable.ic_image_24, ui.palette.accent))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                addView(ui.label(ui.str(R.string.ai_images_from_album), 13f).apply {
                    gravity = Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setPadding(0, ui.dp(6), 0, 0)
                })
            }
            addView(content, LayoutParams(-1, -1))
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }

    /** A square picture with a round check in its corner, as photo pickers show; the grid checks it as a whole. */
    private inner class Cell : FrameLayout(host), Checkable {
        val picture = ImageView(host).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val status = ui.label("", 11f, ui.palette.muted).apply { gravity = Gravity.CENTER }
        private var checkedNow = false
        // Drawn over any picture, so the empty circle keeps the same light look in both themes.
        private val empty = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(0xCCFFFFFF.toInt()); setStroke(ui.dp(2), 0xFF8A8A8A.toInt())
        }
        private val full = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.palette.accent) }
        private val check = ImageView(host).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            background = empty
        }
        private val frame = View(host).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = ui.dp(8).toFloat(); setStroke(ui.dp(3), ui.palette.accent)
            }
        }
        init {
            background = ui.rounded(ui.palette.base, 8)
            clipToOutline = true
            addView(picture, LayoutParams(-1, -1))
            addView(status, LayoutParams(-1, -1))
            addView(frame, LayoutParams(-1, -1))
            addView(check, LayoutParams(ui.dp(22), ui.dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, ui.dp(6), ui.dp(6), 0) })
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        override fun isChecked() = checkedNow
        override fun setChecked(value: Boolean) {
            checkedNow = value
            check.background = if (value) full else empty
            check.setImageDrawable(if (value) ui.tintedIcon(R.drawable.ic_check_24, ui.palette.onAccent) else null)
            frame.visibility = if (value) View.VISIBLE else View.GONE
        }
        override fun toggle() { setChecked(!isChecked()) }
    }
}
