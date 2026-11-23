package xyz.zood.george;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

public class NoticesFragment extends Fragment {
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
        var context = requireContext();
        var scroll = new ScrollView(context);
        scroll.setBackgroundColor(context.getColor(R.color.background));
        var page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(page);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            page.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom);
            return insets;
        });
        var back = new MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        back.setText(R.string.notices_and_trademarks);
        back.setIconResource(R.drawable.ic_chevron_left_24dp);
        back.setOnClickListener(view -> getParentFragmentManager().popBackStack());
        page.addView(back);

        section(page, R.string.app_name, R.string.notice_family_locator, padding);
        section(page, R.string.notice_apple, R.string.notice_apple_body, padding);
        section(page, R.string.notice_google, R.string.notice_google_body, padding);
        section(page, R.string.open_source_software, R.string.notice_libsodium, padding);
        var licenses = new MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        licenses.setText(R.string.open_source_licenses);
        licenses.setOnClickListener(view -> getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_host, LicensesFragment.newInstance()).addToBackStack(null).commit());
        page.addView(licenses);
        return scroll;
    }

    private void section(LinearLayout page, int title, int body, int padding) {
        var heading = new TextView(requireContext());
        heading.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        heading.setText(title);
        heading.setPadding(0, padding, 0, padding / 3);
        ViewCompat.setAccessibilityHeading(heading, true);
        page.addView(heading);
        var notice = new TextView(requireContext());
        notice.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        notice.setText(body);
        page.addView(notice);
    }
}
