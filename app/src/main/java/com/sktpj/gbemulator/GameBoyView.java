package com.sktpj.gbemulator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.sktpj.gbemulator.core.GameBoy;

public final class GameBoyView extends View {
    private static final long FRAME_NS = 16_742_706L;

    private final GameBoy gameBoy = new GameBoy();
    private final Bitmap bitmap = Bitmap.createBitmap(GameBoy.WIDTH, GameBoy.HEIGHT, Bitmap.Config.ARGB_8888);
    private final Paint screenPaint = new Paint();
    private final Paint controlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF screenRect = new RectF();
    private final RectF selectRect = new RectF();
    private final RectF startRect = new RectF();

    private volatile boolean running;
    private volatile boolean paused;
    private Thread emulationThread;

    private float dpadX;
    private float dpadY;
    private float dpadRadius;
    private float aX;
    private float aY;
    private float bX;
    private float bY;
    private float actionRadius;

    public GameBoyView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        screenPaint.setFilterBitmap(false);
        controlPaint.setColor(0xFF303030);
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
        setBackgroundColor(0xFF111111);
    }

    public void loadRom(byte[] rom) {
        gameBoy.loadRom(rom);
        invalidate();
    }

    public String getRomTitle() {
        return gameBoy.getRomTitle();
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) gameBoy.setButtons(0);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startThread();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopThread();
        super.onDetachedFromWindow();
    }

    private void startThread() {
        if (running) return;
        running = true;
        emulationThread = new Thread(this::emulationLoop, "gb-emulation");
        emulationThread.start();
    }

    private void stopThread() {
        running = false;
        Thread thread = emulationThread;
        emulationThread = null;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void emulationLoop() {
        while (running) {
            if (paused || !gameBoy.hasRom()) {
                sleepMs(20);
                continue;
            }
            long started = System.nanoTime();
            gameBoy.runFrame();
            int[] pixels = gameBoy.copyFrameBuffer();
            synchronized (bitmap) {
                bitmap.setPixels(pixels, 0, GameBoy.WIDTH, 0, 0, GameBoy.WIDTH, GameBoy.HEIGHT);
            }
            postInvalidateOnAnimation();
            long remaining = FRAME_NS - (System.nanoTime() - started);
            if (remaining > 0) {
                long ms = remaining / 1_000_000L;
                int ns = (int) (remaining % 1_000_000L);
                try {
                    Thread.sleep(ms, ns);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void sleepMs(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        updateGeometry(getWidth(), getHeight());

        controlPaint.setColor(0xFF050505);
        canvas.drawRoundRect(
                new RectF(screenRect.left - dp(8), screenRect.top - dp(8),
                        screenRect.right + dp(8), screenRect.bottom + dp(8)),
                dp(8), dp(8), controlPaint);

        synchronized (bitmap) {
            canvas.drawBitmap(bitmap, null, screenRect, screenPaint);
        }

        if (!gameBoy.hasRom()) {
            labelPaint.setTextSize(dp(16));
            labelPaint.setColor(0xFFCCCCCC);
            canvas.drawText("上の「ROMを開く」から .gb を選択", getWidth() / 2f,
                    screenRect.centerY(), labelPaint);
        }

        drawControls(canvas);
    }

    private void updateGeometry(int width, int height) {
        float top = Math.max(dp(68), height * 0.075f);
        float maxScreenW = width * 0.92f;
        float maxScreenH = height * 0.47f;
        float scale = Math.min(maxScreenW / GameBoy.WIDTH, maxScreenH / GameBoy.HEIGHT);
        float sw = GameBoy.WIDTH * scale;
        float sh = GameBoy.HEIGHT * scale;
        float left = (width - sw) / 2f;
        screenRect.set(left, top, left + sw, top + sh);

        float controlsTop = Math.max(screenRect.bottom + dp(20), height * 0.62f);
        dpadX = width * 0.24f;
        dpadY = controlsTop + (height - controlsTop) * 0.38f;
        dpadRadius = Math.min(width, height) * 0.12f;

        actionRadius = Math.min(width, height) * 0.068f;
        aX = width * 0.79f;
        aY = controlsTop + (height - controlsTop) * 0.30f;
        bX = width * 0.66f;
        bY = controlsTop + (height - controlsTop) * 0.42f;

        float smallW = width * 0.18f;
        float smallH = Math.max(dp(28), height * 0.035f);
        float smallY = Math.min(height - smallH - dp(10), controlsTop + (height - controlsTop) * 0.74f);
        selectRect.set(width * 0.28f, smallY, width * 0.28f + smallW, smallY + smallH);
        startRect.set(width * 0.54f, smallY, width * 0.54f + smallW, smallY + smallH);
    }

    private void drawControls(Canvas canvas) {
        controlPaint.setColor(0xFF323232);
        float arm = dpadRadius * 0.48f;
        canvas.drawRoundRect(new RectF(dpadX - arm, dpadY - dpadRadius,
                dpadX + arm, dpadY + dpadRadius), dp(6), dp(6), controlPaint);
        canvas.drawRoundRect(new RectF(dpadX - dpadRadius, dpadY - arm,
                dpadX + dpadRadius, dpadY + arm), dp(6), dp(6), controlPaint);

        controlPaint.setColor(0xFF7A254C);
        canvas.drawCircle(aX, aY, actionRadius, controlPaint);
        canvas.drawCircle(bX, bY, actionRadius, controlPaint);

        controlPaint.setColor(0xFF3B3B3B);
        canvas.drawRoundRect(selectRect, selectRect.height() / 2f, selectRect.height() / 2f, controlPaint);
        canvas.drawRoundRect(startRect, startRect.height() / 2f, startRect.height() / 2f, controlPaint);

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(actionRadius * 0.72f);
        canvas.drawText("A", aX, aY + labelPaint.getTextSize() * 0.34f, labelPaint);
        canvas.drawText("B", bX, bY + labelPaint.getTextSize() * 0.34f, labelPaint);
        labelPaint.setTextSize(Math.max(dp(10), selectRect.height() * 0.38f));
        canvas.drawText("SELECT", selectRect.centerX(), selectRect.centerY() + labelPaint.getTextSize() * 0.34f, labelPaint);
        canvas.drawText("START", startRect.centerX(), startRect.centerY() + labelPaint.getTextSize() * 0.34f, labelPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();
        int mask = 0;

        for (int i = 0; i < event.getPointerCount(); i++) {
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && i == actionIndex) {
                continue;
            }
            float x = event.getX(i);
            float y = event.getY(i);
            mask |= buttonsAt(x, y);
        }
        if (action == MotionEvent.ACTION_CANCEL) mask = 0;
        gameBoy.setButtons(mask);
        return true;
    }

    private int buttonsAt(float x, float y) {
        int mask = 0;
        float dx = x - dpadX;
        float dy = y - dpadY;
        if (Math.abs(dx) <= dpadRadius && Math.abs(dy) <= dpadRadius) {
            float threshold = dpadRadius * 0.22f;
            if (dx < -threshold) mask |= GameBoy.BTN_LEFT;
            if (dx > threshold) mask |= GameBoy.BTN_RIGHT;
            if (dy < -threshold) mask |= GameBoy.BTN_UP;
            if (dy > threshold) mask |= GameBoy.BTN_DOWN;
        }
        if (distanceSquared(x, y, aX, aY) <= actionRadius * actionRadius * 1.35f) mask |= GameBoy.BTN_A;
        if (distanceSquared(x, y, bX, bY) <= actionRadius * actionRadius * 1.35f) mask |= GameBoy.BTN_B;
        if (selectRect.contains(x, y)) mask |= GameBoy.BTN_SELECT;
        if (startRect.contains(x, y)) mask |= GameBoy.BTN_START;
        return mask;
    }

    private static float distanceSquared(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
