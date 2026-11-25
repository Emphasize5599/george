package xyz.zood.george;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AlertDialog;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.util.Locale;

import io.pijun.george.App;
import io.pijun.george.L;
import io.pijun.george.Prefs;
import io.pijun.george.UiRunnable;
import io.pijun.george.Utils;
import io.pijun.george.WorkerRunnable;
import io.pijun.george.api.OscarClient;
import io.pijun.george.api.SearchUserResult;
import io.pijun.george.crypto.KeyPair;
import io.pijun.george.database.DB;
import io.pijun.george.database.UserRecord;
import retrofit2.Response;
import xyz.zood.george.databinding.FragmentAddFriendBinding;

public class AddFriendFragment extends Fragment {

    private static final String ARG_ACCESS_TOKEN = "access_token";
    private static final String ARG_PREFILL = "prefill";
    private static final String ARG_INVITE_TOKEN = "invite_token";
    private static final String ARG_KEY_PAIR = "key_pair";

    private String accessToken;
    private FragmentAddFriendBinding binding;
    private FriendshipManager friendshipManager;
    private String sharingCandidate;
    private boolean submitting;
    @Nullable private byte[] inviteToken;
    @Nullable private String invitedUsername;
    private boolean inviteStepShown;
    private ColorStateList continueBackground;
    private androidx.activity.OnBackPressedCallback sharingBack;

    static AddFriendFragment newInstance(@NonNull String accessToken, @NonNull KeyPair keyPair) {
        return newInstance(accessToken, keyPair, null);
    }

    /** {@code prefill} fills in a username from a friend link; nothing is sent until the user confirms. */
    static AddFriendFragment newInstance(@NonNull String accessToken, @NonNull KeyPair keyPair, @Nullable String prefill) {
        return newInstance(accessToken, keyPair, prefill, null);
    }

    /** {@code inviteToken} connects immediately instead of sending a request the other person has to approve. */
    static AddFriendFragment newInstance(@NonNull String accessToken, @NonNull KeyPair keyPair, @Nullable String prefill,
                                         @Nullable byte[] inviteToken) {
        AddFriendFragment fragment = new AddFriendFragment();
        Bundle args = new Bundle();
        if (prefill != null) args.putString(ARG_PREFILL, prefill);
        if (inviteToken != null) args.putByteArray(ARG_INVITE_TOKEN, inviteToken);
        args.putString(ARG_ACCESS_TOKEN, accessToken);
        args.putParcelable(ARG_KEY_PAIR, keyPair);

        fragment.setArguments(args);

        return fragment;
    }

