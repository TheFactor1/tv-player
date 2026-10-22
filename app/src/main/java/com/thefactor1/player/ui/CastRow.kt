package com.thefactor1.player.ui

import android.graphics.Bitmap
import android.graphics.Outline
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.thefactor1.player.R
import com.thefactor1.player.meta.CastMember

/**
 * X-Ray's row of faces: a round headshot (initials until the photo arrives), the name, and who
 * they play. Built into an empty horizontal [container]; hides itself when there's no one to show.
 */
class CastRow(
    private val container: LinearLayout,
    private val fonts: Fonts,
    private val faceDp: Int,
    private val nameSp: Float,
    private val maxPeople: Int,
) {
    private val context = container.context
    private val density = context.resources.displayMetrics.density
    private val faces = HashMap<String, Pair<ImageView, TextView>>()
    private var fitted = false

    init {
        // Whole names or nobody: once laid out, drop the people at the end who'd only fit squeezed.
        container.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (!fitted || right - left != oldRight - oldLeft) container.post { fit() }
        }
    }

    fun show(cast: List<CastMember>) {
        container.removeAllViews()
        faces.clear()
        fitted = false
        cast.take(maxPeople).forEachIndexed { index, member -> container.addView(item(member, first = index == 0)) }
        container.visibility = if (container.childCount > 0) View.VISIBLE else View.GONE
    }

    fun clear() = show(emptyList())

    fun setPhoto(member: CastMember, bitmap: Bitmap) {
        val (face, initials) = faces[member.name] ?: return
        face.setImageBitmap(bitmap)
        initials.visibility = View.GONE
    }

    private fun fit() {
        val available = container.width - container.paddingStart - container.paddingEnd
        if (available <= 0) return
        fitted = true
        var used = 0
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            child.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val params = child.layoutParams as LinearLayout.LayoutParams
            used += child.measuredWidth + params.marginStart + params.marginEnd
            if (used > available) {
                container.removeViews(index, container.childCount - index)
                return
            }
        }
    }

    private fun item(member: CastMember, first: Boolean): View {
        val size = (faceDp * density).toInt()
        val face = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(ContextCompat.getColor(context, R.color.control_rest_fill))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val initials = TextView(context).apply {
            text = member.name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.ink_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSp * 0.8f)
            typeface = fonts.bold
        }
        val faceFrame = android.widget.FrameLayout(context).apply {
            addView(face, android.widget.FrameLayout.LayoutParams(size, size))
            addView(initials, android.widget.FrameLayout.LayoutParams(size, size))
        }
        faces[member.name] = face to initials

        val name = TextView(context).apply {
            text = member.name
            maxLines = 1
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSp)
            typeface = fonts.semibold
        }
        val role = TextView(context).apply {
            text = member.character
            maxLines = 1
            visibility = if (member.character.isNullOrEmpty()) View.GONE else View.VISIBLE
            setTextColor(ContextCompat.getColor(context, R.color.ink_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSp - 2f)
            typeface = fonts.medium
        }
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(role)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = listOfNotNull(member.name, member.character).joinToString(", ")
            addView(faceFrame)
            addView(words, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (10 * density).toInt()
            })
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (!first) marginStart = (24 * density).toInt()
            }
        }
    }
}
