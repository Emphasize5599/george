package xyz.zood.george;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

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
        return scroll;
    }
}
