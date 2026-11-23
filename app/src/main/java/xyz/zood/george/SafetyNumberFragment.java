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
        int padding = Math.round(24 * density);
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
        number.setPadding(0, padding, 0, padding / 2);
        tile.addView(number);

        return new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.safety_number)
                .setView(tile)
                .setNeutralButton(R.string.share_safety_number_via_ellipsis, (dialog, which) -> share(safetyNumber))
                .setPositiveButton(R.string.close, null)
                .create();
    }

    private void share(String safetyNumber) {
        Intent intent = new Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_TEXT, getString(R.string.share_safety_number_msg, safetyNumber))
                .setType("text/plain");
        startActivity(Intent.createChooser(intent, getString(R.string.share_safety_number_via_ellipsis)));
    }
}
