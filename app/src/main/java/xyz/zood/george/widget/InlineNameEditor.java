package xyz.zood.george.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import java.util.function.Consumer;

import xyz.zood.george.R;

/** A wrapping draft field and its save/cancel actions, embedded in the surface being edited. */
public final class InlineNameEditor extends LinearLayout {
    private final EditText input;
    private final LinearLayout actions;

    public InlineNameEditor(Context context, int appearance, int label, Consumer<String> save, Runnable cancel) {
        super(context);
        setOrientation(VERTICAL);
        input = new EditText(context);
        input.setTextAppearance(appearance);
        input.setContentDescription(context.getString(label));
        input.setHint(label);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinHeight(dp(44));
        input.setMaxLines(4);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            save.accept(draft());
            return true;
        });
        addView(input, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        actions = new LinearLayout(context);
        actions.setGravity(Gravity.END);
        addView(actions, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addAction(R.drawable.ic_check_black_24dp, R.string.save, () -> save.accept(draft()));
        addAction(R.drawable.ic_close_black_24dp, android.R.string.cancel, cancel);
        setVisibility(GONE);
    }

    public void open(String initial) {
        input.setText(initial);
        setVisibility(VISIBLE);
        input.post(() -> {
            if (!isShown()) return;
            input.requestFocus();
            input.setSelection(input.length());
            InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    public void close() {
        if (getVisibility() != VISIBLE) return;
        InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
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

    private void addAction(int icon, int description, Runnable action) {
        ImageButton button = new ImageButton(getContext());
        android.util.TypedValue background = new android.util.TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true);
        button.setBackgroundResource(background.resourceId);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(getContext().getColor(R.color.zood_blue)));
        button.setContentDescription(getContext().getString(description));
        button.setOnClickListener(view -> action.run());
        actions.addView(button, new LayoutParams(dp(44), dp(44)));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
