package xyz.zood.george;

import android.os.Bundle;
import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;

import io.pijun.george.Prefs;
import io.pijun.george.Utils;
import io.pijun.george.crypto.KeyPair;
import xyz.zood.george.databinding.FragmentOnboardingBinding;

public class OnboardingFragment extends Fragment {

    static final String TAG = "onboarding";

    private static final String ARG_ACCESS_TOKEN = "access_token";
    private static final String ARG_KEY_PAIR = "key_pair";

    private FragmentOnboardingBinding binding;
    private String accessToken;
    private KeyPair keyPair;
    private int appearance;
    private CentralParkPreview preview;

    @DrawableRes
    private final int[] slideImageIds = new int[]{
            R.drawable.onboarding_add_friends,
            R.drawable.onboarding_share_location,
            R.drawable.onboarding_safe_secure,
            0,
            R.drawable.onboarding_permission,
    };

    private final int[] slideTitleIds = new int[]{
            R.string.onboarding_title_1,
            R.string.onboarding_title_2,
            R.string.onboarding_title_3,
            R.string.onboarding_appearance,
            R.string.location_setup,
    };

    private final int[] slideBodyIds = new int[]{
            R.string.onboarding_body_1,
            R.string.onboarding_body_2,
            R.string.onboarding_body_3,
            R.string.onboarding_appearance_body,
            R.string.onboarding_body_4,
    };

    private final ArrayList<View> itemIndicators = new ArrayList<>();

