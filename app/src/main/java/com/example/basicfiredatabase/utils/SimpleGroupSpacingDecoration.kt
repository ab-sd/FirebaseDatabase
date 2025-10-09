package com.example.basicfiredatabase.utils


class SimpleGroupSpacingDecoration(
    private val adapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>,
    private val spacingPx: Int,
    private val headerViewType: Int = 0 // default header type used in your adapter
) : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: android.view.View,
        parent: androidx.recyclerview.widget.RecyclerView,
        state: androidx.recyclerview.widget.RecyclerView.State
    ) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return

        val itemCount = adapter.itemCount
        val viewType = adapter.getItemViewType(pos)

        val isHeader = viewType == headerViewType
        val isLast = pos == itemCount - 1
        val nextIsHeader = if (!isLast) adapter.getItemViewType(pos + 1) == headerViewType else true

        // If this is an image (not header) and the next item is a header (or it's the last item),
        // add bottom spacing to separate event groups.
        if (!isHeader && nextIsHeader) {
            outRect.set(0, 0, 0, spacingPx)
        } else {
            outRect.set(0, 0, 0, 0)
        }
    }
}