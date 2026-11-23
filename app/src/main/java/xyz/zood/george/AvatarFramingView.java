package xyz.zood.george;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Positions the image and the square framing area independently. */
public final class AvatarFramingView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path mask = new Path();
    private final RectF frame = new RectF();
    private final RectF photo = new RectF();
    private final ScaleGestureDetector pinch;
    private Bitmap image;
    private float scale, offsetX, offsetY, half, maxHalf, lastX, lastY;
    private boolean resizing, multitouch;

    public AvatarFramingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float next = clamp(scale * detector.getScaleFactor(), minScale(), maxScale());
                float ratio = next / scale;
                float x = detector.getFocusX() - getWidth() / 2f;
                float y = detector.getFocusY() - getHeight() / 2f;
                offsetX = x + (offsetX - x) * ratio;
                offsetY = y + (offsetY - y) * ratio;
                scale = next;
                constrain();
                invalidate();
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
    }

    void setImage(Bitmap bitmap) {
        image = bitmap;
        reset();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        reset();
    }

    private void reset() {
        maxHalf = Math.max(0, Math.min(getWidth(), getHeight()) / 2f - dp(28));
        half = maxHalf;
        offsetX = 0;
        offsetY = 0;
        if (image != null) scale = minScale();
        invalidate();
    }

    private float minScale() {
        return 2 * half / Math.min(image.getWidth(), image.getHeight());
    }

    private float maxScale() {
        return 12 * maxHalf / Math.min(image.getWidth(), image.getHeight());
    }

    private void constrain() {
        scale = Math.max(scale, minScale());
        float limitX = Math.max(0, image.getWidth() * scale / 2f - half);
        float limitY = Math.max(0, image.getHeight() * scale / 2f - half);
        offsetX = clamp(offsetX, -limitX, limitX);
        offsetY = clamp(offsetY, -limitY, limitY);
    }

    private void positionPhoto(float x, float y) {
        float w = image.getWidth() * scale / 2;
        float h = image.getHeight() * scale / 2;
        photo.set(x + offsetX - w, y + offsetY - h, x + offsetX + w, y + offsetY + h);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (image == null || half <= 0) return;
        float x = getWidth() / 2f;
        float y = getHeight() / 2f;
        positionPhoto(x, y);
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(image, null, photo, paint);
        mask.reset();
        mask.setFillType(Path.FillType.EVEN_ODD);
        mask.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        mask.addCircle(x, y, half, Path.Direction.CW);
        paint.setColor(0xA6000000);
        canvas.drawPath(mask, paint);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(dp(2));
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(x, y, half, paint);
        paint.setStyle(Paint.Style.FILL);
        frame.set(x - half, y - half, x + half, y + half);
        drawCorner(canvas, frame.left, frame.top, 1, 1);
        drawCorner(canvas, frame.right, frame.top, -1, 1);
        drawCorner(canvas, frame.left, frame.bottom, 1, -1);
        drawCorner(canvas, frame.right, frame.bottom, -1, -1);
    }

    private void drawCorner(Canvas canvas, float x, float y, int horizontal, int vertical) {
        canvas.drawLine(x, y, x + horizontal * dp(14), y, paint);
        canvas.drawLine(x, y, x, y + vertical * dp(14), paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (image == null || half <= 0 || !isEnabled()) return false;
        pinch.onTouchEvent(event);
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                multitouch = false;
                resizing = Math.abs(Math.abs(x - getWidth() / 2f) - half) <= dp(24)
                        && Math.abs(Math.abs(y - getHeight() / 2f) - half) <= dp(24);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                resizing = false;
                multitouch = true;
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerCount() == 2) {
                    int remaining = event.getActionIndex() == 0 ? 1 : 0;
                    x = event.getX(remaining);
                    y = event.getY(remaining);
                    multitouch = false;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!multitouch) {
                    if (resizing) {
                        float dx = Math.abs(x - getWidth() / 2f);
                        float dy = Math.abs(y - getHeight() / 2f);
                        half = clamp((dx + dy) / 2, maxHalf * .5f, maxHalf);
                    } else {
                        offsetX += x - lastX;
                        offsetY += y - lastY;
                    }
                    constrain();
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                performClick();
                break;
            default:
                break;
        }
        lastX = x;
        lastY = y;
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    Bitmap crop() {
        int size = Math.min(768, Math.max(1, Math.round(2 * half / scale)));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float ratio = size / (2 * half);
        canvas.scale(ratio, ratio);
        positionPhoto(half, half);
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(image, null, photo, paint);
        return bitmap;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.min(max, Math.max(min, value));
    }
}
