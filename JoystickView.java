package com.pcbrowser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** On-screen analog stick. Reports x/y in the range -1..1 (y grows downwards). */
public class JoystickView extends View {

    public interface Listener {
        void onChange(float x, float y);
    }

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Listener listener;
    private float nx;
    private float ny;
    private int pointerId = -1;

    public JoystickView(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;

        basePaint.setStyle(Paint.Style.FILL);
        basePaint.setColor(0x44000000);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2f * d);
        ringPaint.setColor(0xAAFFFFFF);

        knobPaint.setStyle(Paint.Style.FILL);
        knobPaint.setColor(0xAAFFFFFF);

        setFocusable(false);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Puts the stick back in the centre and tells the listener. */
    public void reset() {
        pointerId = -1;
        boolean moved = nx != 0f || ny != 0f;
        nx = 0f;
        ny = 0f;
        invalidate();
        if (moved && listener != null) {
            listener.onChange(0f, 0f);
        }
    }

    private float outerRadius() {
        return Math.min(getWidth(), getHeight()) / 2f - ringPaint.getStrokeWidth();
    }

    private float knobRadius() {
        return outerRadius() * 0.36f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = outerRadius();
        float kr = knobRadius();
        float travel = r - kr;

        canvas.drawCircle(cx, cy, r, basePaint);
        canvas.drawCircle(cx, cy, r, ringPaint);
        canvas.drawCircle(cx + nx * travel, cy + ny * travel, kr, knobPaint);
    }

    private void update(float px, float py) {
        float travel = outerRadius() - knobRadius();
        if (travel <= 0f) {
            return;
        }
        float x = (px - getWidth() / 2f) / travel;
        float y = (py - getHeight() / 2f) / travel;
        float len = (float) Math.hypot(x, y);
        if (len > 1f) {
            x /= len;
            y /= len;
        }
        nx = x;
        ny = y;
        invalidate();
        if (listener != null) {
            listener.onChange(x, y);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (pointerId == -1) {
                    int idx = e.getActionIndex();
                    pointerId = e.getPointerId(idx);
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    update(e.getX(idx), e.getY(idx));
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int idx = e.findPointerIndex(pointerId);
                if (idx >= 0) {
                    update(e.getX(idx), e.getY(idx));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (pointerId != -1 && (cancel || e.getPointerId(e.getActionIndex()) == pointerId)) {
                    reset();
                }
                return true;
            }
            default:
                return true;
        }
    }
}
