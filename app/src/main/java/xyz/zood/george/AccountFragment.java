package xyz.zood.george;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.util.Objects;

import io.pijun.george.App;
import io.pijun.george.AuthenticationManager;
import io.pijun.george.L;
import io.pijun.george.Prefs;
import io.pijun.george.api.OscarClient;

public class AccountFragment extends Fragment {

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
        ScrollView scroll = new ScrollView(requireContext());
        scroll.setBackgroundColor(requireContext().getColor(R.color.background));
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding, padding, padding);
        scroll.addView(form);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            androidx.core.graphics.Insets system = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            form.setPadding(padding + system.left, padding + system.top, padding + system.right, padding + system.bottom);
            return insets;
        });

        MaterialButton back = new MaterialButton(requireContext(), null, androidx.appcompat.R.attr.borderlessButtonStyle);
        back.setText(R.string.account);
        back.setIconResource(R.drawable.ic_chevron_left_24dp);
        back.setOnClickListener(view -> getParentFragmentManager().popBackStack());
        form.addView(back);

        TextInputLayout field = new TextInputLayout(requireContext());
        field.setHint(getString(R.string.nickname_optional));
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText nickname = new TextInputEditText(field.getContext());
        nickname.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nickname.setSingleLine(true);
        nickname.setText(Nicknames.own());
        field.addView(nickname);
        form.addView(field);

        TextView explanation = new TextView(requireContext());
        explanation.setText(R.string.shared_nickname_explanation);
        explanation.setPadding(0, padding / 2, 0, padding);
        form.addView(explanation);

        MaterialButton save = new MaterialButton(requireContext());
        save.setText(R.string.save_nickname);
        save.setOnClickListener(view -> {
            Nicknames.setOwn(nickname.getText() == null ? null : nickname.getText().toString());
            android.widget.Toast.makeText(requireContext(), R.string.nickname_saved, android.widget.Toast.LENGTH_SHORT).show();
        });
        form.addView(save);

        MaterialButton logout = new MaterialButton(requireContext(), null, androidx.appcompat.R.attr.borderlessButtonStyle);
        logout.setText(R.string.log_out);
        logout.setOnClickListener(view -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.confirm_log_out_msg)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.log_out, (dialog, which) -> {
                        io.pijun.george.AuthenticationManager.get().logOut(requireContext(), () -> requireActivity().finish());
                    })
                    .show();
        });
        form.addView(logout);

        MaterialButton delete = new MaterialButton(requireContext(), null, androidx.appcompat.R.attr.borderlessButtonStyle);
        delete.setText(R.string.delete_account);
        delete.setTextColor(com.google.android.material.color.MaterialColors.getColor(delete, androidx.appcompat.R.attr.colorError));
        delete.setOnClickListener(view -> showDeleteAccount(delete));
        form.addView(delete);
        return scroll;
    }

    private void showDeleteAccount(MaterialButton action) {
        Context context = requireContext();
        var prefs = Prefs.get(context);
        String username = prefs.getUsername();
        String token = prefs.getAccessToken();
        byte[] account = prefs.getUserId();
        if (username == null || token == null || account == null) return;

        TextInputLayout field = new TextInputLayout(context);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        field.setPadding(padding, padding / 2, padding, 0);
        field.setHint(getString(R.string.delete_account_type_username));
        TextInputEditText input = new TextInputEditText(field.getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine();
        field.addView(input);
        AlertDialog entry = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.delete_account)
                .setMessage(R.string.delete_account_explanation)
                .setView(field)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_account, (dialog, which) ->
                        new MaterialAlertDialogBuilder(context)
                                .setTitle(R.string.delete_account_confirm)
                                .setMessage(R.string.delete_account_warning)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.delete_account_permanently,
                                        (confirmation, button) -> deleteAccount(action, token, account))
                                .show())
                .show();
        var confirm = entry.getButton(AlertDialog.BUTTON_POSITIVE);
        confirm.setEnabled(false);
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                confirm.setEnabled(username.equals(Usernames.forLookup(text.toString())));
            }
            @Override public void afterTextChanged(android.text.Editable text) {}
        });
    }

    private void deleteAccount(MaterialButton action, String token, byte[] account) {
        var activity = requireActivity();
        Context context = activity.getApplicationContext();
        action.setEnabled(false);
        App.runInBackground(() -> {
            try {
                if (!Objects.equals(token, Prefs.get(context).getAccessToken())) return;
                var result = OscarClient.newInstance(token).deleteAccount().execute();
                if (!result.isSuccessful()) throw new IOException("Account deletion HTTP " + result.code());
                LinkedContacts.deleteForAccount(account);
                Nicknames.deleteForAccount(account);
                FriendRequests.deleteForAccount(account);
                if (Objects.equals(token, Prefs.get(context).getAccessToken())) {
                    AuthenticationManager.get().logOut(context, activity::finish);
                }
            } catch (IOException error) {
                L.w("Unable to delete account", error);
                App.runOnUiThread(() -> {
                    action.setEnabled(true);
                    Toast.makeText(context, R.string.delete_account_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
