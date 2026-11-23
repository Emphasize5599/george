package xyz.zood.george;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import io.pijun.george.App;
import io.pijun.george.view.AvatarRenderer;

/**
 * Family Locator's own contact list for linking a friend. Uses the Contacts permission the app already
 * holds, so linking never depends on another app's picker handing a result back (AOSP Contacts, as on
 * GrapheneOS, drops the result whenever it stops for its own permission screen first).
 */
final class ContactChooser {

    private ContactChooser() {}

    private static final class Contact {
        final long id;
        final String lookupKey;
        final String name;
        @Nullable final String thumbnail;

        Contact(long id, String lookupKey, String name, @Nullable String thumbnail) {
            this.id = id;
            this.lookupKey = lookupKey;
            this.name = name;
            this.thumbnail = thumbnail;
        }

        Uri uri() {
            return ContactsContract.Contacts.getLookupUri(id, lookupKey);
        }
    }

    /** Shows the list; {@code onPicked} gets the chosen contact's lookup URI on the main thread. */
    static void show(@NonNull Context context, @NonNull Consumer<Uri> onPicked) {
        int pad = dp(context, 24);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        EditText search = new EditText(context);
        search.setHint(R.string.search_contacts);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.setMargins(pad, dp(context, 8), pad, dp(context, 4));
        content.addView(search, searchParams);

        FrameLayout listHost = new FrameLayout(context);
        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        listHost.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView empty = new TextView(context);
        empty.setGravity(Gravity.CENTER);
        empty.setText(R.string.loading_contacts);
        listHost.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // A fixed share of the screen keeps the dialog from jumping as the search narrows the list.
        int height = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.5f);
        content.addView(listHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.choose_contact)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        Adapter adapter = new Adapter(context, contact -> {
            dialog.dismiss();
            onPicked.accept(contact.uri());
        });
        list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                adapter.filter(s.toString());
                empty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
                empty.setText(R.string.no_contacts_found);
            }
        });
        dialog.show();

        Context app = context.getApplicationContext();
        App.runInBackground(() -> {
            List<Contact> contacts = load(app);
            App.runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                adapter.setAll(contacts);
                adapter.filter(search.getText().toString());
                empty.setText(R.string.no_contacts_found);
                empty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
            });
        });
    }

    @NonNull
    private static List<Contact> load(Context context) {
        ArrayList<Contact> contacts = new ArrayList<>();
        String[] projection = {ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI};
        try (Cursor cursor = context.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI, projection,
                null, null, ContactsContract.Contacts.SORT_KEY_PRIMARY)) {
            while (cursor != null && cursor.moveToNext()) {
                String lookup = cursor.getString(1);
                if (lookup == null) continue;
                String name = cursor.getString(2);
                contacts.add(new Contact(cursor.getLong(0), lookup,
                        name == null || name.trim().isEmpty() ? context.getString(R.string.unnamed_contact) : name.trim(),
                        cursor.getString(3)));
            }
        } catch (SecurityException | IllegalArgumentException error) {
            io.pijun.george.L.w("Unable to list contacts", error);
        }
        return contacts;
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    private static final class Adapter extends RecyclerView.Adapter<Adapter.Row> {
        private final Context context;
        private final Consumer<Contact> onClick;
        private final int avatarSize;
        private final LruCache<Long, Bitmap> avatars = new LruCache<>(80);
        private List<Contact> all = new ArrayList<>();
        private List<Contact> shown = new ArrayList<>();

        Adapter(Context context, Consumer<Contact> onClick) {
            this.context = context;
            this.onClick = onClick;
            this.avatarSize = dp(context, 40);
        }

        void setAll(List<Contact> contacts) {
            all = contacts;
        }

        void filter(String query) {
            String q = fold(query.trim());
            if (q.isEmpty()) {
                shown = all;
            } else {
                ArrayList<Contact> matches = new ArrayList<>();
                for (Contact contact : all) {
                    if (fold(contact.name).contains(q)) matches.add(contact);
                }
                shown = matches;
            }
            notifyDataSetChanged();
        }

        @Override public int getItemCount() {
            return shown.size();
        }

        @NonNull
        @Override
        public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(context, 56));
            row.setPadding(dp(context, 24), dp(context, 8), dp(context, 24), dp(context, 8));
            TypedValue ripple = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            row.setBackgroundResource(ripple.resourceId);
            ImageView avatar = new ImageView(context);
            row.addView(avatar, new LinearLayout.LayoutParams(avatarSize, avatarSize));
            TextView name = new TextView(context);
            name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            nameParams.setMarginStart(dp(context, 16));
            row.addView(name, nameParams);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Row(row, avatar, name);
        }

        @Override
        public void onBindViewHolder(@NonNull Row holder, int position) {
            Contact contact = shown.get(position);
            holder.contact = contact;
            holder.name.setText(contact.name);
            holder.itemView.setOnClickListener(v -> onClick.accept(contact));
            Bitmap cached = avatars.get(contact.id);
            if (cached != null) {
                holder.avatar.setImageBitmap(cached);
                return;
            }
            holder.avatar.setImageDrawable(null);
            Context app = context.getApplicationContext();
            App.runInBackground(() -> {
                Bitmap photo = null;
                if (contact.thumbnail != null) {
                    try (InputStream stream = app.getContentResolver().openInputStream(Uri.parse(contact.thumbnail))) {
                        if (stream != null) photo = BitmapFactory.decodeStream(stream);
                    } catch (Exception ignored) {
                        // Show the initial instead.
                    }
                }
                Bitmap avatar = AvatarRenderer.render(app, contact.name, avatarSize, photo);
                App.runOnUiThread(() -> {
                    avatars.put(contact.id, avatar);
                    if (holder.contact == contact) holder.avatar.setImageBitmap(avatar);
                });
            });
        }

        static final class Row extends RecyclerView.ViewHolder {
            final ImageView avatar;
            final TextView name;
            @Nullable Contact contact;

            Row(View itemView, ImageView avatar, TextView name) {
                super(itemView);
                this.avatar = avatar;
                this.name = name;
            }
        }
    }
}
