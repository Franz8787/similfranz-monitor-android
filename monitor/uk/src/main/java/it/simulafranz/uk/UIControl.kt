package it.simulafranz.uk

const val UIControlEventTouchDown = 1 shl 0
const val UIControlEventTouchDragInside = 1 shl 2
const val UIControlEventTouchDragOutside = 1 shl 3
const val UIControlEventTouchDragEnter = 1 shl 4
const val UIControlEventTouchDragExit = 1 shl 5
const val UIControlEventTouchUpInside = 1 shl 6
const val UIControlEventTouchUpOutside = 1 shl 7
const val UIControlEventTouchCancel = 1 shl 8
const val UIControlEventValueChanged = 1 shl 12

const val UIControlStateNormal = 0
const val UIControlStateHighlighted = 1
const val UIControlStateDisabled = 2
const val UIControlStateSelected = 4

const val UIButtonTypeCustom = 0
const val UIButtonTypeSystem = 1

/**
 * UIControl. `addTarget:action:forControlEvents:` diventa `addTarget(eventi) { controllo -> … }`.
 * enabled / selected sono quelli di Android: `isEnabled`, `isSelected`.
 */
open class UIControl(frame: CGRect = CGRect()) : UIView(frame) {
    private class Target(val events: Int, val action: (UIControl) -> Unit)
    private val targets = ArrayList<Target>()
    var highlighted: Boolean = false
        set(v) { if (field == v) return; field = v; stateChanged() }
    var tracking = false; private set
    private var inside = false
    /** margine oltre il bordo entro cui il dito è ancora "dentro" (come su iOS) */
    private val slop = 44.0

    fun addTarget(forControlEvents: Int, action: (UIControl) -> Unit) { targets.add(Target(forControlEvents, action)) }
    fun removeAllTargets() { targets.clear() }
    fun sendActionsForControlEvents(events: Int) { for (t in ArrayList(targets)) if (t.events and events != 0) t.action(this) }

    override fun setEnabled(enabled: Boolean) { val ch = enabled != isEnabled; super.setEnabled(enabled); if (ch) stateChanged() }
    override fun setSelected(selected: Boolean) { val ch = selected != isSelected; super.setSelected(selected); if (ch) stateChanged() }
    protected open fun stateChanged() { invalidate() }

    override fun hitTest(p: CGPoint): UIView? {
        // un controllo disattivato riceve comunque il tocco (e lo ignora), come su iOS
        return super.hitTest(p)
    }
    private fun near(p: CGPoint) = p.x >= -slop && p.y >= -slop && p.x < bounds.width + slop && p.y < bounds.height + slop

    override fun touchesBegan(p: CGPoint) {
        if (!isEnabled) return
        tracking = true; inside = true; highlighted = true
        sendActionsForControlEvents(UIControlEventTouchDown)
    }
    override fun touchesMoved(p: CGPoint) {
        if (!tracking) return
        val now = near(p)
        if (now != inside) { inside = now; highlighted = now; sendActionsForControlEvents(if (now) UIControlEventTouchDragEnter else UIControlEventTouchDragExit) }
        else sendActionsForControlEvents(if (now) UIControlEventTouchDragInside else UIControlEventTouchDragOutside)
    }
    override fun touchesEnded(p: CGPoint) {
        if (!tracking) return
        tracking = false; highlighted = false
        sendActionsForControlEvents(if (near(p)) UIControlEventTouchUpInside else UIControlEventTouchUpOutside)
    }
    override fun touchesCancelled(p: CGPoint) {
        if (!tracking) return
        tracking = false; highlighted = false
        sendActionsForControlEvents(UIControlEventTouchCancel)
    }
}

/** UIButton: titolo (anche su più righe) per stato, colori per stato, margini del titolo. */
open class UIButton(frame: CGRect = CGRect(), val buttonType: Int = UIButtonTypeCustom) : UIControl(frame) {
    val titleLabel = UILabel(CGRect(0.0, 0.0, frame.width, frame.height))
    private val titles = HashMap<Int, String?>()
    private val attributed = HashMap<Int, NSAttributedString?>()
    private val colors = HashMap<Int, UIColor>()
    var titleEdgeInsets = UIEdgeInsets(); set(v) { field = v; layoutSubviews() }
    var contentEdgeInsets = UIEdgeInsets(); set(v) { field = v; layoutSubviews() }
    /** 0 centro, 1 sinistra, 2 destra (UIControlContentHorizontalAlignment…) */
    var contentHorizontalAlignment: Int = 0
        set(v) { field = v; titleLabel.textAlignment = when (v) { 1 -> NSTextAlignmentLeft; 2 -> NSTextAlignmentRight; else -> NSTextAlignmentCenter } }

    init {
        titleLabel.textAlignment = NSTextAlignmentCenter
        titleLabel.font = UIFont.systemFontOfSize(if (buttonType == UIButtonTypeSystem) 15 else 18)
        titleLabel.textColor = if (buttonType == UIButtonTypeSystem) UIColor.systemBlue else UIColor.whiteColor
        addView(titleLabel)
    }

    override fun layoutSubviews() {
        val r = UIEdgeInsetsInsetRect(UIEdgeInsetsInsetRect(bounds, contentEdgeInsets), titleEdgeInsets)
        titleLabel.frame = CGRect(r.x, r.y, maxOf(r.width, 0.0), maxOf(r.height, 0.0))
    }

    fun setTitle(title: String?, forState: Int = UIControlStateNormal) { titles[forState] = title; refresh() }
    fun setAttributedTitle(title: NSAttributedString?, forState: Int = UIControlStateNormal) { attributed[forState] = title; refresh() }
    fun setTitleColor(color: UIColor, forState: Int = UIControlStateNormal) { colors[forState] = color; refresh() }
    fun titleForState(state: Int): String? = titles[state] ?: titles[UIControlStateNormal]
    fun titleColorForState(state: Int): UIColor? = colors[state] ?: colors[UIControlStateNormal]
    val currentTitle: String? get() = titleForState(state())

    private fun state(): Int = when { !isEnabled -> UIControlStateDisabled; highlighted -> UIControlStateHighlighted; isSelected -> UIControlStateSelected; else -> UIControlStateNormal }
    override fun stateChanged() { refresh() }
    override fun tintColorDidChange() { refresh() }
    private fun refresh() {
        val st = state()
        val at = attributed[st] ?: attributed[UIControlStateNormal]
        if (at != null) titleLabel.attributedText = at else titleLabel.text = titles[st] ?: titles[UIControlStateNormal]
        val base = colors[UIControlStateNormal] ?: if (buttonType == UIButtonTypeSystem) (tintColor ?: UIColor.systemBlue) else UIColor.whiteColor
        titleLabel.textColor = colors[st] ?: base
        // i pulsanti di sistema si attenuano mentre sono premuti o disattivati
        titleLabel.alpha = if (buttonType == UIButtonTypeSystem && (st == UIControlStateHighlighted || st == UIControlStateDisabled) && colors[st] == null) 0.35f else 1f
        invalidate()
    }
    companion object { fun buttonWithType(type: Int) = UIButton(CGRect(), type) }
}
