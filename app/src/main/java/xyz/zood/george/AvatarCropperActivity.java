package xyz.zood.george;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import com.canhub.cropper.CropImageView;

import java.io.IOException;

import io.pijun.george.App;
import io.pijun.george.Utils;

import xyz.zood.george.databinding.ActivityAvatarCropperBinding;

public class AvatarCropperActivity extends AppCompatActivity implements CropImageView.OnCropImageCompleteListener {

    private static final String ARG_URI = "uri";

    private ActivityAvatarCropperBinding binding;

    public static Intent newIntent(@NonNull Context ctx, @NonNull Uri uri) {
        Intent i = new Intent(ctx, AvatarCropperActivity.class);
        i.putExtra(ARG_URI, uri);
        return i;
    }

    @UiThread
    public void onCancel(View v) {
        finish();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = DataBindingUtil.setContentView(this, R.layout.activity_avatar_cropper);

        Bundle extras = getIntent().getExtras();
        if (extras == null) {
            throw new IllegalArgumentException("You need to use newIntent to start the activity");
        }
        Uri uri = extras.getParcelable(ARG_URI);
        if (uri == null) {
            throw new IllegalArgumentException("You promised me a uri");
        }
        binding.done.setEnabled(false);
        binding.cropImageView.setOnSetImageUriCompleteListener((view, loadedUri, error) -> {
            if (error == null) {
                binding.done.setEnabled(true);
            } else {
                showCropError(R.string.avatar_load_error);
            }
        });
        binding.cropImageView.setImageUriAsync(uri);
    }

    public void onDone(View v) {
        binding.cancel.setEnabled(false);
        binding.done.setEnabled(false);
        binding.cropImageView.setEnabled(false);

        binding.cropImageView.setOnCropImageCompleteListener(this);
        binding.cropImageView.croppedImageAsync(Bitmap.CompressFormat.JPEG, 80, 768, 768, CropImageView.RequestSizeOptions.RESIZE_INSIDE, null);
    }

    //region OnCropImageCompleteListener

    @Override
    @UiThread
    public void onCropImageComplete(@NonNull CropImageView view, CropImageView.CropResult result) {
        Bitmap bitmap = result.getBitmap();
        if (bitmap == null) {
            finishSaving(R.string.avatar_crop_error);
            return;
        }

        App.runInBackground(() -> {
            int error;
            try {
                error = AvatarManager.setMyAvatar(this, bitmap) ? 0 : R.string.unknown_avatar_save_error_msg;
            } catch (IOException ex) {
                error = R.string.avatar_save_io_error_msg;
            }
            int resultError = error;
            App.runOnUiThread(() -> finishSaving(resultError));
        });
    }

    private void finishSaving(int error) {
        if (isFinishing() || isDestroyed()) return;
        if (error == 0) {
            finish();
            return;
        }
        showCropError(error);
        binding.done.setEnabled(true);
        binding.cancel.setEnabled(true);
        binding.cropImageView.setEnabled(true);
    }

    private void showCropError(int error) {
        Utils.showAlert(this, R.string.save_error, error, getSupportFragmentManager());
    }
}
