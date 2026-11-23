package xyz.zood.george;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SafetyNumberFragment extends DialogFragment {
    private static final String ARG_MY_USERNAME = "my_username";
    private static final String ARG_MY_PUBLIC_KEY = "my_public_key";
    private static final String ARG_OTHER_USERNAME = "other_username";
    private static final String ARG_OTHER_DISPLAY_NAME = "other_display_name";
    private static final String ARG_OTHER_PUBLIC_KEY = "other_public_key";

    static SafetyNumberFragment newInstance(@NonNull String myUsername, @NonNull byte[] myPublicKey,
                                            @NonNull String otherUsername, @NonNull byte[] otherPublicKey,
                                            @NonNull String otherDisplayName) {
        SafetyNumberFragment fragment = new SafetyNumberFragment();
        Bundle args = new Bundle();
        args.putString(ARG_MY_USERNAME, myUsername);
        args.putByteArray(ARG_MY_PUBLIC_KEY, myPublicKey);
        args.putString(ARG_OTHER_USERNAME, otherUsername);
        args.putByteArray(ARG_OTHER_PUBLIC_KEY, otherPublicKey);
        args.putString(ARG_OTHER_DISPLAY_NAME, otherDisplayName);
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle state) {
        Bundle args = requireArguments();
        String myUsername = args.getString(ARG_MY_USERNAME);
        String otherUsername = args.getString(ARG_OTHER_USERNAME);
        String otherDisplayName = args.getString(ARG_OTHER_DISPLAY_NAME, Usernames.display(otherUsername));
        byte[] myPublicKey = args.getByteArray(ARG_MY_PUBLIC_KEY);
        byte[] otherPublicKey = args.getByteArray(ARG_OTHER_PUBLIC_KEY);
        // Key order follows account usernames so both people see the same number.
        boolean otherFirst = otherUsername.compareToIgnoreCase(myUsername) < 0;
        byte[] first = otherFirst ? otherPublicKey : myPublicKey;
        byte[] second = otherFirst ? myPublicKey : otherPublicKey;
        byte[] keys = new byte[first.length + second.length];
        System.arraycopy(first, 0, keys, 0, first.length);
        System.arraycopy(second, 0, keys, first.length, second.length);
        String safetyNumber = SafetyNumber.toSafetyNumber(keys, 4, "  ");

        var context = requireContext();
        float density = getResources().getDisplayMetrics().density;
        int padding = Math.round(20 * density);
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(padding, padding / 2, padding, 0);

        TextView instructions = new TextView(context);
        instructions.setText(getString(R.string.safety_number_instructions_msg, otherDisplayName));
        instructions.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        tile.addView(instructions);

        TextView number = new TextView(context);
        number.setText(safetyNumber);
        number.setTextIsSelectable(true);
        number.setGravity(Gravity.CENTER);
        number.setTypeface(Typeface.MONOSPACE);
        number.setTextSize(20);
        number.setMaxLines(Integer.MAX_VALUE);
        number.setHorizontallyScrolling(false);
        number.setPadding(0, padding, 0, padding / 2);
        tile.addView(number);
        // Each row of the number must stay on one line (four columns). Shrink the text until the widest row
        // fits the tile instead of letting a row wrap and break the grid.
        number.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, orr, ob) -> fitRows(number, safetyNumber));

        int edge = Math.round(16 * density);
        return new MaterialAlertDialogBuilder(context)
                // A wide tile, just 16dp in from each screen edge (Material's default inset is wider).
                .setBackgroundInsetStart(edge)
                .setBackgroundInsetEnd(edge)
                .setTitle(R.string.safety_number)
                .setView(tile)
                .setNeutralButton(R.string.share_safety_number_via_ellipsis, (dialog, which) -> share(safetyNumber))
                .setPositiveButton(R.string.close, null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        // A wide tile, just a little in from the screen edges, like the nickname tile.
        android.app.Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void fitRows(TextView number, String safetyNumber) {
        int available = number.getWidth() - number.getPaddingLeft() - number.getPaddingRight();
        if (available <= 0) return;
        float density = number.getResources().getDisplayMetrics().scaledDensity;
        android.text.TextPaint paint = new android.text.TextPaint(number.getPaint());
        float size = 20;
        while (size > 10) {
            paint.setTextSize(size * density);
            float widest = 0;
            for (String row : safetyNumber.split("\n")) widest = Math.max(widest, paint.measureText(row));
            if (widest <= available) break;
            size -= 0.5f;
        }
        if (Math.abs(number.getTextSize() - size * density) > 0.5f) {
            final float fitted = size;
            number.post(() -> number.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fitted));
        }
    }

    private void share(String safetyNumber) {
        Intent intent = new Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_TEXT, getString(R.string.share_safety_number_msg, safetyNumber))
                .setType("text/plain");
        startActivity(Intent.createChooser(intent, getString(R.string.share_safety_number_via_ellipsis)));
    }
}
