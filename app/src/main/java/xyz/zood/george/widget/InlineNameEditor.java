package xyz.zood.george.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.function.Consumer;

import xyz.zood.george.R;

/** A wrapping draft field and its save/cancel actions, embedded in the surface being edited. */
public final class InlineNameEditor extends LinearLayout {
    private final EditText input;
    private final LinearLayout actions;
    private final ViewTreeObserver.OnWindowFocusChangeListener keyboardFocusListener;

    public InlineNameEditor(Context context, int appearance, int label, Consumer<String> save, Runnable cancel) {
        super(context);
        setOrientation(VERTICAL);
        input = new EditText(context);
        input.setTextAppearance(appearance);
        input.setContentDescription(context.getString(label));
        input.setHint(label);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        input.setMinHeight(dp(44));
        input.setMaxLines(4);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            save.accept(draft());
            return true;
        });
        keyboardFocusListener = focused -> {
            if (focused) input.post(this::showKeyboard);
        };
        addView(input, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        actions = new LinearLayout(context);
        actions.setGravity(Gravity.END);
        addView(actions, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addAction(R.drawable.ic_check_black_24dp, R.string.save, R.color.action_positive, () -> save.accept(draft()));
        addAction(R.drawable.ic_close_black_24dp, android.R.string.cancel, R.color.action_draft, cancel);
        actions.setVisibility(GONE);
        setVisibility(GONE);
    }

    /** Keep the save/discard row below a header that aligns other buttons with the text field. */
    public void moveActionsTo(LinearLayout parent, int index) {
        removeView(actions);
        parent.addView(actions, index, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public void open(String initial) {
        input.setText(initial);
        setVisibility(VISIBLE);
        actions.setVisibility(VISIBLE);
        input.requestFocus();
        input.setSelection(input.length());
        // A menu can still own window focus when its Rename action opens this editor.
        input.getViewTreeObserver().addOnWindowFocusChangeListener(keyboardFocusListener);
        input.post(this::showKeyboard);
    }

    private void showKeyboard() {
        if (!input.isShown() || !input.hasWindowFocus()) return;
        input.getViewTreeObserver().removeOnWindowFocusChangeListener(keyboardFocusListener);
        input.requestFocus();
        var controller = ViewCompat.getWindowInsetsController(input);
        if (controller != null) {
            controller.show(WindowInsetsCompat.Type.ime());
        } else {
            InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    public void close() {
        if (getVisibility() != VISIBLE) return;
        input.getViewTreeObserver().removeOnWindowFocusChangeListener(keyboardFocusListener);
        InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        actions.setVisibility(GONE);
        setVisibility(GONE);
    }

    public String draft() {
        return input.getText().toString();
    }

    public void onDraftChanged(Runnable listener) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { listener.run(); }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    private void addAction(int icon, int description, int color, Runnable action) {
        ImageButton button = new ImageButton(getContext());
        android.util.TypedValue background = new android.util.TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true);
        button.setBackgroundResource(background.resourceId);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(getContext().getColor(color)));
        button.setContentDescription(getContext().getString(description));
        button.setOnClickListener(view -> action.run());
        actions.addView(button, new LayoutParams(dp(44), dp(44)));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
