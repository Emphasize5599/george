package xyz.zood.george;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import java.io.File;
import java.io.IOException;

import io.pijun.george.App;
import io.pijun.george.Utils;
import xyz.zood.george.databinding.ActivityAvatarCropperBinding;

public class AvatarCropperActivity extends AppCompatActivity {
    private static final String ARG_URI = "uri";
    private ActivityAvatarCropperBinding binding;

    public static Intent newIntent(@NonNull Context context, @NonNull Uri uri) {
        return new Intent(context, AvatarCropperActivity.class).putExtra(ARG_URI, uri);
    }

    public void onCancel(View view) {
        finish();
    }

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_avatar_cropper);
        binding.done.setEnabled(false);
        Uri uri = getIntent().getParcelableExtra(ARG_URI);
        if (uri == null) {
            Utils.showAlert(this, R.string.save_error, R.string.avatar_load_error, getSupportFragmentManager());
            return;
        }
        App.runInBackground(() -> load(uri));
    }

    private void load(Uri uri) {
        try {
            var source = "file".equals(uri.getScheme())
                    ? ImageDecoder.createSource(new File(uri.getPath()))
                    : ImageDecoder.createSource(getContentResolver(), uri);
            Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, input) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int longest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                if (longest > 2048) {
                    float ratio = 2048f / longest;
                    decoder.setTargetSize(Math.max(1, Math.round(info.getSize().getWidth() * ratio)),
                            Math.max(1, Math.round(info.getSize().getHeight() * ratio)));
                }
            });
            App.runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    bitmap.recycle();
                    return;
                }
                binding.cropImageView.setImage(bitmap);
                binding.done.setEnabled(true);
            });
        } catch (IOException | SecurityException error) {
            App.runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    Utils.showAlert(this, R.string.save_error, R.string.avatar_load_error, getSupportFragmentManager());
                }
            });
        }
    }

    public void onDone(View view) {
        setSaving(true);
        Bitmap bitmap = binding.cropImageView.crop();
        App.runInBackground(() -> {
            int error;
            try {
                error = AvatarManager.setMyAvatar(this, bitmap) ? 0 : R.string.unknown_avatar_save_error_msg;
            } catch (IOException ex) {
                error = R.string.avatar_save_io_error_msg;
            } finally {
                bitmap.recycle();
            }
            int result = error;
            App.runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result == 0) {
                    finish();
                } else {
                    setSaving(false);
                    Utils.showAlert(this, R.string.save_error, result, getSupportFragmentManager());
                }
            });
        });
    }

    private void setSaving(boolean saving) {
        binding.done.setEnabled(!saving);
        binding.cancel.setEnabled(!saving);
        binding.cropImageView.setEnabled(!saving);
    }
}