    public static OnboardingFragment newInstance(@NonNull String accessToken, @NonNull KeyPair keyPair) {
        OnboardingFragment f = new OnboardingFragment();
        Bundle args = new Bundle();
        args.putString(ARG_ACCESS_TOKEN, accessToken);
        args.putParcelable(ARG_KEY_PAIR, keyPair);

        f.setArguments(args);

        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appearance = savedInstanceState == null ? Prefs.get(requireContext()).getNightMode()
                : savedInstanceState.getInt("onboarding_appearance");

        Bundle args = getArguments();
        if (args == null) {
            throw new RuntimeException("You must create the fragment via newInstance");
        }
        accessToken = args.getString(ARG_ACCESS_TOKEN);
        if (TextUtils.isEmpty(accessToken)) {
            throw new RuntimeException("missing access token");
        }
        keyPair = args.getParcelable(ARG_KEY_PAIR);
        if (keyPair == null) {
            throw new RuntimeException("missing key pair");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_onboarding, container, false);
        OnboardingAdapter adapter = new OnboardingAdapter();
        binding.viewPager.setAdapter(adapter);
        binding.viewPager.registerOnPageChangeCallback(pageChangeListener);
        binding.button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onButtonClicked();
            }
        });
        itemIndicators.clear();
        itemIndicators.add(binding.indicator1);
        itemIndicators.add(binding.indicator2);
        itemIndicators.add(binding.indicator3);
        itemIndicators.add(binding.indicator4);
        itemIndicators.add(binding.indicator5);

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            binding.getRoot().setPadding(0, 0, 0, navBarHeight);

            return WindowInsetsCompat.CONSUMED;
        });
        Utils.applySystemBarAppearance(requireActivity().getWindow(), binding.getRoot());

        return binding.getRoot();
    }

    private int getLastSlideIndex() {
        return slideBodyIds.length-1;
    }

    private void onButtonClicked() {
        if (binding.viewPager.getCurrentItem() == getLastSlideIndex()) {
            // disable the button so the user can't tap it again while the main fragment loads
            binding.button.setEnabled(false);
            showMain();
        } else {
            binding.viewPager.setCurrentItem(binding.viewPager.getCurrentItem()+1, true);
        }
    }

    private void showMain() {
        AccountPreferences.update(requireContext(), preferences -> preferences.completedOnboarding = true);
        Prefs.get(requireContext()).setNightMode(appearance);

        MainFragment fragment = MainFragment.newInstance(accessToken, keyPair);
        FragmentManager mgr = getParentFragmentManager();
        mgr.beginTransaction()
                .setCustomAnimations(R.animator.new_enter_from_right,
                        R.animator.new_exit_to_left,
                        R.animator.new_enter_from_left,
                        R.animator.new_exit_to_right)
                .replace(R.id.fragment_host, fragment)
                .commit();
        io.pijun.george.App.applyNightMode(appearance);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("onboarding_appearance", appearance);
    }

    @Override
    public void onDestroyView() {
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeListener);
        binding = null;
        preview = null;
        itemIndicators.clear();
        super.onDestroyView();
    }

    private void bindAppearance(OnboardingSlideViewHolder holder) {
        Context context = holder.itemView.getContext();
        if (preview == null) {
            preview = new CentralParkPreview(context, getViewLifecycleOwner());
        }
        if (preview.getParent() instanceof ViewGroup) ((ViewGroup) preview.getParent()).removeView(preview);
        holder.preview.addView(preview, new android.widget.FrameLayout.LayoutParams(-1, -1));
        holder.appearanceChoices.setOnCheckedChangeListener(null);
        holder.appearanceChoices.check(appearance == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                ? R.id.appearance_dark : appearance == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                ? R.id.appearance_light : R.id.appearance_system);
        preview.setAppearance(appearance);
        holder.appearanceChoices.setOnCheckedChangeListener((group, choice) -> {
            if (choice == R.id.appearance_dark) appearance = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
            else if (choice == R.id.appearance_light) appearance = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;
            else appearance = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            preview.setAppearance(appearance);
        });
    }

    private class OnboardingAdapter extends RecyclerView.Adapter<OnboardingSlideViewHolder> {

        @Override
        public int getItemCount() {
            return slideImageIds.length;
        }

        @Override
        public void onBindViewHolder(@NonNull OnboardingSlideViewHolder holder, int position) {
            holder.image.setImageResource(slideImageIds[position]);
            holder.image.setVisibility(slideImageIds[position] == 0 ? View.GONE : View.VISIBLE);
            boolean appearancePage = position == getLastSlideIndex() - 1;
            holder.appearanceOption.setVisibility(appearancePage ? View.VISIBLE : View.GONE);
            if (appearancePage) bindAppearance(holder);
            holder.title.setText(slideTitleIds[position]);
            holder.body.setText(slideBodyIds[position]);
            holder.sharingOption.setVisibility(position == getLastSlideIndex() ? View.VISIBLE : View.GONE);
            holder.defaultSharing.setOnCheckedChangeListener(null);
            holder.defaultSharing.setChecked(NewFriendSharing.isEnabled(holder.itemView.getContext()));
            holder.defaultSharing.setOnCheckedChangeListener((button, checked) ->
                    NewFriendSharing.setEnabled(button.getContext(), checked));
            holder.liveRequests.setOnCheckedChangeListener(null);
            holder.liveRequests.setChecked(xyz.zood.george.service.LiveRequestService.isEnabled(holder.itemView.getContext()));
            holder.liveRequests.setOnCheckedChangeListener((button, checked) ->
                    xyz.zood.george.service.LiveRequestService.setEnabled(button.getContext(), checked));
        }

        @NonNull
        @Override
        public OnboardingSlideViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            View view = inflater.inflate(R.layout.onboarding_slide, parent, false);
            return new OnboardingSlideViewHolder(view);
        }

    }

    private static class OnboardingSlideViewHolder extends RecyclerView.ViewHolder {

        final ImageView image;
        final TextView title;
        final TextView body;
        final View sharingOption;
        final View appearanceOption;
        final android.widget.FrameLayout preview;
        final android.widget.RadioGroup appearanceChoices;
        final com.google.android.material.materialswitch.MaterialSwitch liveRequests;
        final com.google.android.material.materialswitch.MaterialSwitch defaultSharing;

        OnboardingSlideViewHolder(@NonNull View itemView) {
            super(itemView);

            this.image = itemView.findViewById(R.id.image);
            this.title = itemView.findViewById(R.id.title);
            this.body = itemView.findViewById(R.id.body);
            this.appearanceOption = itemView.findViewById(R.id.appearance_option);
            this.preview = itemView.findViewById(R.id.preview_container);
            this.appearanceChoices = itemView.findViewById(R.id.appearance_choices);
            this.liveRequests = itemView.findViewById(R.id.live_requests_setting);
            this.sharingOption = itemView.findViewById(R.id.sharing_option);
            this.defaultSharing = itemView.findViewById(R.id.default_sharing);
        }
    }

    private final ViewPager2.OnPageChangeCallback pageChangeListener = new ViewPager2.OnPageChangeCallback() {
        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

        }

        @Override
        public void onPageSelected(int position) {
            if (binding == null) return;
            if (position == getLastSlideIndex()) {
                binding.button.setText(R.string.start);
            } else {
                binding.button.setText(R.string.next);
            }
            int blue = ContextCompat.getColor(requireContext(), R.color.zood_blue);
            int divider = ContextCompat.getColor(requireContext(), R.color.divider);
            for (int i=0; i<itemIndicators.size(); i++) {
                View indicator = itemIndicators.get(i);
                if (i == position) {
                    indicator.getBackground().setTint(blue);
                } else {
                    indicator.getBackground().setTint(divider);
                }
            }
        }
    };
}
