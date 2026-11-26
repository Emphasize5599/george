package xyz.zood.george;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.squareup.picasso.Picasso;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

import io.pijun.george.App;
import io.pijun.george.AuthenticationManager;
import io.pijun.george.L;
import io.pijun.george.Prefs;
import io.pijun.george.api.OscarClient;
import io.pijun.george.view.AvatarView;

public class AccountFragment extends Fragment implements AvatarManager.Listener {
    private String imgCapturePath;
    private ActivityResultLauncher<PickVisualMediaRequest> pickMedia;
    private ActivityResultLauncher<Uri> takePicture;
    private AvatarView avatar;

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        if (state != null) imgCapturePath = state.getString("capture_path");
        pickMedia = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri == null) {
                return;
            }

            Intent i = AvatarCropperActivity.newIntent(requireContext(), uri);
            startActivity(i);
        });
        takePicture = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            if (!success || imgCapturePath == null) {
                return;
            }

            Uri uri = Uri.fromFile(new File(imgCapturePath));
            Intent i = AvatarCropperActivity.newIntent(requireContext(), uri);
            startActivity(i);
        });

        AvatarManager.addListener(this);
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("capture_path", imgCapturePath);
    }

    @Override public void onDestroyView() {
        avatar = null;
        super.onDestroyView();
    }

    @Override public void onDestroy() {
        AvatarManager.removeListener(this);
        super.onDestroy();
    }

    @Override public void onAvatarUpdated(@Nullable String username) {
        if (username == null && avatar != null) {
            Context context = avatar.getContext();
            Picasso.with(context).load(AvatarManager.getMyAvatar(context)).into(avatar);
        }
    }

    private void onChangeProfilePhoto(View anchor) {
        // Check if there is a camera on this device.
        Context ctx = requireContext();
        PackageManager pm = ctx.getPackageManager();
        boolean hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        if (!hasCamera) {
            // no camera, so just show the avatar picker
            startImagePicker();
            return;
        }

        // Show a popup menu to let the user pick
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        MenuInflater menuInflater = new MenuInflater(requireContext());
        menuInflater.inflate(R.menu.profile_photo, menu.getMenu());
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.camera) {
                    startCamera();
                } else if (item.getItemId() == R.id.images) {
                    startImagePicker();
                }
                return true;
            }
        });
        menu.show();
    }

    private void startCamera() {
        try {
            Context ctx = requireContext();
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File storageDir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            File imgFile = File.createTempFile(timeStamp, ".jpg", storageDir);
            imgCapturePath = imgFile.getAbsolutePath();
            Uri photoUri  = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", imgFile);
            takePicture.launch(photoUri);
        } catch (Throwable t) {
            L.w("startCamera", t);
        }
    }

    private void startImagePicker() {
        pickMedia.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }



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

        LinearLayout changePhoto = new LinearLayout(requireContext());
        changePhoto.setGravity(Gravity.CENTER_VERTICAL);
        changePhoto.setPadding(padding / 2, padding / 2, padding / 2, padding / 2);
        changePhoto.setBackgroundResource(android.R.drawable.list_selector_background);
        avatar = new AvatarView(requireContext());
        String username = Prefs.get(requireContext()).getUsername();
        avatar.setUsername(username == null ? "?" : username);
        int avatarSize = getResources().getDimensionPixelSize(R.dimen.fortyEight);
        changePhoto.addView(avatar, new LinearLayout.LayoutParams(avatarSize, avatarSize));
        onAvatarUpdated(null);
        TextView photoLabel = new TextView(requireContext());
        photoLabel.setText(R.string.change_photo);
        photoLabel.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        photoLabel.setPadding(padding / 2, 0, 0, 0);
        changePhoto.addView(photoLabel);
        changePhoto.setFocusable(true);
        changePhoto.setContentDescription(getString(R.string.change_photo));
        changePhoto.setOnClickListener(this::onChangeProfilePhoto);
        form.addView(changePhoto);
        addDivider(form, padding);

        TextView nickname = new TextView(requireContext());
        nickname.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        nickname.setText(Nicknames.own() == null ? getString(R.string.nickname_optional) : Nicknames.own());
        nickname.setMinHeight(padding * 2);
        nickname.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout nicknameRow = new LinearLayout(requireContext());
        nicknameRow.setGravity(Gravity.TOP);
        android.widget.FrameLayout nicknameHost = new android.widget.FrameLayout(requireContext());
        nicknameRow.addView(nicknameHost, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        nicknameHost.addView(nickname, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.ImageButton editNickname = new android.widget.ImageButton(requireContext());
        editNickname.setImageResource(R.drawable.ic_edit_24dp);
        editNickname.setImageTintList(android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.action_blue)));
        editNickname.setContentDescription(getString(R.string.edit_nickname));
        android.util.TypedValue editBackground = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, editBackground, true);
        editNickname.setBackgroundResource(editBackground.resourceId);
        nicknameRow.addView(editNickname, new LinearLayout.LayoutParams(padding * 2, padding * 2));
        form.addView(nicknameRow);
        Runnable endEdit = () -> {
            ((xyz.zood.george.widget.InlineNameEditor) nicknameHost.getChildAt(1)).close();
            nickname.setVisibility(View.VISIBLE);
            nickname.setText(Nicknames.own() == null ? getString(R.string.nickname_optional) : Nicknames.own());
        };
        var editor = new xyz.zood.george.widget.InlineNameEditor(requireContext(),
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, R.string.nickname_optional,
                value -> { Nicknames.setOwn(value); endEdit.run(); }, endEdit);
        nicknameHost.addView(editor, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View.OnClickListener beginEdit = view -> {
            if (editor.getVisibility() == View.VISIBLE) return;
            nickname.setVisibility(View.GONE);
            editor.open(Nicknames.own());
        };
        nickname.setOnClickListener(beginEdit);
        editNickname.setOnClickListener(beginEdit);

        TextView explanation = new TextView(requireContext());
        explanation.setText(R.string.shared_nickname_explanation);
        explanation.setPadding(0, padding / 2, 0, padding);
        form.addView(explanation);
        addDivider(form, padding);

        MaterialButton blocked = new MaterialButton(requireContext(), null, androidx.appcompat.R.attr.borderlessButtonStyle);
        blocked.setText(R.string.blocked_users);
        blocked.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        blocked.setIconResource(R.drawable.ic_open_palm_24dp);
        blocked.setOnClickListener(view -> getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_host, FriendRequestsFragment.newInstance(true)).addToBackStack(null).commit());
        form.addView(blocked);
        addDivider(form, padding);

        MaterialButton logout = new MaterialButton(requireContext(), null, androidx.appcompat.R.attr.borderlessButtonStyle);
        logout.setText(R.string.log_out);
        logout.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        logout.setTextColor(com.google.android.material.color.MaterialColors.getColor(logout, androidx.appcompat.R.attr.colorError));
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
        delete.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        delete.setTextColor(com.google.android.material.color.MaterialColors.getColor(delete, androidx.appcompat.R.attr.colorError));
        delete.setOnClickListener(view -> showDeleteAccount(delete));
        form.addView(delete);
        return scroll;
    }

    private void addDivider(LinearLayout form, int padding) {
        View divider = new View(requireContext());
        divider.setBackgroundColor(requireContext().getColor(R.color.outline));
        var layout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, padding / 24));
        layout.setMargins(0, padding / 2, 0, padding / 2);
        form.addView(divider, layout);
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
                InviteTokens.deleteForAccount(account);
                NewFriendSharing.deleteForAccount(context, account);
                AccountPreferences.deleteForAccount(context, account);
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
