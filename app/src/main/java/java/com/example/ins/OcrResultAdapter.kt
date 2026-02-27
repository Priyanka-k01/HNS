package java.com.example.ins // Make sure this matches your package

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView

class OcrResultsAdapter(
    private val ocrItems: MutableList<OcrTextData>,
    private val onItemTextChanged: (position: Int, newText: String) -> Unit,
    private val onItemRemoved: (position: Int) -> Unit,
    private val onItemSelected: (item: OcrTextData) -> Unit // <<< ADD THIS CALLBACK
) : RecyclerView.Adapter<OcrResultsAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val editText: EditText = itemView.findViewById(R.id.etOcrText)
        val removeButton: ImageButton = itemView.findViewById(R.id.btnRemoveOcrItem)
        private var currentTextWatcher: TextWatcher? = null

        fun bind(item: OcrTextData, position: Int) {
            // Remove previous watcher to prevent multiple listeners on recycled views
            currentTextWatcher?.let { editText.removeTextChangedListener(it) }
            editText.setText(item.text)

            currentTextWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (adapterPosition != RecyclerView.NO_POSITION) { // Check for valid position
                        onItemTextChanged(adapterPosition, s.toString())
                    }
                }
            }
            editText.addTextChangedListener(currentTextWatcher)

            removeButton.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onItemRemoved(adapterPosition)
                }
            }

            // --- Call onItemSelected when the item is interacted with ---
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && adapterPosition != RecyclerView.NO_POSITION) {
                    onItemSelected(ocrItems[adapterPosition])
                }
            }
            // You might also want to trigger selection on item click itself:
            itemView.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onItemSelected(ocrItems[adapterPosition])
                    editText.requestFocus() // Optionally focus the EditText as well
                }
            }
            // --- End of selection handling ---
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ocr_element, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(ocrItems[position], position)
    }

    override fun getItemCount(): Int = ocrItems.size

    fun updateData(newOcrItems: List<OcrTextData>) {
        ocrItems.clear()
        ocrItems.addAll(newOcrItems)
        notifyDataSetChanged()
    }

    fun addItem(item: OcrTextData) {
        ocrItems.add(item)
        notifyItemInserted(ocrItems.size - 1)
    }

    fun removeItem(position: Int) {
        if (position >= 0 && position < ocrItems.size) {
            // Let the activity manage the source list (currentOcrData)
            // The adapter should only notify about the event.
            // ocrItems.removeAt(position) // Adapter should not modify the list passed to it directly
            // notifyItemRemoved(position)
            // if (position < ocrItems.size) {
            //      notifyItemRangeChanged(position, ocrItems.size - position)
            // }
        }
    }
}