    // applySystemUIInsets applies the system view insets. It should only be called once after the fragment's view is created.
    private void applySystemUIInsets(FragmentAddFriendBinding binding) {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            ConstraintLayout.LayoutParams sbLP = (ConstraintLayout.LayoutParams) binding.statusBarPlaceholder.getLayoutParams();
            sbLP.height = statusBarHeight;
            binding.statusBarPlaceholder.setLayoutParams(sbLP);

            int bottomInset;
            int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            int imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            bottomInset = Math.max(navBarHeight, imeHeight);
            binding.root.setPadding(0, 0, 0, bottomInset);

            return WindowInsetsCompat.CONSUMED;
        });
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        if (args == null) {
            throw new RuntimeException("You must create the fragment via newInstance");
        }
        accessToken = args.getString(ARG_ACCESS_TOKEN);
        inviteToken = args.getByteArray(ARG_INVITE_TOKEN);
        invitedUsername = args.getString(ARG_PREFILL);
        if (accessToken == null) {
            throw new RuntimeException("missing access token");
        }
        KeyPair keyPair = args.getParcelable(ARG_KEY_PAIR);
        if (keyPair == null) {
            throw new RuntimeException("missing key pair");
        }

        Context ctx = requireContext();
        friendshipManager = new FriendshipManager(ctx, DB.get(), OscarClient.getQueue(ctx), accessToken, keyPair);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_add_friend, container, false);
        continueBackground = binding.addFriend.getBackgroundTintList();
        binding.username.addTextChangedListener(
                new UsernameWatcher(
                        binding.usernameContainer, binding.username));
        binding.usernameContainer.setEndIconVisible(true);
        String prefill = requireArguments().getString(ARG_PREFILL);
        if (savedInstanceState == null && prefill != null) binding.username.setText(prefill);
        binding.back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (submitting) return;
                if (sharingCandidate != null) showSharingStep(null);
                else getParentFragmentManager().popBackStack();
            }
        });
        binding.inviteFriend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sharingCandidate != null) addFriend(sharingCandidate, FriendshipManager.AddChoice.WITHOUT_LOCATION);
                else FriendLink.shareInvite(requireContext());
            }
        });
        binding.addFriend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sharingCandidate != null) addFriend(sharingCandidate, FriendshipManager.AddChoice.SHARE);
                else onAddFriendAction();
            }
        });

        applySystemUIInsets(binding);
        if (savedInstanceState != null) showSharingStep(savedInstanceState.getString("sharing_candidate"));

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        sharingBack = new androidx.activity.OnBackPressedCallback(sharingCandidate != null) {
            @Override public void handleOnBackPressed() {
                if (!submitting) showSharingStep(null);
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), sharingBack);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        state.putString("sharing_candidate", sharingCandidate);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onDestroyView() {
        binding = null;
        sharingBack = null;
        super.onDestroyView();
    }

    @WorkerThread
    private void checkUserValidity(@NonNull String username) {
        DB db = DB.get();
        UserRecord user = db.getUser(username);
        if (user != null) {
            App.runOnUiThread(new UiRunnable() {
                @Override
                public void run() {
                    displayUserValidity(username, true);
                }
            });
            return;
        }

        // we have to check with the server
        Response<SearchUserResult> response;
        try {
            response = OscarClient.newInstance(accessToken).searchForUser(username).execute();
        } catch (IOException ignore) {
            L.w("network error checking for user existence");
            return;
        }

        if (!response.isSuccessful()) {
            App.runOnUiThread(new UiRunnable() {
                @Override
                public void run() {
                    displayUserValidity(username, false);
                }
            });
            return;
        }

        SearchUserResult result = response.body();
        if (result == null) {
            L.w("Received a null result checking for user validity");
            return;
        }
        // Time for TOFU!
        /*
        Make sure the server gave us a response with the same username.
        I'm not sure what type of attack this defends against, but it
        seems like a good idea.
         */
        if (!username.equals(result.username)) {
            L.w("Username lookup returned an unexpected account");
            return;
        }
        try {
            db.addUser(result.id, result.username, result.publicKey);
        } catch (DB.DBException ex) {
            L.e("Failed to add user '"+ username + "'", ex);
        }

        App.runOnUiThread(new UiRunnable() {
            @Override
            public void run() {
                displayUserValidity(username, true);
            }
        });
    }

    @UiThread
    private void displayUserValidity(@NonNull String username, boolean valid) {
        if (binding == null || sharingCandidate != null || submitting) {
            return;
        }

        // does the username match the current entry?
        Editable text = binding.username.getText();
        if (text == null) {
            // If there is no text, we shouldn't show anything
            binding.usernameContainer.setEndIconDrawable(null);
            binding.addFriend.setEnabled(false);
            return;
        }
        String currEntry = Usernames.forLookup(text.toString());
        if (!currEntry.equals(username)) {
            return;
        }

        showUserValidityIcon(valid);
        binding.addFriend.setEnabled(valid);
        if (valid && inviteTokenFor(username) != null && !inviteStepShown) {
            inviteStepShown = true;
            showSharingStep(username);
        }
    }

    @UiThread
    private void onAddFriendAction() {
        Editable text = binding.username.getText();
        if (text == null) {
            throw new NullPointerException("How did we get into this state?");
        }
        String username = Usernames.forLookup(text.toString());

        addFriend(username, FriendshipManager.AddChoice.REVIEW);
    }

    private void addFriend(String username, FriendshipManager.AddChoice choice) {
        if (submitting) return;
        submitting = true;
        binding.usernameContainer.setEnabled(false);
        binding.addFriend.setEnabled(false);
        binding.inviteFriend.setEnabled(false);
        byte[] token = inviteTokenFor(username);
        App.runInBackground(() -> friendshipManager.addFriend(username, choice, token, this::onAddFriendFinished));
    }

    @Nullable
    private byte[] inviteTokenFor(@Nullable String username) {
        return username != null && username.equals(invitedUsername) ? inviteToken : null;
    }

    private void showSharingStep(@Nullable String username) {
        sharingCandidate = username;
        boolean choosing = username != null;
        if (sharingBack != null) sharingBack.setEnabled(choosing);
        if (choosing) {
            var keyboard = requireContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(binding.username.getWindowToken(), 0);
            binding.username.clearFocus();
        }
        boolean invite = inviteTokenFor(username) != null;
        binding.usernameContainer.setVisibility(choosing ? View.GONE : View.VISIBLE);
        binding.screenTitle.setText(choosing
                ? (invite ? R.string.connect_with_friend : R.string.confirm_share_own_location)
                : R.string.add_friend);
        binding.screenPurpose.setText(choosing
                ? getString(invite ? R.string.invite_accept_sharing_choice : R.string.friend_request_sharing_choice,
                Usernames.display(username))
                : getString(R.string.check_friend_request_or_share));
        binding.addFriend.setText(choosing ? R.string.share_my_location_action : R.string.continue_friend_request);
        binding.addFriend.setBackgroundTintList(choosing
                ? androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.action_positive_fill)
                : continueBackground);
        binding.inviteFriend.setText(choosing ? R.string.friend_request_not_now : R.string.invite_a_friend);
        binding.addFriend.setEnabled(choosing || (binding.username.getText() != null
                && DB.get().getUser(Usernames.forLookup(binding.username.getText().toString())) != null));
    }

    @WorkerThread
    private void onAddFriendFinished(@NonNull FriendshipManager.AddFriendResult result, @Nullable String extraInfo) {
        App.runOnUiThread(new UiRunnable() {
            @Override
            public void run() {
                if (binding == null || !isAdded()) return;
                submitting = false;
                binding.usernameContainer.setEnabled(true);
                binding.addFriend.setEnabled(true);
                binding.inviteFriend.setEnabled(true);
                String errMsg;
                switch (result) {
                    case Success:
                        getParentFragmentManager().popBackStack();
                        return;
                    case ConfirmSharing:
                        showSharingStep(extraInfo);
                        return;
                    case CantAddSelf:
                        binding.usernameContainer.setError(getString(R.string.cant_add_yourself));
                        showUserValidityIcon(false);
                        binding.addFriend.setEnabled(false);
                        return;
                    case UserNotFound:
                        errMsg = getString(R.string.unknown_error_getting_user_info);
                        break;
                    case DatabaseError:
                        errMsg = getString(R.string.database_error_msg, extraInfo);
                        break;
                    case SharingGrantFailed:
                        errMsg = getString(R.string.sharing_grant_failed_msg, extraInfo);
                        break;
                    default:
                        throw new RuntimeException("unaccounted for result");
                }

                AlertDialog.Builder bldr = new AlertDialog.Builder(requireContext());
                bldr.setTitle(R.string.error);
                bldr.setMessage(errMsg);
                bldr.setPositiveButton(R.string.ok, null);
                bldr.setCancelable(true);
                bldr.show();
            }
        });
    }

    @UiThread
    private void showUserValidityIcon(boolean valid) {
        // The layout tints its end icon with its own tint list, which replaces any tint on the
        // drawable, so the color has to be set on the layout.
        int color = getResources().getColor(valid ? R.color.action_positive : R.color.action_destructive, null);
        binding.usernameContainer.setEndIconTintList(ColorStateList.valueOf(color));
        binding.usernameContainer.setEndIconDrawable(valid ? R.drawable.ic_check_black_24dp : R.drawable.ic_close_black_24dp);
    }

    private class UsernameWatcher implements TextWatcher {

        final TextInputLayout layout;
        final TextInputEditText editText;

        UsernameWatcher(@NonNull TextInputLayout layout, @NonNull TextInputEditText editText) {
            this.layout = layout;
            this.editText = editText;
        }

        @Override @UiThread
        public void afterTextChanged(Editable s) {
            if (s != null) {
                String username = Usernames.forLookup(s.toString());
                layout.setError(null);
                if (Usernames.isOwn(username, Prefs.get(requireContext()).getUsername())) {
                    layout.setError(getString(R.string.cant_add_yourself));
                    showUserValidityIcon(false);
                    binding.addFriend.setEnabled(false);
                    return;
                }
                if (username.isEmpty()) {
                    binding.addFriend.setEnabled(false);
                    layout.setEndIconDrawable(null);
                    return;
                }

                if (Utils.isValidUsername(username)) {
                    layout.setEndIconDrawable(null);
                    binding.addFriend.setEnabled(false);
                    App.runInBackground(new WorkerRunnable() {
                        @Override
                        public void run() {
                            checkUserValidity(username);
                        }
                    });
                } else {
                    showUserValidityIcon(false);
                    binding.addFriend.setEnabled(false);
                }
            }
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

    }
}
