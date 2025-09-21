Perfect request 🙌 — here’s a **clear summary of what worked, what didn’t, what caused issues, and what to do in future** for your case with the **Save button, keyboard, and focusing textboxes**:

---

## ✅ What Worked

1. **Using `WindowInsetsCompat` + `ViewCompat.setOnApplyWindowInsetsListener`**

   * Correct way in Android 11+ (API 30+) to handle nav bar + keyboard insets.
   * Allowed us to adjust `ScrollView` padding dynamically when keyboard/nav bar appeared.

2. **Removing `android:fitsSystemWindows="true"`**

   * This was blocking insets from reaching child views.
   * Once removed, the textboxes at the bottom properly scrolled into view when focused.

3. **Adding a small `extra` padding gap (e.g. `dpToPx(8)`)**

   * Prevented the Save button from being “glued” to the keyboard/nav bar.
   * Allowed smooth scroll behavior.

4. **Focus listener with `ensureVisible(view)`**

   * Guaranteed that the focused textbox is scrolled above the keyboard, even the ones at the very bottom.

---

## ❌ What Didn’t Work

1. **Relying only on `fitsSystemWindows="true"`**

   * Caused the ScrollView to mis-handle insets.
   * Textboxes hidden under the keyboard and Save button not always visible.

2. **Default system `adjustResize` behavior alone**

   * On Android 13, sometimes it doesn’t play nice with gesture navigation + insets.
   * Needed manual handling with `WindowInsetsCompat`.

3. **Trying to scroll Save button directly**

   * Just calling `ensureVisible(btnSave)` on save click didn’t fix textbox focus problems.
   * Proper solution was attaching listeners to *each EditText* so they always scroll into view.

---

## ⚠️ What Caused Issues

* **Mixing old & new APIs** (`fitsSystemWindows` vs `WindowInsetsCompat`) → conflict.
* **Not accounting for both nav bar + IME insets together** → Save button overlapped or textboxes hidden.
* **Extra padding not set** → Save button stuck right against nav bar, looked cramped.

---

## 🔮 What To Do in Future

1. **Never rely on `fitsSystemWindows="true"` in modern apps**

   * Use `WindowInsetsCompat` + manual padding adjustments instead.
   * This gives full control and avoids surprises.

2. **Always handle both navigation bar + keyboard (IME) insets**

   ```kotlin
   val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
   val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
   v.setPadding(..., navBottom + imeBottom + dpToPx(8))
   ```

3. **Attach focus listeners to bottom textboxes**

   * Call `ensureVisible(view)` when a field gains focus.
   * This guarantees smooth scroll no matter which field is selected.

4. **Keep a small `extra` dp gap**

   * Between button & keyboard/nav bar for usability.
   * Tweak this based on design needs (4dp = tight, 16dp = comfy).

5. **Test on multiple navigation modes** (gesture nav vs 3-button nav)

   * Insets differ slightly.
   * Your solution (WindowInsetsCompat + manual padding) will work across both.

---

✨ **One-line future takeaway**:
👉 *Remove `fitsSystemWindows`, handle insets yourself with `WindowInsetsCompat`, and always ensure bottom textboxes + buttons have dynamic padding with a little extra breathing room.*

---
